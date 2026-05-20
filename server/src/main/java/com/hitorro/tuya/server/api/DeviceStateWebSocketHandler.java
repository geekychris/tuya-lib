package com.hitorro.tuya.server.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hitorro.tuya.model.DpsState;
import com.hitorro.tuya.server.device.DeviceManager;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Broadcasts {@code {"deviceId":..., "dps":{...}, "at":"..."}} on every device state
 * change to all connected WebSocket clients at {@code /ws/devices}.
 */
@Component
public class DeviceStateWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(DeviceStateWebSocketHandler.class);

    private final Set<WebSocketSession> sessions = new CopyOnWriteArraySet<>();
    private final ObjectMapper json = new ObjectMapper();
    private final DeviceManager manager;

    public DeviceStateWebSocketHandler(DeviceManager manager) {
        this.manager = manager;
    }

    @PostConstruct
    void wireBroadcaster() {
        manager.setStateBroadcaster(this::broadcast);
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.add(session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, org.springframework.web.socket.CloseStatus status) {
        sessions.remove(session);
    }

    private void broadcast(Long deviceId, DpsState state) {
        Map<String, Object> payload = Map.of(
                "deviceId", deviceId,
                "dps", state.dps(),
                "at", state.at().toString());
        String text;
        try {
            text = json.writeValueAsString(payload);
        } catch (Exception e) {
            log.warn("Failed to serialize device state", e);
            return;
        }
        TextMessage message = new TextMessage(text);
        for (WebSocketSession s : sessions) {
            try {
                if (s.isOpen()) s.sendMessage(message);
            } catch (IOException e) {
                log.debug("Failed to send WS message; removing session", e);
                sessions.remove(s);
            }
        }
    }
}
