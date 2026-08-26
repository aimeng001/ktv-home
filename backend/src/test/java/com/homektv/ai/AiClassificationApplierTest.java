package com.homektv.ai;

import com.homektv.domain.Song;
import com.homektv.repo.SongRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiClassificationApplierTest {
    @Test
    void aiDoesNotOverwriteLockedTags() {
        Song song = songWithTags("人工分类");
        song.lockMetadata("tags");
        SongRepository repository = mock(SongRepository.class);
        when(repository.findById(1L)).thenReturn(Optional.of(song));
        when(repository.save(any(Song.class))).thenAnswer(invocation -> invocation.getArgument(0));
        AiConfigService config = mock(AiConfigService.class);
        when(config.resolve()).thenReturn(defaultConfig());

        new AiClassificationApplier(repository, config).apply(1L, resultWithTags("AI分类"));

        assertThat(song.getTags()).containsExactly("人工分类");
    }

    @Test
    void aiStillMergesTagsWhenTheyAreNotLocked() {
        Song song = songWithTags("已有分类");
        SongRepository repository = mock(SongRepository.class);
        when(repository.findById(1L)).thenReturn(Optional.of(song));
        when(repository.save(any(Song.class))).thenAnswer(invocation -> invocation.getArgument(0));
        AiConfigService config = mock(AiConfigService.class);
        when(config.resolve()).thenReturn(defaultConfig());

        new AiClassificationApplier(repository, config).apply(1L, resultWithTags("AI分类"));

        assertThat(song.getTags()).contains("已有分类", "AI分类", "00年代", "独唱");
    }

    private static Song songWithTags(String... tags) {
        Song song = new Song();
        song.setId(1L);
        song.setTitle("歌名");
        song.setArtist("歌手");
        song.setMediaType("AUDIO");
        song.setFingerprint("fp-ai-test");
        song.setTags(tags);
        return song;
    }

    private static AiSongClassification resultWithTags(String tag) {
        return new AiSongClassification("国语", "00年代", List.of(tag), List.of(),
                "全年龄", "独唱", List.of(), "test", 0.99);
    }

    private static AiConfigService.ResolvedConfig defaultConfig() {
        return new AiConfigService.ResolvedConfig(true, "https://ai.example.test", "model", "",
                30, 0.97, 0.92, AiConfigService.JsonMode.AUTO, 1, 1, "key");
    }
}
