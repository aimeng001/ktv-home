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

    public ControlController(QueueService queueService, PlaybackService playbackService,
                             SnapshotService snapshotService, UserService userService,
                             QueueItemRepository queueRepo, WsBroadcaster broadcaster,
                             RoomHostService roomHostService) {
        this.queueService = queueService;
        this.playbackService = playbackService;
        this.snapshotService = snapshotService;
        this.userService = userService;
        this.queueRepo = queueRepo;
        this.broadcaster = broadcaster;
        this.roomHostService = roomHostService;
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
        Long userId = userService.resolveUserId(req.clientToken());

        switch (action) {
            case "order" -> {
                queueService.order(req.longParam("song_id"), userId, req.boolParam("force"));
                boolean started = playbackService.startIfIdle();
                broadcast(WsEvent.QUEUE_UPDATED);
                if (started) {
                    broadcast(WsEvent.NOW_PLAYING);
                }
            }
            case "shuffle" -> {
                queueService.shuffleWaiting();
                broadcast(WsEvent.QUEUE_UPDATED);
            }
            case "top" -> {
                queueService.top(req.longParam("queue_id"));
                broadcast(WsEvent.QUEUE_UPDATED);
            }
            case "cancel" -> {
                cancelWithPermission(req.longParam("queue_id"), userId, req.clientToken());
                broadcast(WsEvent.QUEUE_UPDATED);
            }
            case "play", "pause", "stop" -> {
                dispatchPlayback(action);
                broadcast(WsEvent.PLAYER_STATE);
            }
            case "seek" -> {
                Long positionMs = req.longParam("position_ms");
                if (positionMs == null) {
                    throw new ApiException("INVALID_ACTION", "seek 缺少 position_ms");
                }
                playbackService.seek(positionMs);
                broadcast(WsEvent.PLAYBACK_SEEKED);
            }
            case "restart" -> {
                dispatchPlayback(action);
                broadcast(WsEvent.PLAYBACK_RESTARTED);
            }
            case "next" -> {
                dispatchPlayback(action);
                broadcast(WsEvent.NOW_PLAYING);
            }
            case "set_volume" -> {
                playbackService.setVolume(req.intParam("volume", 60));
                broadcast(WsEvent.VOLUME_CHANGED);
            }
            case "mute" -> {
                playbackService.setMuted(req.boolParam("muted"));
                broadcast(WsEvent.VOLUME_CHANGED);
            }
            case "set_vocal" -> {
                playbackService.setVocalMode(req.strParam("mode"));
                broadcast(WsEvent.VOCAL_CHANGED);
            }
            case "swap_vocal_tracks" -> {
                playbackService.swapVocalTracks();
                broadcast(WsEvent.VOCAL_CHANGED);
            }
            case "effect" -> broadcaster.broadcastPlayback(
                    WsEvent.of(WsEvent.EFFECT_PLAY, java.util.Map.of("effect_id", req.strParam("effect_id"))));
            default -> throw new ApiException("INVALID_ACTION", "未知指令：" + action);
        }
        return snapshotService.snapshot();
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

    /**
     * 广播当前快照到所有端（详设§4.1：客户端以广播为准）。
     * Broadcasts the current snapshot to all clients (detailed design §4.1: clients rely on broadcasts).
     */
    private void broadcast(String eventType) {
        broadcaster.broadcastPlayback(WsEvent.of(eventType, snapshotService.snapshot()));
    }

    /**
     * 删歌权限：本人或当前房主可删（详设§4.4.3；TV/后台删任意由其它入口处理）。
     * Cancel permission: only the user who ordered a song or the room host may remove it.
     */
    private void cancelWithPermission(Long queueId, Long userId, String clientToken) {
        QueueItem item = queueRepo.findById(queueId)
                .orElseThrow(() -> new ApiException("QUEUE_ITEM_NOT_FOUND", "队列项不存在"));
        boolean isHost = roomHostService != null && roomHostService.isHost(clientToken);
        if (!isHost && item.getOrderedBy() != null && !item.getOrderedBy().equals(userId)) {
            throw new ApiException("FORBIDDEN", "只能删除自己点的歌曲");
        }
        queueService.cancel(queueId);
    }
}
