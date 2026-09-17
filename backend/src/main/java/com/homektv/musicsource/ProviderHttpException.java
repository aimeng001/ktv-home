package com.homektv.musicsource;

import java.time.Instant;

/** An upstream HTTP response that must update the provider rate state. */
public final class ProviderHttpException extends MusicSourceException {
    private final int statusCode;
    private final Instant retryAfter;

    public ProviderHttpException(MusicProvider provider, int statusCode, Instant retryAfter, String message) {
        super(provider, message);
        this.statusCode = statusCode;
        this.retryAfter = retryAfter;
    }

    public int statusCode() {
        return statusCode;
    }

    public Instant retryAfter() {
        return retryAfter;
    }
}
