package com.homektv.library;

import com.homektv.config.AppProperties;
import com.homektv.domain.AudioLayout;
import com.homektv.domain.PlayerState;
import com.homektv.domain.QueueItem;
import com.homektv.domain.Song;
import com.homektv.domain.SongFile;
import com.homektv.repo.PlayHistoryRepository;
import com.homektv.repo.PlayerStateRepository;
import com.homektv.repo.QueueItemRepository;
import com.homektv.repo.SongFileRepository;
import com.homektv.repo.SongRepository;
import com.homektv.web.ApiException;
import com.homektv.web.dto.AudioLayoutUpdateRequest;
import com.homektv.web.dto.SongEditRequest;
import com.homektv.ws.WsBroadcaster;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminServiceBehaviorTest {

    @TempDir
    Path tempDir;

    private final SongRepository songRepo = mock(SongRepository.class);
    private final SongFileRepository fileRepo = mock(SongFileRepository.class);
    private final PlayHistoryRepository historyRepo = mock(PlayHistoryRepository.class);
    private final QueueItemRepository queueRepo = mock(QueueItemRepository.class);
    private final PlayerStateRepository playerRepo = mock(PlayerStateRepository.class);
    private final AssetWriter assetWriter = mock(AssetWriter.class);
    private final ArtistCreditService artistCredits = mock(ArtistCreditService.class);
    private final SongProjectionService projection = mock(SongProjectionService.class);
    private final CatalogRevisionService catalogRevision = mock(CatalogRevisionService.class);
    private final AppProperties properties = new AppProperties();
    private final AdminService service = new AdminService(songRepo, fileRepo, historyRepo,
            mock(WsBroadcaster.class), assetWriter, queueRepo, playerRepo, properties,
            artistCredits, projection);

    @Test
    void nullFiltersClampPagesAndEmptyAdminResultsSkipFileLookup() {
        Page<Song> emptySongs = Page.empty();
        when(songRepo.findAll(any(Pageable.class))).thenReturn(emptySongs);

        assertThat(service.listSongs(null, -4, 10)).isSameAs(emptySongs);

        ArgumentCaptor<Pageable> songPage = ArgumentCaptor.forClass(Pageable.class);
        verify(songRepo).findAll(songPage.capture());
        assertThat(songPage.getValue().getPageNumber()).isZero();

        Page<Song> emptyAdminSongs = Page.empty();
        when(songRepo.searchAdminSongs(eq(""), eq("MV"), eq(""), any(Pageable.class)))
                .thenReturn(emptyAdminSongs);

        assertThat(service.listAdminSongs(null, " MV ", null, -3, 0).getContent()).isEmpty();

        ArgumentCaptor<Pageable> adminPage = ArgumentCaptor.forClass(Pageable.class);
        verify(songRepo).searchAdminSongs(eq(""), eq("MV"), eq(""), adminPage.capture());
        assertThat(adminPage.getValue().getPageNumber()).isZero();
        assertThat(adminPage.getValue().getPageSize()).isEqualTo(1);
        verify(fileRepo, never()).findBySongIdInAndValidTrueOrderByPriorityDesc(any());
    }

    @Test
    void dashboardCountsExternalFilesOnlyWhenExternalReadOnlyModeIsEnabled() {
        properties.setLibraryMode(LibraryMode.EXTERNAL_READ_ONLY);
        when(fileRepo.countByFileRoleAndValidTrue(LibraryModePolicy.EXTERNAL_FILE_ROLE)).thenReturn(7L);

        assertThat(service.dashboard().externalIndexedFiles()).isEqualTo(7L);

        properties.setLibraryMode(LibraryMode.MANAGED);
        assertThat(service.dashboard().externalIndexedFiles()).isZero();
        verify(fileRepo, times(1)).countByFileRoleAndValidTrue(LibraryModePolicy.EXTERNAL_FILE_ROLE);
    }

    @Test
    void blankEditFieldsLeaveMetadataUnchangedAndDoNotRunIdentityOrAssetWork() {
        Song song = song(1L, "Existing title", "Existing artist", "unrecognized");
        when(songRepo.findById(1L)).thenReturn(Optional.of(song));
        when(songRepo.save(any(Song.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Song result = service.editSong(1L,
                new SongEditRequest(" \t ", " ", null, null, "\n", " ", "\t"));

        assertThat(result).isSameAs(song);
        assertThat(song.getTitle()).isEqualTo("Existing title");
        assertThat(song.getArtist()).isEqualTo("Existing artist");
        assertThat(song.getStatus()).isEqualTo("unrecognized");
        assertThat(song.isMetadataLocked("title")).isFalse();
        assertThat(song.isMetadataLocked("artist")).isFalse();
        assertThat(song.isMetadataLocked("vocalForm")).isFalse();
        assertThat(song.isMetadataLocked("artistGender")).isFalse();
        verify(songRepo, never()).findByFingerprint(anyString());
        verify(assetWriter, never()).writeLyric(anyString(), anyString());
        verify(artistCredits, never()).replace(eq(1L), anyString());
    }

    @Test
    void reviewedIdentityAllowsItsOwnFingerprintAndUpdatesLyricsCreditsAndRevision() {
        Song song = song(1L, "Old title", "Old artist", "unrecognized");
        when(songRepo.findById(1L)).thenReturn(Optional.of(song));
        when(songRepo.findByFingerprint(anyString())).thenReturn(Optional.of(song));
        when(songRepo.save(any(Song.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(assetWriter.writeLyric(anyString(), eq(" manual lyric "))).thenReturn("lyrics/manual.lrc");
        service.setCatalogRevisionService(catalogRevision);

        service.editSong(1L, new SongEditRequest(" New title ", " New artist ", "粤语",
                new String[0], " manual lyric ", "对唱", "女歌手"));

        assertThat(song.getTitle()).isEqualTo("New title");
        assertThat(song.getArtist()).isEqualTo("New artist");
        assertThat(song.getStatus()).isEqualTo("ok");
        assertThat(song.getLyricPath()).isEqualTo("lyrics/manual.lrc");
        assertThat(song.getLyricSource()).isEqualTo(Song.LYRIC_SOURCE_MANUAL);
        assertThat(song.getLanguage()).isEqualTo("粤语");
        assertThat(song.getVocalForm()).isEqualTo("对唱");
        assertThat(song.getArtistGender()).isEqualTo("女歌手");
        assertThat(song.getTags()).isEmpty();
        assertThat(song.isMetadataLocked("title")).isTrue();
        assertThat(song.isMetadataLocked("artist")).isTrue();
        assertThat(song.isMetadataLocked("tags")).isTrue();
        assertThat(song.isMetadataLocked("vocalForm")).isTrue();
        assertThat(song.isMetadataLocked("artistGender")).isTrue();
        verify(artistCredits).replace(1L, "New artist");
        verify(catalogRevision).bumpIfChanged(true);
    }

    @Test
    void editingRecognizedArtistKeepsStatusAndWorksWithoutArtistCreditService() {
        Song song = song(2L, "Title", "Old artist", "ok");
        when(songRepo.findById(2L)).thenReturn(Optional.of(song));
        when(songRepo.findByFingerprint(anyString())).thenReturn(Optional.of(song));
        when(songRepo.save(any(Song.class))).thenAnswer(invocation -> invocation.getArgument(0));
        AdminService withoutArtistCredits = new AdminService(songRepo, fileRepo, historyRepo,
                mock(WsBroadcaster.class), assetWriter, queueRepo, playerRepo, properties);

        Song result = withoutArtistCredits.editSong(2L,
                new SongEditRequest(null, "New artist", null, null, null, null, null));

        assertThat(result.getArtist()).isEqualTo("New artist");
        assertThat(result.getStatus()).isEqualTo("ok");
        verify(songRepo).save(song);
    }

    @Test
    void rejectsUnsupportedArtistGenderBeforeSaving() {
        Song song = song(1L, "Title", "Artist", "ok");
        when(songRepo.findById(1L)).thenReturn(Optional.of(song));

        assertApiCode(() -> service.editSong(1L,
                new SongEditRequest(null, null, null, null, null, null, "alien")),
                "INVALID_ARTIST_GENDER");

        verify(songRepo, never()).save(any(Song.class));
    }

    @Test
    void vocalReviewHandlesDeletedSongsAndConfirmationValidatesBothTrackBounds() {
        SongFile reviewFile = file(10L, 99L, 2);
        reviewFile.setFormat("mkv");
        reviewFile.setVocalConfidence("LOW");
        when(fileRepo.findByVocalConfidence(eq("LOW"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(reviewFile)));
        when(songRepo.findById(99L)).thenReturn(Optional.empty());

        var review = service.listVocalReview(-2, 500).getContent().getFirst();

        assertThat(review.title()).isNull();
        assertThat(review.artist()).isNull();
        ArgumentCaptor<Pageable> reviewPage = ArgumentCaptor.forClass(Pageable.class);
        verify(fileRepo).findByVocalConfidence(eq("LOW"), reviewPage.capture());
        assertThat(reviewPage.getValue().getPageNumber()).isZero();
        assertThat(reviewPage.getValue().getPageSize()).isEqualTo(200);

        when(fileRepo.findById(101L)).thenReturn(Optional.empty());
        assertApiCode(() -> service.confirmVocalTrack(101L, 0), "FILE_NOT_FOUND");

        SongFile singleTrack = file(102L, 12L, 1);
        when(fileRepo.findById(102L)).thenReturn(Optional.of(singleTrack));
        assertApiCode(() -> service.confirmVocalTrack(102L, 0), "INVALID_ACTION");

        SongFile twoTracks = file(103L, 13L, 2);
        when(fileRepo.findById(103L)).thenReturn(Optional.of(twoTracks));
        assertApiCode(() -> service.confirmVocalTrack(103L, -1), "INVALID_ACTION");
        service.setCatalogRevisionService(catalogRevision);
        when(fileRepo.save(twoTracks)).thenReturn(twoTracks);

        service.confirmVocalTrack(103L, 1);

        assertThat(twoTracks.getOriginalTrackIndex()).isZero();
        assertThat(twoTracks.getAccompanimentTrackIndex()).isEqualTo(1);
        assertThat(twoTracks.getVocalConfidence()).isEqualTo("HIGH");
        verify(projection).recompute(13L);
        verify(catalogRevision).bumpIfChanged(true);
    }

    @Test
    void deletingCurrentSongFileAndQueueClearsThePlayerWithinManagedRoot() throws Exception {
        Path root = Files.createDirectory(tempDir.resolve("managed"));
        Path media = root.resolve("song.mkv");
        Files.write(media, new byte[]{1, 2, 3});
        properties.setKtvLibraryPath(root.toString());

        Song song = song(1L, "Title", "Artist", "ok");
        SongFile file = file(10L, 1L, 2);
        file.setFilePath(media.toString());
        QueueItem current = new QueueItem();
        current.setId(88L);
        PlayerState player = new PlayerState();
        player.setCurrentQueueId(88L);
        when(songRepo.findById(1L)).thenReturn(Optional.of(song));
        when(fileRepo.findBySongIdOrderByPriorityDesc(1L)).thenReturn(List.of(file));
        when(queueRepo.findBySongId(1L)).thenReturn(List.of(current));
        when(playerRepo.getSingleton()).thenReturn(player);

        service.deleteSong(1L);

        assertThat(Files.exists(media)).isFalse();
        assertThat(player.getCurrentQueueId()).isNull();
        assertThat(player.getState()).isEqualTo("idle");
        verify(playerRepo).save(player);
        verify(queueRepo).deleteAll(List.of(current));
        verify(historyRepo).deleteBySongId(1L);
        verify(songRepo).delete(song);
    }

    @Test
    void nonemptyDirectoryFailureLeavesDatabaseRowsUntouched() throws Exception {
        Path root = Files.createDirectory(tempDir.resolve("managed-failure"));
        Path directory = Files.createDirectory(root.resolve("not-a-file"));
        Files.write(directory.resolve("keep.bin"), new byte[]{4, 5, 6});
        properties.setKtvLibraryPath(root.toString());

        Song song = song(1L, "Title", "Artist", "ok");
        SongFile file = file(10L, 1L, 1);
        file.setFilePath(directory.toString());
        when(songRepo.findById(1L)).thenReturn(Optional.of(song));
        when(fileRepo.findBySongIdOrderByPriorityDesc(1L)).thenReturn(List.of(file));

        assertApiCode(() -> service.deleteSong(1L), "DELETE_LIBRARY_FILE_FAILED");

        assertThat(Files.exists(directory.resolve("keep.bin"))).isTrue();
        verify(songRepo, never()).delete(song);
        verify(queueRepo, never()).deleteAll(any());
        verify(historyRepo, never()).deleteBySongId(1L);
    }

    @Test
    void swapRejectsDualTrackAssignmentsWhenOnlyAccompanimentIsMissing() {
        SongFile file = file(20L, 1L, 2);
        file.setAudioLayout(AudioLayout.DUAL_TRACK);
        file.setOriginalTrackIndex(0);
        file.setAccompanimentTrackIndex(null);
        when(fileRepo.findById(20L)).thenReturn(Optional.of(file));

        assertApiCode(() -> service.swapAudioLayout(20L), "INVALID_AUDIO_LAYOUT");

        verify(fileRepo, never()).save(any(SongFile.class));
    }

    @Test
    void swapRejectsDualTrackAssignmentsWhenOriginalTrackIsMissing() {
        SongFile file = file(21L, 1L, 1);
        file.setAudioLayout(AudioLayout.DUAL_TRACK);
        file.setOriginalTrackIndex(null);
        file.setAccompanimentTrackIndex(0);
        when(fileRepo.findById(21L)).thenReturn(Optional.of(file));

        assertApiCode(() -> service.swapAudioLayout(21L), "INVALID_AUDIO_LAYOUT");

        verify(fileRepo, never()).save(any(SongFile.class));
    }

    @Test
    void dualTrackDefaultsOriginalToOneWhenAccompanimentIsTrackZero() {
        SongFile file = file(22L, 1L, 2);
        file.setAccompanimentTrackIndex(0);
        when(fileRepo.findById(22L)).thenReturn(Optional.of(file));
        when(fileRepo.save(file)).thenReturn(file);

        var result = service.updateAudioLayout(22L,
                new AudioLayoutUpdateRequest(AudioLayout.DUAL_TRACK, null, 0, null, null));

        assertThat(result.originalTrackIndex()).isEqualTo(1);
        assertThat(result.accompanimentTrackIndex()).isZero();
    }

    @Test
    void managedDeleteClearsLockedPlayerWithoutSavingItAgain() {
        Song song = song(3L, "Title", "Artist", "ok");
        QueueItem current = new QueueItem();
        current.setId(89L);
        PlayerState player = new PlayerState();
        player.setCurrentQueueId(89L);
        player.setState("playing");
        when(songRepo.findById(3L)).thenReturn(Optional.of(song));
        when(fileRepo.findBySongIdOrderByPriorityDesc(3L)).thenReturn(List.of());
        when(queueRepo.findBySongId(3L)).thenReturn(List.of(current));
        when(playerRepo.getSingletonForUpdate()).thenReturn(player);
        service.setManagedLibraryDeleteService(mock(ManagedLibraryDeleteService.class));

        service.deleteSong(3L);

        assertThat(player.getCurrentQueueId()).isNull();
        assertThat(player.getState()).isEqualTo("idle");
        verify(playerRepo, never()).save(any(PlayerState.class));
        verify(playerRepo).getSingletonForUpdate();
        verify(queueRepo).lockQueueMutation();
    }

    private static void assertApiCode(Runnable action, String expected) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.getCode()).isEqualTo(expected));
    }

    private static Song song(Long id, String title, String artist, String status) {
        Song song = new Song();
        song.setId(id);
        song.setTitle(title);
        song.setArtist(artist);
        song.setStatus(status);
        song.setFingerprint("fp-" + id);
        return song;
    }

    private static SongFile file(Long id, Long songId, int audioTracks) {
        SongFile file = new SongFile();
        file.setId(id);
        file.setSongId(songId);
        file.setAudioTracks(audioTracks);
        file.setFilePath("/synthetic/" + id + ".mkv");
        file.setFormat("mkv");
        return file;
    }
}
