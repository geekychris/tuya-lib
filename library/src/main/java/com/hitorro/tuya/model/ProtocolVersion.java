package com.hitorro.tuya.model;

public enum ProtocolVersion {
    V3_1("3.1"),
    V3_3("3.3"),
    V3_4("3.4"),
    V3_5("3.5");

    private final String wire;

    ProtocolVersion(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    public static ProtocolVersion fromWire(String s) {
        for (ProtocolVersion v : values()) {
            if (v.wire.equals(s)) return v;
        }
        throw new IllegalArgumentException("Unknown protocol version: " + s);
    }
}
