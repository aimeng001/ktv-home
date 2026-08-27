package com.homektv.ws;

import com.homektv.queue.PlaybackService;
import com.homektv.queue.SnapshotService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * TV 离线看门狗：TV 端 WebSocket 断开后若 10s 内未重连，清空播放队列，
 * 避免下次打开 TV 时快照带着未播完的歌而自动续播；10s 内重连则取消清理。
 *
 * TV offline watchdog: when the TV WebSocket disconnects, if no reconnection
 * occurs within 10 seconds the playback queue is cleared, preventing the next
 * TV launch from auto-resuming via a stale snapshot; reconnection within 10s
 * cancels the scheduled clear.
 */
@Component
public class TvOfflineWatcher {

    private static final Logger log = LoggerFactory.getLogger(TvOfflineWatcher.class);

    /** TV 离线判定窗口：超过该时长未重连即清空队列。
     *  TV offline threshold: queue is cleared if the TV stays disconnected
     *  longer than this duration. */
    private static final long OFFLINE_CLEAR_DELAY_MS = 10_000L;

    private final WsBroadcaster broadcaster;
    private final PlaybackService playbackService;
    private final SnapshotService snapshotService;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "tv-offline-watcher");
        t.setDaemon(true);
        return t;
    });
    private ScheduledFuture<?> pending;

    public TvOfflineWatcher(WsBroadcaster broadcaster, PlaybackService playbackService,
                            SnapshotService snapshotService) {
        this.broadcaster = broadcaster;
        this.playbackService = playbackService;
        this.snapshotService = snapshotService;
    }

    /** TV 会话建立：取消待执行的离线清理。
     *  TV session established: cancel any pending offline clear. */
    public synchronized void onTvConnected() {
        if (pending != null) {
            pending.cancel(false);
            pending = null;
            log.info("TV 已重连，取消离线清空队列");
        }
    }

    /** TV 会话断开：无其他 TV 在线时，10s 后清空队列。
     *  TV session disconnected: if no other TV session is online,
     *  schedule queue clear after 10 seconds. */
    public synchronized void onTvDisconnected() {
        if (broadcaster.isTvOnline()) {
            return; // 还有其他 TV 会话在线
        }
        if (pending != null) {
            pending.cancel(false);
        }
        pending = scheduler.schedule(this::clearIfStillOffline,
                OFFLINE_CLEAR_DELAY_MS, TimeUnit.MILLISECONDS);
        log.info("TV 离线，{}ms 后若无重连将清空播放队列", OFFLINE_CLEAR_DELAY_MS);
    }

    private void clearIfStillOffline() {
        if (broadcaster.isTvOnline()) {
            return;
        }
        boolean cleared = playbackService.clearOnTvOffline();
        if (cleared) {
            log.info("TV 离线超时，已清空播放队列");
            broadcaster.broadcastPlayback(WsEvent.of(WsEvent.QUEUE_UPDATED, snapshotService.snapshot()));
            broadcaster.broadcast(WsEvent.of(WsEvent.TOAST,
                    java.util.Map.of("text", "电视已离线，播放队列已清空")));
        }
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdownNow();
    }
}
