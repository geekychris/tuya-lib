package com.hitorro.tuya.cloud;

public class TuyaCloudException extends RuntimeException {
    public TuyaCloudException(String message) {
        super(message);
    }

    public TuyaCloudException(String message, Throwable cause) {
        super(message, cause);
    }
}
