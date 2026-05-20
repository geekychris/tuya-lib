package com.hitorro.tuya.protocol;

import com.hitorro.tuya.crypto.TuyaCipher;
import com.hitorro.tuya.model.ProtocolVersion;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class FrameCodecTest {

    private static final String LOCAL_KEY = "0123456789abcdef"; // 16 ASCII bytes
    private final FrameCodec codec = new FrameCodec(
            ProtocolVersion.V3_3, TuyaCipher.forLocalKey(LOCAL_KEY));

    @Test
    void encodesFrameStructureCorrectly() {
        byte[] json = "{\"gwId\":\"abc\",\"devId\":\"abc\"}".getBytes(StandardCharsets.UTF_8);
        byte[] frame = codec.encode(1, TuyaCommand.DP_QUERY, json);

        ByteBuffer buf = ByteBuffer.wrap(frame);
        assertThat(buf.getInt()).isEqualTo(FrameCodec.PREFIX);
        assertThat(buf.getInt()).isEqualTo(1);                  // sequence
        assertThat(buf.getInt()).isEqualTo(TuyaCommand.DP_QUERY.code());
        int payloadLen = buf.getInt();
        // payloadLen covers payload + crc(4) + suffix(4); payload is 16-byte-aligned AES blocks
        assertThat(payloadLen).isEqualTo(frame.length - 16);
        // skip payload + crc(4); check suffix at the very end
        int suffix = ByteBuffer.wrap(frame, frame.length - 4, 4).getInt();
        assertThat(suffix).isEqualTo(FrameCodec.SUFFIX);
    }

    @Test
    void dpQueryDoesNotIncludeVersionHeader() {
        byte[] json = "{}".getBytes(StandardCharsets.UTF_8);
        byte[] frame = codec.encode(7, TuyaCommand.DP_QUERY, json);

        // Skip 16-byte header. Next bytes are the encrypted payload directly —
        // they should NOT begin with ASCII "3.3".
        byte[] firstPayloadBytes = new byte[3];
        System.arraycopy(frame, 16, firstPayloadBytes, 0, 3);
        assertThat(new String(firstPayloadBytes, StandardCharsets.US_ASCII)).isNotEqualTo("3.3");
    }

    @Test
    void controlIncludesV33VersionHeader() {
        byte[] json = "{\"dps\":{\"1\":true}}".getBytes(StandardCharsets.UTF_8);
        byte[] frame = codec.encode(42, TuyaCommand.CONTROL, json);

        // Skip 16-byte frame header. Next 3 bytes should be ASCII "3.3", followed by 12 zero bytes.
        byte[] versionTag = new byte[3];
        System.arraycopy(frame, 16, versionTag, 0, 3);
        assertThat(new String(versionTag, StandardCharsets.US_ASCII)).isEqualTo("3.3");
        for (int i = 19; i < 31; i++) {
            assertThat(frame[i]).as("zero byte at index %d", i).isEqualTo((byte) 0);
        }
    }

    @Test
    void roundTripsViaSyntheticResponse() {
        // We can't decode our own encoded request (no return code in request format).
        // So build a synthetic response frame mirroring what a device would send back
        // for a DP_QUERY, and verify decode recovers the JSON.
        String json = "{\"dps\":{\"1\":true,\"9\":0}}";
        byte[] plaintext = json.getBytes(StandardCharsets.UTF_8);
        byte[] encrypted = TuyaCipher.forLocalKey(LOCAL_KEY).encrypt(plaintext);
        byte[] frame = buildResponseFrame(99, TuyaCommand.DP_QUERY, 0, encrypted);

        TuyaMessage msg = codec.decode(ByteBuffer.wrap(frame));
        assertThat(msg).isNotNull();
        assertThat(msg.sequence()).isEqualTo(99);
        assertThat(msg.command()).isEqualTo(TuyaCommand.DP_QUERY);
        assertThat(msg.returnCode()).isEqualTo(0);
        assertThat(new String(msg.payload(), StandardCharsets.UTF_8)).isEqualTo(json);
    }

    @Test
    void stripsV33HeaderOnControlResponse() {
        String json = "{\"dps\":{\"1\":true}}";
        byte[] plaintext = json.getBytes(StandardCharsets.UTF_8);
        byte[] encrypted = TuyaCipher.forLocalKey(LOCAL_KEY).encrypt(plaintext);

        // Real device responses to CONTROL include the "3.3" + 12 zero header before
        // the encrypted bytes.
        byte[] withHeader = new byte[15 + encrypted.length];
        System.arraycopy("3.3".getBytes(StandardCharsets.US_ASCII), 0, withHeader, 0, 3);
        System.arraycopy(encrypted, 0, withHeader, 15, encrypted.length);

        byte[] frame = buildResponseFrame(7, TuyaCommand.CONTROL, 0, withHeader);
        TuyaMessage msg = codec.decode(ByteBuffer.wrap(frame));
        assertThat(new String(msg.payload(), StandardCharsets.UTF_8)).isEqualTo(json);
    }

    @Test
    void detectsBadCrc() {
        byte[] frame = buildResponseFrame(1, TuyaCommand.DP_QUERY, 0, new byte[0]);
        // Frame layout for empty inner: 16-byte header + 4-byte returnCode + 4-byte CRC + 4-byte suffix.
        // Flip a byte inside the stored CRC at offset 20 so computed CRC != stored CRC.
        frame[20] ^= 0x01;
        org.junit.jupiter.api.Assertions.assertThrows(
                TuyaProtocolException.class,
                () -> codec.decode(ByteBuffer.wrap(frame)));
    }

    /** Build a wire-format response frame with the given inner payload bytes. */
    private static byte[] buildResponseFrame(int seq, TuyaCommand cmd, int returnCode, byte[] inner) {
        int payloadLen = 4 + inner.length + 8; // returnCode + payload + crc + suffix
        ByteBuffer buf = ByteBuffer.allocate(16 + 4 + inner.length + 8);
        buf.putInt(FrameCodec.PREFIX);
        buf.putInt(seq);
        buf.putInt(cmd.code());
        buf.putInt(payloadLen);
        buf.putInt(returnCode);
        buf.put(inner);

        byte[] sofar = buf.array();
        int crc = computeCrc32(sofar, 0, 16 + 4 + inner.length);
        buf.putInt(crc);
        buf.putInt(FrameCodec.SUFFIX);
        return buf.array();
    }

    private static int computeCrc32(byte[] data, int offset, int length) {
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(data, offset, length);
        return (int) crc.getValue();
    }
}
