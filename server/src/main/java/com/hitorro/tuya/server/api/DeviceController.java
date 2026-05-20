package com.hitorro.tuya.server.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.hitorro.tuya.cloud.TuyaCloudException;
import com.hitorro.tuya.model.DpsState;
import com.hitorro.tuya.server.cloud.CloudClientHolder;
import com.hitorro.tuya.server.device.DeviceEntity;
import com.hitorro.tuya.server.device.DeviceManager;
import com.hitorro.tuya.server.device.DeviceRepository;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/devices")
public class DeviceController {

    private final DeviceRepository repository;
    private final DeviceManager manager;
    private final CloudClientHolder cloud;

    public DeviceController(DeviceRepository repository, DeviceManager manager, CloudClientHolder cloud) {
        this.repository = repository;
        this.manager = manager;
        this.cloud = cloud;
    }

    @GetMapping
    public List<DeviceDto> list() {
        return repository.findAll().stream()
                .map(e -> DeviceDto.from(e, manager.connection(e.getId()).map(c -> c.isConnected()).orElse(false)))
                .collect(Collectors.toList());
    }

    @PostMapping
    public ResponseEntity<DeviceDto> create(@Valid @RequestBody DeviceDto in) {
        if (repository.findByGwId(in.gwId).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Device with this gwId already registered");
        }
        DeviceEntity e = new DeviceEntity();
        e.setName(in.name);
        e.setGwId(in.gwId);
        e.setLocalKey(in.localKey);
        e.setIp(in.ip);
        e.setProtocolVersion(in.protocolVersion == null ? "3.3" : in.protocolVersion);
        e.setSource(DeviceEntity.Source.valueOf(in.source == null ? "LOCAL" : in.source));
        e.setDpsSchemaJson(in.dpsSchemaJson);
        repository.save(e);
        manager.onDeviceCreated(e);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(DeviceDto.from(e, manager.connection(e.getId()).map(c -> c.isConnected()).orElse(false)));
    }

    @PutMapping("/{id}")
    public DeviceDto update(@PathVariable Long id, @Valid @RequestBody DeviceDto in) {
        DeviceEntity e = repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        e.setName(in.name);
        if (in.localKey != null && !in.localKey.isBlank()) e.setLocalKey(in.localKey);
        if (in.ip != null) e.setIp(in.ip);
        e.setProtocolVersion(in.protocolVersion == null ? "3.3" : in.protocolVersion);
        if (in.preferredTransport != null && !in.preferredTransport.isBlank()) {
            e.setPreferredTransport(DeviceEntity.Transport.valueOf(in.preferredTransport));
        }
        if (in.dpsSchemaJson != null) e.setDpsSchemaJson(in.dpsSchemaJson);
        repository.save(e);
        // Reconnect to pick up new ip/localKey.
        manager.onDeviceDeleted(id);
        manager.onDeviceCreated(e);
        return DeviceDto.from(e, manager.connection(e.getId()).map(c -> c.isConnected()).orElse(false));
    }

    @PostMapping("/{id}/transport")
    public DeviceDto setTransport(@PathVariable Long id, @RequestBody Map<String, String> body) {
        String t = body.get("transport");
        if (t == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing 'transport'");
        DeviceEntity e = repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        try { e.setPreferredTransport(DeviceEntity.Transport.valueOf(t)); }
        catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "transport must be AUTO|LAN|CLOUD");
        }
        repository.save(e);
        return DeviceDto.from(e, manager.connection(id).map(c -> c.isConnected()).orElse(false));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        manager.onDeviceDeleted(id);
        repository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/state")
    public Map<String, Object> getState(@PathVariable Long id) throws Exception {
        DpsState state = manager.getState(id);
        return Map.of("dps", state.dps(), "at", state.at().toString());
    }

    @PostMapping("/{id}/reconnect")
    public Map<String, Object> reconnect(@PathVariable Long id) {
        boolean ok = manager.reconnect(id);
        return Map.of("connected", ok);
    }

    @PostMapping("/{id}/dps")
    public Map<String, Object> setDps(@PathVariable Long id, @RequestBody Map<String, Object> body) throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> dps = (Map<String, Object>) body.get("dps");
        if (dps == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Body must include 'dps' map");
        DpsState state = manager.setState(id, dps);
        return Map.of("dps", state.dps(), "at", state.at().toString());
    }

    /**
     * Import a device from the Tuya cloud. If the caller already has the
     * {@code localKey} (e.g. from the cloud devices list, which includes it),
     * pass it in to avoid an extra round-trip. Otherwise we look it up by
     * scanning {@code /v1.0/iot-01/associated-users/devices}. Note: the
     * {@code factory-infos} endpoint does NOT return local_key — only mac/sn/uuid.
     */
    @PostMapping("/from-cloud")
    public ResponseEntity<DeviceDto> importFromCloud(@RequestBody Map<String, String> body) throws Exception {
        String deviceId = required(body, "deviceId");
        String ip = required(body, "ip");
        String protocolVersion = body.getOrDefault("protocolVersion", "3.3");
        String name = body.getOrDefault("name", "");
        String localKey = body.getOrDefault("localKey", "");

        if (repository.findByGwId(deviceId).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Device " + deviceId + " is already registered");
        }

        if (localKey.isBlank()) {
            // Walk the associated-users devices list looking for this device.
            JsonNode result = cloud.client().listAssociatedUsersDevices(100, null);
            JsonNode devices = result.path("devices");
            if (devices.isArray()) {
                for (JsonNode d : devices) {
                    if (deviceId.equals(d.path("id").asText())) {
                        localKey = d.path("local_key").asText("");
                        if (name.isBlank()) name = d.path("name").asText(deviceId);
                        break;
                    }
                }
            }
        }
        if (localKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "No local_key found for device " + deviceId + " (not in associated-users list, and none provided)");
        }
        if (name.isBlank()) name = deviceId;

        DeviceEntity e = new DeviceEntity();
        e.setName(name);
        e.setGwId(deviceId);
        e.setLocalKey(localKey);
        e.setIp(ip);
        e.setProtocolVersion(protocolVersion);
        e.setSource(DeviceEntity.Source.LOCAL);

        // Best-effort: fetch the device's cloud shadow properties to auto-populate the DP schema.
        try {
            JsonNode props = manager.fetchCloudProperties(deviceId).path("properties");
            if (props.isArray() && !props.isEmpty()) {
                e.setDpsSchemaJson(buildSchemaFromCloud(props));
            }
        } catch (Exception ignore) {
            // Schema population is optional; carry on with the import.
        }

        repository.save(e);
        manager.onDeviceCreated(e);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(DeviceDto.from(e, manager.connection(e.getId()).map(c -> c.isConnected()).orElse(false)));
    }

    private static String buildSchemaFromCloud(JsonNode props) {
        com.fasterxml.jackson.databind.ObjectMapper m = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.node.ObjectNode root = m.createObjectNode();
        com.fasterxml.jackson.databind.node.ArrayNode entries = m.createArrayNode();
        for (JsonNode p : props) {
            String type = p.path("type").asText("");
            com.fasterxml.jackson.databind.node.ObjectNode entry = m.createObjectNode();
            entry.put("index", Integer.toString(p.path("dp_id").asInt()));
            String code = p.path("code").asText();
            entry.put("label", humanLabel(code));
            switch (type) {
                case "bool" -> entry.put("kind", "bool");
                case "value" -> entry.put("kind", "number");
                case "enum" -> entry.put("kind", "enum");
                default -> entry.put("kind", "string");
            }
            entry.put("code", code);
            entries.add(entry);
        }
        root.set("entries", entries);
        try { return m.writeValueAsString(root); }
        catch (Exception e) { return null; }
    }

    private static String humanLabel(String code) {
        if (code == null || code.isEmpty()) return code;
        // Snake_case → Title Case
        String[] parts = code.split("_");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
        }
        return sb.toString();
    }

    private static String required(Map<String, String> body, String key) {
        String v = body.get(key);
        if (v == null || v.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing required field: " + key);
        }
        return v;
    }
}
