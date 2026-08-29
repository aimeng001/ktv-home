package com.homektv.library;

/**
 * Fail-closed policy for suspicious large-scale disappearance during a scan.
 * This class only decides whether reconciliation may run; it never touches files.
 */
public final class MissingFileGuard {
    private final long minimumPreviousFiles;
    private final double minimumSeenRatio;

    public MissingFileGuard(long minimumPreviousFiles, double minimumSeenRatio) {
        if (minimumPreviousFiles < 0) {
            throw new IllegalArgumentException("minimumPreviousFiles must not be negative");
        }
        if (!Double.isFinite(minimumSeenRatio) || minimumSeenRatio < 0d || minimumSeenRatio > 1d) {
            throw new IllegalArgumentException("minimumSeenRatio must be between 0 and 1");
        }
        this.minimumPreviousFiles = minimumPreviousFiles;
        this.minimumSeenRatio = minimumSeenRatio;
    }

    public boolean shouldBlock(long previousValidFiles, long currentSeenFiles,
                               boolean enumerationComplete, boolean allowMassMissing) {
        if (allowMassMissing) return false;
        if (!enumerationComplete) return true;
        if (previousValidFiles < minimumPreviousFiles) return false;
        long minimumExpected = (long) Math.ceil(previousValidFiles * minimumSeenRatio);
        return currentSeenFiles < minimumExpected;
    }
}
