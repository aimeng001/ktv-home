package com.homektv.library;

import com.homektv.domain.PlayHistory;
import com.homektv.domain.Song;
import com.homektv.musicsource.CoverImageNormalizer;
import com.homektv.repo.PlayHistoryRepository;
import com.homektv.repo.SongRepository;
import com.homektv.web.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class StandbyContentServiceTest {

    @Test
    void blankLogoSettingDoesNotExposeALogoUrl() {
        SettingService settings = mock(SettingService.class);
        when(settings.getAll()).thenReturn(Map.of("standby_logo_path", ""));
        StandbyContentService service = new StandbyContentService(settings,
                mock(SongRepository.class), mock(PlayHistoryRepository.class), mock(AssetWriter.class));

        assertThat(service.content()).containsEntry("logoUrl", null);
    }

    @Test
    void miniQrSettingIsIncludedInStandbyContent() {
        SettingService settings = mock(SettingService.class);
        when(settings.getAll()).thenReturn(Map.of("mini_qr", false));
        StandbyContentService service = new StandbyContentService(settings,
                mock(SongRepository.class), mock(PlayHistoryRepository.class), mock(AssetWriter.class));

        assertThat(service.content()).containsEntry("miniQr", false);
    }

    @Test
    void replacingLogoSchedulesThePreviousAssetForCleanup() throws Exception {
        SettingService settings = mock(SettingService.class);
        when(settings.getAll()).thenReturn(Map.of("standby_logo_path", "standby/logo-old.png"));
        AssetWriter writer = mock(AssetWriter.class);
        when(writer.writeStandbyLogo(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("png")))
                .thenReturn("standby/logo-new.png");
        AssetCleanupService cleanup = mock(AssetCleanupService.class);
        StandbyContentService service = new StandbyContentService(settings,
                mock(SongRepository.class), mock(PlayHistoryRepository.class), writer);
        service.setAssetCleanupService(cleanup);

        service.uploadLogo(new MockMultipartFile("file", "logo.png", "image/png", new byte[]{1, 2, 3}));

        verify(cleanup).afterCommitIfUnreferenced("standby/logo-old.png");
    }

    @Test
    void hotSongsIgnoreMalformedRanksAndUnavailableSongsButKeepRankingOrder() {
        SettingService settings = mock(SettingService.class);
        PlayHistoryRepository history = mock(PlayHistoryRepository.class);
        SongRepository songs = mock(SongRepository.class);
        when(settings.getAll()).thenReturn(Map.of("standby_source", "hot"));
        when(history.ranking(any(), eq(20))).thenReturn(Arrays.asList(
                new Object[]{2L}, new Object[]{"not-a-number"}, new Object[0], new Object[]{0L},
                new Object[]{2L}, new Object[]{1L}, new Object[]{4L}));
        Song second = song(2L, "第二首", "ok");
        Song unavailable = song(1L, "不可播放", "pending");
        Song fourth = song(4L, "第四首", "ok");
        when(songs.findAllById(anyIterable())).thenReturn(List.of(second, unavailable, fourth));
        StandbyContentService service = new StandbyContentService(settings, songs, history, mock(AssetWriter.class));

        @SuppressWarnings("unchecked")
        List<com.homektv.web.dto.SongDto> result = (List<com.homektv.web.dto.SongDto>) service.content().get("songs");

        assertThat(result).extracting(com.homektv.web.dto.SongDto::title).containsExactly("第二首", "第四首");
        verify(songs).findAllById(List.of(2L, 1L, 4L));
        verify(songs, never()).findTop50ByOrderByCreatedAtDesc();
    }

    @Test
    void emptyHotRankingDoesNotLoadSongs() {
        SettingService settings = mock(SettingService.class);
        PlayHistoryRepository history = mock(PlayHistoryRepository.class);
        SongRepository songs = mock(SongRepository.class);
        when(settings.getAll()).thenReturn(Map.of("standby_source", "hot"));
        when(history.ranking(any(), eq(20))).thenReturn(Arrays.asList(
                new Object[]{"invalid"}, new Object[0], new Object[]{-1L}));
        StandbyContentService service = new StandbyContentService(settings, songs, history, mock(AssetWriter.class));

        assertThat(service.content().get("songs")).isEqualTo(List.of());
        verify(songs, never()).findAllById(anyIterable());
    }

    @Test
    void mixedSongsKeepHotOrderAndDoNotDuplicateSongsFromNewList() {
        SettingService settings = mock(SettingService.class);
        PlayHistoryRepository history = mock(PlayHistoryRepository.class);
        SongRepository songs = mock(SongRepository.class);
        when(settings.getAll()).thenReturn(Map.of("standby_source", "mixed"));
        when(history.ranking(any(), eq(20))).thenReturn(Collections.singletonList(new Object[]{1L}));
        Song hot = song(1L, "熱門歌", "ok");
        when(songs.findAllById(anyIterable())).thenReturn(List.of(hot));
        when(songs.findTop50ByOrderByCreatedAtDesc()).thenReturn(List.of(
                song(1L, "熱門歌的新記錄", "ok"), song(2L, "新歌", "ok")));
        StandbyContentService service = new StandbyContentService(settings, songs, history, mock(AssetWriter.class));

        @SuppressWarnings("unchecked")
        List<com.homektv.web.dto.SongDto> result = (List<com.homektv.web.dto.SongDto>) service.content().get("songs");

        assertThat(result).extracting(com.homektv.web.dto.SongDto::title).containsExactly("熱門歌", "新歌");
    }

    @Test
    void customSongsFilterInvalidIdsAndPreserveRequestedOrder() {
        SettingService settings = mock(SettingService.class);
        SongRepository songs = mock(SongRepository.class);
        PlayHistoryRepository history = mock(PlayHistoryRepository.class);
        when(settings.getAll()).thenReturn(Map.of("standby_source", "custom",
                "standby_song_ids", List.of(5L, 2, "3", 0L, -1L, 5, 4L)));
        Song five = song(5L, "第五首", "ok");
        Song two = song(2L, "第二首", "ok");
        Song four = song(4L, "第四首", "ok");
        when(songs.findAllById(anyIterable())).thenReturn(List.of(four, five, two));
        StandbyContentService service = new StandbyContentService(settings, songs, history, mock(AssetWriter.class));

        @SuppressWarnings("unchecked")
        List<com.homektv.web.dto.SongDto> result = (List<com.homektv.web.dto.SongDto>) service.content().get("songs");

        assertThat(result).extracting(com.homektv.web.dto.SongDto::id).containsExactly(5L, 2L, 4L);
        verify(songs).findAllById(List.of(5L, 2L, 4L));
        verifyNoInteractions(history);
    }

    @Test
    void customSongsWithoutAListReturnEmptyAndSettingsUseDocumentedFallbacksAndBounds() {
        SettingService settings = mock(SettingService.class);
        when(settings.getAll()).thenReturn(Map.of(
                "standby_source", "custom",
                "standby_song_ids", List.of(),
                "standby_welcome", " ",
                "standby_subtitle", "",
                "standby_carousel", "false",
                "anti_burn", "invalid",
                "standby_interval_sec", 120,
                "tv_video_scale_mode", "stretch",
                "mini_qr", "false",
                "standby_logo_path", "standby/logo.png"));
        SongRepository songs = mock(SongRepository.class);
        StandbyContentService service = new StandbyContentService(settings, songs,
                mock(PlayHistoryRepository.class), mock(AssetWriter.class));

        Map<String, Object> content = service.content();

        assertThat(content).containsEntry("welcomeText", "今晚开唱")
                .containsEntry("subtitle", "手机点歌，电视欢唱\n一家人的客厅 KTV")
                .containsEntry("carouselEnabled", false)
                .containsEntry("antiBurn", false)
                .containsEntry("intervalSeconds", 60)
                .containsEntry("videoScaleMode", "zoom")
                .containsEntry("miniQr", false)
                .containsEntry("logoUrl", "/api/standby/logo")
                .containsEntry("songs", List.of());
        verify(songs, never()).findAllById(anyIterable());
    }

    @Test
    void customSongsWithNonListIdsReturnEmpty() {
        SettingService settings = mock(SettingService.class);
        when(settings.getAll()).thenReturn(Map.of("standby_source", "custom", "standby_song_ids", "1,2"));
        SongRepository songs = mock(SongRepository.class);
        StandbyContentService service = new StandbyContentService(settings, songs,
                mock(PlayHistoryRepository.class), mock(AssetWriter.class));

        assertThat(service.content().get("songs")).isEqualTo(List.of());
        verify(songs, never()).findAllById(anyIterable());
    }

    @Test
    void newSongsFilterUnavailableEntriesAndLimitTheCarouselToTwenty() {
        SettingService settings = mock(SettingService.class);
        SongRepository songs = mock(SongRepository.class);
        when(settings.getAll()).thenReturn(Map.of("standby_source", "new"));
        List<Song> newest = new java.util.ArrayList<>();
        newest.add(song(0L, "pending", "pending"));
        for (long id = 1; id <= 22; id++) newest.add(song(id, "新歌" + id, "ok"));
        when(songs.findTop50ByOrderByCreatedAtDesc()).thenReturn(newest);
        StandbyContentService service = new StandbyContentService(settings, songs,
                mock(PlayHistoryRepository.class), mock(AssetWriter.class));

        @SuppressWarnings("unchecked")
        List<com.homektv.web.dto.SongDto> result = (List<com.homektv.web.dto.SongDto>) service.content().get("songs");

        assertThat(result).hasSize(20);
        assertThat(result.get(0).title()).isEqualTo("新歌1");
        assertThat(result.get(19).title()).isEqualTo("新歌20");
    }

    @Test
    void standbyIntervalClampsAtMinimumAndScaleModeIsCaseInsensitive() {
        SettingService settings = mock(SettingService.class);
        when(settings.getAll()).thenReturn(Map.of("standby_source", "custom", "standby_song_ids", List.of(),
                "standby_interval_sec", 2, "tv_video_scale_mode", "FIT"));
        StandbyContentService service = new StandbyContentService(settings, mock(SongRepository.class),
                mock(PlayHistoryRepository.class), mock(AssetWriter.class));

        assertThat(service.content()).containsEntry("intervalSeconds", 3)
                .containsEntry("videoScaleMode", "fit");
    }

    @Test
    void uploadLogoRejectsMissingEmptyOversizedAndUnsupportedImagesBeforeWriting() {
        AssetWriter writer = mock(AssetWriter.class);
        StandbyContentService service = new StandbyContentService(mock(SettingService.class),
                mock(SongRepository.class), mock(PlayHistoryRepository.class), writer);

        assertApiCode("INVALID_IMAGE", () -> service.uploadLogo(null));
        assertApiCode("INVALID_IMAGE", () -> service.uploadLogo(
                new MockMultipartFile("file", "empty.png", "image/png", new byte[0])));
        assertApiCode("IMAGE_TOO_LARGE", () -> service.uploadLogo(
                new MockMultipartFile("file", "large.png", "image/png", new byte[5 * 1024 * 1024 + 1])));
        assertApiCode("INVALID_IMAGE", () -> service.uploadLogo(
                new MockMultipartFile("file", "logo.gif", "image/gif", new byte[]{1, 2, 3})));
        verify(writer, never()).writeStandbyLogo(any(), any());
    }

    @Test
    void uploadLogoNormalizesAcceptedImageAndStoresJpegWithoutCleanupService() throws Exception {
        SettingService settings = mock(SettingService.class);
        AssetWriter writer = mock(AssetWriter.class);
        CoverImageNormalizer normalizer = mock(CoverImageNormalizer.class);
        byte[] source = {1, 2, 3};
        byte[] normalized = {4, 5, 6};
        when(settings.getAll()).thenReturn(Map.of());
        when(normalizer.normalize(source)).thenReturn(normalized);
        when(writer.writeStandbyLogo(normalized, "jpg")).thenReturn("standby/logo.jpg");
        StandbyContentService service = new StandbyContentService(settings,
                mock(SongRepository.class), mock(PlayHistoryRepository.class), writer);
        service.setCoverImageNormalizer(normalizer);

        String path = service.uploadLogo(new MockMultipartFile("file", "logo.png", "image/png", source));

        assertThat(path).isEqualTo("standby/logo.jpg");
        verify(settings).putInternal("standby_logo_path", path);
        verify(writer).writeStandbyLogo(normalized, "jpg");
    }

    @Test
    void uploadLogoReportsReadFailureAndRejectsUnrecognizedNormalizedImages() throws Exception {
        MultipartFile unreadable = mock(MultipartFile.class);
        when(unreadable.isEmpty()).thenReturn(false);
        when(unreadable.getSize()).thenReturn(3L);
        when(unreadable.getContentType()).thenReturn("image/jpeg");
        when(unreadable.getBytes()).thenThrow(new IOException("synthetic read failure"));
        StandbyContentService readFailureService = new StandbyContentService(mock(SettingService.class),
                mock(SongRepository.class), mock(PlayHistoryRepository.class), mock(AssetWriter.class));
        assertApiCode("IMAGE_WRITE_FAILED", () -> readFailureService.uploadLogo(unreadable));

        SettingService settings = mock(SettingService.class);
        when(settings.getAll()).thenReturn(Map.of());
        CoverImageNormalizer normalizer = mock(CoverImageNormalizer.class);
        when(normalizer.normalize(any())).thenThrow(new ApiException("INVALID_IMAGE", "not an image"));
        AssetWriter writer = mock(AssetWriter.class);
        StandbyContentService invalidImageService = new StandbyContentService(settings,
                mock(SongRepository.class), mock(PlayHistoryRepository.class), writer);
        invalidImageService.setCoverImageNormalizer(normalizer);
        assertApiCode("INVALID_IMAGE", () -> invalidImageService.uploadLogo(
                new MockMultipartFile("file", "logo.jpg", "image/jpeg", new byte[]{1, 2, 3})));
        verify(writer, never()).writeStandbyLogo(any(), any());
    }

    private static void assertApiCode(String expectedCode,
                                      org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(ApiException.class,
                failure -> assertThat(failure.getCode()).isEqualTo(expectedCode));
    }

    private static Song song(long id, String title, String status) {
        Song song = new Song();
        song.setId(id);
        song.setTitle(title);
        song.setArtist("测试歌手");
        song.setStatus(status);
        song.setMediaType("KTV_VIDEO");
        return song;
    }
}
