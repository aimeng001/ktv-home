package com.homektv.library;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homektv.ai.AiConfigService;
import com.homektv.ai.OpenAiCompatibleClient;
import com.homektv.domain.Song;
import com.homektv.repo.SongRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ArtistLibraryServiceTest {
    private SongRepository songs;
    private AiConfigService aiConfig;
    private ArtistLibraryService service;

    @BeforeEach
    void setUp() {
        songs = mock(SongRepository.class);
        aiConfig = mock(AiConfigService.class);
        service = new ArtistLibraryService(songs, aiConfig, mock(OpenAiCompatibleClient.class), new ObjectMapper());
    }

    @Test
    void groupsSameNameAndReturnsAtMostFiveRepresentativeSongs() {
        List<Song> library = java.util.stream.IntStream.rangeClosed(1, 7)
                .mapToObj(index -> song((long) index, "同名歌手", "歌曲" + index, "未知", false, index))
                .toList();
        givenSongs(library);

        List<Map<String, Object>> result = service.list("同名", null, null, 100);

        assertThat(result).hasSize(1);
        assertThat(result.get(0)).containsEntry("name", "同名歌手").containsEntry("songCount", 7);
        assertThat((List<?>) result.get(0).get("songs")).hasSize(5);
    }

    @Test
    void artistAfterTheFirstFiveThousandSongsRemainsVisible() {
        when(songs.findByStatus(eq("ok"), any(Pageable.class))).thenAnswer(invocation -> {
            Pageable pageable = invocation.getArgument(1, Pageable.class);
            int firstId = pageable.getPageNumber() * pageable.getPageSize() + 1;
            int lastId = Math.min(firstId + pageable.getPageSize() - 1, 5001);
            List<Song> content = java.util.stream.IntStream.rangeClosed(firstId, lastId)
                    .mapToObj(index -> song((long) index,
                            index == 5001 ? "边界歌手" : "普通歌手",
                            index == 5001 ? "边界歌曲" : "歌曲" + index,
                            "未知", false, index == 5001 ? 1 : 0))
                    .toList();
            return new PageImpl<>(content, pageable, 5001);
        });

        List<Map<String, Object>> result = service.list("边界歌手", null, null, 100);

        assertThat(result).hasSize(1);
        assertThat(result.get(0))
                .containsEntry("name", "边界歌手")
                .containsEntry("songCount", 1);
        verify(songs, times(11)).findByStatus(eq("ok"), any(Pageable.class));
    }

    @Test
    void onlyReportsReviewedWhenEveryGroupedSongHasTheManualLock() {
        Song locked = song(1L, "歌手", "歌曲一", "女歌手", true, 2);
        Song unlocked = song(2L, "歌手", "歌曲二", "女歌手", false, 1);
        givenSongs(List.of(locked, unlocked));

        assertThat(service.list(null, null, null, 100).get(0)).containsEntry("reviewed", false);

        unlocked.lockMetadata("artistGender");
        assertThat(service.list(null, null, null, 100).get(0)).containsEntry("reviewed", true);
    }

    @Test
    void manualReviewWritesAndLocksEverySongWithTheSameName() {
        Song first = song(1L, "歌手", "歌曲一", "未知", false, 2);
        Song second = song(2L, "歌手", "歌曲二", "未知", false, 1);
        givenSongs(List.of(first, second));

        Map<String, Object> result = service.apply("歌手", "男歌手");

        assertThat(result).containsEntry("updated", 2).containsEntry("gender", "男歌手");
        assertThat(List.of(first, second)).allSatisfy(song -> {
            assertThat(song.getArtistGender()).isEqualTo("男歌手");
            assertThat(song.isMetadataLocked("artistGender")).isTrue();
        });
        verify(songs).saveAll(List.of(first, second));
    }

    @Test
    void returnsManualFallbackWhenAiIsNotConfigured() {
        Song sample = song(1L, "歌手", "歌曲", "未知", false, 1);
        givenSongs(List.of(sample));
        when(aiConfig.isConfigured()).thenReturn(false);

        assertThat(service.analyze("歌手"))
                .containsEntry("gender", "未知")
                .containsEntry("source", "LOCAL")
                .containsEntry("confidence", 0.0);
    }

    @Test
    void batchAnalysisReturnsOneSuggestionPerDistinctArtist() {
        Song first = song(1L, "歌手甲", "歌曲一", "未知", false, 1);
        Song second = song(2L, "歌手乙", "歌曲二", "未知", false, 1);
        givenSongs(List.of(first, second));
        when(aiConfig.isConfigured()).thenReturn(false);

        List<Map<String, Object>> result = service.analyzeBatch(List.of("歌手甲", "歌手甲", "歌手乙"));

        assertThat(result).extracting(item -> item.get("artist")).containsExactly("歌手甲", "歌手乙");
        assertThat(result).allSatisfy(item -> assertThat(item).containsEntry("source", "LOCAL"));
        verify(songs, times(1)).findByStatus(eq("ok"), any(Pageable.class));
        verify(songs, never()).findAll();
    }

    private void givenSongs(List<Song> library) {
        when(songs.findByStatus(eq("ok"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(library));
    }

    private Song song(Long id, String artist, String title, String gender, boolean locked, int playCount) {
        Song song = new Song();
        song.setId(id);
        song.setArtist(artist);
        song.setTitle(title);
        song.setArtistGender(gender);
        song.setStatus("ok");
        song.setLanguage("国语");
        song.setMediaType("KTV_VIDEO");
        song.setPlayCount(playCount);
        if (locked) song.lockMetadata("artistGender");
        return song;
    }
}
