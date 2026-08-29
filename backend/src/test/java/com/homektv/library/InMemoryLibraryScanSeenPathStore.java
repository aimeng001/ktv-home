package com.homektv.library;

import com.homektv.domain.SongFile;
import com.homektv.repo.SongFileRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/** Test-only implementation of the database reconciliation boundary. */
final class InMemoryLibraryScanSeenPathStore implements LibraryScanSeenPathStore {
    private final SongFileRepository files;
    private final Map<UUID, Set<String>> seenPaths = new HashMap<>();
    private final Map<UUID, Set<String>> pendingAtStart = new HashMap<>();
    private final AtomicInteger recordBatchCalls = new AtomicInteger();
    private boolean failRecordBatches;

    InMemoryLibraryScanSeenPathStore(SongFileRepository files) {
        this.files = files;
    }

    @Override
    public void recordBatch(UUID scanId, String fileRole, Collection<String> filePaths) {
        recordBatchCalls.incrementAndGet();
        if (failRecordBatches) throw new IllegalStateException("test seen-path store failure");
        seenPaths.computeIfAbsent(scanId, ignored -> new HashSet<>())
                .addAll(filePaths);
    }

    void failRecordBatches() {
        failRecordBatches = true;
    }

    int recordBatchCalls() {
        return recordBatchCalls.get();
    }

    int activeScanCount() {
        return seenPaths.size();
    }

    @Override
    public void recordPendingBatch(UUID scanId, String fileRole, Collection<String> filePaths) {
        pendingAtStart.computeIfAbsent(scanId, ignored -> new HashSet<>())
                .addAll(filePaths);
    }

    @Override
    public Set<String> findPendingAtStart(UUID scanId, String fileRole, Collection<String> filePaths) {
        Set<String> pending = pendingAtStart.getOrDefault(scanId, Set.of());
        return filePaths.stream().filter(pending::contains).collect(java.util.stream.Collectors.toSet());
    }

    boolean isSeen(UUID scanId, String filePath) {
        return seenPaths.getOrDefault(scanId, Set.of()).contains(filePath);
    }

    @Override
    public MissingFiles markMissing(UUID scanId, String fileRole, String activeRoot) {
        Set<String> seen = seenPaths.getOrDefault(scanId, Set.of());
        java.nio.file.Path root = java.nio.file.Path.of(activeRoot).toAbsolutePath().normalize();
        String afterPath = "";
        int marked = 0;
        Set<Long> songIds = new HashSet<>();

        while (true) {
            Slice<SongFile> page = files.findByFileRoleAndFilePathGreaterThanOrderByFilePath(
                    fileRole, afterPath, PageRequest.of(0, LibraryScanService.FAST_INDEX_BATCH_SIZE));
            if (page == null || page.getContent().isEmpty()) break;

            for (SongFile file : page.getContent()) {
                if (file == null || file.getFilePath() == null) continue;
                afterPath = file.getFilePath();
                boolean insideRoot;
                try {
                    insideRoot = java.nio.file.Path.of(file.getFilePath()).toAbsolutePath()
                            .normalize().startsWith(root);
                } catch (RuntimeException invalidPath) {
                    insideRoot = false;
                }
                if (insideRoot && file.isValid() && !seen.contains(file.getFilePath())) {
                    file.setValid(false);
                    files.save(file);
                    marked++;
                    if (file.getSongId() != null) songIds.add(file.getSongId());
                }
            }
            if (!page.hasNext()) break;
        }
        return new MissingFiles(marked, List.copyOf(songIds));
    }

    @Override
    public void delete(UUID scanId) {
        seenPaths.remove(scanId);
        pendingAtStart.remove(scanId);
    }
}
