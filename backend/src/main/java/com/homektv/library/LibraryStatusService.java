package com.homektv.library;

import com.homektv.config.AppProperties;
import com.homektv.repo.SongFileRepository;
import com.homektv.repo.SongRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.Optional;

/** Builds the small public catalogue/status contract without exposing filesystem diagnostics. */
@Service
public class LibraryStatusService {
    private final AppProperties props;
    private final SongRepository songs;
    private final SongFileRepository files;
    private final LibraryScanStateStore stateStore;
    private final LibraryCatalogStatsService catalogStats;
    private CatalogRevisionService catalogRevisionService;

    public LibraryStatusService(AppProperties props, SongRepository songs,
                                SongFileRepository files, LibraryScanStateStore stateStore) {
        this(props, songs, files, stateStore, null);
    }

    @Autowired
    public LibraryStatusService(AppProperties props, SongRepository songs,
                                SongFileRepository files, LibraryScanStateStore stateStore,
                                LibraryCatalogStatsService catalogStats) {
        this.props = props;
        this.songs = songs;
        this.files = files;
        this.stateStore = stateStore;
        this.catalogStats = catalogStats;
    }

    @Autowired(required = false)
    void setCatalogRevisionService(CatalogRevisionService catalogRevisionService) {
        this.catalogRevisionService = catalogRevisionService;
    }

    public PublicStatus status() {
        LibraryScanStateStore.Snapshot snapshot = stateStore.find().orElse(null);
        String role = LibraryModePolicy.isExternalReadOnly(props)
                ? LibraryModePolicy.EXTERNAL_FILE_ROLE : "LIBRARY";
        Optional<LibraryCatalogStatsService.Stats> cached = catalogStats == null
                ? Optional.empty() : catalogStats.find();
        long totalSongs = cached.map(LibraryCatalogStatsService.Stats::totalSongs).orElseGet(songs::count);
        long indexedSongs = cached.map(LibraryCatalogStatsService.Stats::indexedSongs)
                .orElseGet(() -> songs.countIndexedSongs("ok", role));
        long readySongs = cached.map(LibraryCatalogStatsService.Stats::readySongs)
                .orElseGet(() -> songs.countReadySongs("ok", role));
        long pendingFiles = cached.map(LibraryCatalogStatsService.Stats::probePendingFiles)
                .orElseGet(() -> files.countByFileRoleAndProbePendingTrue(role));
        long statusRevision = snapshot == null ? 0 : snapshot.generation();
        long catalogRevision = catalogRevisionService == null
                ? snapshot != null && snapshot.state() == LibraryScanStateStore.State.COMPLETED
                    ? snapshot.generation() : 0
                : catalogRevisionService.current();

        String currentRootState = rootState();
        String rootIdentityState = identityState(snapshot);
        boolean countsKnown = "READABLE".equals(currentRootState)
                && "MATCH".equals(rootIdentityState);

        return new PublicStatus(
                totalSongs,
                props == null || props.getLibraryMode() == null ? "UNKNOWN" : props.getLibraryMode().name(),
                currentRootState,
                rootIdentityState,
                countsKnown,
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

    private String identityState(LibraryScanStateStore.Snapshot snapshot) {
        if (snapshot == null || props == null) return "UNKNOWN";
        Path root = LibraryModePolicy.activeLibraryRoot(props);
        return LibraryIdentity.compare(
                snapshot.rootIdentity(), LibraryIdentity.resolve(root)).name();
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
            String rootIdentityState,
            boolean countsKnown,
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
        public PublicStatus(long totalSongs, String libraryMode, String rootState,
                            String scanState, String phase, long discoveredFiles,
                            long indexedFiles, long indexedSongs, long readySongs,
                            long probePendingFiles, long catalogRevision,
                            long statusRevision, String errorCode,
                            OffsetDateTime updatedAt) {
            this(totalSongs, libraryMode, rootState, "UNKNOWN", true, scanState, phase,
                    discoveredFiles, indexedFiles, indexedSongs, readySongs,
                    probePendingFiles, catalogRevision, statusRevision, errorCode, updatedAt);
        }
    }
}
