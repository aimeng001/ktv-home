package com.homektv.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.homektv.queue.PlaybackService;
import com.homektv.queue.PlaybackTransitionResult;
import com.homektv.queue.PositionUpdateResult;
import com.homektv.queue.FinishResult;
import com.homektv.queue.SnapshotService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.context.event.EventListener;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * KTV WebSocket 处理器（P1.13/P1.15/P1.16，详设§4.1/§4.2）。
 * - 连接建立即推送 sync_full 全量快照
 * - 接收 TV 上行 progress → 转发广播给 H5（歌词/进度同步）
 * - 接收 ping → 回 pong（心跳）
 *
 * KTV WebSocket handler (P1.13/P1.15/P1.16, detailed design §4.1/§4.2).
 * - Pushes a sync_full full snapshot upon connection establishment.
 * - Receives progress messages from TV → broadcasts to H5 clients (lyrics/progress sync).
 * - Receives ping → replies with pong (heartbeat).
 */
@Component
public class KtvWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(KtvWebSocketHandler.class);

    private final WsBroadcaster broadcaster;
    private final SnapshotService snapshotService;
    private final PlaybackService playbackService;
    private final TvOfflineWatcher tvOfflineWatcher;
    private final ActivePlayerRegistry playerRegistry;
    private final ObjectMapper mapper;

    public KtvWebSocketHandler(WsBroadcaster broadcaster, SnapshotService snapshotService,
                               PlaybackService playbackService, TvOfflineWatcher tvOfflineWatcher,
                               ActivePlayerRegistry playerRegistry, ObjectMapper mapper) {
        this.broadcaster = broadcaster;
        this.snapshotService = snapshotService;
        this.playbackService = playbackService;
        this.tvOfflineWatcher = tvOfflineWatcher;
        this.playerRegistry = playerRegistry;
        this.mapper = mapper;
    }

    /**
     * 连接建立后注册会话并推送全量快照；若为 TV 端则触发上线监听。
     *
     * Registers the session and pushes a full snapshot upon connection
     * establishment; triggers online watcher if the client is a TV.
     *
     * @param session WebSocket 会话 / WebSocket session
     */
    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        broadcaster.register(session);
        if (isTv(session)) {
            ActivePlayerRegistry.Assignment assignment = playerRegistry.register(
                    session.getId(), playerHello(session));
            tvOfflineWatcher.onTvConnected();
            sendRole(session, assignment);
            if (assignment.role() == ActivePlayerRegistry.Role.STANDBY) {
                return;
            }
        }
        // 连接/重连即推全量快照（详设§4.1）
        broadcaster.sendTo(session, WsEvent.of(WsEvent.SYNC_FULL, snapshotService.snapshot()));
        log.debug("WS 连接建立: {}，当前在线 {}", session.getId(), broadcaster.sessionCount());
    }

    /**
     * 处理上行消息：ping/pong 心跳、TV 播放进度广播、曲目完成/播放错误切歌。
     *
     * Handles incoming messages: ping/pong heartbeat, TV playback progress
     * broadcast, track finished / play-error skip and broadcast.
     *
     * @param session WebSocket 会话 / WebSocket session
     * @param message 文本消息 / text message
     */
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        JsonNode node = mapper.readTree(message.getPayload());
        String type = node.path("type").asText("");

        if (("progress".equals(type) || "finished".equals(type) || "play_error".equals(type))
                && !authorizesPlaybackReport(session, node)) {
            return;
        }

        switch (type) {
            case "ping" -> handlePing(session, node);
            case "progress" -> {
                // TV 上行播放进度 → 转发给所有端（详设§4.2 progress）
                long positionMs = node.path("payload").path("position_ms").asLong(0);
                Long queueId = node.path("payload").path("queue_id").isNumber()
                        ? node.path("payload").path("queue_id").asLong() : null;
                PositionUpdateResult result = playbackService.updatePosition(queueId, positionMs);
                if (!result.accepted()) {
                    return;
                }
                java.util.Map<String, Object> progress = new java.util.LinkedHashMap<>();
                progress.put("position_ms", result.state().getPositionMs());
                if (queueId != null) progress.put("queue_id", queueId);
                broadcaster.broadcastPlayback(WsEvent.of(WsEvent.PROGRESS, progress));
            }
            case "finished" -> {
                // TV 上报当前曲目播放完成 → 推进队列并广播
                Long queueId = node.path("payload").path("queue_id").isNumber()
                        ? node.path("payload").path("queue_id").asLong() : null;
                if (queueId == null || queueId <= 0) {
                    return;
                }
                FinishResult result = playbackService.onFinished(queueId);
                broadcaster.sendTo(session, WsEvent.of(WsEvent.PLAYBACK_REPORT_ACK,
                        java.util.Map.of(
                                "queue_id", queueId,
                                "status", result.status().name())));
                if (result.status() == FinishResult.Status.APPLIED) {
                    broadcaster.broadcastPlayback(WsEvent.of(WsEvent.NOW_PLAYING, snapshotService.snapshot()));
                    broadcaster.broadcastPlayback(WsEvent.of(WsEvent.HISTORY_UPDATED,
                            java.util.Map.of("queue_id", queueId)));
                }
            }
            case "play_error" -> {
                // TV 无法读取当前媒体时按异常切歌，避免队列卡死；将原因同步给手机端。
                String reason = node.path("payload").path("message").asText("媒体读取失败");
                Long fileId = node.path("payload").path("file_id").isNumber()
                        ? node.path("payload").path("file_id").asLong() : null;
                Long queueId = node.path("payload").path("queue_id").isNumber()
                        ? node.path("payload").path("queue_id").asLong() : null;
                PlaybackTransitionResult result = playbackService.onPlayError(fileId, queueId);
                if (!result.accepted()) return;
                broadcaster.broadcast(WsEvent.of(WsEvent.TOAST,
                        java.util.Map.of("text", "当前歌曲播放失败，已自动切换下一首：" + reason)));
                broadcaster.broadcastPlayback(WsEvent.of(WsEvent.NOW_PLAYING, snapshotService.snapshot()));
            }
            default -> log.debug("未知 WS 消息类型: {}", type);
        }
    }

    /**
     * 连接关闭时注销会话；若为 TV 端则触发离线监听。
     *
     * Unregisters the session on close; triggers offline watcher if the
     * client was a TV.
     *
     * @param session WebSocket 会话 / WebSocket session
     * @param status  关闭状态 / close status
     */
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        unregister(session);
        log.debug("WS 连接关闭: {}，剩余在线 {}", session.getId(), broadcaster.sessionCount());
    }

    /**
     * 传输异常时注销会话并记录日志；若为 TV 端则触发离线监听。
     *
     * Unregisters the session and logs the error on transport failure;
     * triggers offline watcher if the client was a TV.
     *
     * @param session   WebSocket 会话 / WebSocket session
     * @param exception 异常 / the exception
     */
    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.debug("WS 传输错误 {}: {}", session.getId(), exception.getMessage());
        unregister(session);
    }

    /** Completes player promotion when the broadcaster discovered the broken transport. */
    @EventListener
    void onSessionDisconnected(WsSessionDisconnectedEvent event) {
        handlePlayerUnregistration(event.sessionId(), event.clientType(), event.playerRemoval());
    }

    private boolean isTv(WebSocketSession session) {
        Object type = session.getAttributes().get("client_type");
        return "tv".equals(type == null ? null : type.toString());
    }

    private void notifyOfflineIfTv(String clientType) {
        if ("tv".equals(clientType)) {
            tvOfflineWatcher.onTvDisconnected();
        }
    }

    private void unregister(WebSocketSession session) {
        String clientType = broadcaster.unregister(session);
        ActivePlayerRegistry.Unregistration removal = playerRegistry.unregisterPlayer(session.getId());
        handlePlayerUnregistration(session.getId(), isTv(session) ? "tv" : clientType, removal);
    }

    private void handlePlayerUnregistration(String sessionId, String clientType,
                                            ActivePlayerRegistry.Unregistration removal) {
        if (!"tv".equals(clientType)) {
            return;
        }
        if (!removal.removed()) {
            return;
        }
        java.util.Optional<ActivePlayerRegistry.Promotion> promotion = removal.promotion();
        if (promotion.isPresent()) {
            ActivePlayerRegistry.Promotion next = promotion.get();
            sendRole(next.sessionId(), next.assignment());
            broadcaster.sendTo(next.sessionId(),
                    WsEvent.of(WsEvent.SYNC_FULL, snapshotService.snapshot()));
            tvOfflineWatcher.onTvConnected();
            return;
        }
        if (playerRegistry.activeSessionId().isEmpty()) {
            notifyOfflineIfTv(clientType);
        }
    }

    private ActivePlayerRegistry.PlayerHello playerHello(WebSocketSession session) {
        String token = attribute(session, "client_token", session.getId());
        String platform = attribute(session, "platform", "LEGACY");
        int protocolVersion;
        try {
            protocolVersion = Integer.parseInt(attribute(session, "protocol_version", "1"));
        } catch (NumberFormatException ignored) {
            protocolVersion = 1;
        }
        return new ActivePlayerRegistry.PlayerHello(token, platform, protocolVersion);
    }

    private boolean authorizesPlaybackReport(WebSocketSession session, JsonNode node) {
        if (!isTv(session)) return false;
        JsonNode generation = node.path("payload").path("generation");
        Long value = generation.isIntegralNumber() ? generation.asLong() : null;
        if (!playerRegistry.authorizesUpstream(session.getId(), value)) return false;
        if (!playerRegistry.requiresQueueIdentity(session.getId())) return true;
        JsonNode queueId = node.path("payload").path("queue_id");
        return queueId.isIntegralNumber() && queueId.asLong() > 0;
    }

    private void sendRole(WebSocketSession session, ActivePlayerRegistry.Assignment assignment) {
        broadcaster.sendTo(session, WsEvent.of(WsEvent.PLAYER_ROLE,
                assignmentPayload(assignment)));
    }

    private void sendRole(String sessionId, ActivePlayerRegistry.Assignment assignment) {
        broadcaster.sendTo(sessionId, WsEvent.of(WsEvent.PLAYER_ROLE,
                assignmentPayload(assignment)));
    }

    private void handlePing(WebSocketSession session, JsonNode node) {
        if (!isTv(session)) {
            broadcaster.sendTo(session, WsEvent.of("pong", null));
            return;
        }
        playerRegistry.expireAndPromote().ifPresent(expiration -> {
            broadcaster.disconnect(expiration.expiredSessionId());
            if (expiration.promotion().isPresent()) {
                promote(expiration.promotion().get());
            } else {
                tvOfflineWatcher.onTvDisconnected();
            }
        });
        JsonNode generation = node.path("payload").path("generation");
        Long value = generation.isIntegralNumber() ? generation.asLong() : null;
        playerRegistry.heartbeat(session.getId(), value)
                .ifPresent(assignment -> broadcaster.sendTo(session,
                        WsEvent.of("pong", assignmentPayload(assignment))));
    }

    private void promote(ActivePlayerRegistry.Promotion next) {
        sendRole(next.sessionId(), next.assignment());
        broadcaster.sendTo(next.sessionId(),
                WsEvent.of(WsEvent.SYNC_FULL, snapshotService.snapshot()));
        tvOfflineWatcher.onTvConnected();
    }

    private java.util.Map<String, Object> assignmentPayload(
            ActivePlayerRegistry.Assignment assignment) {
        return java.util.Map.of(
                "role", assignment.role().name(),
                "generation", assignment.generation(),
                "lease_ms", assignment.leaseMs());
    }

    private String attribute(WebSocketSession session, String name, String fallback) {
        Object value = session.getAttributes().get(name);
        return value == null || value.toString().isBlank() ? fallback : value.toString();
    }
}
