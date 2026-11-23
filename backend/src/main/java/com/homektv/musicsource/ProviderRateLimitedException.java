package com.homektv.musicsource;

import java.time.Instant;

/** Raised before an external request when the persisted provider gate is closed. */
public final class ProviderRateLimitedException extends MusicSourceException {
    private final Instant retryAt;

    public ProviderRateLimitedException(MusicProvider provider, Instant retryAt) {
        this(provider, "音乐平台请求处于冷却或限额状态", retryAt);
    }

    public ProviderRateLimitedException(MusicProvider provider, String message, Instant retryAt) {
        super(provider, message);
        this.retryAt = retryAt;
    }

    public Instant retryAt() {
        return retryAt;
    }
}
