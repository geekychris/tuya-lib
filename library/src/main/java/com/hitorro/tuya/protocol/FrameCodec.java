package com.hitorro.tuya.protocol;

import com.hitorro.tuya.crypto.TuyaCipher;
import com.hitorro.tuya.model.ProtocolVersion;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Encode/decode Tuya LAN protocol frames for v3.3 (the most common version).
 *
 * <pre>
 * prefix(4)=0x000055AA | seq(4) | cmd(4) | payloadLen(4) |
 *   [returnCode(4) on responses only] | payload | crc32(4) | suffix(4)=0x0000AA55
 * </pre>
 *
 * <p>payloadLen covers everything from itself onward except itself, i.e.
 * (optional returnCode) + payload + crc + suffix.
 *
 * <p>For v3.3:
 * <ul>
 *   <li>CONTROL / CONTROL_NEW / DP_QUERY_NEW payloads are framed as ASCII "3.3"
 *       + 12 zero bytes + AES-128-ECB(localKey, jsonBytes).</li>
 *   <li>DP_QUERY / DP_REFRESH payloads are just AES-128-ECB(localKey, jsonBytes).</li>
 *   <li>HEART_BEAT has an empty payload (no encryption).</li>
 * </ul>
 */
public final class FrameCodec {

    public static final int PREFIX = 0x000055AA;
    public static final int SUFFIX = 0x0000AA55;
    public static final int PREFIX_V35 = 0x00006699;
    public static final int SUFFIX_V35 = 0x00009966;
    static final byte[] V33_HEADER;

    static {
        V33_HEADER = new byte[15];
        byte[] tag = "3.3".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(tag, 0, V33_HEADER, 0, tag.length);
    }

    private final TuyaCipher cipher;
    private final ProtocolVersion version;

    public FrameCodec(ProtocolVersion version, TuyaCipher cipher) {
        if (version != ProtocolVersion.V3_3 && version != ProtocolVersion.V3_5) {
            throw new IllegalArgumentException(
                    "Only protocol v3.3 and v3.5 are implemented; got " + version);
        }
        this.version = version;
        this.cipher = cipher;
    }

    /** Encode a request to send to the device. */
    public byte[] encode(int sequence, TuyaCommand command, byte[] jsonPayload) {
        if (version == ProtocolVersion.V3_5) return encodeV35(sequence, command, jsonPayload);
        byte[] framed = framePayload(command, jsonPayload);
        return wrap(sequence, command, framed);
    }

    private byte[] encodeV35(int sequence, TuyaCommand command, byte[] plaintext) {
        // Frame: prefix(4=0x00006699) reserved(2=0x0000) seq(4) cmd(4) length(4)
        //        IV(12) ciphertext(plaintext.len) tag(16) suffix(4=0x00009966)
        // length covers IV + ciphertext + tag (NOT the suffix).
        if (plaintext == null) plaintext = new byte[0];
        byte[] iv = TuyaCipher.randomIv(12);
        // Header bytes that go into the GCM AAD = bytes 4..18 (14 bytes after prefix)
        ByteBuffer header = ByteBuffer.allocate(18);
        header.putInt(PREFIX_V35);
        header.putShort((short) 0);
        header.putInt(sequence);
        header.putInt(command.code());
        header.putInt(plaintext.length + 28); // 12(iv) + plaintext + 16(tag)
        byte[] aad = new byte[14];
        System.arraycopy(header.array(), 4, aad, 0, 14);

        byte[] ctWithTag = cipher.gcmEncrypt(plaintext, iv, aad);

        ByteBuffer out = ByteBuffer.allocate(18 + 12 + ctWithTag.length + 4);
        out.put(header.array());
        out.put(iv);
        out.put(ctWithTag);
        out.putInt(SUFFIX_V35);
        return out.array();
    }

    private byte[] framePayload(TuyaCommand command, byte[] jsonPayload) {
        if (jsonPayload == null || jsonPayload.length == 0) {
            return new byte[0];
        }
        byte[] encrypted = cipher.encrypt(jsonPayload);
        if (needsVersionHeader(command)) {
            byte[] out = new byte[V33_HEADER.length + encrypted.length];
            System.arraycopy(V33_HEADER, 0, out, 0, V33_HEADER.length);
            System.arraycopy(encrypted, 0, out, V33_HEADER.length, encrypted.length);
            return out;
        }
        return encrypted;
    }

    private static boolean needsVersionHeader(TuyaCommand command) {
        // Tuya v3.3 prepends "3.3" + 12 zero bytes to everything EXCEPT
        // DP_QUERY / DP_REFRESH (and unencrypted commands like HEART_BEAT,
        // which are handled by the empty-payload short-circuit above).
        return command != TuyaCommand.DP_QUERY;
    }

    private byte[] wrap(int sequence, TuyaCommand command, byte[] payload) {
        int payloadLen = payload.length + 8; // crc(4) + suffix(4)
        ByteBuffer buf = ByteBuffer.allocate(16 + payload.length + 8);
        buf.putInt(PREFIX);
        buf.putInt(sequence);
        buf.putInt(command.code());
        buf.putInt(payloadLen);
        buf.put(payload);

        byte[] sofar = buf.array();
        int crc = Crc32.compute(sofar, 0, 16 + payload.length);
        buf.putInt(crc);
        buf.putInt(SUFFIX);
        return buf.array();
    }

    /**
     * Decode one frame from {@code buffer}. The buffer must be positioned at
     * the start of a frame and must contain at least one complete frame.
     * Returns null if there aren't enough bytes yet (caller should accumulate).
     */
    public TuyaMessage decode(ByteBuffer buffer) {
        if (version == ProtocolVersion.V3_5) return decodeV35(buffer);
        if (buffer.remaining() < 20) return null;
        int markStart = buffer.position();

        int prefix = buffer.getInt();
        if (prefix != PREFIX) {
            throw new TuyaProtocolException(
                    "Bad frame prefix: 0x" + Integer.toHexString(prefix));
        }
        int sequence = buffer.getInt();
        int commandCode = buffer.getInt();
        int payloadLen = buffer.getInt();

        if (buffer.remaining() < payloadLen) {
            buffer.position(markStart);
            return null;
        }

        // payloadLen covers returnCode(4) + payload + crc(4) + suffix(4).
        int returnCode = buffer.getInt();
        int innerLen = payloadLen - 12;
        byte[] inner = new byte[innerLen];
        buffer.get(inner);
        int crc = buffer.getInt();
        int suffix = buffer.getInt();
        if (suffix != SUFFIX) {
            throw new TuyaProtocolException(
                    "Bad frame suffix: 0x" + Integer.toHexString(suffix));
        }

        int frameLen = 16 + payloadLen - 8; // up to and including payload
        int computedCrc = Crc32.compute(buffer.array(), markStart, frameLen);
        if (computedCrc != crc) {
            throw new TuyaProtocolException(
                    "CRC mismatch: expected " + Integer.toHexString(crc)
                            + " got " + Integer.toHexString(computedCrc));
        }

        TuyaCommand command = TuyaCommand.fromCode(commandCode);
        byte[] payload = decodePayload(inner);
        return new TuyaMessage(sequence, command, returnCode, payload);
    }

    private TuyaMessage decodeV35(ByteBuffer buffer) {
        // Need: prefix(4) reserved(2) seq(4) cmd(4) length(4) + length bytes + suffix(4) = 22 + length
        if (buffer.remaining() < 22) return null;
        int markStart = buffer.position();

        int prefix = buffer.getInt();
        if (prefix != PREFIX_V35) {
            throw new TuyaProtocolException(
                    "Bad v3.5 frame prefix: 0x" + Integer.toHexString(prefix));
        }
        buffer.getShort(); // reserved
        int sequence = buffer.getInt();
        int commandCode = buffer.getInt();
        int length = buffer.getInt(); // covers IV + ct + tag (not suffix)
        if (length < 28 || buffer.remaining() < length + 4) {
            buffer.position(markStart);
            return null;
        }

        byte[] iv = new byte[12];
        buffer.get(iv);
        int ctLen = length - 12 - 16; // ciphertext length without the trailing 16-byte tag
        byte[] ctWithTag = new byte[ctLen + 16];
        buffer.get(ctWithTag);
        int suffix = buffer.getInt();
        if (suffix != SUFFIX_V35) {
            throw new TuyaProtocolException(
                    "Bad v3.5 frame suffix: 0x" + Integer.toHexString(suffix));
        }

        byte[] aad = new byte[14];
        System.arraycopy(buffer.array(), markStart + 4, aad, 0, 14);

        byte[] plaintext;
        try {
            plaintext = cipher.gcmDecrypt(ctWithTag, iv, aad);
        } catch (Exception e) {
            throw new TuyaProtocolException("AES-GCM decrypt/auth failed: " + e.getMessage(), e);
        }

        // v3.5 response payloads sometimes start with a 4-byte return code BE
        // followed by the JSON. Detect by scanning for the first '{' (0x7B).
        int returnCode = 0;
        int jsonStart = 0;
        if (plaintext.length >= 4) {
            // Look for the first opening brace in the first 8 bytes
            for (int i = 0; i < Math.min(plaintext.length, 8); i++) {
                if (plaintext[i] == '{' || plaintext[i] == '[') { jsonStart = i; break; }
            }
            if (jsonStart >= 4) {
                returnCode = ((plaintext[jsonStart - 4] & 0xFF) << 24)
                        | ((plaintext[jsonStart - 3] & 0xFF) << 16)
                        | ((plaintext[jsonStart - 2] & 0xFF) << 8)
                        | (plaintext[jsonStart - 1] & 0xFF);
            }
        }
        byte[] payload = (jsonStart == 0)
                ? plaintext
                : java.util.Arrays.copyOfRange(plaintext, jsonStart, plaintext.length);

        TuyaCommand command = TuyaCommand.fromCode(commandCode);
        return new TuyaMessage(sequence, command, returnCode, payload);
    }

    private byte[] decodePayload(byte[] inner) {
        if (inner.length == 0) return inner;

        byte[] cipherBytes = inner;
        if (startsWith(inner, V33_HEADER, 3)) {
            // "3.3" + 12 zero bytes precede the encrypted payload
            cipherBytes = Arrays.copyOfRange(inner, V33_HEADER.length, inner.length);
        }
        if (cipherBytes.length == 0) return cipherBytes;
        return cipher.decrypt(cipherBytes);
    }

    private static boolean startsWith(byte[] haystack, byte[] needle, int compareLen) {
        if (haystack.length < needle.length) return false;
        for (int i = 0; i < compareLen; i++) {
            if (haystack[i] != needle[i]) return false;
        }
        return true;
    }

    public ProtocolVersion version() {
        return version;
    }
}
