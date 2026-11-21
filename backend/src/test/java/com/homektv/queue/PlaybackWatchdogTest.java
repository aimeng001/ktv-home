package com.homektv.queue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homektv.domain.PlayerState;
import com.homektv.repo.PlayerStateRepository;
import com.homektv.ws.ActivePlayerRegistry;
import com.homektv.ws.TvOfflineWatcher;
import com.homektv.ws.WebPlayerCoordinator;
import com.homektv.ws.WsBroadcaster;
import com.homektv.ws.WsEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 播放孤儿看门狗的行为规格（A-01）。
 *
 * 只覆盖「谁能被暂停」「谁不能被暂停」两类判定，
 * 以及 D-01/D-02/D-03 三条修正（无观众跳过、连续两拍、启动宽限）。
 */
class PlaybackWatchdogTest {

    private static final Duration INTERVAL = Duration.ofSeconds(10);
    private static final Instant BOOT = Instant.parse("2026-11-21T00:00:00Z");

    private MutableClock clock;
    private PlayerStateRepository playerRepo;
    private TestPlaybackService playbackService;
    private TestBroadcaster broadcaster;
    private WebPlayerCoordinator webPlayerCoordinator;
    private ActivePlayerRegistry playerRegistry;
    private TvOfflineWatcher tvOfflineWatcher;
    private PlaybackWatchdog watchdog;
    private PlayerState state;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(BOOT);
        state = new PlayerState();
        playerRepo = mock(PlayerStateRepository.class);
        when(playerRepo.findById(PlayerState.SINGLETON_ID)).thenReturn(Optional.of(state));
        playbackService = new TestPlaybackService();
        broadcaster = new TestBroadcaster();
        webPlayerCoordinator = mock(WebPlayerCoordinator.class);
        when(webPlayerCoordinator.status(anyBoolean()))
                .thenReturn(new WebPlayerCoordinator.WebPlayerStatus(false, null));
        playerRegistry = mock(ActivePlayerRegistry.class);
        when(playerRegistry.activeSessionId()).thenReturn(Optional.empty());
        tvOfflineWatcher = mock(TvOfflineWatcher.class);
        watchdog = new PlaybackWatchdog(playerRepo, playbackService, broadcaster,
                webPlayerCoordinator, playerRegistry, mock(SnapshotService.class),
                tvOfflineWatcher, clock, INTERVAL);
    }

    @Test
    void pausesWhenTvOfflineButPhoneConnected() {
        playingSong();
        broadcaster.phones = 1;
        afterStartupGrace();

        tickTwice();

        assertThat(playbackService.pauseCalls).isEqualTo(1);
        assertThat(broadcaster.playbackEvents)
                .extracting(WsEvent::type)
                .containsExactly(WsEvent.PLAYER_STATE);
        assertThat(broadcaster.events)
                .extracting(WsEvent::type)
                .containsExactly(WsEvent.TOAST);
    }

    @Test
    void pausesWhenWebHostLeaseExpired() {
        playingSong();
        broadcaster.phones = 1;                       // 遥控端还在，但主播放端租约已失效
        when(playerRegistry.activeSessionId()).thenReturn(Optional.empty());
        when(webPlayerCoordinator.status(anyBoolean()))
                .thenReturn(new WebPlayerCoordinator.WebPlayerStatus(false, null));
        afterStartupGrace();

        tickTwice();

        assertThat(playbackService.pauseCalls).isEqualTo(1);
    }

    @Test
    void doesNotPauseWhenTvPlayerActive() {
        playingSong();
        broadcaster.tvOnline = true;
        broadcaster.phones = 1;
        when(playerRegistry.activeSessionId()).thenReturn(Optional.of("tv-session"));
        afterStartupGrace();

        tickTwice();

        assertThat(playbackService.pauseCalls).isZero();
    }

    @Test
    void doesNotPauseWhenWebLeaseActive() {
        playingSong();
        broadcaster.phones = 1;
        when(webPlayerCoordinator.status(anyBoolean()))
                .thenReturn(new WebPlayerCoordinator.WebPlayerStatus(true, "pc-kiosk"));
        afterStartupGrace();

        tickTwice();

        assertThat(playbackService.pauseCalls).isZero();
    }

    @Test
    void doesNotPauseWithinStartupGrace() {
        playingSong();
        broadcaster.phones = 1;

        // 重启后 100 秒：浏览器与 TV 都还没重连，此时绝不能把 recoverAfterRestart 的歌暂停
        clock.advance(Duration.ofSeconds(100));
        watchdog.tick();
        clock.advance(INTERVAL);
        watchdog.tick();

        assertThat(playbackService.pauseCalls).isZero();
    }

    @Test
    void skipsPauseWhenNoClientEverConnected() {
        playingSong();
        broadcaster.phones = 0;
        broadcaster.seenClient = false;
        afterStartupGrace();

        tickTwice();

        assertThat(playbackService.pauseCalls).isZero();
    }

    @Test
    void skipsPauseWhenTvOfflineClearIsPending() {
        // TV 离线 + 无手机：180s 清空会负责收尾，看门狗不再多插一次暂停（拍板 B）
        playingSong();
        broadcaster.phones = 0;
        broadcaster.tvOnline = false;
        when(tvOfflineWatcher.hasPendingClear()).thenReturn(true);
        afterStartupGrace();

        tickTwice();

        assertThat(playbackService.pauseCalls).isZero();
    }

    @Test
    void pausesWithoutToastWhenNoPhoneConnected() {
        // 浏览器主播放端关掉标签页：没有任何 pending 清空，也不会有人看到提示
        playingSong();
        broadcaster.phones = 0;
        when(tvOfflineWatcher.hasPendingClear()).thenReturn(false);
        afterStartupGrace();

        tickTwice();

        assertThat(playbackService.pauseCalls).isEqualTo(1);
        assertThat(broadcaster.events).isEmpty();
    }

    @Test
    void requiresTwoConsecutiveTicks() {
        playingSong();
        broadcaster.phones = 1;
        afterStartupGrace();

        watchdog.tick();

        assertThat(playbackService.pauseCalls).isZero();

        clock.advance(INTERVAL);
        watchdog.tick();

        assertThat(playbackService.pauseCalls).isEqualTo(1);
    }

    @Test
    void resetsOrphanWindowWhenPlaybackEnds() {
        playingSong();
        broadcaster.phones = 1;
        afterStartupGrace();

        watchdog.tick();                       // 第一拍命中，记下 orphanSince
        state.setState("paused");              // 用户自己暂停了
        clock.advance(INTERVAL);
        watchdog.tick();                       // 这一拍不命中，窗口重置
        state.setState("playing");
        clock.advance(INTERVAL);
        watchdog.tick();                       // 重新从第一拍开始

        assertThat(playbackService.pauseCalls).isZero();

        clock.advance(INTERVAL);
        watchdog.tick();

        assertThat(playbackService.pauseCalls).isEqualTo(1);
    }

    @Test
    void noopWhenStateIsIdleOrPaused() {
        state.setState("idle");
        broadcaster.phones = 1;
        afterStartupGrace();

        tickTwice();
        state.setState("paused");
        tickTwice();

        assertThat(playbackService.pauseCalls).isZero();
    }

    @Test
    void noopWhenNoSongIsAttachedToThePlayer() {
        state.setState("playing");
        state.setCurrentQueueId(null);
        broadcaster.phones = 1;
        afterStartupGrace();

        tickTwice();

        assertThat(playbackService.pauseCalls).isZero();
    }

    private void playingSong() {
        state.setState("playing");
        state.setCurrentQueueId(42L);
    }

    /** 跳过 180 秒启动宽限：先推进时钟，再让空闲状态把窗口清掉。 */
    private void afterStartupGrace() {
        clock.advance(PlaybackWatchdog.STARTUP_GRACE.plusSeconds(1));
        String restore = state.getState();
        state.setState("idle");
        watchdog.tick();
        state.setState(restore);
    }

    private void tickTwice() {
        watchdog.tick();
        clock.advance(INTERVAL);
        watchdog.tick();
    }

    private static final class TestBroadcaster extends WsBroadcaster {
        private volatile boolean tvOnline;
        private volatile long phones;
        private volatile boolean seenClient = true;
        private final List<WsEvent> playbackEvents = new ArrayList<>();
        private final List<WsEvent> events = new ArrayList<>();

        private TestBroadcaster() {
            super(new ObjectMapper());
        }

        @Override
        public boolean isTvOnline() {
            return tvOnline;
        }

        @Override
        public long connectedPhonesCount() {
            return phones;
        }

        @Override
        public boolean hasSeenClient() {
            return seenClient;
        }

        @Override
        public void broadcastPlayback(WsEvent event) {
            playbackEvents.add(event);
        }

        @Override
        public void broadcast(WsEvent event) {
            events.add(event);
        }
    }

    private static final class TestPlaybackService extends PlaybackService {
        private int pauseCalls;

        private TestPlaybackService() {
            super(null, null, null, null, null);
        }

        @Override
        public PlayerState pause() {
            pauseCalls++;
            PlayerState paused = new PlayerState();
            paused.setState("paused");
            return paused;
        }
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }
    }
}
