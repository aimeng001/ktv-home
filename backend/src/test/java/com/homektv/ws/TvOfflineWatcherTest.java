package com.homektv.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homektv.queue.PlaybackService;
import com.homektv.queue.SnapshotService;
import com.homektv.web.dto.QueueSnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

class TvOfflineWatcherTest {
    private TestBroadcaster broadcaster;
    private TestPlaybackService playbackService;
    private TestSnapshotService snapshotService;
    private ManualScheduler scheduler;
    private TvOfflineWatcher watcher;

    @BeforeEach
    void setUp() {
        broadcaster = new TestBroadcaster();
        playbackService = new TestPlaybackService();
        snapshotService = new TestSnapshotService(broadcaster);
        scheduler = new ManualScheduler();
        watcher = new TvOfflineWatcher(broadcaster, playbackService, snapshotService, scheduler);
    }

    @AfterEach
    void shutDown() {
        watcher.shutdown();
    }

    @Test
    void doesNotScheduleAQueueClearWhileAnotherTvSessionIsOnline() {
        broadcaster.tvOnline = true;

        watcher.onTvDisconnected();

        assertThat(scheduler.tasks).isEmpty();
        assertThat(playbackService.clearCalls).isZero();
    }

    @Test
    void clearsAndBroadcastsTheSnapshotAndToastWhenStillOfflineAfterTheDelay() {
        QueueSnapshot snapshot = new QueueSnapshot(null, List.of(), "idle", 50, false,
                "ORIGINAL", false, 0);
        snapshotService.value = snapshot;
        playbackService.clearResult = true;

        watcher.onTvDisconnected();
        scheduler.tasks.getFirst().runNow();

        assertThat(scheduler.tasks).hasSize(1);
        assertThat(scheduler.tasks.getFirst().delayMs).isEqualTo(10_000L);
        assertThat(playbackService.clearCalls).isEqualTo(1);
        assertThat(broadcaster.playbackEvents)
                .containsExactly(new WsEvent(WsEvent.QUEUE_UPDATED, snapshot));
        assertThat(broadcaster.events).containsExactly(new WsEvent(WsEvent.TOAST,
                Map.of("text", "电视已离线，播放队列已清空")));
    }

    @Test
    void doesNotClearOrBroadcastIfTvReconnectsBeforeTheScheduledTaskRuns() {
        watcher.onTvDisconnected();
        broadcaster.tvOnline = true;
        scheduler.tasks.getFirst().runNow();

        assertThat(playbackService.clearCalls).isZero();
        assertThat(snapshotService.calls).isZero();
        assertThat(broadcaster.playbackEvents).isEmpty();
        assertThat(broadcaster.events).isEmpty();
    }

    @Test
    void doesNotBroadcastWhenTheOfflineQueueWasAlreadyEmpty() {
        playbackService.clearResult = false;

        watcher.onTvDisconnected();
        scheduler.tasks.getFirst().runNow();

        assertThat(playbackService.clearCalls).isEqualTo(1);
        assertThat(snapshotService.calls).isZero();
        assertThat(broadcaster.playbackEvents).isEmpty();
        assertThat(broadcaster.events).isEmpty();
    }

    @Test
    void repeatedDisconnectReplacesTheTimerAndReconnectCancelsThePendingClear() {
        watcher.onTvDisconnected();
        ManualFuture first = scheduler.tasks.getFirst();
        watcher.onTvDisconnected();
        ManualFuture second = scheduler.tasks.getLast();

        watcher.onTvConnected();
        watcher.onTvConnected();

        assertThat(scheduler.tasks).hasSize(2);
        assertThat(first.isCancelled()).isTrue();
        assertThat(second.isCancelled()).isTrue();
    }

    @Test
    void shutdownStopsTheScheduler() {
        watcher.shutdown();

        assertThat(scheduler.isShutdown()).isTrue();
    }

    private static final class TestBroadcaster extends WsBroadcaster {
        private boolean tvOnline;
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
        public void broadcastPlayback(WsEvent event) {
            playbackEvents.add(event);
        }

        @Override
        public void broadcast(WsEvent event) {
            events.add(event);
        }
    }

    private static final class TestPlaybackService extends PlaybackService {
        private boolean clearResult;
        private int clearCalls;

        private TestPlaybackService() {
            super(null, null, null, null, null);
        }

        @Override
        public boolean clearOnTvOffline() {
            clearCalls++;
            return clearResult;
        }
    }

    private static final class TestSnapshotService extends SnapshotService {
        private QueueSnapshot value;
        private int calls;

        private TestSnapshotService(WsBroadcaster broadcaster) {
            super(null, null, null, null, broadcaster);
        }

        @Override
        public QueueSnapshot snapshot() {
            calls++;
            return value;
        }
    }

    private static final class ManualScheduler extends AbstractExecutorService
            implements ScheduledExecutorService {
        private final List<ManualFuture> tasks = new ArrayList<>();
        private boolean shutdown;

        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            ManualFuture future = new ManualFuture(command, unit.toMillis(delay));
            tasks.add(future);
            return future;
        }

        @Override
        public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
            throw new UnsupportedOperationException("Callable scheduling is not used by TvOfflineWatcher");
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay,
                                                       long period, TimeUnit unit) {
            throw new UnsupportedOperationException("Periodic scheduling is not used by TvOfflineWatcher");
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay,
                                                          long delay, TimeUnit unit) {
            throw new UnsupportedOperationException("Periodic scheduling is not used by TvOfflineWatcher");
        }

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return shutdown;
        }

        @Override
        public void execute(Runnable command) {
            command.run();
        }
    }

    private static final class ManualFuture implements ScheduledFuture<Object> {
        private final Runnable command;
        private final long delayMs;
        private boolean cancelled;
        private boolean done;

        private ManualFuture(Runnable command, long delayMs) {
            this.command = command;
            this.delayMs = delayMs;
        }

        private void runNow() {
            if (!cancelled) {
                command.run();
                done = true;
            }
        }

        @Override
        public long getDelay(TimeUnit unit) {
            return unit.convert(delayMs, TimeUnit.MILLISECONDS);
        }

        @Override
        public int compareTo(Delayed other) {
            return Long.compare(getDelay(TimeUnit.NANOSECONDS), other.getDelay(TimeUnit.NANOSECONDS));
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            cancelled = !done;
            return cancelled;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public boolean isDone() {
            return done || cancelled;
        }

        @Override
        public Object get() throws InterruptedException, ExecutionException {
            if (!isDone()) throw new IllegalStateException("Scheduled task has not run");
            return null;
        }

        @Override
        public Object get(long timeout, TimeUnit unit)
                throws InterruptedException, ExecutionException, TimeoutException {
            return get();
        }
    }
}
