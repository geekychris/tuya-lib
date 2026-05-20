package com.hitorro.tuya.server.cloud;

import com.fasterxml.jackson.databind.JsonNode;
import com.hitorro.tuya.cloud.TuyaCloudException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * Cloud configuration + passthrough to the Tuya Cloud OpenAPI.
 *
 * <p>Config endpoints let the UI set the access ID / secret / region at runtime
 * without restarting; the {@link CloudClientHolder} rebuilds the active client
 * on every update. Passthrough endpoints expose the most useful Tuya OpenAPI
 * calls (device list, status, send commands).
 */
@RestController
@RequestMapping("/api/cloud")
public class CloudDeviceController {

    private final CloudClientHolder holder;
    private final CloudConfigRepository repository;

    public CloudDeviceController(CloudClientHolder holder, CloudConfigRepository repository) {
        this.holder = holder;
        this.repository = repository;
    }

    /** Raw debug passthrough: GET any Tuya path. Intentionally simple. Remove in prod. */
    @GetMapping("/raw")
    public JsonNode raw(@org.springframework.web.bind.annotation.RequestParam("path") String path) throws Exception {
        return holder.client().call("GET", path, null);
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of(
                "enabled", holder.isEnabled(),
                "accessId", holder.accessId(),
                "region", holder.region()
        );
    }

    /** Returns the current configuration WITHOUT the secret, plus a flag noting whether one is stored. */
    @GetMapping("/config")
    public Map<String, Object> getConfig() {
        CloudConfigEntity e = repository.findById(CloudConfigEntity.SINGLETON_ID).orElseGet(() -> {
            CloudConfigEntity fresh = new CloudConfigEntity();
            return repository.save(fresh);
        });
        return Map.of(
                "accessId", e.getAccessId() == null ? "" : e.getAccessId(),
                "region", e.getRegion(),
                "hasSecret", e.getAccessSecret() != null && !e.getAccessSecret().isBlank(),
                "enabled", holder.isEnabled()
        );
    }

    @PutMapping("/config")
    @Transactional
    public Map<String, Object> updateConfig(@RequestBody Map<String, String> body) {
        String accessId = body.getOrDefault("accessId", "").trim();
        String accessSecret = body.getOrDefault("accessSecret", "");
        String region = body.getOrDefault("region", "us").trim();

        CloudConfigEntity e = repository.findById(CloudConfigEntity.SINGLETON_ID)
                .orElseGet(CloudConfigEntity::new);
        e.setId(CloudConfigEntity.SINGLETON_ID);
        e.setAccessId(accessId.isBlank() ? null : accessId);
        // Empty string in the request means "leave the existing secret as-is";
        // explicit clearing happens via DELETE /api/cloud/config.
        if (!accessSecret.isEmpty()) {
            e.setAccessSecret(accessSecret);
        }
        e.setRegion(region.isBlank() ? "us" : region);
        repository.save(e);

        String effectiveSecret = accessSecret.isEmpty() ? e.getAccessSecret() : accessSecret;
        boolean enabled = holder.apply(e.getAccessId(), effectiveSecret, e.getRegion());
        return Map.of(
                "accessId", e.getAccessId() == null ? "" : e.getAccessId(),
                "region", e.getRegion(),
                "hasSecret", effectiveSecret != null && !effectiveSecret.isBlank(),
                "enabled", enabled
        );
    }

    @DeleteMapping("/config")
    @Transactional
    public Map<String, Object> clearConfig() {
        CloudConfigEntity e = repository.findById(CloudConfigEntity.SINGLETON_ID)
                .orElseGet(CloudConfigEntity::new);
        e.setId(CloudConfigEntity.SINGLETON_ID);
        e.setAccessId(null);
        e.setAccessSecret(null);
        e.setRegion("us");
        repository.save(e);
        holder.apply(null, null, "us");
        return Map.of("enabled", false);
    }

    /**
     * Try to acquire a token with the currently-saved credentials. The request body
     * is optional — if provided ({@code accessId}, {@code accessSecret}, {@code region}),
     * tests those credentials without persisting them, so the UI can validate before save.
     */
    @PostMapping("/test")
    public Map<String, Object> testConfig(@RequestBody(required = false) Map<String, String> body) {
        try {
            if (body != null && !body.getOrDefault("accessId", "").isBlank()) {
                String accessId = body.get("accessId");
                String accessSecret = body.getOrDefault("accessSecret", "");
                String region = body.getOrDefault("region", "us");
                if (accessSecret.isEmpty()) {
                    CloudConfigEntity e = repository.findById(CloudConfigEntity.SINGLETON_ID)
                            .orElse(null);
                    if (e != null) accessSecret = e.getAccessSecret() == null ? "" : e.getAccessSecret();
                }
                com.hitorro.tuya.cloud.TuyaCloudClient tmp = new com.hitorro.tuya.cloud.TuyaCloudClient(
                        accessId, accessSecret, com.hitorro.tuya.cloud.TuyaCloudRegion.fromCode(region));
                // Any signed call forces a token fetch; pick a cheap one.
                tmp.call("GET", "/v1.0/token?grant_type=1", null);
            } else {
                if (!holder.isEnabled()) {
                    return Map.of("ok", false, "error", "No credentials configured");
                }
                holder.client().call("GET", "/v1.0/token?grant_type=1", null);
            }
            return Map.of("ok", true);
        } catch (Exception ex) {
            return Map.of("ok", false, "error", ex.getMessage() == null ? ex.toString() : ex.getMessage());
        }
    }

    @GetMapping("/users/{userId}/devices")
    public JsonNode listUserDevices(@PathVariable String userId) throws Exception {
        return holder.client().listDevices(userId);
    }

    /**
     * List devices linked to the cloud project (across all associated app users).
     * Returns the raw Tuya result so the UI can render rich device info.
     */
    @GetMapping("/devices")
    public JsonNode listProjectDevices(
            @org.springframework.web.bind.annotation.RequestParam(name = "pageSize", defaultValue = "50") int pageSize,
            @org.springframework.web.bind.annotation.RequestParam(name = "lastRowKey", required = false) String lastRowKey) throws Exception {
        return holder.client().listAssociatedUsersDevices(pageSize, lastRowKey);
    }

    /** Fetch factory_infos (includes local_key) for one or more device IDs. */
    @GetMapping("/devices/factory-infos")
    public JsonNode getFactoryInfos(
            @org.springframework.web.bind.annotation.RequestParam("deviceIds") String deviceIds) throws Exception {
        return holder.client().getFactoryInfos(java.util.Arrays.asList(deviceIds.split(",")));
    }

    @GetMapping("/devices/{deviceId}")
    public JsonNode getDevice(@PathVariable String deviceId) throws Exception {
        return holder.client().getDevice(deviceId);
    }

    @GetMapping("/devices/{deviceId}/status")
    public JsonNode getDeviceStatus(@PathVariable String deviceId) throws Exception {
        return holder.client().getDeviceStatus(deviceId);
    }

    @PostMapping("/devices/{deviceId}/commands")
    @SuppressWarnings("unchecked")
    public JsonNode sendCommands(@PathVariable String deviceId, @RequestBody Map<String, Object> body) throws Exception {
        List<Map<String, Object>> commands = (List<Map<String, Object>>) body.get("commands");
        if (commands == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Body must include 'commands' array");
        }
        return holder.client().sendCommands(deviceId, commands);
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(IllegalStateException.class)
    public org.springframework.http.ResponseEntity<Map<String, Object>> handleDisabled(IllegalStateException e) {
        return org.springframework.http.ResponseEntity
                .status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("error", e.getMessage()));
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(TuyaCloudException.class)
    public org.springframework.http.ResponseEntity<Map<String, Object>> handleCloudException(TuyaCloudException e) {
        return org.springframework.http.ResponseEntity
                .status(HttpStatus.BAD_GATEWAY)
                .body(Map.of("error", e.getMessage()));
    }
}
