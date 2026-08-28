package com.homektv.library;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homektv.config.AppProperties;
import com.homektv.domain.Song;
import com.homektv.domain.SongFile;
import com.homektv.media.FFprobeService;
import com.homektv.musicsource.ExternalCoverService;
import com.homektv.musicsource.ExternalTrack;
import com.homektv.musicsource.ExternalTrackStorage;
import com.homektv.musicsource.MusicMetadataApplyService;
import com.homektv.musicsource.MusicProvider;
import com.homektv.musicsource.MusicSourceConfigService;
import com.homektv.musicsource.MusicSourceSearchService;
import com.homektv.repo.PlayHistoryRepository;
import com.homektv.repo.PlayerStateRepository;
import com.homektv.repo.QueueItemRepository;
import com.homektv.repo.SongFileRepository;
import com.homektv.repo.SongRepository;
import com.homektv.web.dto.SongEditRequest;
import com.homektv.ws.WsBroadcaster;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CollaborativeArtistWritePathTest {

    @Test
    void adminArtistEditRefreshesIndependentCredits() {
        Song song = song(1L, "合唱歌曲", "旧歌手");
        SongRepository songs = mock(SongRepository.class);
        ArtistCreditService credits = mock(ArtistCreditService.class);
        when(songs.findById(1L)).thenReturn(Optional.of(song));
        when(songs.findByFingerprint(anyString())).thenReturn(Optional.empty());
        when(songs.save(any(Song.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AdminService service = new AdminService(songs, mock(SongFileRepository.class),
                mock(PlayHistoryRepository.class), mock(WsBroadcaster.class), mock(AssetWriter.class),
                mock(QueueItemRepository.class), mock(PlayerStateRepository.class), new AppProperties(), credits);

        service.editSong(1L, new SongEditRequest(null, "单依纯_王子异", null, null, null));

        verify(credits).replace(1L, "单依纯_王子异");
    }

    @Test
    void reparsingRefreshesIndependentCredits() {
        Song song = song(2L, "旧歌名", "旧歌手");
        SongFile file = new SongFile();
        file.setSongId(2L);
        file.setFilePath("/source/单依纯_王子异-合唱歌曲-国语-合唱.mkv");
        SongRepository songs = mock(SongRepository.class);
        SongFileRepository files = mock(SongFileRepository.class);
        ArtistCreditService credits = mock(ArtistCreditService.class);
        when(songs.findById(2L)).thenReturn(Optional.of(song));
        when(songs.findByFingerprint(anyString())).thenReturn(Optional.empty());
        when(songs.save(any(Song.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(files.findBySongIdAndValidTrueOrderByPriorityDesc(2L)).thenReturn(List.of(file));

        SongReparseService service = new SongReparseService(songs, files, credits);

        service.apply(List.of(2L), "artist_title");

        verify(credits).replace(2L, "单依纯_王子异");
    }

    @Test
    void externalStructuredArtistsAreStoredAsSeparateCredits() {
        Song song = song(3L, "旧歌名", "旧歌手");
        ExternalTrack track = new ExternalTrack(MusicProvider.QQ, "track-3", "合唱歌曲",
                List.of("单依纯", "王子异"), "专辑", 180_000, "2024-01-01", List.of(),
                null, "AVAILABLE", null);
        SongRepository songs = mock(SongRepository.class);
        ExternalTrackStorage storage = mock(ExternalTrackStorage.class);
        ArtistCreditService credits = mock(ArtistCreditService.class);
        when(songs.findById(3L)).thenReturn(Optional.of(song));
        when(songs.findByFingerprint(anyString())).thenReturn(Optional.empty());
        when(songs.save(any(Song.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(storage.track(MusicProvider.QQ, "track-3", false)).thenReturn(Optional.of(track));

        MusicMetadataApplyService service = new MusicMetadataApplyService(songs,
                mock(MusicSourceSearchService.class), storage, mock(ExternalCoverService.class),
                mock(MusicSourceConfigService.class), new ObjectMapper(), credits);

        service.apply(3L, MusicProvider.QQ, "track-3",
                new MusicMetadataApplyService.ApplyRequest(Set.of("artist"), Map.of()));

        verify(credits).replace(eq(3L), eq(List.of("单依纯", "王子异")));
    }

    private static Song song(long id, String title, String artist) {
        Song song = new Song();
        song.setId(id);
        song.setTitle(title);
        song.setArtist(artist);
        song.setDurationMs(180_000);
        song.setFingerprint("old-" + id);
        song.setMetadataProvenance("{}");
        return song;
    }
}
