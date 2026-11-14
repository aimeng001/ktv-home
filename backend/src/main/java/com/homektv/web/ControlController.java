package com.homektv.web;

import com.homektv.domain.QueueItem;
import com.homektv.queue.PlaybackService;
import com.homektv.queue.QueueService;
import com.homektv.queue.RoomHostService;
import com.homektv.queue.SnapshotService;
import com.homektv.queue.UserService;
import com.homektv.repo.QueueItemRepository;
import com.homektv.web.dto.ControlRequest;
import com.homektv.web.dto.QueueSnapshot;
import com.homektv.ws.WsBroadcaster;
import com.homektv.ws.WsEvent;
import org.springframework.web.bind.annotation.*;

/**
 * 统一控制入口 + 队列快照（P1.12，详设§4.3/§11.1）。
 * 变更后返回最新快照；WebSocket 广播接入见 P1.14。
 *
 * Unified control endpoint and queue snapshot (P1.12, detailed design §4.3/§11.1).
 * Returns the latest snapshot after every mutation; WebSocket broadcasting is covered in P1.14.
 */
@RestController
@RequestMapping("/api")
public class ControlController {

    private final QueueService queueService;
    private final PlaybackService playbackService;
    private final SnapshotService snapshotService;
    private final UserService userService;
    private final QueueItemRepository queueRepo;
    private final WsBroadcaster broadcaster;
    private final RoomHostService roomHostService;
    private final com.homektv.library.SongProbeService songProbeService;

    @org.springframework.beans.factory.annotation.Autowired
    public ControlController(QueueService queueService, PlaybackService playbackService,
                             SnapshotService snapshotService, UserService userService,
                             QueueItemRepository queueRepo, WsBroadcaster broadcaster,
                             RoomHostService roomHostService,
                             com.homektv.library.SongProbeService songProbeService) {
        this.queueService = queueService;
        this.playbackService = playbackService;
        this.snapshotService = snapshotService;
        this.userService = userService;
        this.queueRepo = queueRepo;
        this.broadcaster = broadcaster;
        this.roomHostService = roomHostService;
        this.songProbeService = songProbeService;
    }

    public ControlController(QueueService queueService, PlaybackService playbackService,
                             SnapshotService snapshotService, UserService userService,
                             QueueItemRepository queueRepo, WsBroadcaster broadcaster,
                             RoomHostService roomHostService) {
        this(queueService, playbackService, snapshotService, userService, queueRepo, broadcaster, roomHostService, null);
    }

    /**
     * 获取当前队列与播放状态快照。
     *
     * Returns the current queue and playback state snapshot.
     * @return 包含队列列表及播放器状态的快照对象 / snapshot containing the queue items and player state
     */
    @GetMapping("/queue")
    public QueueSnapshot queue() {
        return snapshotService.snapshot();
    }

    /**
     * 统一控制指令入口，支持点歌、切歌、暂停、音量等操作。
     * 所有变更后自动广播对应 WebSocket 事件并返回最新快照。
     *
     * Unified control endpoint for ordering songs, skipping, pausing, adjusting
     * volume, and other playback commands. Automatically broadcasts the relevant
     * WebSocket event and returns the latest snapshot after every mutation.
     * @param req 控制请求体 / control request body
     * @return 变更后的最新队列与播放状态快照 / latest snapshot after the mutation
     */
    @PostMapping("/control")
    public QueueSnapshot control(@RequestBody ControlRequest req) {
        String action = req.action() == null ? "" : req.action();
        // Do not create/resolve a default user for playback-only controls. This
        // keeps a failed registration from silently changing queue ownership;
        // identity is required only for user-owned queue mutations.
        Long userId = requiresUserIdentity(action) && userService != null
                ? userService.resolveUserId(req.clientToken())
                : null;

        QueueSnapshot snapshot;
        switch (action) {
            case "order" -> {
                Long songId = req.longParam("song_id");
                if (songProbeService != null) {
                    songProbeService.ensurePlayable(songId);
                }
                queueService.order(songId, userId, req.boolParam("force"));
                boolean started = playbackService.startIfIdle();
                snapshot = snapshotService.snapshot();
                broadcast(WsEvent.QUEUE_UPDATED, snapshot);
                if (started) {
                    broadcast(WsEvent.NOW_PLAYING, snapshot);
                }
            }
            case "shuffle" -> {
                queueService.shuffleWaiting();
                snapshot = snapshotService.snapshot();
                broadcast(WsEvent.QUEUE_UPDATED, snapshot);
            }
            case "top" -> {
                requireOwnerOrHost(req.longParam("queue_id"), userId, req.clientToken(),
                        "只能置顶自己点的歌曲");
                queueService.top(req.longParam("queue_id"));
                snapshot = snapshotService.snapshot();
                broadcast(WsEvent.QUEUE_UPDATED, snapshot);
            }
            case "cancel" -> {
                cancelWithPermission(req.longParam("queue_id"), userId, req.clientToken());
                snapshot = snapshotService.snapshot();
                broadcast(WsEvent.QUEUE_UPDATED, snapshot);
            }
            case "play", "pause", "stop" -> {
                dispatchPlayback(action);
                snapshot = snapshotService.snapshot();
                broadcast(WsEvent.PLAYER_STATE, snapshot);
            }
            case "seek" -> {
                Long positionMs = req.longParam("position_ms");
                if (positionMs == null) {
                    throw new ApiException("INVALID_ACTION", "seek 缺少 position_ms");
                }
                playbackService.seek(positionMs);
                snapshot = snapshotService.snapshot();
                broadcast(WsEvent.PLAYBACK_SEEKED, snapshot);
            }
            case "restart" -> {
                dispatchPlayback(action);
                snapshot = snapshotService.snapshot();
                broadcast(WsEvent.PLAYBACK_RESTARTED, snapshot);
            }
            case "next" -> {
                dispatchPlayback(action);
                snapshot = snapshotService.snapshot();
                broadcast(WsEvent.NOW_PLAYING, snapshot);
            }
            case "set_volume" -> {
                playbackService.setVolume(req.intParam("volume", 60));
                snapshot = snapshotService.snapshot();
                broadcast(WsEvent.VOLUME_CHANGED, snapshot);
            }
            case "mute" -> {
                playbackService.setMuted(req.boolParam("muted"));
                snapshot = snapshotService.snapshot();
                broadcast(WsEvent.VOLUME_CHANGED, snapshot);
            }
            case "set_vocal" -> {
                playbackService.setVocalMode(req.strParam("mode"));
                snapshot = snapshotService.snapshot();
                broadcast(WsEvent.VOCAL_CHANGED, snapshot);
            }
            case "swap_vocal_tracks" -> {
                playbackService.swapVocalTracks();
                snapshot = snapshotService.snapshot();
                broadcast(WsEvent.VOCAL_CHANGED, snapshot);
            }
            case "effect" -> {
                broadcaster.broadcastPlayback(
                        WsEvent.of(WsEvent.EFFECT_PLAY, java.util.Map.of("effect_id", req.strParam("effect_id"))));
                snapshot = snapshotService.snapshot();
            }
            default -> throw new ApiException("INVALID_ACTION", "未知指令：" + action);
        }
        return snapshot;
    }

    private void dispatchPlayback(String action) {
        switch (action) {
            case "play" -> playbackService.play();
            case "pause" -> playbackService.pause();
            case "stop" -> playbackService.stop();
            case "restart" -> playbackService.restart();
            case "next" -> playbackService.next();
        }
    }

    private boolean requiresUserIdentity(String action) {
        return "order".equals(action) || "top".equals(action) || "cancel".equals(action);
    }

    /**
     * 广播当前快照到所有端（详设§4.1：客户端以广播为准）。
     * Broadcasts the current snapshot to all clients (detailed design §4.1: clients rely on broadcasts).
     */
    private void broadcast(String eventType, QueueSnapshot snapshot) {
        broadcaster.broadcastPlayback(WsEvent.of(eventType, snapshot));
    }

    private void broadcast(String eventType) {
        broadcast(eventType, snapshotService.snapshot());
    }

    /**
     * 删歌权限：本人或当前房主可删（详设§4.4.3；TV/后台删任意由其它入口处理）。
     * Cancel permission: only the user who ordered a song or the room host may remove it.
     */
    private void cancelWithPermission(Long queueId, Long userId, String clientToken) {
        requireOwnerOrHost(queueId, userId, clientToken, "只能删除自己点的歌曲");
        queueService.cancel(queueId);
    }

    /**
     * Queue mutations must be authorized from the server-side orderedBy value.
     * A legacy row without an owner is intentionally fail-closed for non-hosts;
     * nickname or UI visibility checks are not an authorization boundary.
     */
    private void requireOwnerOrHost(Long queueId, Long userId, String clientToken, String deniedMessage) {
        if (queueRepo == null) {
            throw new ApiException("FORBIDDEN", deniedMessage);
        }
        QueueItem item = queueRepo.findById(queueId)
                .orElseThrow(() -> new ApiException("QUEUE_ITEM_NOT_FOUND", "队列项不存在"));
        boolean isHost = roomHostService != null && roomHostService.isHost(clientToken);
        boolean isOwner = userId != null && item.getOrderedBy() != null
                && item.getOrderedBy().equals(userId);
        if (!isHost && !isOwner) {
            throw new ApiException("FORBIDDEN", deniedMessage);
        }
    }
}
