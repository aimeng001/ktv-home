package com.homektv.musicsource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homektv.domain.Song;
import com.homektv.repo.SongRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MusicMetadataApplyServiceTest {
    @Test
    void appliesAndLocksValuesEditedDuringManualReview() {
        Song song = new Song();
        song.setId(7L); song.setTitle("旧歌名"); song.setArtist("旧歌手"); song.setDurationMs(200_000);
        song.setFingerprint("old"); song.setMetadataProvenance("{}");
        ExternalTrack track = new ExternalTrack(MusicProvider.QQ, "track-7", "平台歌名",
                List.of("平台歌手"), "平台专辑", 200_000, "2024-01-01", List.of("平台别名"),
                null, "AVAILABLE", null);
        SongRepository songs = mock(SongRepository.class);
        ExternalTrackStorage storage = mock(ExternalTrackStorage.class);
        when(songs.findById(7L)).thenReturn(Optional.of(song));
        when(songs.findByFingerprint(org.mockito.ArgumentMatchers.anyString())).thenReturn(Optional.empty());
        when(storage.track(MusicProvider.QQ, "track-7", false)).thenReturn(Optional.of(track));
        MusicMetadataApplyService service = new MusicMetadataApplyService(songs, mock(MusicSourceSearchService.class),
                storage, mock(ExternalCoverService.class), mock(MusicSourceConfigService.class), new ObjectMapper());

        service.apply(7L, MusicProvider.QQ, "track-7", new MusicMetadataApplyService.ApplyRequest(
                Set.of("title", "artist", "album", "releaseDate", "aliases"),
                Map.of("title", "人工确认歌名", "aliases", "别名一, 别名二")));

        assertThat(song.getTitle()).isEqualTo("人工确认歌名");
        assertThat(song.getArtist()).isEqualTo("平台歌手");
        assertThat(song.getAlbum()).isEqualTo("平台专辑");
        assertThat(song.getAliases()).containsExactly("别名一", "别名二");
        assertThat(song.getMetadataLocks()).containsExactlyInAnyOrder("title", "aliases");
        assertThat(song.getMetadataProvenance()).contains("MANUAL_REVIEW").contains("QQ");
    }

    @Test
    void appliesManualFieldsWithoutAnExternalCandidate() {
        Song song = new Song();
        song.setId(8L); song.setTitle("原歌名"); song.setArtist("原歌手"); song.setDurationMs(180_000);
        song.setFingerprint("old"); song.setMetadataProvenance("{}");
        SongRepository songs = mock(SongRepository.class);
        when(songs.findById(8L)).thenReturn(Optional.of(song));
        when(songs.findByFingerprint(org.mockito.ArgumentMatchers.anyString())).thenReturn(Optional.empty());
        MusicMetadataApplyService service = new MusicMetadataApplyService(songs, mock(MusicSourceSearchService.class),
                mock(ExternalTrackStorage.class), mock(ExternalCoverService.class),
                mock(MusicSourceConfigService.class), new ObjectMapper());

        service.applyManual(8L, new MusicMetadataApplyService.ApplyRequest(
                Set.of("album", "releaseDate", "aliases"),
                Map.of("album", "人工专辑", "releaseDate", "2001", "aliases", "别名甲,别名乙")));

        assertThat(song.getAlbum()).isEqualTo("人工专辑");
        assertThat(song.getReleaseDate()).isEqualTo("2001");
        assertThat(song.getAliases()).containsExactly("别名甲", "别名乙");
        assertThat(song.getMetadataLocks()).containsExactlyInAnyOrder("album", "releaseDate", "aliases");
        assertThat(song.getMetadataProvenance()).contains("MANUAL_REVIEW").doesNotContain("externalId");
    }

    @Test
    void promotesUnrecognizedSongToOkAndRefreshesProjectionWhenIdentityIsPopulated() {
        Song song = new Song();
        song.setId(9L);
        song.setTitle("未识别");
        song.setArtist("未知歌手");
        song.setStatus("unrecognized");
        song.setDurationMs(210_000);
        song.setMetadataProvenance("{}");

        ExternalTrack track = new ExternalTrack(MusicProvider.QQ, "track-9", "我的背后",
                List.of("5566"), "专辑", 210_000, "2003-01-01", List.of(),
                null, "AVAILABLE", null);
        SongRepository songs = mock(SongRepository.class);
        ExternalTrackStorage storage = mock(ExternalTrackStorage.class);
        when(songs.findById(9L)).thenReturn(Optional.of(song));
        when(songs.findByFingerprint(org.mockito.ArgumentMatchers.anyString())).thenReturn(Optional.empty());
        when(storage.track(MusicProvider.QQ, "track-9", false)).thenReturn(Optional.of(track));

        com.homektv.library.ArtistDirectoryProjectionService projection = mock(com.homektv.library.ArtistDirectoryProjectionService.class);
        MusicMetadataApplyService service = new MusicMetadataApplyService(songs, mock(MusicSourceSearchService.class),
                storage, mock(ExternalCoverService.class), mock(MusicSourceConfigService.class), new ObjectMapper());
        service.setArtistDirectoryProjectionService(projection);

        service.apply(9L, MusicProvider.QQ, "track-9", new MusicMetadataApplyService.ApplyRequest(
                Set.of("title", "artist"),
                Map.of()));

        assertThat(song.getStatus()).isEqualTo("ok");
        assertThat(song.getTitle()).isEqualTo("我的背后");
        assertThat(song.getArtist()).isEqualTo("5566");
        org.mockito.Mockito.verify(projection).requestRefreshAfterCommit();
    }

    @Test
    void protectsStructuredFilenameTitleAndArtistWhenFilenameMetadataIsPreferred() {
        Song song = new Song();
        song.setId(10L);
        song.setTitle("权威歌名");
        song.setArtist("权威歌手");
        song.setDurationMs(200_000);
        song.setMetadataProvenance("{\"title\":{\"source\":\"filename\"},\"artist\":{\"source\":\"filename\"}}");
        ExternalTrack track = new ExternalTrack(MusicProvider.QQ, "track-10", "平台别名歌名",
                List.of("平台歌手"), "正版专辑", 200_000, "2020-01-01", List.of("别名A"),
                null, "AVAILABLE", null);
        SongRepository songs = mock(SongRepository.class);
        ExternalTrackStorage storage = mock(ExternalTrackStorage.class);
        when(songs.findById(10L)).thenReturn(Optional.of(song));
        when(songs.findByFingerprint(org.mockito.ArgumentMatchers.anyString())).thenReturn(Optional.empty());
        when(storage.track(MusicProvider.QQ, "track-10", false)).thenReturn(Optional.of(track));
        com.homektv.config.AppProperties props = new com.homektv.config.AppProperties();
        props.setStructuredFilenameMetadataPreferred(true);
        MusicMetadataApplyService service = new MusicMetadataApplyService(songs, mock(MusicSourceSearchService.class),
                storage, mock(ExternalCoverService.class), mock(MusicSourceConfigService.class), new ObjectMapper());
        service.setAppProperties(props);

        MusicMetadataApplyService.ApplyResult result = service.apply(10L, MusicProvider.QQ, "track-10",
                new MusicMetadataApplyService.ApplyRequest(Set.of("title", "artist", "album", "releaseDate", "aliases"), Map.of()));

        assertThat(song.getTitle()).isEqualTo("权威歌名");
        assertThat(song.getArtist()).isEqualTo("权威歌手");
        assertThat(song.getAlbum()).isEqualTo("正版专辑");
        assertThat(result.skippedLockedFields()).containsExactlyInAnyOrder("title", "artist");
    }
}
