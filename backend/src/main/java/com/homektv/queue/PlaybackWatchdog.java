package com.homektv.queue;

import com.homektv.domain.PlayerState;
import com.homektv.repo.PlayerStateRepository;
import com.homektv.ws.ActivePlayerRegistry;
import com.homektv.ws.TvOfflineWatcher;
import com.homektv.ws.WebPlayerCoordinator;
import com.homektv.ws.WsBroadcaster;
import com.homektv.ws.WsEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * 播放孤儿看门狗：播放端全部离线后，把停在 playing 的队列就地暂停。
 *
 * <p>覆盖两种真实缺口（{@code TvOfflineWatcher} 管不到的地方）：
 * <ul>
 *   <li>TV 离线但仍有手机在线 —— {@code TvOfflineWatcher} 见 phones&gt;0 直接放弃清理</li>
 *   <li>浏览器主播放端关掉标签页 —— 断连处理只释放播放租约，没人管播放状态</li>
 * </ul>
 *
 * <p>三条刻意的保守约束：
 * <ul>
 *   <li><b>非锁定读</b>：判定阶段用 {@code findById}，只有真要暂停时才走 {@code pause()} 的悲观锁，
 *       避免和 TV 每秒的 progress 抢锁、把同一条调度线程上的其他定时任务拖住</li>
 *   <li><b>连续两拍</b>：连续两次 tick 都命中才动手，避开切歌瞬间的抖动</li>
 *   <li><b>启动宽限</b>：启动后 {@link #STARTUP_GRACE} 内不判定，否则会把
 *       {@code recoverAfterRestart} 刚恢复的歌立刻暂停，反向破坏「重启后自动续播」</li>
 * </ul>
 *
 * <p>无观众时主动让位：TV 离线且没有手机连接时，收尾由 {@code TvOfflineWatcher} 的 180s 清空负责，
 * 这里不再多插一次暂停，避免「先看到已暂停、再看到队列已清空」两次惊吓。
 */
@Component
public class PlaybackWatchdog {

    private static final Logger log = LoggerFactory.getLogger(PlaybackWatchdog.class);

    /** 启动宽限期：覆盖 recoverAfterRestart 与客户端重连的时间窗。 */
    static final Duration STARTUP_GRACE = Duration.ofSeconds(180);

    private final PlayerStateRepository playerRepo;
    private final PlaybackService playbackService;
    private final WsBroadcaster broadcaster;
    private final WebPlayerCoordinator webPlayerCoordinator;
    private final ActivePlayerRegistry playerRegistry;
    private final SnapshotService snapshotService;
    private final TvOfflineWatcher tvOfflineWatcher;
    private final Clock clock;
    private final Duration interval;
    private final Instant startupGraceUntil;

    /** 首次命中「孤儿」的时刻；连续两拍都命中才动手。 */
    private Instant orphanSince;

    @Autowired
    public PlaybackWatchdog(PlayerStateRepository playerRepo, PlaybackService playbackService,
                            WsBroadcaster broadcaster, WebPlayerCoordinator webPlayerCoordinator,
                            ActivePlayerRegistry playerRegistry, SnapshotService snapshotService,
                            TvOfflineWatcher tvOfflineWatcher) {
        this(playerRepo, playbackService, broadcaster, webPlayerCoordinator, playerRegistry,
                snapshotService, tvOfflineWatcher, Clock.systemUTC(), Duration.ofSeconds(10));
    }

    /** Package-private seam for deterministic clock/interval tests. */
    PlaybackWatchdog(PlayerStateRepository playerRepo, PlaybackService playbackService,
                     WsBroadcaster broadcaster, WebPlayerCoordinator webPlayerCoordinator,
                     ActivePlayerRegistry playerRegistry, SnapshotService snapshotService,
                     TvOfflineWatcher tvOfflineWatcher, Clock clock, Duration interval) {
        this.playerRepo = playerRepo;
        this.playbackService = playbackService;
        this.broadcaster = broadcaster;
        this.webPlayerCoordinator = webPlayerCoordinator;
        this.playerRegistry = playerRegistry;
        this.snapshotService = snapshotService;
        this.tvOfflineWatcher = tvOfflineWatcher;
        this.clock = clock;
        this.interval = interval;
        this.startupGraceUntil = clock.instant().plus(STARTUP_GRACE);
    }

    @Scheduled(fixedDelayString = "${app.playback.watchdog-interval-ms:10000}")
    public void tick() {
        evaluate(clock.instant());
    }

    void evaluate(Instant now) {
        if (now.isBefore(startupGraceUntil) || !broadcaster.hasSeenClient()) {
            orphanSince = null;
            return;
        }

        PlayerState state = playerRepo.findById(PlayerState.SINGLETON_ID).orElse(null);
        if (state == null || !"playing".equals(state.getState()) || state.getCurrentQueueId() == null) {
            orphanSince = null;
            return;
        }

        if (hasActivePlayer()) {
            orphanSince = null;
            return;
        }

        // TV 离线且无手机：180s 清空队列会自己收尾，这里不再多插一次暂停。
        if (tvOfflineWatcher.hasPendingClear()) {
            orphanSince = null;
            return;
        }

        if (orphanSince == null) {
            orphanSince = now;
            return;
        }
        if (Duration.between(orphanSince, now).compareTo(interval) < 0) {
            return;
        }

        pauseOrphanedPlayback();
    }

    /** 是否存在任何一个有播放权的端：TV 播放端持租约，或网页播放端租约未失效。 */
    private boolean hasActivePlayer() {
        if (broadcaster.isTvOnline() && playerRegistry.activeSessionId().isPresent()) {
            return true;
        }
        // status(false) 内部会先 expireIfElapsed：45s 租约到期后 active 自然转 false。
        return webPlayerCoordinator.status(false).active();
    }

    private void pauseOrphanedPlayback() {
        orphanSince = null;
        try {
            playbackService.pause();
        } catch (RuntimeException e) {
            log.warn("播放孤儿暂停失败，等下一拍重试", e);
            return;
        }
        broadcaster.broadcastPlayback(WsEvent.of(WsEvent.PLAYER_STATE, snapshotService.snapshot()));
        if (broadcaster.connectedPhonesCount() > 0) {
            broadcaster.broadcast(WsEvent.of(WsEvent.TOAST, Map.of("text", "播放端已离线，已暂停")));
        }
        log.info("播放端全部离线，已暂停当前曲目");
    }
}
