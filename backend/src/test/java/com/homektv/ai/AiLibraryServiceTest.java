package com.homektv.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homektv.domain.AiAnalysisTask;
import com.homektv.domain.Song;
import com.homektv.domain.Playlist;
import com.homektv.domain.PlaylistSong;
import com.homektv.library.AssetWriter;
import com.homektv.repo.AiAnalysisTaskRepository;
import com.homektv.repo.MediaImportRecordRepository;
import com.homektv.repo.PlaylistRepository;
import com.homektv.repo.PlaylistSongRepository;
import com.homektv.repo.SongRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.mockito.ArgumentCaptor;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiLibraryServiceTest {
    @Test
    void playlistCandidateContainsTrustedScrapedMetadata() {
        Song song = new Song();
        song.setId(36L);
        song.setTitle("刮削歌名");
        song.setArtist("刮削歌手");
        song.setLanguage("粤语");
        song.setVocalForm("对唱");
        song.setAlbum("平台专辑");
        song.setReleaseDate("1999-01-01");
        song.setAliases(new String[]{"歌曲别名"});
        song.setTags(new String[]{"聚会"});
        song.setAiGenres(new String[]{"流行"});
        song.setAiThemes(new String[]{"怀旧"});
        song.setAiEra("90年代");
        song.setDurationMs(210_000);
        song.setMetadataProvenance("""
                {"title":{"source":"QQ","externalId":"qq-36","trusted":true},
                 "album":{"source":"NETEASE","externalId":"ne-36","trusted":true},
                 "aliases":{"source":"UNTRUSTED","trusted":false}}
                """);

        Map<String, Object> candidate = service().playlistCandidate(song);

        assertThat(candidate).containsEntry("id", 36L)
                .containsEntry("album", "平台专辑")
                .containsEntry("releaseDate", "1999-01-01")
                .containsEntry("metadataScraped", true)
                .containsEntry("durationSeconds", 210L);
        assertThat(candidate.get("aliases")).isEqualTo(java.util.List.of("歌曲别名"));
        assertThat(candidate.get("genres")).isEqualTo(java.util.List.of("流行"));
        assertThat(candidate.get("themes")).isEqualTo(java.util.List.of("怀旧"));
        assertThat(candidate.get("metadataSources")).isEqualTo(Map.of("title", "QQ", "album", "NETEASE"));
    }

    @Test
    void playlistPreviewConsidersPreferredSongsBeyondTheFirstFiveThousandRows() throws Exception {
        SongRepository songRepository = mock(SongRepository.class);
        OpenAiCompatibleClient aiClient = mock(OpenAiCompatibleClient.class);
        ObjectMapper objectMapper = new ObjectMapper();

        when(songRepository.findAll(any(Pageable.class))).thenAnswer(invocation -> {
            Pageable pageable = invocation.getArgument(0, Pageable.class);
            int firstId = pageable.getPageNumber() * pageable.getPageSize() + 1;
            int lastId = Math.min(firstId + pageable.getPageSize() - 1, 5001);
            List<Song> content = java.util.stream.IntStream.rangeClosed(firstId, lastId)
                    .mapToObj(id -> {
                        Song song = song(id, "歌曲" + id, "歌手");
                        if (id == 5001) {
                            song.setAiAnalyzedAt(OffsetDateTime.parse("2026-01-01T00:00:00Z"));
                        }
                        return song;
                    })
                    .toList();
            return new PageImpl<>(content, pageable, 5001);
        });
        when(aiClient.completeJsonPromptOnly(anyString(), anyString(), anyString(), anyInt())).thenReturn(
                objectMapper.readTree("{\"intent\":\"优先可信元数据\"}"),
                objectMapper.readTree("{\"name\":\"边界歌单\",\"songIds\":[5001]}"));

        Map<String, Object> result = service(songRepository, mock(PlaylistRepository.class),
                mock(PlaylistSongRepository.class), aiClient, objectMapper)
                .previewPlaylist("优先可信元数据", 1);

        assertThat(result).containsEntry("songIds", List.of(5001L))
                .containsEntry("selectedCount", 1)
                .containsEntry("candidateCount", 5000);
    }

    @Test
    void playlistPreviewAcceptsFewerSongsAndCommonNestedResponse() throws Exception {
        SongRepository songRepository = mock(SongRepository.class);
        OpenAiCompatibleClient aiClient = mock(OpenAiCompatibleClient.class);
        ObjectMapper objectMapper = new ObjectMapper();
        Song first = song(1L, "第一首", "歌手甲");
        Song second = song(2L, "第二首", "歌手乙");
        Song third = song(3L, "第三首", "歌手丙");
        when(songRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(first, second, third)));
        when(songRepository.findAll()).thenThrow(new AssertionError("unpaged song query is forbidden"));
        when(aiClient.completeJsonPromptOnly(anyString(), anyString(), anyString(), anyInt())).thenReturn(
                objectMapper.readTree("{\"intent\":\"轻松聚会\",\"maxSongs\":100}"),
                objectMapper.readTree("{\"playlist\":{\"playlistName\":\"少而精\",\"songs\":[{\"songId\":1},{\"id\":2},{\"id\":999}]}}"));

        Map<String, Object> result = service(songRepository, mock(PlaylistRepository.class),
                mock(PlaylistSongRepository.class), aiClient, objectMapper).previewPlaylist("轻松聚会", 500);

        assertThat(result).containsEntry("name", "少而精")
                .containsEntry("limit", 100)
                .containsEntry("selectedCount", 2);
        assertThat(result.get("songIds")).isEqualTo(List.of(1L, 2L));
        verify(songRepository, never()).findAll();
    }

    @Test
    void repairBatchKeepsBulkRoleSeparateFromConfiguredModelName() {
        SongRepository songRepository = mock(SongRepository.class);
        AiAnalysisTaskRepository taskRepository = mock(AiAnalysisTaskRepository.class);
        AiAnalysisWorker worker = mock(AiAnalysisWorker.class);
        AiConfigService configService = mock(AiConfigService.class);
        when(songRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(song(4L, "批量修复", "歌手"))));
        when(configService.isConfigured()).thenReturn(true);
        when(configService.resolve()).thenReturn(new AiConfigService.ResolvedConfig(true, "http://ai.test/v1",
                "bulk-model", "", 30, 0.97, 0.92, AiConfigService.JsonMode.AUTO, 2, 1, "secret"));

        AiLibraryService service = new AiLibraryService(taskRepository, songRepository,
                mock(PlaylistRepository.class), mock(PlaylistSongRepository.class), worker,
                new ObjectMapper(), configService, mock(AssetWriter.class),
                mock(AiClassificationApplier.class), mock(OpenAiCompatibleClient.class),
                mock(MediaImportRecordRepository.class));

        service.createRepairBatch();

        ArgumentCaptor<AiAnalysisTask> captor = ArgumentCaptor.forClass(AiAnalysisTask.class);
        verify(taskRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getModel()).isEqualTo("bulk-model");
        assertThat(captor.getValue().getModelRole()).isEqualTo("BULK");
    }

    @Test
    void addingSongBeyondPlaylistLimitIsRejected() {
        SongRepository songRepository = mock(SongRepository.class);
        PlaylistRepository playlistRepository = mock(PlaylistRepository.class);
        PlaylistSongRepository playlistSongRepository = mock(PlaylistSongRepository.class);
        Playlist playlist = new Playlist();
        when(playlistRepository.findById(9L)).thenReturn(Optional.of(playlist));
        when(songRepository.findById(101L)).thenReturn(Optional.of(song(101L, "新歌", "歌手")));
        when(playlistSongRepository.findByPlaylistIdOrderBySortOrder(9L)).thenReturn(
                java.util.stream.LongStream.rangeClosed(1, 100).mapToObj(id -> {
                    PlaylistSong item = new PlaylistSong();
                    item.setSongId(id);
                    item.setPlaylistId(9L);
                    return item;
                }).toList());

        AiLibraryService service = service(songRepository, playlistRepository, playlistSongRepository,
                mock(OpenAiCompatibleClient.class), new ObjectMapper());

        assertThatThrownBy(() -> service.addPlaylistSong(9L, 101L))
                .hasMessageContaining("最多包含 100 首");
    }

    @Test
    void removingPlaylistSongLocksThePlaylistBeforeDeleting() {
        PlaylistRepository playlists = mock(PlaylistRepository.class);
        PlaylistSongRepository playlistSongs = mock(PlaylistSongRepository.class);
        Playlist playlist = new Playlist();
        when(playlists.findById(9L)).thenReturn(Optional.of(playlist));
        AiLibraryService service = service(mock(SongRepository.class), playlists, playlistSongs,
                mock(OpenAiCompatibleClient.class), new ObjectMapper());

        service.removePlaylistSong(9L, 101L);

        verify(playlistSongs).lockPlaylist(9L);
        verify(playlistSongs).deleteByPlaylistIdAndSongId(9L, 101L);
    }

    @Test
    void reorderingPlaylistSongsLocksBeforeReadingTheCurrentOrder() {
        PlaylistRepository playlists = mock(PlaylistRepository.class);
        PlaylistSongRepository playlistSongs = mock(PlaylistSongRepository.class);
        SongRepository songs = mock(SongRepository.class);
        Playlist playlist = new Playlist();
        when(playlists.findById(9L)).thenReturn(Optional.of(playlist));
        PlaylistSong first = playlistSong(9L, 1L, 0);
        PlaylistSong second = playlistSong(9L, 2L, 1);
        when(playlistSongs.findByPlaylistIdOrderBySortOrder(9L)).thenReturn(List.of(first, second));
        when(songs.findById(1L)).thenReturn(Optional.of(song(1L, "一", "甲")));
        when(songs.findById(2L)).thenReturn(Optional.of(song(2L, "二", "乙")));
        AiLibraryService service = service(songs, playlists, playlistSongs,
                mock(OpenAiCompatibleClient.class), new ObjectMapper());

        service.reorderPlaylistSongs(9L, List.of(2L, 1L));

        verify(playlistSongs).lockPlaylist(9L);
        verify(playlistSongs).saveAll(any());
    }

    private PlaylistSong playlistSong(long playlistId, long songId, int sortOrder) {
        PlaylistSong item = new PlaylistSong();
        item.setPlaylistId(playlistId);
        item.setSongId(songId);
        item.setSortOrder(sortOrder);
        return item;
    }

    private Song song(long id, String title, String artist) {
        Song song = new Song();
        song.setId(id);
        song.setTitle(title);
        song.setArtist(artist);
        return song;
    }

    private AiLibraryService service() {
        return service(mock(SongRepository.class), mock(PlaylistRepository.class),
                mock(PlaylistSongRepository.class), mock(OpenAiCompatibleClient.class), new ObjectMapper());
    }

    private AiLibraryService service(SongRepository songRepository, PlaylistRepository playlistRepository,
                                     PlaylistSongRepository playlistSongRepository,
                                     OpenAiCompatibleClient aiClient, ObjectMapper objectMapper) {
        AiConfigService configService = mock(AiConfigService.class);
        when(configService.resolve()).thenReturn(new AiConfigService.ResolvedConfig(true, "http://ai.test/v1",
                "bulk-model", "", 30, 0.97, 0.92, AiConfigService.JsonMode.AUTO, 2, 1, "secret"));
        return new AiLibraryService(mock(AiAnalysisTaskRepository.class), songRepository,
                playlistRepository, playlistSongRepository, mock(AiAnalysisWorker.class),
                objectMapper, configService, mock(AssetWriter.class),
                mock(AiClassificationApplier.class), aiClient, mock(MediaImportRecordRepository.class));
    }
}
