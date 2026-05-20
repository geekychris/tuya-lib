package com.hitorro.tuya.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hitorro.tuya.crypto.TuyaCipher;
import com.hitorro.tuya.model.DeviceConfig;
import com.hitorro.tuya.model.DpsState;
import com.hitorro.tuya.protocol.FrameCodec;
import com.hitorro.tuya.protocol.TuyaCommand;
import com.hitorro.tuya.protocol.TuyaMessage;
import com.hitorro.tuya.protocol.TuyaProtocolException;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Long-lived client for a single Tuya device on the LAN.
 *
 * <p>Owns a single {@link SocketChannel} to {@code <ip>:6668}, sends a heartbeat
 * every 10 s, and matches responses back to outstanding requests via the
 * sequence number. {@link #connect()} returns once the socket is open and the
 * reader/heartbeat threads are running. Call {@link #close()} when done.
 *
 * <p>Thread safety: public methods are safe to call from any thread.
 */
public final class TuyaDevice implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(TuyaDevice.class.getName());
    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(10);
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);

    private final DeviceConfig config;
    private final FrameCodec codec;
    private final ObjectMapper json = new ObjectMapper();
    private final AtomicInteger sequence = new AtomicInteger(0);
    private final Map<Integer, CompletableFuture<TuyaMessage>> pending = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler;

    private volatile SocketChannel channel;
    private volatile Thread readerThread;
    private volatile ScheduledFuture<?> heartbeatTask;
    private volatile boolean closed = false;
    private volatile Consumer<DpsState> stateListener = state -> { };

    public TuyaDevice(DeviceConfig config) {
        this.config = config;
        this.codec = new FrameCodec(config.protocolVersion(), TuyaCipher.forLocalKey(config.localKey()));
        String tag = config.gwId().substring(0, Math.min(8, config.gwId().length()));
        this.scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "tuya-" + tag);
            t.setDaemon(true);
            return t;
        });
    }

    /** Register a callback for unsolicited STATUS messages pushed by the device. */
    public void onStateChange(Consumer<DpsState> listener) {
        this.stateListener = listener == null ? (s -> { }) : listener;
    }

    public synchronized void connect() throws IOException {
        if (closed) throw new IllegalStateException("Device closed");
        if (channel != null && channel.isOpen()) return;

        SocketChannel ch = SocketChannel.open();
        ch.configureBlocking(true);
        ch.socket().connect(new InetSocketAddress(config.ip(), config.port()), 5000);
        this.channel = ch;

        Thread reader = new Thread(this::readLoop, "tuya-reader-" + config.ip());
        reader.setDaemon(true);
        reader.start();
        this.readerThread = reader;

        this.heartbeatTask = scheduler.scheduleAtFixedRate(
                this::sendHeartbeatQuietly,
                HEARTBEAT_INTERVAL.toMillis(),
                HEARTBEAT_INTERVAL.toMillis(),
                TimeUnit.MILLISECONDS);
    }

    public DpsState get() throws IOException, InterruptedException, TimeoutException {
        ObjectNode payload = json.createObjectNode();
        payload.put("gwId", config.gwId());
        payload.put("devId", config.gwId());
        payload.put("uid", config.gwId());
        payload.put("t", Long.toString(Instant.now().getEpochSecond()));
        payload.putObject("dps");
        TuyaMessage response = requestWithFallback(TuyaCommand.DP_QUERY, TuyaCommand.DP_QUERY_NEW, payload);
        return parseDpsResponse(response);
    }

    public DpsState set(Map<String, Object> dps) throws IOException, InterruptedException, TimeoutException {
        ObjectNode payload = json.createObjectNode();
        payload.put("devId", config.gwId());
        payload.put("gwId", config.gwId());
        payload.put("uid", "");
        payload.put("t", Long.toString(Instant.now().getEpochSecond()));
        ObjectNode dpsNode = payload.putObject("dps");
        for (Map.Entry<String, Object> e : dps.entrySet()) {
            dpsNode.putPOJO(e.getKey(), e.getValue());
        }
        TuyaMessage response = requestWithFallback(TuyaCommand.CONTROL, TuyaCommand.CONTROL_NEW, payload);
        // Devices may echo state after a SET; otherwise fall back to a GET.
        DpsState fromSet = parseDpsResponse(response);
        if (!fromSet.dps().isEmpty()) return fromSet;
        return get();
    }

    /**
     * Try the classic v3.3 command first; if the socket dies (some devices refuse
     * the classic CONTROL/DP_QUERY opcodes and just close the connection), retry
     * once with the *_NEW variant. Caches which one worked for subsequent calls.
     */
    private volatile boolean useNewCommands = false;
    private TuyaMessage requestWithFallback(TuyaCommand classic, TuyaCommand newer, ObjectNode payload)
            throws IOException, InterruptedException, TimeoutException {
        TuyaCommand first = useNewCommands ? newer : classic;
        try {
            return request(first, payload, DEFAULT_TIMEOUT);
        } catch (IOException io) {
            if (useNewCommands) throw io;
            LOG.info(() -> "Tuya " + config.ip() + " " + classic + " failed (" + io.getMessage()
                    + ") — retrying as " + newer);
            useNewCommands = true;
            // Reconnect: the device closed the socket on us.
            try { if (channel != null) channel.close(); } catch (IOException ignore) { }
            connect();
            return request(newer, payload, DEFAULT_TIMEOUT);
        }
    }

    private TuyaMessage request(TuyaCommand cmd, JsonNode payload, Duration timeout)
            throws IOException, InterruptedException, TimeoutException {
        SocketChannel ch = channel;
        if (ch == null || !ch.isOpen()) {
            connect();
            ch = channel;
        }

        int seq = sequence.incrementAndGet();
        byte[] body = json.writeValueAsBytes(payload);
        byte[] frame = codec.encode(seq, cmd, body);

        CompletableFuture<TuyaMessage> future = new CompletableFuture<>();
        pending.put(seq, future);

        ByteBuffer toSend = ByteBuffer.wrap(frame);
        synchronized (writeLock) {
            while (toSend.hasRemaining()) ch.write(toSend);
        }

        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            pending.remove(seq);
            if (e.getCause() instanceof IOException io) throw io;
            throw new IOException("Request failed", e.getCause());
        } catch (TimeoutException e) {
            pending.remove(seq);
            throw e;
        }
    }

    private final Object writeLock = new Object();

    private void readLoop() {
        ByteBuffer accum = ByteBuffer.allocate(64 * 1024);
        ByteBuffer recv = ByteBuffer.allocate(8 * 1024);
        SocketChannel ch = channel;
        try {
            while (!closed && ch.isOpen()) {
                recv.clear();
                int n = ch.read(recv);
                if (n < 0) {
                    LOG.fine(() -> "Tuya " + config.ip() + " EOF (device closed socket)");
                    break;
                }
                if (n == 0) continue;
                recv.flip();
                if (LOG.isLoggable(Level.FINE)) {
                    LOG.fine("Tuya " + config.ip() + " read " + n + " bytes");
                }
                accum.put(recv);
                accum.flip();
                drain(accum);
                accum.compact();
            }
        } catch (Exception e) {
            if (!closed) LOG.log(Level.WARNING, "Read loop ended for " + config.ip(), e);
        } finally {
            failPending(new IOException("Connection closed"));
        }
    }

    private void drain(ByteBuffer accum) {
        while (true) {
            int markStart = accum.position();
            TuyaMessage msg;
            try {
                msg = codec.decode(accum);
            } catch (TuyaProtocolException e) {
                LOG.log(Level.WARNING, "Bad frame from " + config.ip() + ": " + e.getMessage());
                accum.position(accum.limit()); // discard everything; let the device resync
                return;
            }
            if (msg == null) {
                accum.position(markStart);
                return;
            }
            dispatch(msg);
        }
    }

    private void dispatch(TuyaMessage msg) {
        CompletableFuture<TuyaMessage> waiting = pending.remove(msg.sequence());
        if (waiting != null) {
            waiting.complete(msg);
        }
        if (msg.command() == TuyaCommand.STATUS && msg.payload().length > 0) {
            try {
                DpsState state = parseDpsResponse(msg);
                if (!state.dps().isEmpty()) stateListener.accept(state);
            } catch (Exception e) {
                LOG.log(Level.FINE, "Failed to parse STATUS push", e);
            }
        }
    }

    private DpsState parseDpsResponse(TuyaMessage msg) {
        if (msg.payload().length == 0) return DpsState.empty();
        try {
            String text = new String(msg.payload(), StandardCharsets.UTF_8).trim();
            // Devices sometimes pad with trailing nulls; trim() handles ASCII whitespace, strip trailing 0x00 separately.
            text = stripTrailingNulls(text);
            if (text.isEmpty()) return DpsState.empty();
            JsonNode root = json.readTree(text);
            JsonNode dps = root.get("dps");
            if (dps == null || !dps.isObject()) return DpsState.empty();
            java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
            Iterator<Map.Entry<String, JsonNode>> it = dps.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                map.put(e.getKey(), unwrap(e.getValue()));
            }
            return new DpsState(map, Instant.now());
        } catch (Exception e) {
            throw new TuyaProtocolException("Failed to parse DPS response", e);
        }
    }

    private static String stripTrailingNulls(String s) {
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == '\0') end--;
        return s.substring(0, end);
    }

    private static Object unwrap(JsonNode n) {
        if (n.isBoolean()) return n.asBoolean();
        if (n.isInt() || n.isLong()) return n.asLong();
        if (n.isDouble() || n.isFloat()) return n.asDouble();
        if (n.isTextual()) return n.asText();
        return n.toString();
    }

    private void sendHeartbeatQuietly() {
        try {
            int seq = sequence.incrementAndGet();
            byte[] frame = codec.encode(seq, TuyaCommand.HEART_BEAT, new byte[0]);
            ByteBuffer buf = ByteBuffer.wrap(frame);
            synchronized (writeLock) {
                while (buf.hasRemaining()) channel.write(buf);
            }
        } catch (Exception e) {
            LOG.log(Level.FINE, "Heartbeat failed for " + config.ip(), e);
        }
    }

    private void failPending(Throwable cause) {
        for (CompletableFuture<TuyaMessage> f : pending.values()) {
            f.completeExceptionally(cause);
        }
        pending.clear();
    }

    public DeviceConfig config() {
        return config;
    }

    public boolean isConnected() {
        SocketChannel ch = channel;
        return ch != null && ch.isOpen();
    }

    @Override
    public synchronized void close() {
        closed = true;
        if (heartbeatTask != null) heartbeatTask.cancel(false);
        scheduler.shutdownNow();
        try {
            if (channel != null) channel.close();
        } catch (IOException ignore) { }
        if (readerThread != null) readerThread.interrupt();
        failPending(new IOException("Closed"));
    }
}
