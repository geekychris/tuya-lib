package com.hitorro.tuya.client;

import com.hitorro.tuya.crypto.TuyaCipher;
import com.hitorro.tuya.protocol.Crc32;
import com.hitorro.tuya.protocol.FrameCodec;
import com.hitorro.tuya.protocol.TuyaCommand;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class TuyaDiscoveryTest {

    @Test
    void parsesEncryptedBroadcast() {
        String json = "{\"ip\":\"192.168.1.42\",\"gwId\":\"abc123def456\",\"version\":\"3.3\",\"productKey\":\"keyABC\"}";
        byte[] encrypted = TuyaCipher.forUdpDiscovery().encrypt(json.getBytes(StandardCharsets.UTF_8));
        byte[] frame = buildUdpFrame(encrypted);
        TuyaDiscovery d = new TuyaDiscovery();
        DiscoveredDevice device = d.parsePacket(frame, frame.length, true);
        assertThat(device).isNotNull();
        assertThat(device.gwId()).isEqualTo("abc123def456");
        assertThat(device.ip()).isEqualTo("192.168.1.42");
        assertThat(device.version()).isEqualTo("3.3");
        assertThat(device.productKey()).isEqualTo("keyABC");
    }

    @Test
    void parsesPlaintextBroadcast() {
        String json = "{\"ip\":\"192.168.1.99\",\"gwId\":\"oldDevice\",\"version\":\"3.1\"}";
        byte[] frame = buildUdpFrame(json.getBytes(StandardCharsets.UTF_8));
        TuyaDiscovery d = new TuyaDiscovery();
        DiscoveredDevice device = d.parsePacket(frame, frame.length, false);
        assertThat(device).isNotNull();
        assertThat(device.gwId()).isEqualTo("oldDevice");
        assertThat(device.version()).isEqualTo("3.1");
    }

    private static byte[] buildUdpFrame(byte[] inner) {
        int payloadLen = inner.length + 8;
        ByteBuffer buf = ByteBuffer.allocate(16 + inner.length + 8);
        buf.putInt(FrameCodec.PREFIX);
        buf.putInt(0);
        buf.putInt(TuyaCommand.UDP.code());
        buf.putInt(payloadLen);
        buf.put(inner);
        byte[] sofar = buf.array();
        int crc = Crc32.compute(sofar, 0, 16 + inner.length);
        buf.putInt(crc);
        buf.putInt(FrameCodec.SUFFIX);
        return buf.array();
    }
}
