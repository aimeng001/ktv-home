package com.homektv.library;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homektv.domain.Song;
import com.homektv.repo.PlayHistoryRepository;
import com.homektv.repo.SettingRepository;
import com.homektv.repo.SongRepository;
import com.homektv.web.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class StandbyContentBatchTest {
    private SettingRepository settingRepo;
    private SettingService settingService;
    private SongRepository songRepository;
    private PlayHistoryRepository historyRepository;
    private AssetWriter assetWriter;
    private StandbyContentService standbyContentService;

    @BeforeEach
    void setUp() {
        settingRepo = mock(SettingRepository.class);
        settingService = spy(new SettingService(settingRepo, new ObjectMapper()));
        songRepository = mock(SongRepository.class);
        historyRepository = mock(PlayHistoryRepository.class);
        assetWriter = mock(AssetWriter.class);

        standbyContentService = new StandbyContentService(settingService, songRepository,
                historyRepository, assetWriter);
    }

    @Test
    void standbySongIds_validatedForLimitAndPositive() {
        List<Long> overLimit = LongStream.rangeClosed(1, 101).boxed().toList();
        assertThatThrownBy(() -> settingService.putEditable(Map.of("standby_song_ids", overLimit)))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "SETTING_INVALID_RANGE");
    }

    @Test
    void customSongs_usesSingleBatchQueryAndPreservesOrder() {
        doReturn(Map.of("standby_source", "custom", "standby_song_ids", List.of(3, 1, 2)))
                .when(settingService).getAll();

        Song s1 = new Song(); s1.setId(1L); s1.setStatus("ok"); s1.setTitle("Song 1");
        Song s2 = new Song(); s2.setId(2L); s2.setStatus("ok"); s2.setTitle("Song 2");
        Song s3 = new Song(); s3.setId(3L); s3.setStatus("ok"); s3.setTitle("Song 3");
        when(songRepository.findAllById(List.of(3L, 1L, 2L))).thenReturn(List.of(s1, s2, s3));

        Map<String, Object> content = standbyContentService.content();
        List<?> songs = (List<?>) content.get("songs");
        assertThat(songs).hasSize(3);
        verify(songRepository, times(1)).findAllById(List.of(3L, 1L, 2L));
        verify(songRepository, never()).findById(anyLong());
    }
}
