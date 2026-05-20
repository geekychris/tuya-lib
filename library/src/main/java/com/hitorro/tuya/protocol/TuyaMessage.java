package com.hitorro.tuya.protocol;

/**
 * A decoded frame from the wire. {@code returnCode} is only meaningful on
 * responses from the device (0 = success). {@code payload} is the raw post-decryption
 * payload bytes — for v3.3 SET responses this still contains the "3.3" + 12-byte
 * header which {@link FrameCodec} strips for you.
 */
public record TuyaMessage(
        int sequence,
        TuyaCommand command,
        int returnCode,
        byte[] payload
) {
}
