package com.hitorro.tuya.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hitorro.tuya.crypto.TuyaCipher;
import com.hitorro.tuya.protocol.Crc32;
import com.hitorro.tuya.protocol.FrameCodec;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Listens for Tuya UDP discovery broadcasts. Tuya devices broadcast every ~5s
 * on UDP port 6666 (v3.1, plaintext payload) or 6667 (v3.3+, AES-128-ECB
 * encrypted with md5("yGAdlopoPVldABfn")).
 *
 * <p>Frame layout is the same as TCP: 0x000055AA prefix · seq · cmd · payloadLen
 * · payload · CRC32 · 0x0000AA55 suffix. The payload is JSON like
 * {@code {"ip":"192.168.1.42","gwId":"abc...","version":"3.3", ...}}.
 */
public final class TuyaDiscovery implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(TuyaDiscovery.class.getName());
    public static final int PORT_PLAINTEXT = 6666;
    public static final int PORT_ENCRYPTED = 6667;

    private final ObjectMapper json = new ObjectMapper();
    private final TuyaCipher udpCipher = TuyaCipher.forUdpDiscovery();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private DatagramSocket plaintextSocket;
    private DatagramSocket encryptedSocket;
    private Thread plaintextThread;
    private Thread encryptedThread;

    public void start(Consumer<DiscoveredDevice> listener) {
        if (!running.compareAndSet(false, true)) return;
        try {
            this.plaintextSocket = new DatagramSocket(PORT_PLAINTEXT);
            this.encryptedSocket = new DatagramSocket(PORT_ENCRYPTED);
        } catch (Exception e) {
            running.set(false);
            throw new IllegalStateException("Failed to bind UDP discovery sockets (try sudo / check 6666/6667 not in use)", e);
        }

        this.plaintextThread = new Thread(
                () -> runLoop(plaintextSocket, listener, false), "tuya-discovery-plain");
        this.plaintextThread.setDaemon(true);
        this.plaintextThread.start();

        this.encryptedThread = new Thread(
                () -> runLoop(encryptedSocket, listener, true), "tuya-discovery-enc");
        this.encryptedThread.setDaemon(true);
        this.encryptedThread.start();
    }

    private void runLoop(DatagramSocket socket, Consumer<DiscoveredDevice> listener, boolean encrypted) {
        byte[] buf = new byte[1024];
        while (running.get() && !socket.isClosed()) {
            try {
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                socket.receive(packet);
                DiscoveredDevice device = parsePacket(packet.getData(), packet.getLength(), encrypted);
                if (device != null) listener.accept(device);
            } catch (Exception e) {
                if (running.get()) {
                    LOG.log(Level.FINE, "UDP discovery parse failed", e);
                }
            }
        }
    }

    DiscoveredDevice parsePacket(byte[] data, int length, boolean encrypted) {
        if (length < 20) return null;
        ByteBuffer buf = ByteBuffer.wrap(data, 0, length);
        int prefix = buf.getInt();
        if (prefix != FrameCodec.PREFIX) return null;
        buf.getInt(); // sequence
        buf.getInt(); // command
        int payloadLen = buf.getInt();
        if (payloadLen + 16 > length) return null;

        // UDP broadcasts have no return code in front of the payload.
        int innerLen = payloadLen - 8;
        byte[] inner = new byte[innerLen];
        buf.get(inner);
        int crc = buf.getInt();
        int suffix = buf.getInt();
        if (suffix != FrameCodec.SUFFIX) return null;
        int computed = Crc32.compute(data, 0, 16 + innerLen);
        if (computed != crc) return null;

        byte[] jsonBytes = encrypted ? udpCipher.decrypt(inner) : inner;
        // Strip an optional "3.3" + 12 zero version header.
        if (jsonBytes.length > 15
                && jsonBytes[0] == '3' && jsonBytes[1] == '.' && jsonBytes[2] == '3') {
            jsonBytes = Arrays.copyOfRange(jsonBytes, 15, jsonBytes.length);
        }
        // Strip trailing nulls.
        int end = jsonBytes.length;
        while (end > 0 && jsonBytes[end - 1] == 0) end--;
        if (end == 0) return null;

        try {
            JsonNode root = json.readTree(new String(jsonBytes, 0, end, StandardCharsets.UTF_8));
            String gwId = textOrNull(root, "gwId");
            String ip = textOrNull(root, "ip");
            String version = textOrNull(root, "version");
            String productKey = textOrNull(root, "productKey");
            boolean enc = root.has("encrypt") && root.get("encrypt").asBoolean();
            if (gwId == null || ip == null) return null;
            return new DiscoveredDevice(gwId, ip, version, productKey, enc, Instant.now());
        } catch (Exception e) {
            return null;
        }
    }

    private static String textOrNull(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    @Override
    public void close() {
        running.set(false);
        if (plaintextSocket != null) plaintextSocket.close();
        if (encryptedSocket != null) encryptedSocket.close();
        if (plaintextThread != null) plaintextThread.interrupt();
        if (encryptedThread != null) encryptedThread.interrupt();
    }
}
