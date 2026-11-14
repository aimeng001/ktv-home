package com.homektv.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * WebSocket 会话注册与事件广播（P1.14，详设§4.1/§4.2）。
 * 线程安全：会话存于 ConcurrentHashMap；每个会话通过有界 FIFO 串行发送。
 * 活动播放器使用独立的有界发送池，避免观察者连接拖住播放控制。
 *
 * WebSocket session registration and event broadcasting (P1.14, detailed design §4.1/§4.2).
 * Thread-safe: sessions are stored in a ConcurrentHashMap and each connection has a bounded,
 * ordered outbound queue. The active player has a separate bounded sender pool so observers
 * cannot delay playback controls.
 */
@Component
public class WsBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(WsBroadcaster.class);
    private static final long DEFAULT_SEND_TIMEOUT_MILLIS = 10_000L;
    private static final ScheduledThreadPoolExecutor SEND_TIMEOUTS = createSendTimeoutScheduler();
    private static final ThreadPoolExecutor ACTIVE_PLAYER_SENDERS = createSenderPool(
            2, 64, "ktv-ws-player-sender-");
    private static final ThreadPoolExecutor OBSERVER_SENDERS = createSenderPool(
            16, 256, "ktv-ws-observer-sender-");

    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, SessionOutboundQueue> outboundQueues = new ConcurrentHashMap<>();
    private final ObjectMapper mapper;
    private final ActivePlayerRegistry playerRegistry;
    private final ApplicationEventPublisher eventPublisher;
    private final long outboundSendTimeoutMillis;

    @Autowired
    public WsBroadcaster(ObjectMapper mapper, ActivePlayerRegistry playerRegistry,
                         ApplicationEventPublisher eventPublisher) {
        this(mapper, playerRegistry, eventPublisher, DEFAULT_SEND_TIMEOUT_MILLIS);
    }

    WsBroadcaster(ObjectMapper mapper, ActivePlayerRegistry playerRegistry,
                  ApplicationEventPublisher eventPublisher, long outboundSendTimeoutMillis) {
        if (outboundSendTimeoutMillis < 1) {
            throw new IllegalArgumentException("outbound send timeout must be positive");
        }
        this.mapper = mapper;
        this.playerRegistry = playerRegistry;
        this.eventPublisher = eventPublisher;
        this.outboundSendTimeoutMillis = outboundSendTimeoutMillis;
    }

    /** Test/backward-compatible constructor without an application event bus. */
    public WsBroadcaster(ObjectMapper mapper, ActivePlayerRegistry playerRegistry) {
        this(mapper, playerRegistry, event -> { });
    }

    /** Test/backward-compatible constructor; production uses the shared registry. */
    public WsBroadcaster(ObjectMapper mapper) {
        this(mapper, new ActivePlayerRegistry());
    }

    /** 记录每个会话的 client_type（tv/h5/controller），用于在线统计。 */
    private final Map<String, String> sessionTypes = new ConcurrentHashMap<>();

    /**
     * 注册 WebSocket 会话。
     *
     * Registers a WebSocket session.
     * @param session 要注册的 WebSocket 会话 / the WebSocket session to register
     */
    public void register(WebSocketSession session) {
        WebSocketSession outboundSession = session instanceof ConcurrentWebSocketSessionDecorator
                ? session
                : new ConcurrentWebSocketSessionDecorator(session, 10_000, WsProtocolLimits.MAX_MESSAGE_BYTES);
        WebSocketSession previous = sessions.put(session.getId(), outboundSession);
        if (previous != null && previous != outboundSession) closeOutboundQueue(session.getId());
        Object type = session.getAttributes().get("client_type");
        if (type != null) sessionTypes.put(session.getId(), type.toString());
    }

    /**
     * 注销会话，返回其 client_type（tv/h5/controller，可能为 null），供离线清理等逻辑判断。
     *
     * Unregisters a session and returns its client_type (tv/h5, may be null) for offline cleanup logic.
     * @param session 要注销的 WebSocket 会话 / the WebSocket session to unregister
     * @return client_type（tv/h5，可能为 null）/ the client_type (tv/h5, may be null)
     */
    public String unregister(WebSocketSession session) {
        sessions.remove(session.getId());
        closeOutboundQueue(session.getId());
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
        if (!sessionTypes.containsValue("tv")) {
            return false;
        }
        return playerRegistry.connectedPlayerCount() == 0 || playerRegistry.activeSessionId().isPresent();
    }

    /** Number of connected browser H5 sessions (native controllers excluded). */
    public long h5Count() {
        return sessionTypes.values().stream().filter("h5"::equals).count();
    }

    /** Number of connected native controller sessions. */
    public long controllerCount() {
        return sessionTypes.values().stream().filter("controller"::equals).count();
    }

    /** Legacy QueueSnapshot field: browser H5 plus native controller observers. */
    public long connectedPhonesCount() {
        return h5Count() + controllerCount();
    }

    /**
     * 向单个会话发送事件（如连接后的 sync_full）。
     *
     * Sends an event to a single session (e.g. sync_full after connection).
     * @param session 目标 WebSocket 会话 / the target WebSocket session
     * @param event 要发送的事件 / the event to send
     */
    public void sendTo(WebSocketSession session, WsEvent event) {
        WebSocketSession outboundSession = sessions.get(session.getId());
        if (outboundSession != null) sendPrepared(outboundSession, prepareOutbound(event));
    }

    /** Removes and closes a fenced player session so the client can reconnect cleanly. */
    public String disconnect(String sessionId) {
        WebSocketSession session = sessions.remove(sessionId);
        String type = sessionTypes.remove(sessionId);
        closeOutboundQueue(sessionId);
        if (session != null && session.isOpen()) {
            closeSessionAsync(session, CloseStatus.POLICY_VIOLATION);
        }
        return type;
    }

    /** Sends an event to a registered session without exposing the session map. */
    public void sendTo(String sessionId, WsEvent event) {
        WebSocketSession session = sessions.get(sessionId);
        if (session != null) {
            sendPrepared(session, prepareOutbound(event));
        }
    }

    /**
     * 向所有在线会话广播事件。
     *
     * Broadcasts an event to all online sessions.
     * @param event 要广播的事件 / the event to broadcast
     */
    public void broadcast(WsEvent event) {
        OutboundBatch batch = prepareOutbound(event);
        for (WebSocketSession s : sessions.values()) {
            sendPrepared(s, batch);
        }
    }

    /** Broadcasts a complete state snapshot to browser and native observers, never to TV players. */
    public void broadcastObservers(WsEvent event) {
        OutboundBatch batch = prepareOutbound(event);
        for (WebSocketSession session : sessions.values()) {
            if (!"tv".equals(sessionTypes.get(session.getId()))) {
                sendPrepared(session, batch);
            }
        }
    }

    /**
     * Broadcasts playback commands/state to H5 observers and the single active
     * TV player. Standby TV sessions never receive commands that could start a
     * second projection.
     */
    public void broadcastPlayback(WsEvent event) {
        String activeSessionId = playerRegistry.activeSessionId().orElse(null);
        OutboundBatch batch = prepareOutbound(event);
        for (WebSocketSession session : sessions.values()) {
            String type = sessionTypes.get(session.getId());
            if (!"tv".equals(type) || session.getId().equals(activeSessionId)) {
                sendPrepared(session, batch);
            }
        }
    }

    private OutboundBatch prepareOutbound(WsEvent event) {
        if (event != null && event.payload() instanceof com.homektv.web.dto.QueueSnapshot snapshot) {
            return prepareSnapshot(event.type(), snapshot);
        }
        String json = serializeWithinBudget(event);
        return json == null ? null : OutboundBatch.of(json);
    }

    private OutboundBatch prepareSnapshot(String eventType, com.homektv.web.dto.QueueSnapshot snapshot) {
        String legacy = serializeWithinBudget(WsEvent.of(eventType, snapshot));
        if (legacy != null) return OutboundBatch.of(legacy);

        List<String> frames = new ArrayList<>();
        long[] byteCount = {0L};
        try {
            QueueSnapshotChunker.stream(eventType, snapshot, mapper, chunk -> {
                String json = serializeWithinBudget(WsEvent.of(WsEvent.SNAPSHOT_CHUNK, chunk));
                if (json == null) {
                    throw new QueueChunkTooLargeException(chunk.index(),
                            "队列快照分片超过 WebSocket 大小上限");
                }
                frames.add(json);
                byteCount[0] += json.getBytes(StandardCharsets.UTF_8).length;
            });
            return new OutboundBatch(frames, byteCount[0]);
        } catch (QueueChunkTooLargeException exception) {
            log.warn("拒绝无法安全同步的队列快照: chunk={}", exception.getChunkIndex());
            return null;
        }
    }

    private void sendPrepared(WebSocketSession session, OutboundBatch batch) {
        if (session == null || sessions.get(session.getId()) != session || !session.isOpen()) return;
        if (batch == null) {
            log.warn("拒绝无效或超出 WebSocket 大小上限的事件: session={}", session.getId());
            removeFailedSession(session, new IOException("outbound event exceeds the websocket budget"));
            return;
        }

        SessionOutboundQueue queue = outboundQueues.computeIfAbsent(session.getId(),
                ignored -> new SessionOutboundQueue(session));
        if (!queue.enqueue(batch)) {
            log.warn("WebSocket 会话发送队列超限，关闭慢会话: session={}, frames={}, bytes={}",
                    session.getId(), batch.frameCount(), batch.byteCount());
            removeFailedSession(session, new IOException("outbound websocket queue limit exceeded"));
        }
    }

    private void removeFailedSession(WebSocketSession session, Exception cause) {
        String sessionId = session.getId();
        if (!sessions.remove(sessionId, session)) return;
        String clientType = sessionTypes.remove(sessionId);
        closeOutboundQueue(sessionId);
        log.debug("发送失败，移除会话 {}: {}", sessionId, cause.getMessage());
        ActivePlayerRegistry.Unregistration removal = playerRegistry.unregisterPlayer(sessionId);
        eventPublisher.publishEvent(new WsSessionDisconnectedEvent(sessionId, clientType, removal));
        closeSessionAsync(session, CloseStatus.POLICY_VIOLATION);
    }

    private void closeOutboundQueue(String sessionId) {
        SessionOutboundQueue queue = outboundQueues.remove(sessionId);
        if (queue != null) queue.close();
    }

    private static void closeSessionAsync(WebSocketSession session, CloseStatus status) {
        Thread.startVirtualThread(() -> {
            try {
                if (session.isOpen()) session.close(status);
            } catch (IOException | IllegalStateException ignored) {
                // The session is already unusable or concurrently closing.
            }
        });
    }

    private static ScheduledThreadPoolExecutor createSendTimeoutScheduler() {
        ThreadFactory factory = task -> {
            Thread thread = new Thread(task, "ktv-ws-send-timeouts");
            thread.setDaemon(true);
            return thread;
        };
        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1, factory);
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        return scheduler;
    }

    private static ThreadPoolExecutor createSenderPool(int workerCount, int queuedSessions, String threadPrefix) {
        AtomicInteger threadNumber = new AtomicInteger();
        ThreadFactory factory = task -> {
            Thread thread = new Thread(task, threadPrefix + threadNumber.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        return new ThreadPoolExecutor(workerCount, workerCount, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queuedSessions), factory, new ThreadPoolExecutor.AbortPolicy());
    }

    private ThreadPoolExecutor senderPoolFor(WebSocketSession session) {
        String sessionId = session.getId();
        boolean activePlayer = "tv".equals(sessionTypes.get(sessionId))
                && playerRegistry.activeSessionId().filter(sessionId::equals).isPresent();
        return activePlayer ? ACTIVE_PLAYER_SENDERS : OBSERVER_SENDERS;
    }

    private boolean isCurrentSession(WebSocketSession session) {
        return sessions.get(session.getId()) == session;
    }

    private record OutboundBatch(List<String> frames, long byteCount) {
        private OutboundBatch {
            frames = List.copyOf(frames);
        }

        private static OutboundBatch of(String json) {
            return new OutboundBatch(List.of(json), json.getBytes(StandardCharsets.UTF_8).length);
        }

        private int frameCount() {
            return frames.size();
        }
    }

    /** One serial virtual sender and a bounded FIFO per WebSocket session. */
    private final class SessionOutboundQueue {
        private static final int MAX_PENDING_FRAMES = 128;
        private static final long MAX_PENDING_BYTES = WsProtocolLimits.MAX_SNAPSHOT_BYTES;

        private final WebSocketSession session;
        private final Deque<OutboundBatch> pending = new ArrayDeque<>();
        private int pendingFrames;
        private long pendingBytes;
        private boolean draining;
        private boolean closed;

        private SessionOutboundQueue(WebSocketSession session) {
            this.session = session;
        }

        private boolean enqueue(OutboundBatch batch) {
            boolean startWorker = false;
            synchronized (this) {
                if (closed || batch.frameCount() > MAX_PENDING_FRAMES
                        || pendingFrames > MAX_PENDING_FRAMES - batch.frameCount()
                        || pendingBytes > MAX_PENDING_BYTES - batch.byteCount()) {
                    return false;
                }
                pending.addLast(batch);
                pendingFrames += batch.frameCount();
                pendingBytes += batch.byteCount();
                if (!draining) {
                    draining = true;
                    startWorker = true;
                }
            }
            if (startWorker) {
                try {
                    senderPoolFor(session).execute(this::drain);
                } catch (RejectedExecutionException rejected) {
                    removeFailedSession(session, rejected);
                }
            }
            return true;
        }

        private void drain() {
            while (true) {
                OutboundBatch batch;
                synchronized (this) {
                    if (closed) {
                        clearPending();
                        draining = false;
                        return;
                    }
                    batch = pending.pollFirst();
                    if (batch == null) {
                        draining = false;
                        return;
                    }
                    pendingFrames -= batch.frameCount();
                    pendingBytes -= batch.byteCount();
                }

                try {
                    for (String frame : batch.frames()) {
                        if (!isCurrentSession(session) || !session.isOpen()) return;
                        sendFrame(frame);
                    }
                } catch (Exception exception) {
                    removeFailedSession(session, exception);
                    return;
                }
            }
        }

        private void sendFrame(String frame) throws Exception {
            if (!isCurrentSession(session) || !session.isOpen()) return;
            ScheduledFuture<?> timeout = SEND_TIMEOUTS.schedule(
                    () -> removeFailedSession(session,
                            new TimeoutException("websocket send exceeded "
                                    + outboundSendTimeoutMillis + " ms")),
                    outboundSendTimeoutMillis, TimeUnit.MILLISECONDS);
            try {
                synchronized (session) {
                    if (!isCurrentSession(session) || !session.isOpen()) return;
                    session.sendMessage(new TextMessage(frame));
                }
            } finally {
                timeout.cancel(false);
            }
        }

        private synchronized void close() {
            closed = true;
            clearPending();
        }

        private void clearPending() {
            pending.clear();
            pendingFrames = 0;
            pendingBytes = 0L;
        }
    }

    private String serializeWithinBudget(WsEvent event) {
        try {
            byte[] bytes = BoundedJson.serialize(mapper, event, WsProtocolLimits.MAX_MESSAGE_BYTES);
            if (bytes == null) return null;
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("事件序列化失败: {}", e.getMessage());
            return null;
        }
    }
}
