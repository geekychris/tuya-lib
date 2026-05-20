package com.hitorro.tuya.cloud;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Thin Tuya Cloud OpenAPI client. Handles the slightly fiddly HMAC-SHA256
 * signature scheme and access-token caching. Supports the device list and
 * command-send endpoints; the rest can be added by calling {@link #call}
 * directly.
 *
 * <p>Signature algorithm (token + business APIs):
 * <pre>
 *   stringToSign = HTTPMethod + "\n" + SHA256_hex(body) + "\n" + headersToSign + "\n" + url
 *   sign = HMAC_SHA256(secret, clientId + access_token + t + nonce + stringToSign)
 * </pre>
 * For the token endpoint, access_token is omitted from the sign input.
 */
public final class TuyaCloudClient {

    private static final Logger LOG = Logger.getLogger(TuyaCloudClient.class.getName());

    private final String clientId;
    private final String clientSecret;
    private final TuyaCloudRegion region;
    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper();

    private volatile String accessToken;
    private volatile long accessTokenExpiresAtMs;

    public TuyaCloudClient(String clientId, String clientSecret, TuyaCloudRegion region) {
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.region = region;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /** List devices belonging to the linked user. */
    public JsonNode listDevices(String userId) throws Exception {
        return call("GET", "/v1.0/users/" + userId + "/devices", null);
    }

    /**
     * List all devices linked to any app-account user associated with this project.
     * Returns rows that include {@code local_key} and {@code ip} when available — the
     * exact fields needed to register the device for LAN control.
     * Paginated via {@code last_row_key} (pass {@code null} for the first page).
     */
    public JsonNode listAssociatedUsersDevices(int pageSize, String lastRowKey) throws Exception {
        StringBuilder path = new StringBuilder("/v1.0/iot-01/associated-users/devices?page_size=");
        path.append(pageSize);
        if (lastRowKey != null && !lastRowKey.isBlank()) {
            path.append("&last_row_key=").append(lastRowKey);
        }
        return call("GET", path.toString(), null);
    }

    /** Returns factory infos (which include {@code local_key}) for the given device IDs. */
    public JsonNode getFactoryInfos(List<String> deviceIds) throws Exception {
        String ids = String.join(",", deviceIds);
        return call("GET", "/v1.0/iot-03/devices/factory-infos?device_ids=" + ids, null);
    }

    /**
     * Get the device's shadow properties: per-DP code, dp_id, type, and current value.
     * Best endpoint for reading state on devices the cloud knows about.
     */
    public JsonNode getShadowProperties(String deviceId) throws Exception {
        return call("GET", "/v2.0/cloud/thing/" + deviceId + "/shadow/properties", null);
    }

    /** Send commands keyed by friendly DP code, e.g. [{"code":"pump","value":true}]. */
    public JsonNode sendShadowCommands(String deviceId, List<Map<String, Object>> commands) throws Exception {
        Map<String, Object> body = Map.of("properties", json.writeValueAsString(toPropertiesMap(commands)));
        return call("POST", "/v2.0/cloud/thing/" + deviceId + "/shadow/properties/issue", json.writeValueAsString(body));
    }

    private static Map<String, Object> toPropertiesMap(List<Map<String, Object>> commands) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        for (Map<String, Object> c : commands) {
            out.put((String) c.get("code"), c.get("value"));
        }
        return out;
    }

    /** Get one device's full record (includes online status). */
    public JsonNode getDevice(String deviceId) throws Exception {
        return call("GET", "/v1.0/iot-03/devices/" + deviceId, null);
    }

    /** Get current DP values for a device. */
    public JsonNode getDeviceStatus(String deviceId) throws Exception {
        return call("GET", "/v1.0/iot-03/devices/" + deviceId + "/status", null);
    }

    /**
     * Send a SET command. {@code commands} is a list of {@code {"code": "switch_1", "value": true}} maps.
     */
    public JsonNode sendCommands(String deviceId, List<Map<String, Object>> commands) throws Exception {
        Map<String, Object> body = Map.of("commands", commands);
        return call("POST", "/v1.0/iot-03/devices/" + deviceId + "/commands", json.writeValueAsString(body));
    }

    /**
     * Low-level signed call. {@code path} starts with "/". {@code body} may be null.
     * Returns the {@code result} field of the Tuya response, or throws on error.
     *
     * <p>The {@code /v1.0/token} endpoint is the one place where {@code access_token}
     * must NOT be in the signature input — we'd be using a token to authenticate the
     * request that fetches the token. Detect that and route through the no-token path.
     */
    public JsonNode call(String method, String path, String body) throws Exception {
        if (path.startsWith("/v1.0/token")) {
            return signedRequest(method, path, body, null);
        }
        ensureToken();
        return signedRequest(method, path, body, accessToken);
    }

    private synchronized void ensureToken() throws Exception {
        if (accessToken != null && System.currentTimeMillis() < accessTokenExpiresAtMs - 60_000) return;
        JsonNode result = signedRequest("GET", "/v1.0/token?grant_type=1", null, null);
        accessToken = result.get("access_token").asText();
        long ttlSec = result.get("expire_time").asLong();
        accessTokenExpiresAtMs = System.currentTimeMillis() + ttlSec * 1000L;
    }

    private JsonNode signedRequest(String method, String path, String body, String token) throws Exception {
        long t = System.currentTimeMillis();
        String bodyHash = sha256Hex(body == null ? "" : body);
        // stringToSign = METHOD \n Content-SHA256 \n SignedHeaders \n URL.
        // We don't send any signed headers, so the headers section is blank → "\n\n".
        String stringToSign = method + "\n" + bodyHash + "\n\n" + path;
        // Sign input: client_id [+ access_token] + t + stringToSign.
        // No nonce — Tuya's public docs mention nonce but the production endpoints (matching the
        // tuya-iot-python-sdk) actually verify against this shorter form. Adding nonce returns
        // {"code":1004,"msg":"sign invalid"}.
        String signInput = clientId + (token == null ? "" : token) + t + stringToSign;
        String signature = hmacSha256Hex(clientSecret, signInput).toUpperCase();

        URI uri = URI.create(region.baseUrl() + path);
        HttpRequest.Builder req = HttpRequest.newBuilder(uri)
                .header("client_id", clientId)
                .header("sign", signature)
                .header("sign_method", "HMAC-SHA256")
                .header("t", Long.toString(t));
        if (token != null) req.header("access_token", token);
        if (body != null && !body.isEmpty()) req.header("Content-Type", "application/json");

        switch (method) {
            case "GET" -> req.GET();
            case "POST" -> req.POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body));
            case "PUT" -> req.PUT(HttpRequest.BodyPublishers.ofString(body == null ? "" : body));
            case "DELETE" -> req.DELETE();
            default -> throw new IllegalArgumentException("Unsupported method: " + method);
        }

        if (LOG.isLoggable(Level.INFO)) {
            LOG.info(String.format(
                    "Tuya signed request: %s %s | clientIdLen=%d clientIdHead=%s secretLen=%d region=%s t=%d tokenPresent=%s stringToSign=%s signature=%s",
                    method, region.baseUrl() + path,
                    clientId.length(), clientId.length() > 4 ? clientId.substring(0, 4) + "…" : "****",
                    clientSecret.length(), region.name(), t, token != null,
                    stringToSign.replace("\n", "\\n"),
                    signature));
        }

        HttpResponse<String> resp = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
        LOG.info("Tuya response: HTTP " + resp.statusCode() + " body=" + resp.body());
        JsonNode root = json.readTree(resp.body());
        if (!root.path("success").asBoolean(false)) {
            throw new TuyaCloudException(
                    "Tuya cloud call failed: code=" + root.path("code").asText("?")
                            + " msg=" + root.path("msg").asText("?"));
        }
        return root.path("result");
    }

    /** Path containing query string is fine; query params are part of stringToSign as-is. */
    private static String sha256Hex(String s) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
    }

    private static String hmacSha256Hex(String secret, String message) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
    }

    // sorted-header helper retained for callers that need to add signed headers later
    @SuppressWarnings("unused")
    private static String joinSortedHeaders(Map<String, String> headers) {
        TreeMap<String, String> sorted = new TreeMap<>(headers);
        StringBuilder b = new StringBuilder();
        sorted.forEach((k, v) -> b.append(k).append(":").append(v).append("\n"));
        return b.toString();
    }
}
