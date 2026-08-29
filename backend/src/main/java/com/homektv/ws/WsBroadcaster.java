package com.homektv.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket 会话注册与事件广播（P1.14，详设§4.1/§4.2）。
 * 线程安全：会话存于 ConcurrentHashMap，发送时对单会话加锁（WebSocketSession 非并发安全）。
 *
 * WebSocket session registration and event broadcasting (P1.14, detailed design §4.1/§4.2).
 * Thread-safe: sessions stored in ConcurrentHashMap, with per-session locking during send
 * (WebSocketSession is not concurrency-safe).
 */
@Component
public class WsBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(WsBroadcaster.class);

    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final ObjectMapper mapper;
    private final ActivePlayerRegistry playerRegistry;
    private final ApplicationEventPublisher eventPublisher;

    @Autowired
    public WsBroadcaster(ObjectMapper mapper, ActivePlayerRegistry playerRegistry,
                         ApplicationEventPublisher eventPublisher) {
        this.mapper = mapper;
        this.playerRegistry = playerRegistry;
        this.eventPublisher = eventPublisher;
    }

    /** Test/backward-compatible constructor without an application event bus. */
    public WsBroadcaster(ObjectMapper mapper, ActivePlayerRegistry playerRegistry) {
        this(mapper, playerRegistry, event -> { });
    }

    /** Test/backward-compatible constructor; production uses the shared registry. */
    public WsBroadcaster(ObjectMapper mapper) {
        this(mapper, new ActivePlayerRegistry());
    }

    /** 记录每个会话的 client_type（tv/h5），用于 TV 在线检测（P2.13）。
     *  Records the client_type (tv/h5) of each session, used for TV online detection (P2.13). */
    private final Map<String, String> sessionTypes = new ConcurrentHashMap<>();

    /**
     * 注册 WebSocket 会话。
     *
     * Registers a WebSocket session.
     * @param session 要注册的 WebSocket 会话 / the WebSocket session to register
     */
    public void register(WebSocketSession session) {
        sessions.put(session.getId(), session);
        Object type = session.getAttributes().get("client_type");
        if (type != null) sessionTypes.put(session.getId(), type.toString());
    }

    /**
     * 注销会话，返回其 client_type（tv/h5，可能为 null），供离线清理等逻辑判断。
     *
     * Unregisters a session and returns its client_type (tv/h5, may be null) for offline cleanup logic.
     * @param session 要注销的 WebSocket 会话 / the WebSocket session to unregister
     * @return client_type（tv/h5，可能为 null）/ the client_type (tv/h5, may be null)
     */
    public String unregister(WebSocketSession session) {
        sessions.remove(session.getId());
        return sessionTypes.remove(session.getId());
    }

    public int sessionCount() {
        return sessions.size();
    }

    /**
     * TV 是否在线（详设§4.5：点了歌但 TV 不在线时 H5 显示横幅）。
     *
     * Whether the TV is online (detailed design §4.5: H5 shows a banner when songs are queued but TV is offline).
     * @return true if TV is online
     */
    public boolean isTvOnline() {
        return sessionTypes.containsValue("tv");
    }

    /**
     * 已连接的 H5 手机数。
     *
     * Number of connected H5 mobile clients.
     * @return the count of connected H5 sessions
     */
    public long h5Count() {
        return sessionTypes.values().stream().filter("h5"::equals).count();
    }

    /**
     * 向单个会话发送事件（如连接后的 sync_full）。
     *
     * Sends an event to a single session (e.g. sync_full after connection).
     * @param session 目标 WebSocket 会话 / the target WebSocket session
     * @param event 要发送的事件 / the event to send
     */
    public void sendTo(WebSocketSession session, WsEvent event) {
        send(session, serialize(event));
    }

    /** Removes and closes a fenced player session so the client can reconnect cleanly. */
    public String disconnect(String sessionId) {
        WebSocketSession session = sessions.remove(sessionId);
        String type = sessionTypes.remove(sessionId);
        if (session != null && session.isOpen()) {
            try {
                session.close(org.springframework.web.socket.CloseStatus.POLICY_VIOLATION);
            } catch (IOException ignored) {
                // The session is already unusable and has been removed locally.
            }
        }
        return type;
    }

    /** Sends an event to a registered session without exposing the session map. */
    public void sendTo(String sessionId, WsEvent event) {
        WebSocketSession session = sessions.get(sessionId);
        if (session != null) {
            sendTo(session, event);
        }
    }

    /**
     * 向所有在线会话广播事件。
     *
     * Broadcasts an event to all online sessions.
     * @param event 要广播的事件 / the event to broadcast
     */
    public void broadcast(WsEvent event) {
        String json = serialize(event);
        for (WebSocketSession s : sessions.values()) {
            send(s, json);
        }
    }

    /**
     * Broadcasts playback commands/state to H5 observers and the single active
     * TV player. Standby TV sessions never receive commands that could start a
     * second projection.
     */
    public void broadcastPlayback(WsEvent event) {
        String json = serialize(event);
        String activeSessionId = playerRegistry.activeSessionId().orElse(null);
        for (WebSocketSession session : sessions.values()) {
            String type = sessionTypes.get(session.getId());
            if (!"tv".equals(type) || session.getId().equals(activeSessionId)) {
                send(session, json);
            }
        }
    }

    private void send(WebSocketSession session, String json) {
        if (json == null || !session.isOpen()) return;
        try {
            synchronized (session) {
                session.sendMessage(new TextMessage(json));
            }
        } catch (IOException e) {
            log.debug("发送失败，移除会话 {}: {}", session.getId(), e.getMessage());
            String clientType = unregister(session);
            ActivePlayerRegistry.Unregistration removal = playerRegistry.unregisterPlayer(session.getId());
            eventPublisher.publishEvent(new WsSessionDisconnectedEvent(session.getId(), clientType, removal));
        }
    }

    private String serialize(WsEvent event) {
        try {
            return mapper.writeValueAsString(event);
        } catch (Exception e) {
            log.warn("事件序列化失败: {}", e.getMessage());
            return null;
        }
    }
}
