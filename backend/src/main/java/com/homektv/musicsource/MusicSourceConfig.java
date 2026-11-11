package com.homektv.musicsource;

import java.util.Set;

public record MusicSourceConfig(boolean enabled, Set<MusicProvider> providers, int resultLimit,
                                int timeoutSeconds, int searchCacheHours, int concurrencyLimit,
                                int requestIntervalMs, double autoApplyThreshold, int dailyLimit) {

    public MusicSourceConfig(boolean enabled, Set<MusicProvider> providers, int resultLimit,
                            int timeoutSeconds, int searchCacheHours, int concurrencyLimit,
                            int requestIntervalMs, double autoApplyThreshold) {
        this(enabled, providers, resultLimit, timeoutSeconds, searchCacheHours, concurrencyLimit,
                requestIntervalMs, autoApplyThreshold, 300);
    }

    public static MusicSourceConfig defaults() {
        return defaults(300);
    }

    public static MusicSourceConfig defaults(int dailyLimit) {
        return new MusicSourceConfig(false, Set.of(), 20, 5, 6, 1, 1500, 0.95, dailyLimit);
    }
}
