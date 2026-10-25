package com.homektv.resource;

import com.homektv.domain.PlayerState;
import com.homektv.repo.PlayerStateRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlaybackPressureMonitorTest {
    @Test
    void playingAndQueuedPauseAreBusyButIdleAndUnqueuedPauseAreNot() {
        PlayerStateRepository repository = mock(PlayerStateRepository.class, CALLS_REAL_METHODS);
        PlayerState state = new PlayerState();
        when(repository.findById(PlayerState.SINGLETON_ID)).thenReturn(Optional.of(state));
        PlaybackPressureMonitor monitor = new PlaybackPressureMonitor(repository);

        state.setState("idle");
        monitor.refresh();
        assertThat(monitor.isPlaybackBusy()).isFalse();

        state.setState("playing");
        monitor.refresh();
        assertThat(monitor.isPlaybackBusy()).isTrue();

        state.setState("paused");
        state.setCurrentQueueId(17L);
        monitor.refresh();
        assertThat(monitor.isPlaybackBusy()).isTrue();

        state.setCurrentQueueId(null);
        monitor.refresh();
        assertThat(monitor.isPlaybackBusy()).isFalse();
    }

    @Test
    void failedReadRetainsLastKnownPressureState() {
        PlayerStateRepository repository = mock(PlayerStateRepository.class, CALLS_REAL_METHODS);
        PlayerState state = new PlayerState();
        state.setState("playing");
        when(repository.findById(PlayerState.SINGLETON_ID)).thenReturn(Optional.of(state));
        PlaybackPressureMonitor monitor = new PlaybackPressureMonitor(repository);
        monitor.refresh();
        when(repository.findById(PlayerState.SINGLETON_ID)).thenThrow(new IllegalStateException("offline"));

        monitor.refresh();

        assertThat(monitor.isPlaybackBusy()).isTrue();

        monitor.refresh();

        assertThat(monitor.isPlaybackBusy()).isTrue();
    }

    @Test
    void missingAndUnknownPlaybackStatesFailClosed() {
        PlayerStateRepository repository = mock(PlayerStateRepository.class, CALLS_REAL_METHODS);
        PlaybackPressureMonitor monitor = new PlaybackPressureMonitor(repository);
        doReturn(null).when(repository).getSingleton();

        monitor.refresh();

        assertThat(monitor.isPlaybackBusy()).isTrue();

        PlayerState state = new PlayerState();
        doReturn(state).when(repository).getSingleton();
        state.setState(null);
        monitor.refresh();
        assertThat(monitor.isPlaybackBusy()).isTrue();

        state.setState("starting");
        monitor.refresh();
        assertThat(monitor.isPlaybackBusy()).isTrue();
    }

    @Test
    void throwingListenerDoesNotPreventOtherListenersOrStateUpdates() {
        PlayerStateRepository repository = mock(PlayerStateRepository.class, CALLS_REAL_METHODS);
        PlayerState state = new PlayerState();
        when(repository.findById(PlayerState.SINGLETON_ID)).thenReturn(Optional.of(state));
        PlaybackPressureMonitor monitor = new PlaybackPressureMonitor(repository);
        AtomicInteger laterListenerCalls = new AtomicInteger();
        monitor.addChangeListener(() -> {
            throw new IllegalStateException("listener failure");
        });
        monitor.addChangeListener(laterListenerCalls::incrementAndGet);

        state.setState("idle");
        monitor.refresh();

        assertThat(monitor.isPlaybackBusy()).isFalse();
        assertThat(laterListenerCalls).hasValue(1);
    }
}
