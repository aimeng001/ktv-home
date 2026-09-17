package com.homektv.library;

import com.homektv.domain.Song;
import com.homektv.repo.SongFileRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SongAvailabilityPolicyTest {

    @Mock
    private SongFileRepository fileRepository;

    @Test
    void pendingProbeSongMustNotBePlayable() {
        Song song = song(7L, "ok");
        when(fileRepository.existsReadyFile(7L)).thenReturn(false);

        SongAvailabilityPolicy policy = new SongAvailabilityPolicy(fileRepository);

        assertThat(policy.isPlayable(song)).isFalse();
        assertThatThrownBy(() -> policy.requirePlayable(song))
                .hasFieldOrPropertyWithValue("code", "SONG_NOT_READY");
    }

    @Test
    void recognizedSongWithAReadyFileIsPlayable() {
        Song song = song(8L, "ok");
        when(fileRepository.existsReadyFile(8L)).thenReturn(true);

        SongAvailabilityPolicy policy = new SongAvailabilityPolicy(fileRepository);

        assertThat(policy.isPlayable(song)).isTrue();
    }

    @Test
    void batchPlayabilityUsesOneReadinessQueryAndKeepsStatusPolicy() {
        Song ready = song(10L, "ok");
        Song pending = song(11L, "ok");
        Song unrecognized = song(12L, "file_missing");
        when(fileRepository.findSongIdsWithReadyFile(java.util.Set.of(10L, 11L))).thenReturn(java.util.Set.of(10L));

        SongAvailabilityPolicy policy = new SongAvailabilityPolicy(fileRepository);

        assertThat(policy.playableSongIds(java.util.List.of(ready, pending, unrecognized)))
                .containsExactly(10L);
        verify(fileRepository, times(1)).findSongIdsWithReadyFile(java.util.Set.of(10L, 11L));
    }
    @Test
    void missingOrUnrecognizedSongIsNotPlayable() {
        Song song = song(9L, "unrecognized");

        SongAvailabilityPolicy policy = new SongAvailabilityPolicy(fileRepository);

        assertThat(policy.isPlayable(song)).isFalse();
    }

    private static Song song(long id, String status) {
        Song song = new Song();
        song.setId(id);
        song.setTitle("测试歌曲");
        song.setStatus(status);
        return song;
    }
}
