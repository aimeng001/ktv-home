package com.homektv.diagnostics;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

/** Lightweight in-memory operational counters exported in admin diagnostics without external metrics dependencies. */
@Component
public class DiagnosticsMetricsRecorder {
    private final LongAdder providerRateLimited = new LongAdder();
    private final LongAdder providerHttpErrors = new LongAdder();
    private final LongAdder transcodeBusyRejected = new LongAdder();
    private final LongAdder playbackErrors = new LongAdder();

    public void recordProviderRateLimited() {
        providerRateLimited.increment();
    }

    public void recordProviderHttpError() {
        providerHttpErrors.increment();
    }

    public void recordTranscodeBusyRejected() {
        transcodeBusyRejected.increment();
    }

    public void recordPlaybackError() {
        playbackErrors.increment();
    }

    public Map<String, Long> snapshot() {
        return Map.of(
                "providerRateLimited", providerRateLimited.sum(),
                "providerHttpErrors", providerHttpErrors.sum(),
                "transcodeBusyRejected", transcodeBusyRejected.sum(),
                "playbackErrors", playbackErrors.sum());
    }
}
