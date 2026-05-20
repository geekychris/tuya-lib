package com.hitorro.tuya.cloud;

public enum TuyaCloudRegion {
    US("https://openapi.tuyaus.com"),
    EU("https://openapi.tuyaeu.com"),
    CN("https://openapi.tuyacn.com"),
    IN("https://openapi.tuyain.com");

    private final String baseUrl;

    TuyaCloudRegion(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String baseUrl() {
        return baseUrl;
    }

    public static TuyaCloudRegion fromCode(String code) {
        if (code == null) return US;
        return switch (code.toLowerCase()) {
            case "us" -> US;
            case "eu" -> EU;
            case "cn" -> CN;
            case "in" -> IN;
            default -> throw new IllegalArgumentException("Unknown Tuya cloud region: " + code);
        };
    }
}
