package com.hitorro.tuya.server.api;

import com.hitorro.tuya.server.device.DeviceEntity;
import jakarta.validation.constraints.NotBlank;

public class DeviceDto {

    public Long id;
    @NotBlank public String name;
    @NotBlank public String gwId;
    public String localKey;
    public String ip;
    public String protocolVersion = "3.3";
    public String source = "LOCAL";
    public String preferredTransport = "AUTO";
    public String dpsSchemaJson;
    public boolean connected;

    public static DeviceDto from(DeviceEntity e, boolean connected) {
        DeviceDto d = new DeviceDto();
        d.id = e.getId();
        d.name = e.getName();
        d.gwId = e.getGwId();
        d.localKey = e.getLocalKey();
        d.ip = e.getIp();
        d.protocolVersion = e.getProtocolVersion();
        d.source = e.getSource().name();
        d.preferredTransport = e.getPreferredTransport().name();
        d.dpsSchemaJson = e.getDpsSchemaJson();
        d.connected = connected;
        return d;
    }
}
