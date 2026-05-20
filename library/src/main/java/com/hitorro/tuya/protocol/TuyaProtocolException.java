package com.hitorro.tuya.protocol;

public class TuyaProtocolException extends RuntimeException {
    public TuyaProtocolException(String message) {
        super(message);
    }

    public TuyaProtocolException(String message, Throwable cause) {
        super(message, cause);
    }
}
