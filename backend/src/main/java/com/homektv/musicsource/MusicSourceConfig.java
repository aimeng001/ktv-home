package com.homektv.musicsource;

import java.util.Set;

public record MusicSourceConfig(
        boolean enabled,
        Set<MusicProvider> providers,
        int resultLimit,
        int timeoutSeconds,
        int searchCacheHours,
        int concurrencyLimit,
        int requestIntervalMs,
        double autoApplyThreshold,
        int dailyLimit,
        Set<AvatarFallbackProvider> fallbackProviders,
        int fallbackDailyLimit) {

    public static final int DEFAULT_FALLBACK_DAILY_LIMIT = 2000;

    public MusicSourceConfig(boolean enabled, Set<MusicProvider> providers, int resultLimit,
                             int timeoutSeconds, int searchCacheHours, int concurrencyLimit,
                             int requestIntervalMs, double autoApplyThreshold) {
        this(enabled, providers, resultLimit, timeoutSeconds, searchCacheHours, concurrencyLimit,
                requestIntervalMs, autoApplyThreshold, 300, Set.of(), DEFAULT_FALLBACK_DAILY_LIMIT);
    }

    public MusicSourceConfig(boolean enabled, Set<MusicProvider> providers, int resultLimit,
                             int timeoutSeconds, int searchCacheHours, int concurrencyLimit,
                             int requestIntervalMs, double autoApplyThreshold, int dailyLimit) {
        this(enabled, providers, resultLimit, timeoutSeconds, searchCacheHours, concurrencyLimit,
                requestIntervalMs, autoApplyThreshold, dailyLimit, Set.of(), DEFAULT_FALLBACK_DAILY_LIMIT);
    }

    public static MusicSourceConfig defaults() {
        return defaults(300);
    }

    public static MusicSourceConfig defaults(int dailyLimit) {
        return new MusicSourceConfig(
                false, Set.of(), 20, 5, 6, 1, 1500, 0.95, dailyLimit, Set.of(), DEFAULT_FALLBACK_DAILY_LIMIT);
    }
}
