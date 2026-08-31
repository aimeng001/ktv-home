package com.homektv.library;

import com.homektv.domain.Song;
import com.homektv.repo.SongFileRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
