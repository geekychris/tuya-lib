package com.hitorro.tuya.protocol;

public enum TuyaCommand {
    UDP(0),
    AP_CONFIG(1),
    ACTIVE(2),
    SESS_KEY_NEG_START(3),
    SESS_KEY_NEG_RESP(4),
    SESS_KEY_NEG_FINISH(5),
    UNBIND(6),
    CONTROL(7),
    STATUS(8),
    HEART_BEAT(9),
    DP_QUERY(10),
    QUERY_WIFI(11),
    TOKEN_BIND(12),
    CONTROL_NEW(13),
    ENABLE_WIFI(14),
    DP_QUERY_NEW(16),
    SCENE_EXECUTE(17),
    UDP_NEW(19),
    AP_CONFIG_NEW(20);

    private final int code;

    TuyaCommand(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static TuyaCommand fromCode(int code) {
        for (TuyaCommand c : values()) {
            if (c.code == code) return c;
        }
        throw new IllegalArgumentException("Unknown Tuya command code: " + code);
    }
}
