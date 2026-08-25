package com.homektv.web.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.homektv.domain.AudioChannel;
import com.homektv.domain.AudioLayout;
import com.homektv.domain.PlayerState;
import com.homektv.domain.QueueItem;
import com.homektv.domain.Song;
import com.homektv.domain.SongFile;
import com.homektv.queue.SnapshotService;
import com.homektv.repo.AppUserRepository;
import com.homektv.repo.PlayerStateRepository;
import com.homektv.repo.QueueItemRepository;
import com.homektv.repo.SongFileRepository;
import com.homektv.repo.SongRepository;
import com.homektv.ws.WsBroadcaster;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import static org.assertj.core.api.Assertions.assertThat;

class AudioLayoutSerializationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void songDetailExposesPlatformNeutralAudioLayoutSemantics() throws Exception {
        SongFile file = new SongFile();
        file.setId(7L);
        file.setFormat("mkv");
        file.setAudioTracks(1);
        file.setAudioLayout(AudioLayout.DUAL_CHANNEL);
        file.setOriginalChannel(AudioChannel.LEFT);
        file.setAccompanimentChannel(AudioChannel.RIGHT);

        JsonNode json = mapper.readTree(mapper.writeValueAsString(
                SongDetailDto.FileSourceDto.from(file)));

        assertThat(json.path("audioLayout").path("layout").asText())
                .isEqualTo("DUAL_CHANNEL");
        assertThat(json.path("audioLayout").path("originalChannel").asText())
                .isEqualTo("LEFT");
        assertThat(json.path("audioLayout").path("accompanimentChannel").asText())
                .isEqualTo("RIGHT");
        assertThat(json.toString()).doesNotContain("ExoPlayer", "TrackSelection", "PCM");
    }

    @Test
    void websocketSnapshotCarriesSemanticLayoutWithoutPlatformDetails() throws Exception {
        AudioLayoutDto layout = new AudioLayoutDto(
                AudioLayout.DUAL_CHANNEL,
                null,
                null,
                AudioChannel.LEFT,
                AudioChannel.RIGHT);
        QueueSnapshot snapshot = new QueueSnapshot(
                null, List.of(), "playing", 60, false, "original", layout, true, 1);

        JsonNode json = mapper.readTree(mapper.writeValueAsString(snapshot));

        assertThat(json.path("audioLayout").path("layout").asText())
                .isEqualTo("DUAL_CHANNEL");
        assertThat(json.path("audioLayout").path("originalChannel").asText())
                .isEqualTo("LEFT");
        assertThat(json.path("audioLayout").path("accompanimentChannel").asText())
                .isEqualTo("RIGHT");
        assertThat(json.toString()).doesNotContain("ExoPlayer", "TrackSelection", "PCM");
    }

    @Test
    void snapshotServicePublishesTheCurrentFileLayoutToWebsocketPayload() {
        PlayerStateRepository playerRepository = mock(PlayerStateRepository.class);
        QueueItemRepository queueRepository = mock(QueueItemRepository.class);
        SongRepository songRepository = mock(SongRepository.class);
        AppUserRepository userRepository = mock(AppUserRepository.class);
        SongFileRepository fileRepository = mock(SongFileRepository.class);
        WsBroadcaster broadcaster = mock(WsBroadcaster.class);

        PlayerState player = new PlayerState();
        player.setCurrentQueueId(11L);
        player.setState("playing");
        QueueItem current = new QueueItem();
        current.setId(11L);
        current.setSongId(22L);
        Song song = new Song();
        song.setId(22L);
        song.setTitle("测试歌曲");
        song.setArtist("测试歌手");
        SongFile file = new SongFile();
        file.setAudioLayout(AudioLayout.DUAL_CHANNEL);

        when(playerRepository.getSingleton()).thenReturn(player);
        when(queueRepository.findByStatusOrderByOrderIndexAsc("waiting")).thenReturn(List.of());
        when(queueRepository.findById(11L)).thenReturn(Optional.of(current));
        when(songRepository.findById(22L)).thenReturn(Optional.of(song));
        when(fileRepository.findBySongIdAndValidTrueOrderByPriorityDesc(22L)).thenReturn(List.of(file));
        when(broadcaster.isTvOnline()).thenReturn(false);
        when(broadcaster.h5Count()).thenReturn(0L);

        QueueSnapshot snapshot = new SnapshotService(
                playerRepository, queueRepository, songRepository, userRepository,
                broadcaster, fileRepository).snapshot();

        assertThat(snapshot.audioLayout().layout()).isEqualTo(AudioLayout.DUAL_CHANNEL);
        assertThat(snapshot.audioLayout().originalChannel()).isEqualTo(AudioChannel.LEFT);
        assertThat(snapshot.audioLayout().accompanimentChannel()).isEqualTo(AudioChannel.RIGHT);
    }
}
