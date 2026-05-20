package com.hitorro.tuya.model;

import java.util.Objects;

public record DeviceConfig(
        String gwId,
        String localKey,
        String ip,
        ProtocolVersion protocolVersion,
        int port
) {
    public static final int DEFAULT_PORT = 6668;

    public DeviceConfig {
        Objects.requireNonNull(gwId, "gwId");
        Objects.requireNonNull(localKey, "localKey");
        Objects.requireNonNull(ip, "ip");
        Objects.requireNonNull(protocolVersion, "protocolVersion");
        if (localKey.length() != 16) {
            throw new IllegalArgumentException("localKey must be 16 ASCII characters, got " + localKey.length());
        }
    }

    public DeviceConfig(String gwId, String localKey, String ip, ProtocolVersion protocolVersion) {
        this(gwId, localKey, ip, protocolVersion, DEFAULT_PORT);
    }
}
