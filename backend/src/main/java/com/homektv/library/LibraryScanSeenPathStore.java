package com.homektv.library;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Bounded-memory store for the paths observed during one complete library scan.
 * Implementations must never modify the external media source.
 */
public interface LibraryScanSeenPathStore {
    void recordBatch(UUID scanId, String fileRole, Collection<String> filePaths);

    /** Records rows that were already pending before this scan mutated them. */
    void recordPendingBatch(UUID scanId, String fileRole, Collection<String> filePaths);

    /** Returns the pending-at-scan-start paths from one bounded probe page. */
    Set<String> findPendingAtStart(UUID scanId, String fileRole, Collection<String> filePaths);

    MissingFiles markMissing(UUID scanId, String fileRole, String activeRoot);

    void delete(UUID scanId);

    record MissingFiles(int filesMarked, List<Long> songIds) {}
}
