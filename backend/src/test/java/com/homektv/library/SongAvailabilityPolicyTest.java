package com.homektv.library;

import com.homektv.domain.Song;
import com.homektv.domain.SongFile;
import com.homektv.repo.SongFileRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
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
    void requirePlayableAllowsAReadySong() {
        Song song = song(80L, "OK");
        when(fileRepository.existsReadyFile(80L)).thenReturn(true);
        SongAvailabilityPolicy policy = new SongAvailabilityPolicy(fileRepository);

        assertThatCode(() -> policy.requirePlayable(song)).doesNotThrowAnyException();
        verify(fileRepository).existsReadyFile(80L);
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

    @Test
    void nullUnpersistedAndUnrecognizedSongsAreRejectedBeforeReadinessLookup() {
        Song withoutId = new Song();
        withoutId.setStatus("ok");
        Song withoutStatus = song(13L, null);
        Song pending = song(14L, "pending");
        SongAvailabilityPolicy policy = new SongAvailabilityPolicy(fileRepository);

        assertThat(policy.isPlayable(null)).isFalse();
        assertThat(policy.isPlayable(withoutId)).isFalse();
        assertThat(policy.isPlayable(withoutStatus)).isFalse();
        assertThat(policy.isPlayable(pending)).isFalse();
        org.mockito.Mockito.verifyNoInteractions(fileRepository);
    }

    @Test
    void batchPlayabilitySkipsEmptyAndIneligibleInputsWithoutQuerying() {
        Song withoutId = new Song();
        withoutId.setStatus("ok");
        Song unrecognized = song(15L, "file_missing");
        SongAvailabilityPolicy policy = new SongAvailabilityPolicy(fileRepository);

        assertThat(policy.playableSongIds(null)).isEmpty();
        assertThat(policy.playableSongIds(java.util.List.of())).isEmpty();
        assertThat(policy.playableSongIds(java.util.Arrays.asList(null, withoutId, unrecognized))).isEmpty();
        org.mockito.Mockito.verifyNoInteractions(fileRepository);
    }

    @Test
    void batchPlayabilityTreatsNullRepositoryResultAsEmptyAndDeduplicatesIds() {
        Song duplicate = song(16L, "ok");
        when(fileRepository.findSongIdsWithReadyFile(java.util.Set.of(16L))).thenReturn(null);
        SongAvailabilityPolicy policy = new SongAvailabilityPolicy(fileRepository);

        assertThat(policy.playableSongIds(java.util.List.of(duplicate, duplicate))).isEmpty();
        verify(fileRepository, times(1)).findSongIdsWithReadyFile(java.util.Set.of(16L));
    }

    @Test
    void fileReadinessRequiresAValidNonPendingMediaType() {
        SongFile invalid = readyFile("KTV_VIDEO");
        invalid.setValid(false);
        SongFile pendingProbe = readyFile("KTV_VIDEO");
        pendingProbe.setProbePending(true);
        SongFile missingMediaType = readyFile(null);
        SongFile blankMediaType = readyFile("  ");
        SongFile pendingMediaType = readyFile("pending_probe");

        assertThat(SongAvailabilityPolicy.isReadyMediaFile(null)).isFalse();
        assertThat(SongAvailabilityPolicy.isReadyMediaFile(invalid)).isFalse();
        assertThat(SongAvailabilityPolicy.isReadyMediaFile(pendingProbe)).isFalse();
        assertThat(SongAvailabilityPolicy.isReadyMediaFile(missingMediaType)).isFalse();
        assertThat(SongAvailabilityPolicy.isReadyMediaFile(blankMediaType)).isFalse();
        assertThat(SongAvailabilityPolicy.isReadyMediaFile(pendingMediaType)).isFalse();
        assertThat(SongAvailabilityPolicy.isReadyMediaFile(readyFile("MV"))).isTrue();

        SongAvailabilityPolicy policy = new SongAvailabilityPolicy(fileRepository);
        assertThat(policy.isReadyFile(null)).isFalse();
        assertThat(policy.isReadyFile(pendingMediaType)).isFalse();
        assertThat(policy.isReadyFile(readyFile("MV"))).isTrue();
    }

    private static Song song(long id, String status) {
        Song song = new Song();
        song.setId(id);
        song.setTitle("测试歌曲");
        song.setStatus(status);
        return song;
    }

    private static SongFile readyFile(String mediaType) {
        SongFile file = new SongFile();
        file.setValid(true);
        file.setMediaType(mediaType);
        return file;
    }
}
