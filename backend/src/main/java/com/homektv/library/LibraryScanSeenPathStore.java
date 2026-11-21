package com.homektv.library;

import java.util.Collection;
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

    /**
     * Cleans up orphan seen-path rows whose scan_id does not belong to any currently active
     * heartbeat lease in library_scan_state. Returns the number of deleted rows.
     */
    default int cleanOrphanSeenPaths() {
        return 0;
    }

    record MissingFiles(int filesMarked, int songsMarked) {}
}
