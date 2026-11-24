package com.homektv.common;

/**
 * Lightweight contract implemented by each background job coordinator without coupling their schemas.
 */
public interface BackgroundJobStatusProvider {
    BackgroundJobSummary jobStatus();
}
