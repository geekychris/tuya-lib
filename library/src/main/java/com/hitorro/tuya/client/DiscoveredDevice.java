package com.hitorro.tuya.client;

import java.time.Instant;

/**
 * One Tuya device discovered via UDP broadcast. {@code productKey} and
 * {@code version} come straight from the broadcast JSON. {@code localKey} is
 * NOT present in broadcasts — you still need to fetch it from the Tuya IoT
 * Platform.
 */
public record DiscoveredDevice(
        String gwId,
        String ip,
        String version,
        String productKey,
        boolean encrypt,
        Instant seenAt
) {
}
