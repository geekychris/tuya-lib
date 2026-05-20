package com.hitorro.tuya.server.device;

import com.fasterxml.jackson.databind.JsonNode;
import com.hitorro.tuya.client.TuyaDevice;
import com.hitorro.tuya.model.DeviceConfig;
import com.hitorro.tuya.model.DpsState;
import com.hitorro.tuya.model.ProtocolVersion;
import com.hitorro.tuya.server.cloud.CloudClientHolder;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * Owns one {@link TuyaDevice} per locally-registered device. Connects on startup
 * and reconnects on demand. Cloud-source devices have no LAN connection.
 */
@Component
public class DeviceManager {

    private static final Logger log = LoggerFactory.getLogger(DeviceManager.class);

    private final DeviceRepository repository;
    private final CloudClientHolder cloud;
    private final Map<Long, TuyaDevice> connections = new ConcurrentHashMap<>();
    private volatile BiConsumer<Long, DpsState> stateBroadcaster = (id, s) -> { };

    public DeviceManager(DeviceRepository repository, CloudClientHolder cloud) {
        this.repository = repository;
        this.cloud = cloud;
    }

    public void setStateBroadcaster(BiConsumer<Long, DpsState> broadcaster) {
        this.stateBroadcaster = broadcaster == null ? (id, s) -> { } : broadcaster;
    }

    @PostConstruct
    public void connectAll() {
        for (DeviceEntity e : repository.findAll()) {
            if (e.getSource() == DeviceEntity.Source.LOCAL && hasLocalCreds(e)) {
                openLocalConnection(e);
            }
        }
    }

    @PreDestroy
    public void closeAll() {
        for (TuyaDevice d : connections.values()) {
            try { d.close(); } catch (Exception ignore) { }
        }
        connections.clear();
    }

    public void onDeviceCreated(DeviceEntity e) {
        if (e.getSource() == DeviceEntity.Source.LOCAL && hasLocalCreds(e)) {
            openLocalConnection(e);
        }
    }

    public void onDeviceDeleted(Long id) {
        TuyaDevice d = connections.remove(id);
        if (d != null) {
            try { d.close(); } catch (Exception ignore) { }
        }
    }

    public Optional<TuyaDevice> connection(Long deviceId) {
        return Optional.ofNullable(connections.get(deviceId));
    }

    public DpsState getState(Long deviceId) throws Exception {
        DeviceEntity e = lookup(deviceId);
        return switch (resolveTransport(e)) {
            case CLOUD -> cloudGetState(e.getGwId());
            case LAN -> ensureLive(deviceId).get();
            case AUTO -> {
                try { yield ensureLive(deviceId).get(); }
                catch (Exception ex) {
                    if (cloud.isEnabled()) {
                        log.info("LAN get failed for {} ({}); falling back to cloud", e.getName(), ex.getMessage());
                        yield cloudGetState(e.getGwId());
                    }
                    throw ex;
                }
            }
        };
    }

    public DpsState setState(Long deviceId, Map<String, Object> dps) throws Exception {
        DeviceEntity e = lookup(deviceId);
        DpsState after = switch (resolveTransport(e)) {
            case CLOUD -> cloudSetState(e.getGwId(), dps);
            case LAN -> ensureLive(deviceId).set(dps);
            case AUTO -> {
                try { yield ensureLive(deviceId).set(dps); }
                catch (Exception ex) {
                    if (cloud.isEnabled()) {
                        log.info("LAN set failed for {} ({}); falling back to cloud", e.getName(), ex.getMessage());
                        yield cloudSetState(e.getGwId(), dps);
                    }
                    throw ex;
                }
            }
        };
        stateBroadcaster.accept(deviceId, after);
        return after;
    }

    private DeviceEntity lookup(Long deviceId) {
        return repository.findById(deviceId)
                .orElseThrow(() -> new IllegalArgumentException("No device with id " + deviceId));
    }

    /**
     * Decide where this call goes. Explicit user preference (LAN/CLOUD) wins.
     * AUTO defaults to cloud for v3.4/3.5 (LAN handshake not implemented) and
     * LAN otherwise, with cloud fallback on failure.
     */
    private DeviceEntity.Transport resolveTransport(DeviceEntity e) {
        DeviceEntity.Transport pref = e.getPreferredTransport();
        if (pref == DeviceEntity.Transport.LAN) return DeviceEntity.Transport.LAN;
        if (pref == DeviceEntity.Transport.CLOUD) {
            return cloud.isEnabled() ? DeviceEntity.Transport.CLOUD : DeviceEntity.Transport.LAN;
        }
        // AUTO
        String pv = e.getProtocolVersion();
        boolean lanLikelyUnsupported = "3.5".equals(pv) || "3.4".equals(pv);
        if (lanLikelyUnsupported && cloud.isEnabled()) return DeviceEntity.Transport.CLOUD;
        return DeviceEntity.Transport.AUTO;
    }

    /** Convert /shadow/properties response into our {dp_id → value} DpsState format. */
    private DpsState cloudGetState(String gwId) throws Exception {
        JsonNode result = cloud.client().getShadowProperties(gwId);
        JsonNode props = result.path("properties");
        Map<String, Object> out = new LinkedHashMap<>();
        for (JsonNode p : props) {
            String dpId = Integer.toString(p.path("dp_id").asInt());
            JsonNode val = p.get("value");
            if (val == null || val.isNull()) continue;
            if (val.isBoolean()) out.put(dpId, val.asBoolean());
            else if (val.isInt() || val.isLong()) out.put(dpId, val.asLong());
            else if (val.isDouble() || val.isFloat()) out.put(dpId, val.asDouble());
            else out.put(dpId, val.asText());
        }
        return new DpsState(out, Instant.now());
    }

    /** Look up {@code code}s from /shadow/properties for the requested dp_ids, then send commands. */
    private DpsState cloudSetState(String gwId, Map<String, Object> dps) throws Exception {
        log.info("cloudSetState gwId={} dps={}", gwId, dps);
        JsonNode result = cloud.client().getShadowProperties(gwId);
        JsonNode props = result.path("properties");
        Map<String, String> dpIdToCode = new LinkedHashMap<>();
        for (JsonNode p : props) {
            dpIdToCode.put(Integer.toString(p.path("dp_id").asInt()), p.path("code").asText());
        }
        List<Map<String, Object>> commands = new ArrayList<>();
        for (var e : dps.entrySet()) {
            String code = dpIdToCode.get(e.getKey());
            if (code == null) {
                throw new IllegalArgumentException("Cloud doesn't expose DP " + e.getKey() + " for this device");
            }
            commands.add(Map.of("code", code, "value", e.getValue()));
        }
        cloud.client().sendShadowCommands(gwId, commands);
        // Tuya's shadow is eventually consistent — the value the user just set
        // typically takes 1-5s to reflect in /shadow/properties. Returning the
        // pre-change state would let the UI overwrite the user's input. Instead,
        // merge the requested dps over the most-recent state so the user sees
        // their command stick; the next full GET will reveal the eventual state.
        DpsState fresh = cloudGetState(gwId);
        Map<String, Object> merged = new LinkedHashMap<>(fresh.dps());
        merged.putAll(dps);
        return new DpsState(merged, Instant.now());
    }

    /** Public helper so the import flow can build a dpsSchemaJson from cloud properties. */
    public JsonNode fetchCloudProperties(String gwId) throws Exception {
        return cloud.client().getShadowProperties(gwId);
    }

    /** Force-reconnect (drops any existing connection and opens a fresh one). */
    public boolean reconnect(Long deviceId) {
        TuyaDevice old = connections.remove(deviceId);
        if (old != null) {
            try { old.close(); } catch (Exception ignore) { }
        }
        return repository.findById(deviceId)
                .map(e -> {
                    openLocalConnection(e);
                    return connections.containsKey(e.getId());
                })
                .orElse(false);
    }

    /**
     * Return a live, connected {@link TuyaDevice}, opening or reconnecting the
     * underlying socket as needed. Throws if the device record can't be found.
     */
    private TuyaDevice ensureLive(Long deviceId) {
        TuyaDevice d = connections.get(deviceId);
        if (d != null && d.isConnected()) return d;
        if (d != null) {
            try { d.close(); } catch (Exception ignore) { }
            connections.remove(deviceId);
        }
        DeviceEntity e = repository.findById(deviceId)
                .orElseThrow(() -> new IllegalArgumentException("No device with id " + deviceId));
        if (e.getSource() != DeviceEntity.Source.LOCAL || !hasLocalCreds(e)) {
            throw new IllegalStateException("Device " + deviceId + " is not configured for LAN control");
        }
        openLocalConnection(e);
        TuyaDevice fresh = connections.get(deviceId);
        if (fresh == null) {
            throw new IllegalStateException("Failed to connect to " + e.getIp()
                    + " — device may be offline or on a different network");
        }
        return fresh;
    }

    private void openLocalConnection(DeviceEntity e) {
        try {
            DeviceConfig config = new DeviceConfig(
                    e.getGwId(),
                    e.getLocalKey(),
                    e.getIp(),
                    ProtocolVersion.fromWire(e.getProtocolVersion()));
            TuyaDevice device = new TuyaDevice(config);
            Long id = e.getId();
            device.onStateChange(state -> stateBroadcaster.accept(id, state));
            device.connect();
            connections.put(id, device);
            log.info("Connected to Tuya device {} at {} ({})", maskedGwId(e.getGwId()), e.getIp(), e.getName());
        } catch (IOException | RuntimeException ex) {
            log.warn("Failed to open connection to {} ({}): {}", e.getName(), e.getIp(), ex.getMessage());
        }
    }

    private static boolean hasLocalCreds(DeviceEntity e) {
        return e.getLocalKey() != null && !e.getLocalKey().isBlank()
                && e.getIp() != null && !e.getIp().isBlank();
    }

    private static String maskedGwId(String gwId) {
        if (gwId == null || gwId.length() < 8) return "****";
        return gwId.substring(0, 4) + "…" + gwId.substring(gwId.length() - 4);
    }
}
