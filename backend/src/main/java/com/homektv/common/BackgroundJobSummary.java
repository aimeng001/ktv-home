package com.homektv.common;

import java.util.Map;

/**
 * Unified operational snapshot across the five background job engines for admin overview and diagnostics.
 */
public record BackgroundJobSummary(
        String jobKey,
        String displayName,
        String status,
        boolean running,
        long total,
        long completed,
        long failed,
        long pending,
        boolean supportsPause,
        boolean supportsCancel,
        Map<String, Object> extra
) {
}
