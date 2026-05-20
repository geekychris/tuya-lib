package com.hitorro.tuya.protocol;

/**
 * Standard CRC-32 (IEEE 802.3, poly 0xEDB88320). Matches java.util.zip.CRC32 and
 * Node.js Buffer crc32. Kept as a tiny private helper so the protocol package is
 * self-contained and easy to unit-test.
 */
public final class Crc32 {

    private static final int[] TABLE = new int[256];

    static {
        for (int i = 0; i < 256; i++) {
            int c = i;
            for (int j = 0; j < 8; j++) {
                c = ((c & 1) != 0) ? (0xEDB88320 ^ (c >>> 1)) : (c >>> 1);
            }
            TABLE[i] = c;
        }
    }

    private Crc32() {
    }

    public static int compute(byte[] data, int offset, int length) {
        int crc = 0xFFFFFFFF;
        for (int i = offset; i < offset + length; i++) {
            crc = TABLE[(crc ^ data[i]) & 0xFF] ^ (crc >>> 8);
        }
        return crc ^ 0xFFFFFFFF;
    }
}
