package com.homektv.resource;

import com.homektv.domain.PlayerState;
import com.homektv.repo.PlayerStateRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/** Samples the server's playback state for admission decisions; an unreadable state fails busy. */
@Component
public final class PlaybackPressureMonitor {
    private static final Logger log = LoggerFactory.getLogger(PlaybackPressureMonitor.class);

    private final PlayerStateRepository playerStateRepository;
    private final AtomicBoolean playbackBusy = new AtomicBoolean(true);
    private final AtomicBoolean readFailureLogged = new AtomicBoolean();
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();

    public PlaybackPressureMonitor(PlayerStateRepository playerStateRepository) {
        this.playerStateRepository = Objects.requireNonNull(playerStateRepository, "playerStateRepository");
    }

    @PostConstruct
    void initialize() {
        refresh();
    }

    @Scheduled(fixedDelayString = "${app.resource-budget.playback-poll-ms:500}")
    public void refresh() {
        try {
            boolean sampledBusy = isBusy(playerStateRepository.getSingleton());
            boolean changed = playbackBusy.getAndSet(sampledBusy) != sampledBusy;
            readFailureLogged.set(false);
            if (changed) listeners.forEach(PlaybackPressureMonitor::notifyListener);
        } catch (RuntimeException failure) {
            if (readFailureLogged.compareAndSet(false, true)) {
                log.warn("Unable to sample playback pressure; retaining the last known state (failureType={})",
                        failure.getClass().getSimpleName());
            }
        }
    }

    public boolean isPlaybackBusy() {
        return playbackBusy.get();
    }

    public void addChangeListener(Runnable listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    private static boolean isBusy(PlayerState state) {
        if (state == null) return true;
        String value = state.getState() == null ? "" : state.getState().trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "idle" -> false;
            case "playing" -> true;
            case "paused" -> state.getCurrentQueueId() != null;
            default -> true;
        };
    }

    private static void notifyListener(Runnable listener) {
        try {
            listener.run();
        } catch (RuntimeException failure) {
            log.warn("Playback pressure listener failed", failure);
        }
    }
}
