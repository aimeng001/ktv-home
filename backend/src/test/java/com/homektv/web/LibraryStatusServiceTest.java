package com.homektv.web;

import com.homektv.library.LibraryIdentity;
import com.homektv.library.LibraryCatalogStatsService;

import com.homektv.config.AppProperties;
import com.homektv.library.LibraryMode;
import com.homektv.library.LibraryScanStateStore;
import com.homektv.library.LibraryStatusService;
import com.homektv.repo.SongFileRepository;
import com.homektv.repo.SongRepository;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LibraryStatusServiceTest {

    @Test
    void exposesDistinctReadySongsAndPersistentScanStateWithoutLeakingRootOrErrors() throws Exception {
        AppProperties props = new AppProperties();
        props.setLibraryMode(LibraryMode.EXTERNAL_READ_ONLY);
        Path root = Files.createTempDirectory("library-status-");
        props.setSourceLibraryPath(root.toString());

        SongRepository songs = mock(SongRepository.class);
        SongFileRepository files = mock(SongFileRepository.class);
        LibraryScanStateStore state = mock(LibraryScanStateStore.class);
        when(songs.count()).thenReturn(42L);
        when(songs.countIndexedSongs("ok", "EXTERNAL_READ_ONLY")).thenReturn(40L);
        when(songs.countReadySongs("ok", "EXTERNAL_READ_ONLY")).thenReturn(37L);
        when(files.countByFileRoleAndProbePendingTrue("EXTERNAL_READ_ONLY")).thenReturn(3L);
        when(state.find()).thenReturn(Optional.of(new LibraryScanStateStore.Snapshot(
                "active", root.toString(), "EXTERNAL_READ_ONLY", 1,
                LibraryScanStateStore.State.PARTIAL, "MEDIA_PROBE", null, null, 9,
                100, 40, 35, 3, "PROBE_FAILED", "internal detail", null)));

        LibraryStatusService service = new LibraryStatusService(props, songs, files, state);

        LibraryStatusService.PublicStatus result = service.status();

        assertThat(result.totalSongs()).isEqualTo(42);
        assertThat(result.libraryMode()).isEqualTo("EXTERNAL_READ_ONLY");
        assertThat(result.rootState()).isEqualTo("READABLE");
        assertThat(result.scanState()).isEqualTo("PARTIAL");
        assertThat(result.phase()).isEqualTo("MEDIA_PROBE");
        assertThat(result.discoveredFiles()).isEqualTo(100);
        assertThat(result.indexedFiles()).isEqualTo(40);
        assertThat(result.indexedSongs()).isEqualTo(40);
        assertThat(result.readySongs()).isEqualTo(37);
        assertThat(result.probePendingFiles()).isEqualTo(3);
        assertThat(result.catalogRevision()).isZero();
        assertThat(result.statusRevision()).isEqualTo(9);
        assertThat(result.errorCode()).isEqualTo("PROBE_FAILED");
        assertThat(result.toString()).doesNotContain(root.toString(), "internal detail");
    }

    @Test
    void reportsRootIdentityMismatchEvenWhenTheNewPathIsReadable() throws Exception {
        AppProperties props = new AppProperties();
        props.setLibraryMode(LibraryMode.EXTERNAL_READ_ONLY);
        Path oldRoot = Files.createTempDirectory("library-status-old-");
        Path newRoot = Files.createTempDirectory("library-status-new-");
        props.setSourceLibraryPath(newRoot.toString());

        SongRepository songs = mock(SongRepository.class);
        SongFileRepository files = mock(SongFileRepository.class);
        LibraryScanStateStore state = mock(LibraryScanStateStore.class);
        when(songs.count()).thenReturn(1L);
        when(songs.countIndexedSongs("ok", "EXTERNAL_READ_ONLY")).thenReturn(1L);
        when(songs.countReadySongs("ok", "EXTERNAL_READ_ONLY")).thenReturn(1L);
        when(files.countByFileRoleAndProbePendingTrue("EXTERNAL_READ_ONLY")).thenReturn(0L);
        when(state.find()).thenReturn(Optional.of(new LibraryScanStateStore.Snapshot(
                "active", oldRoot.toString(),
                LibraryIdentity.resolve(oldRoot).persistedValue(),
                "EXTERNAL_READ_ONLY", 1,
                LibraryScanStateStore.State.COMPLETED, "COMPLETED", null, null, 4,
                1, 1, 1, 0, null, null, null)));

        LibraryStatusService.PublicStatus result =
                new LibraryStatusService(props, songs, files, state).status();

        assertThat(result.rootState()).isEqualTo("READABLE");
        assertThat(result.rootIdentityState()).isEqualTo("MISMATCH");
        assertThat(result.countsKnown()).isFalse();
    }

    @Test
    void usesCachedCatalogStatsWithoutRunningFourLiveCountQueries() throws Exception {
        AppProperties props = new AppProperties();
        props.setLibraryMode(LibraryMode.EXTERNAL_READ_ONLY);
        Path root = Files.createTempDirectory("library-status-cache-");
        props.setSourceLibraryPath(root.toString());

        SongRepository songs = mock(SongRepository.class);
        SongFileRepository files = mock(SongFileRepository.class);
        LibraryScanStateStore state = mock(LibraryScanStateStore.class);
        LibraryCatalogStatsService stats = mock(LibraryCatalogStatsService.class);
        when(state.find()).thenReturn(Optional.empty());
        when(stats.find()).thenReturn(Optional.of(new LibraryCatalogStatsService.Stats(200_000, 199_500, 198_700, 500, null)));

        LibraryStatusService service = new LibraryStatusService(props, songs, files, state, stats);
        LibraryStatusService.PublicStatus result = service.status();

        assertThat(result.totalSongs()).isEqualTo(200_000);
        assertThat(result.indexedSongs()).isEqualTo(199_500);
        assertThat(result.readySongs()).isEqualTo(198_700);
        assertThat(result.probePendingFiles()).isEqualTo(500);
        org.mockito.Mockito.verifyNoInteractions(songs, files);
    }
}
