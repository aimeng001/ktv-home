package com.homektv.library;

import com.homektv.config.AppProperties;
import com.homektv.repo.SongFileRepository;
import com.homektv.repo.SongRepository;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.OffsetDateTime;

/** Builds the small public catalogue/status contract without exposing filesystem diagnostics. */
@Service
public class LibraryStatusService {
    private final AppProperties props;
    private final SongRepository songs;
    private final SongFileRepository files;
    private final LibraryScanStateStore stateStore;

    public LibraryStatusService(AppProperties props, SongRepository songs,
                                SongFileRepository files, LibraryScanStateStore stateStore) {
        this.props = props;
        this.songs = songs;
        this.files = files;
        this.stateStore = stateStore;
    }

    public PublicStatus status() {
        LibraryScanStateStore.Snapshot snapshot = stateStore.find().orElse(null);
        String role = LibraryModePolicy.isExternalReadOnly(props)
                ? LibraryModePolicy.EXTERNAL_FILE_ROLE : "LIBRARY";
        long indexedSongs = songs.countIndexedSongs("ok", role);
        long readySongs = songs.countReadySongs("ok", role);
        long pendingFiles = files.countByFileRoleAndProbePendingTrue(role);
        long statusRevision = snapshot == null ? 0 : snapshot.generation();
        long catalogRevision = snapshot != null
                && snapshot.state() == LibraryScanStateStore.State.COMPLETED
                ? snapshot.generation() : 0;
        return new PublicStatus(
                songs.count(),
                props == null || props.getLibraryMode() == null ? "UNKNOWN" : props.getLibraryMode().name(),
                rootState(),
                snapshot == null ? LibraryScanStateStore.State.IDLE.name() : snapshot.state().name(),
                snapshot == null || snapshot.phase() == null ? "IDLE" : snapshot.phase(),
                snapshot == null ? 0 : snapshot.discoveredFiles(),
                snapshot == null ? 0 : snapshot.indexedFiles(),
                indexedSongs,
                readySongs,
                pendingFiles,
                catalogRevision,
                statusRevision,
                snapshot == null ? null : snapshot.errorCode(),
                snapshot == null ? null : snapshot.updatedAt());
    }

    private String rootState() {
        if (props == null) return "UNKNOWN";
        try {
            Path root = LibraryModePolicy.activeLibraryRoot(props);
            if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return "NOT_FOUND";
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
                    || !Files.isReadable(root)) return "NOT_READABLE";
            return "READABLE";
        } catch (RuntimeException failure) {
            return "UNKNOWN";
        }
    }

    public record PublicStatus(
            long totalSongs,
            String libraryMode,
            String rootState,
            String scanState,
            String phase,
            long discoveredFiles,
            long indexedFiles,
            long indexedSongs,
            long readySongs,
            long probePendingFiles,
            long catalogRevision,
            long statusRevision,
            String errorCode,
            OffsetDateTime updatedAt) {
    }
}
