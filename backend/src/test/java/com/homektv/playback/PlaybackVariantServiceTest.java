package com.homektv.playback;

import com.homektv.config.AppProperties;
import com.homektv.domain.AudioLayout;
import com.homektv.domain.PlaybackVariant;
import com.homektv.domain.PlaybackVariantProfile;
import com.homektv.domain.PlaybackVariantStatus;
import com.homektv.domain.SongFile;
import com.homektv.library.MediaProbeStage;
import com.homektv.media.FFprobeService;
import com.homektv.media.MediaProbe;
import com.homektv.repo.PlaybackVariantRepository;
import com.homektv.repo.SongFileRepository;
import com.homektv.resource.ResourceBudget;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.springframework.dao.DataAccessResourceFailureException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PlaybackVariantServiceTest {
    private final Executor idleExecutor = runnable -> { };

    @Test
    void constructorsRejectInvalidTimeoutAndCacheCapacityAndShutdownOwnedExecutor() throws Exception {
        Path sourceRoot = Files.createTempDirectory("ktv-source-config-");
        Path dataRoot = Files.createTempDirectory("ktv-data-config-");
        SongFileRepository files = mock(SongFileRepository.class);
        PlaybackVariantRepository variants = mock(PlaybackVariantRepository.class);
        MediaProbeStage probeStage = mock(MediaProbeStage.class);

        for (Duration timeout : new Duration[] { null, Duration.ZERO, Duration.ofSeconds(-1) }) {
            assertThatThrownBy(() -> new PlaybackVariantService(files, variants, mock(FFprobeService.class),
                    props(sourceRoot, dataRoot), "ffmpeg", idleExecutor,
                    command -> { throw new AssertionError("invalid configuration must not launch FFmpeg"); }, timeout))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("timeout must be positive");
        }
        assertThatThrownBy(() -> new PlaybackVariantService(files, variants, probeStage,
                props(sourceRoot, dataRoot), "ffmpeg", idleExecutor,
                command -> { throw new AssertionError("invalid configuration must not launch FFmpeg"); },
                Duration.ofSeconds(1), 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cache max bytes must be positive");

        PlaybackVariantService owned = new PlaybackVariantService(files, variants, probeStage,
                props(sourceRoot, dataRoot), "ffmpeg", 1, 1024);
        owned.shutdown();
        service(files, variants, sourceRoot, dataRoot).shutdown();
    }

    @Test
    void scheduledCleanupDoesNotCreateCacheDirectoryWhenThereAreNoHistoricalAssets() throws Exception {
        Path sourceRoot = Files.createTempDirectory("ktv-source-");
        Path dataRoot = Files.createTempDirectory("ktv-data-");
        SongFileRepository files = mock(SongFileRepository.class);
        PlaybackVariantRepository variants = mock(PlaybackVariantRepository.class);
        when(variants.findAll()).thenReturn(List.of());
        PlaybackVariantService service = service(files, variants, sourceRoot, dataRoot);

        service.cleanupCache();

        assertThat(Files.notExists(dataRoot.resolve("playback-cache")))
                .as("default cache maintenance should not create an empty cache directory")
                .isTrue();
    }

    @Test
    void cleanupPreservesHistoricalCacheFilesUntilLegacyInventoryIsKnown() throws Exception {
        Path sourceRoot = Files.createTempDirectory("ktv-source-");
        Path dataRoot = Files.createTempDirectory("ktv-data-");
        Path cacheRoot = Files.createDirectory(dataRoot.resolve("playback-cache"));
        Path historicalAsset = Files.writeString(cacheRoot.resolve("legacy-ready.mp4"), "old cache");
        SongFileRepository files = mock(SongFileRepository.class);
        PlaybackVariantRepository variants = mock(PlaybackVariantRepository.class);
        when(variants.findAll()).thenReturn(List.of());
        PlaybackVariantService service = service(files, variants, sourceRoot, dataRoot);

        service.cleanupCache();

        assertThat(Files.exists(historicalAsset))
                .as("legacy cache files must remain available until the old-client inventory is known")
                .isTrue();
    }

    @Test
    void cleanupMarksOnlyExpiredPreparingVariantsStale() throws Exception {
        Path sourceRoot = Files.createTempDirectory("ktv-source-");
        Path dataRoot = Files.createTempDirectory("ktv-data-");
        PlaybackVariant expired = variant(1L);
        expired.markPreparing("old-worker", OffsetDateTime.now().minusMinutes(1));
        PlaybackVariant active = variant(2L);
        active.markPreparing("active-worker", OffsetDateTime.now().plusMinutes(1));
        PlaybackVariant ready = readyVariant(3L, "ready.mp4");
        PlaybackVariantRepository variants = mock(PlaybackVariantRepository.class);
        when(variants.findAll()).thenReturn(List.of(expired, active, ready));
        PlaybackVariantService service = service(mock(SongFileRepository.class), variants, sourceRoot, dataRoot);

        service.cleanupCache();

        assertThat(expired.getStatus()).isEqualTo(PlaybackVariantStatus.STALE);
        assertThat(active.getStatus()).isEqualTo(PlaybackVariantStatus.PREPARING);
        assertThat(ready.getStatus()).isEqualTo(PlaybackVariantStatus.READY);
        verify(variants).save(expired);
        verify(variants, never()).save(active);
        verify(variants, never()).save(ready);
    }

    @Test
    void cleanupMarksPreparingVariantWithMissingLeaseStale() throws Exception {
        Path sourceRoot = Files.createTempDirectory("ktv-source-no-lease-");
        Path dataRoot = Files.createTempDirectory("ktv-data-no-lease-");
        PlaybackVariant abandoned = variant(4L);
        abandoned.markPreparing("lost-worker", null);
        PlaybackVariantRepository variants = mock(PlaybackVariantRepository.class);
        when(variants.findAll()).thenReturn(List.of(abandoned));
        PlaybackVariantService service = service(mock(SongFileRepository.class), variants, sourceRoot, dataRoot);

        service.cleanupCache();

        assertThat(abandoned.getStatus()).isEqualTo(PlaybackVariantStatus.STALE);
        verify(variants).save(abandoned);
    }

    @Test
    void cleanupAndLeaseRefreshContainRepositoryFailures() throws Exception {
        Path sourceRoot = Files.createTempDirectory("ktv-source-");
        Path dataRoot = Files.createTempDirectory("ktv-data-");
        PlaybackVariantRepository variants = mock(PlaybackVariantRepository.class);
        when(variants.findAll()).thenThrow(new DataAccessResourceFailureException("offline"));
        PlaybackVariantService service = service(mock(SongFileRepository.class), variants, sourceRoot, dataRoot);

        service.cleanupCache();
        service.renewStreamLease(7L);

        verify(variants).findAll();
    }

    @Test
    void readableCachePathRequiresReadyRegularFileBelowTheCacheRoot() throws Exception {
        Path sourceRoot = Files.createTempDirectory("ktv-source-");
        Path dataRoot = Files.createTempDirectory("ktv-data-");
        Path cacheRoot = Files.createDirectory(dataRoot.resolve("playback-cache"));
        Path outside = Files.writeString(dataRoot.resolve("outside.mp4"), "synthetic");
        SongFileRepository files = mock(SongFileRepository.class);
        PlaybackVariantRepository variants = mock(PlaybackVariantRepository.class);
        PlaybackVariantService service = service(files, variants, sourceRoot, dataRoot);

        assertThatThrownBy(() -> service.readableCachePath(variant(1L)))
                .isInstanceOf(com.homektv.web.ApiException.class)
                .hasMessageContaining("尚未就绪");
        assertThatThrownBy(() -> service.readableCachePath(readyVariant(2L, "../outside.mp4")))
                .isInstanceOf(com.homektv.web.ApiException.class)
                .hasMessageContaining("缓存不存在");
        assertThatThrownBy(() -> service.readableCachePath(null))
                .isInstanceOf(com.homektv.web.ApiException.class)
                .hasMessageContaining("尚未就绪");
        assertThatThrownBy(() -> service.readableCachePath(readyVariant(4L, "missing.mp4")))
                .isInstanceOf(com.homektv.web.ApiException.class)
                .hasMessageContaining("缓存不存在");
        PlaybackVariant readyWithoutCachePath = variant(5L);
        readyWithoutCachePath.setStatus(PlaybackVariantStatus.READY);
        assertThat(readyWithoutCachePath.isReady()).isFalse();
        assertThatThrownBy(() -> service.readableCachePath(readyWithoutCachePath))
                .isInstanceOf(com.homektv.web.ApiException.class)
                .hasMessageContaining("尚未就绪");
        assertThat(Files.exists(outside)).isTrue();
        assertThat(Files.isDirectory(cacheRoot)).isTrue();
        verifyNoInteractions(variants);
    }

    @Test
    void readableCachePathRejectsAParentSymlinkThatEscapesTheCacheRoot() throws Exception {
        Path sourceRoot = Files.createTempDirectory("ktv-source-cache-link-");
        Path dataRoot = Files.createTempDirectory("ktv-data-cache-link-");
        Path cacheRoot = Files.createDirectory(dataRoot.resolve("playback-cache"));
        Path outside = Files.createDirectory(dataRoot.resolve("outside"));
        Path protectedFile = Files.writeString(outside.resolve("protected.mp4"), "outside-cache");
        Path link = cacheRoot.resolve("linked");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (IOException | UnsupportedOperationException | SecurityException failure) {
            Assumptions.assumeTrue(false, "symbolic links are unavailable in this test environment");
        }

        PlaybackVariantService service = service(
                mock(SongFileRepository.class), mock(PlaybackVariantRepository.class), sourceRoot, dataRoot);

        assertThatThrownBy(() -> service.readableCachePath(readyVariant(6L, "linked/protected.mp4")))
                .isInstanceOf(com.homektv.web.ApiException.class)
                .hasMessageContaining("越界");
        assertThat(Files.readString(protectedFile)).isEqualTo("outside-cache");
    }

    @Test
    void readableCachePathRenewsLeaseAndSavesAccessTime() throws Exception {
        Path sourceRoot = Files.createTempDirectory("ktv-source-");
        Path dataRoot = Files.createTempDirectory("ktv-data-");
        Path cacheRoot = Files.createDirectory(dataRoot.resolve("playback-cache"));
        Path cacheFile = Files.writeString(cacheRoot.resolve("ready.mp4"), "synthetic");
        PlaybackVariantRepository variants = mock(PlaybackVariantRepository.class);
        PlaybackVariant variant = readyVariant(5L, "ready.mp4");
        PlaybackVariantService service = service(mock(SongFileRepository.class), variants, sourceRoot, dataRoot);

        Path resolved = service.readableCachePath(variant);

        assertThat(resolved).isEqualTo(cacheFile.toRealPath());
        assertThat(variant.getLastAccessAt()).isNotNull();
        assertThat(variant.getStreamLeaseUntil()).isAfter(OffsetDateTime.now());
        verify(variants).save(variant);
    }

    @Test
    void leaseRefreshSkipsNullMissingAndNonReadyVariants() throws Exception {
        Path sourceRoot = Files.createTempDirectory("ktv-source-");
        Path dataRoot = Files.createTempDirectory("ktv-data-");
        PlaybackVariantRepository variants = mock(PlaybackVariantRepository.class);
        PlaybackVariant pending = variant(9L);
        when(variants.findById(8L)).thenReturn(Optional.empty());
        when(variants.findById(9L)).thenReturn(Optional.of(pending));
        PlaybackVariantService service = service(mock(SongFileRepository.class), variants, sourceRoot, dataRoot);

        service.renewStreamLease(null);
        service.renewStreamLease(8L);
        service.renewStreamLease(9L);

        verify(variants, never()).save(pending);
    }

    @Test
    void leaseRefreshSavesReadyVariantAndContainsPersistenceFailure() throws Exception {
        Path sourceRoot = Files.createTempDirectory("ktv-source-lease-ready-");
        Path dataRoot = Files.createTempDirectory("ktv-data-lease-ready-");
        PlaybackVariantRepository variants = mock(PlaybackVariantRepository.class);
        PlaybackVariant saved = readyVariant(10L, "saved.mp4");
        PlaybackVariant persistenceFailure = readyVariant(11L, "failure.mp4");
        when(variants.findById(10L)).thenReturn(Optional.of(saved));
        when(variants.findById(11L)).thenReturn(Optional.of(persistenceFailure));
        doThrow(new DataAccessResourceFailureException("offline")).when(variants).save(persistenceFailure);
        PlaybackVariantService service = service(mock(SongFileRepository.class), variants, sourceRoot, dataRoot);

        service.renewStreamLease(10L);
        service.renewStreamLease(11L);

        assertThat(saved.getLastAccessAt()).isNotNull();
        assertThat(saved.getStreamLeaseUntil()).isAfter(OffsetDateTime.now());
        assertThat(persistenceFailure.getLastAccessAt()).isNotNull();
        assertThat(persistenceFailure.getStreamLeaseUntil()).isAfter(OffsetDateTime.now());
        verify(variants).save(saved);
        verify(variants).save(persistenceFailure);
    }

    @Test
    void cacheCannotBePlacedInsideAnExternalReadOnlySource() throws Exception {
        Path sourceRoot = Files.createTempDirectory("ktv-source-");
        SongFileRepository files = mock(SongFileRepository.class);
        PlaybackVariantRepository variants = mock(PlaybackVariantRepository.class);
        PlaybackVariantService service = new PlaybackVariantService(files, variants, mock(FFprobeService.class),
                props(sourceRoot, sourceRoot), "ffmpeg", idleExecutor,
                command -> { throw new AssertionError("path validation must not launch FFmpeg"); }, Duration.ofSeconds(5));

        assertThatThrownBy(() -> service.readableCachePath(readyVariant(10L, "ready.mp4")))
                .isInstanceOf(com.homektv.web.ApiException.class)
                .hasMessageContaining("不能位于外部曲库目录内");
    }

    @Test
    void cacheCapacityIgnoresMalformedCacheRowsAndNonRegularCacheEntries() throws Exception {
        var fixture = transcodeFixture(new MediaProbe(120_000, 2, 0, true, "1920x1080",
                List.of(), "h264", "aac"), false, 0, "new-output", 1024);
        Path cacheRoot = Files.createDirectories(fixture.dataRoot.resolve("playback-cache"));
        Files.createDirectory(cacheRoot.resolve("directory.mp4"));
        PlaybackVariant nullCache = mock(PlaybackVariant.class);
        when(nullCache.getStatus()).thenReturn(PlaybackVariantStatus.READY);
        when(nullCache.getCachePath()).thenReturn(null);
        PlaybackVariant blankCache = mock(PlaybackVariant.class);
        when(blankCache.getStatus()).thenReturn(PlaybackVariantStatus.READY);
        when(blankCache.getCachePath()).thenReturn(" ");
        when(fixture.variants.findAll()).thenReturn(List.of(nullCache, blankCache,
                readyVariant(57L, "directory.mp4")));

        fixture.service.transcodeVariant(fixture.variant.getId());

        assertThat(fixture.variant.getStatus())
                .as("transcode failure: %s - %s", fixture.variant.getErrorCode(), fixture.variant.getErrorMessage())
                .isEqualTo(PlaybackVariantStatus.READY);
        assertThat(Files.isDirectory(cacheRoot.resolve("directory.mp4"))).isTrue();
        verify(fixture.variants, never()).save(nullCache);
        verify(fixture.variants, never()).save(blankCache);
    }

    @Test
    void transcodeSuccessKeepsSourceReadOnlyAndPreservesDualTrackSemantics() throws Exception {
        var fixture = transcodeFixture(new MediaProbe(120_000, 2, 0, true, "1920x1080",
                List.of(), "h264", "aac"), false);

        fixture.service.transcodeVariant(fixture.variant.getId());

        Path output = fixture.dataRoot.resolve("playback-cache/21-fingerprint-21.mp4");
        assertThat(fixture.variant.getStatus())
                .as("transcode failure: %s - %s", fixture.variant.getErrorCode(), fixture.variant.getErrorMessage())
                .isEqualTo(PlaybackVariantStatus.READY);
        assertThat(fixture.variant.getAudioLayout()).isEqualTo(AudioLayout.DUAL_TRACK);
        assertThat(fixture.variant.getOriginalTrackIndex()).isZero();
        assertThat(fixture.variant.getAccompanimentTrackIndex()).isEqualTo(1);
        assertThat(Files.readString(fixture.sourcePath)).isEqualTo("immutable-source");
        assertThat(Files.readString(output)).isEqualTo("synthetic-transcode");
        assertThat(Files.notExists(fixture.dataRoot.resolve("playback-cache/41.part"))).isTrue();
        assertThat(fixture.command.get()).containsSubsequence("-map", "0:v:0", "-map", "0:a?");
        verify(fixture.variants).save(fixture.variant);
    }

    @Test
    void transcodeAcceptsVideoWhenProbeDoesNotReportCodecName() throws Exception {
        var fixture = transcodeFixture(new MediaProbe(120_000, 2, 0, true, "1920x1080",
                List.of(), null, "aac"), false);

        fixture.service.transcodeVariant(fixture.variant.getId());

        assertThat(fixture.variant.getStatus()).isEqualTo(PlaybackVariantStatus.READY);
        assertThat(Files.readString(fixture.sourcePath)).isEqualTo("immutable-source");
        assertThat(Files.exists(fixture.dataRoot.resolve("playback-cache/21-fingerprint-21.mp4"))).isTrue();
        verify(fixture.variants).save(fixture.variant);
    }

    @Test
    void transcodeRejectsCachePathTraversalInSourceFingerprintBeforeLaunchingFfmpeg() throws Exception {
        var fixture = transcodeFixture(new MediaProbe(120_000, 2, 0, true, "1920x1080",
                List.of(), "h264", "aac"), false, 0, "must-not-be-written", 1024, false,
                "../../../outside");

        fixture.service.transcodeVariant(fixture.variant.getId());

        assertThat(fixture.variant.getStatus()).isEqualTo(PlaybackVariantStatus.FAILED);
        assertThat(fixture.variant.getErrorCode()).isEqualTo("PLAYBACK_CACHE_PATH_INVALID");
        assertThat(fixture.command.get()).isNull();
        assertThat(Files.notExists(fixture.dataRoot.resolve("outside.mp4"))).isTrue();
    }

    @Test
    void transcodeAllowsOutputWithNoAudioWhenSourceHasNoAudioTracks() throws Exception {
        var fixture = transcodeFixture(new MediaProbe(120_000, 0, 0, true, "1920x1080",
                List.of(), "h264", null), false);
        fixture.source.setAudioTracks(0);

        fixture.service.transcodeVariant(fixture.variant.getId());

        assertThat(fixture.variant.getStatus()).isEqualTo(PlaybackVariantStatus.READY);
        assertThat(fixture.variant.getAudioTracks()).isZero();
        verify(fixture.variants).save(fixture.variant);
    }

    @Test
    void transcodeRejectsNullProbeResultAndRemovesPartialOutput() throws Exception {
        var fixture = transcodeFixture(null, false);

        fixture.service.transcodeVariant(fixture.variant.getId());

        assertThat(fixture.variant.getStatus()).isEqualTo(PlaybackVariantStatus.FAILED);
        assertThat(fixture.variant.getErrorCode()).isEqualTo("OUTPUT_NO_VIDEO");
        assertThat(Files.notExists(fixture.dataRoot.resolve("playback-cache/41.part"))).isTrue();
    }

    @Test
    void transcodeMarksVariantFailedWhenSourceRecordIsMissing() throws Exception {
        var fixture = transcodeFixture(new MediaProbe(120_000, 2, 0, true, "1920x1080",
                List.of(), "h264", "aac"), false);
        when(fixture.files.findById(21L)).thenReturn(Optional.empty());

        fixture.service.transcodeVariant(fixture.variant.getId());

        assertThat(fixture.variant.getStatus()).isEqualTo(PlaybackVariantStatus.FAILED);
        assertThat(fixture.variant.getErrorCode()).isEqualTo("FILE_NOT_FOUND");
        assertThat(fixture.command.get()).isNull();
        assertThat(fixture.process.get()).isNull();
        assertThat(Files.readString(fixture.sourcePath)).isEqualTo("immutable-source");
        verify(fixture.variants).save(fixture.variant);
    }

    @Test
    void transcodeRejectsMissingOrBlankSourcePath() throws Exception {
        MediaProbe output = new MediaProbe(120_000, 2, 0, true, "1920x1080",
                List.of(), "h264", "aac");
        for (String path : new String[] { null, "   " }) {
            var fixture = transcodeFixture(output, false);
            fixture.source.setFilePath(path);

            fixture.service.transcodeVariant(fixture.variant.getId());

            assertThat(fixture.variant.getStatus()).isEqualTo(PlaybackVariantStatus.FAILED);
            assertThat(fixture.variant.getErrorCode()).isEqualTo("FILE_NOT_FOUND");
            assertThat(fixture.command.get()).isNull();
            verify(fixture.variants).save(fixture.variant);
        }
    }

    @Test
    void transcodeHandlesLauncherFailureWithFallbackMessageAndDeletedVariant() throws Exception {
        MediaProbe output = new MediaProbe(120_000, 2, 0, true, "1920x1080",
                List.of(), "h264", "aac");
        var fixture = transcodeFixture(output, false, 0, null, 1024, true);

        fixture.service.transcodeVariant(fixture.variant.getId());

        assertThat(fixture.variant.getStatus()).isEqualTo(PlaybackVariantStatus.FAILED);
        assertThat(fixture.variant.getErrorCode()).isEqualTo("TRANSCODE_FAILED");
        assertThat(fixture.variant.getErrorMessage()).isEqualTo("IOException");
        assertThat(fixture.process.get()).isNull();
        verify(fixture.variants).save(fixture.variant);

        var deletedFixture = transcodeFixture(output, false, 0, null, 1024, true);
        when(deletedFixture.variants.findById(41L)).thenReturn(Optional.of(deletedFixture.variant), Optional.empty());

        deletedFixture.service.transcodeVariant(deletedFixture.variant.getId());

        assertThat(deletedFixture.variant.getStatus()).isEqualTo(PlaybackVariantStatus.PREPARING);
        verify(deletedFixture.variants, never()).save(deletedFixture.variant);
    }

    @Test
    void transcodeRejectsAudioTrackLossAndRemovesThePartialOutput() throws Exception {
        var fixture = transcodeFixture(new MediaProbe(120_000, 1, 0, true, "1920x1080",
                List.of(), "h264", "aac"), false);

        fixture.service.transcodeVariant(fixture.variant.getId());

        assertThat(fixture.variant.getStatus()).isEqualTo(PlaybackVariantStatus.FAILED);
        assertThat(fixture.variant.getErrorCode()).isEqualTo("OUTPUT_AUDIO_TRACK_LOSS");
        assertThat(Files.readString(fixture.sourcePath)).isEqualTo("immutable-source");
        assertThat(Files.notExists(fixture.dataRoot.resolve("playback-cache/41.part"))).isTrue();
        assertThat(Files.notExists(fixture.dataRoot.resolve("playback-cache/21-fingerprint-21.mp4"))).isTrue();
        verify(fixture.variants).save(fixture.variant);
    }

    @Test
    void transcodeRejectsOutputWithoutVideo() throws Exception {
        var fixture = transcodeFixture(new MediaProbe(120_000, 2, 0, false, null,
                List.of(), null, "aac"), false);

        fixture.service.transcodeVariant(fixture.variant.getId());

        assertThat(fixture.variant.getStatus()).isEqualTo(PlaybackVariantStatus.FAILED);
        assertThat(fixture.variant.getErrorCode()).isEqualTo("OUTPUT_NO_VIDEO");
        assertThat(Files.notExists(fixture.dataRoot.resolve("playback-cache/41.part"))).isTrue();
        verify(fixture.variants).save(fixture.variant);
    }

    @Test
    void transcodeRejectsNonH264VideoOutput() throws Exception {
        var fixture = transcodeFixture(new MediaProbe(120_000, 2, 0, true, "1920x1080",
                List.of(), "hevc", "aac"), false);

        fixture.service.transcodeVariant(fixture.variant.getId());

        assertThat(fixture.variant.getStatus()).isEqualTo(PlaybackVariantStatus.FAILED);
        assertThat(fixture.variant.getErrorCode()).isEqualTo("OUTPUT_VIDEO_CODEC_INVALID");
        assertThat(Files.readString(fixture.sourcePath)).isEqualTo("immutable-source");
        assertThat(Files.notExists(fixture.dataRoot.resolve("playback-cache/41.part"))).isTrue();
        verify(fixture.variants).save(fixture.variant);
    }

    @Test
    void transcodeSkipsMissingAndAlreadyReadyVariants() throws Exception {
        var fixture = transcodeFixture(new MediaProbe(120_000, 2, 0, true, "1920x1080",
                List.of(), "h264", "aac"), false);
        fixture.variant.markReady("existing.mp4", 8);

        fixture.service.transcodeVariant(null);
        fixture.service.transcodeVariant(fixture.variant.getId());

        assertThat(fixture.variant.getStatus()).isEqualTo(PlaybackVariantStatus.READY);
        assertThat(fixture.command.get()).isNull();
        assertThat(fixture.process.get()).isNull();
        verify(fixture.variants, never()).save(fixture.variant);
    }

    @Test
    void transcodeTimeoutForciblyStopsTheChildAndRemovesThePartialOutput() throws Exception {
        var fixture = transcodeFixture(new MediaProbe(120_000, 2, 0, true, "1920x1080",
                List.of(), "h264", "aac"), true);

        fixture.service.transcodeVariant(fixture.variant.getId());

        assertThat(fixture.variant.getStatus()).isEqualTo(PlaybackVariantStatus.FAILED);
        assertThat(fixture.variant.getErrorCode()).isEqualTo("TRANSCODE_TIMEOUT");
        assertThat(fixture.process.get().forciblyDestroyed).isEqualTo(1);
        assertThat(Files.readString(fixture.sourcePath)).isEqualTo("immutable-source");
        assertThat(Files.notExists(fixture.dataRoot.resolve("playback-cache/41.part"))).isTrue();
        verify(fixture.variants).save(fixture.variant);
    }

    @Test
    void interruptedProcessWaitForciblyStopsTheStillRunningChild() throws Exception {
        var fixture = transcodeFixture(new MediaProbe(120_000, 2, 0, true, "1920x1080",
                List.of(), "h264", "aac"), false);
        fixture.interruptOnWait().set(true);

        fixture.service.transcodeVariant(fixture.variant.getId());

        assertThat(fixture.variant.getStatus()).isEqualTo(PlaybackVariantStatus.FAILED);
        assertThat(fixture.variant.getErrorCode()).isEqualTo("TRANSCODE_FAILED");
        assertThat(fixture.variant.getErrorMessage()).isEqualTo("synthetic process wait interrupted");
        assertThat(fixture.process.get().forciblyDestroyed).isEqualTo(1);
        assertThat(Files.readString(fixture.sourcePath)).isEqualTo("immutable-source");
        assertThat(Files.notExists(fixture.dataRoot.resolve("playback-cache/41.part"))).isTrue();
        verify(fixture.variants).save(fixture.variant);
    }

    @Test
    void transcodeRejectsNonzeroExitAndRemovesPartialOutput() throws Exception {
        var fixture = transcodeFixture(new MediaProbe(120_000, 2, 0, true, "1920x1080",
                List.of(), "h264", "aac"), false, 7, "partial-output");

        fixture.service.transcodeVariant(fixture.variant.getId());

        assertThat(fixture.variant.getStatus()).isEqualTo(PlaybackVariantStatus.FAILED);
        assertThat(fixture.variant.getErrorCode()).isEqualTo("TRANSCODE_FAILED");
        assertThat(Files.notExists(fixture.dataRoot.resolve("playback-cache/41.part"))).isTrue();
        assertThat(Files.notExists(fixture.dataRoot.resolve("playback-cache/21-fingerprint-21.mp4"))).isTrue();
        verify(fixture.variants).save(fixture.variant);
    }

    @Test
    void transcodeUsesFfmpegOutputAsFailureMessageWhenItIsNonBlank() throws Exception {
        var fixture = transcodeFixture(new MediaProbe(120_000, 2, 0, true, "1920x1080",
                List.of(), "h264", "aac"), false, 7, "partial-output", 1024,
                false, "fingerprint-21", "synthetic ffmpeg stderr");

        fixture.service.transcodeVariant(fixture.variant.getId());

        assertThat(fixture.variant.getStatus()).isEqualTo(PlaybackVariantStatus.FAILED);
        assertThat(fixture.variant.getErrorCode()).isEqualTo("TRANSCODE_FAILED");
        assertThat(fixture.variant.getErrorMessage()).isEqualTo("synthetic ffmpeg stderr");
    }

    @Test
    void transcodeContainsFailureWhileReadingProcessOutput() throws Exception {
        var fixture = transcodeFixture(new MediaProbe(120_000, 2, 0, true, "1920x1080",
                List.of(), "h264", "aac"), false, 0, "partial-output", 1024,
                false, "fingerprint-21", "", true);

        fixture.service.transcodeVariant(fixture.variant.getId());

        assertThat(fixture.variant.getStatus()).isEqualTo(PlaybackVariantStatus.FAILED);
        assertThat(fixture.variant.getErrorCode()).isEqualTo("TRANSCODE_FAILED");
        assertThat(fixture.variant.getErrorMessage()).isEqualTo("读取 ffmpeg 输出失败");
        assertThat(Files.readString(fixture.sourcePath)).isEqualTo("immutable-source");
        assertThat(Files.notExists(fixture.dataRoot.resolve("playback-cache/41.part"))).isTrue();
        verify(fixture.variants).save(fixture.variant);
    }

    @Test
    void transcodeBoundsFailureMessageWhenTheProcessWritesMoreThan64KiB() throws Exception {
        String processOutput = "x".repeat(70 * 1024);
        var fixture = transcodeFixture(new MediaProbe(120_000, 2, 0, true, "1920x1080",
                List.of(), "h264", "aac"), false, 7, "partial-output", 1024,
                false, "fingerprint-21", processOutput);
        var logger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(PlaybackVariantService.class);
        var previousLevel = logger.getLevel();
        logger.setLevel(ch.qos.logback.classic.Level.OFF);
        try {
            fixture.service.transcodeVariant(fixture.variant.getId());
        } finally {
            logger.setLevel(previousLevel);
        }

        assertThat(fixture.variant.getStatus()).isEqualTo(PlaybackVariantStatus.FAILED);
        assertThat(fixture.variant.getErrorCode()).isEqualTo("TRANSCODE_FAILED");
        assertThat(fixture.variant.getErrorMessage()).hasSize(64 * 1024)
                .isEqualTo(processOutput.substring(0, 64 * 1024));
        assertThat(Files.readString(fixture.sourcePath)).isEqualTo("immutable-source");
        assertThat(Files.notExists(fixture.dataRoot.resolve("playback-cache/41.part"))).isTrue();
    }

    @Test
    void transcodeRejectsMissingAndEmptyProcessOutput() throws Exception {
        for (String output : new String[] { null, "" }) {
            var fixture = transcodeFixture(new MediaProbe(120_000, 2, 0, true, "1920x1080",
                    List.of(), "h264", "aac"), false, 0, output);

            fixture.service.transcodeVariant(fixture.variant.getId());

            assertThat(fixture.variant.getStatus()).isEqualTo(PlaybackVariantStatus.FAILED);
            assertThat(fixture.variant.getErrorCode()).isEqualTo("TRANSCODE_FAILED");
            assertThat(Files.notExists(fixture.dataRoot.resolve("playback-cache/41.part"))).isTrue();
            assertThat(Files.notExists(fixture.dataRoot.resolve("playback-cache/21-fingerprint-21.mp4"))).isTrue();
            verify(fixture.variants).save(fixture.variant);
        }
    }

    @Test
    void transcodeEvictsLeastRecentlyUsedCacheUntilOutputFits() throws Exception {
        var fixture = transcodeFixture(new MediaProbe(120_000, 2, 0, true, "1920x1080",
                List.of(), "h264", "aac"), false, 0, "new-output", 17);
        Path cacheRoot = Files.createDirectories(fixture.dataRoot.resolve("playback-cache"));
        Path oldestFile = Files.writeString(cacheRoot.resolve("oldest.mp4"), "12345");
        Path newerFile = Files.writeString(cacheRoot.resolve("newer.mp4"), "1234567");
        PlaybackVariant oldest = readyVariant(51L, "oldest.mp4");
        oldest.touchAccess(OffsetDateTime.now().minusHours(2), OffsetDateTime.now().minusHours(1));
        PlaybackVariant newer = readyVariant(52L, "newer.mp4");
        newer.touchAccess(OffsetDateTime.now().minusHours(1), OffsetDateTime.now().minusMinutes(30));
        when(fixture.variants.findAll()).thenReturn(List.of(oldest, newer, fixture.variant));

        fixture.service.transcodeVariant(fixture.variant.getId());

        assertThat(fixture.variant.getStatus()).isEqualTo(PlaybackVariantStatus.READY);
        assertThat(Files.notExists(oldestFile)).isTrue();
        assertThat(oldest.getStatus()).isEqualTo(PlaybackVariantStatus.STALE);
        assertThat(Files.exists(newerFile)).isTrue();
        assertThat(newer.getStatus()).isEqualTo(PlaybackVariantStatus.READY);
        verify(fixture.variants).save(oldest);
        verify(fixture.variants).save(fixture.variant);
    }

    @Test
    void cacheEvictionUsesReadyTimeWhenAccessTimeIsMissingAndOldestFallbackWhenBothAreMissing() throws Exception {
        var fixture = transcodeFixture(new MediaProbe(120_000, 2, 0, true, "1920x1080",
                List.of(), "h264", "aac"), false, 0, "new-output", 16);
        Path cacheRoot = Files.createDirectories(fixture.dataRoot.resolve("playback-cache"));
        Path oldestFile = Files.writeString(cacheRoot.resolve("oldest.mp4"), "12345");
        Path nextFile = Files.writeString(cacheRoot.resolve("next.mp4"), "1234567");
        PlaybackVariant oldest = readyVariant(55L, "oldest.mp4");
        oldest.markPreparing("legacy", null);
        oldest.setStatus(PlaybackVariantStatus.READY);
        oldest.touchAccess(null, null);
        PlaybackVariant next = readyVariant(56L, "next.mp4");
        next.touchAccess(null, null);
        when(fixture.variants.findAll()).thenReturn(List.of(next, oldest));

        fixture.service.transcodeVariant(fixture.variant.getId());

        assertThat(Files.notExists(oldestFile)).isTrue();
        assertThat(Files.notExists(nextFile)).isTrue();
        assertThat(oldest.getStatus()).isEqualTo(PlaybackVariantStatus.STALE);
        assertThat(next.getStatus()).isEqualTo(PlaybackVariantStatus.STALE);
        assertThat(fixture.variant.getStatus()).isEqualTo(PlaybackVariantStatus.READY);
    }

    @Test
    void transcodeFailsWithoutEvictingCacheProtectedByActiveStreamLease() throws Exception {
        var fixture = transcodeFixture(new MediaProbe(120_000, 2, 0, true, "1920x1080",
                List.of(), "h264", "aac"), false, 0, "new-output", 16);
        Path cacheRoot = Files.createDirectories(fixture.dataRoot.resolve("playback-cache"));
        Path leasedFile = Files.writeString(cacheRoot.resolve("leased.mp4"), "1234567");
        PlaybackVariant leased = readyVariant(53L, "leased.mp4");
        leased.touchAccess(OffsetDateTime.now().minusHours(1), OffsetDateTime.now().plusMinutes(5));
        when(fixture.variants.findAll()).thenReturn(List.of(leased, fixture.variant));

        fixture.service.transcodeVariant(fixture.variant.getId());

        assertThat(fixture.variant.getStatus()).isEqualTo(PlaybackVariantStatus.FAILED);
        assertThat(fixture.variant.getErrorCode()).isEqualTo("PLAYBACK_CACHE_FULL");
        assertThat(Files.exists(leasedFile)).isTrue();
        assertThat(leased.getStatus()).isEqualTo(PlaybackVariantStatus.READY);
        assertThat(Files.notExists(cacheRoot.resolve("41.part"))).isTrue();
        verify(fixture.variants, never()).save(leased);
        verify(fixture.variants).save(fixture.variant);
    }

    @Test
    void capacityEvictionNeverDeletesCachePathOutsideCacheRoot() throws Exception {
        var fixture = transcodeFixture(new MediaProbe(120_000, 2, 0, true, "1920x1080",
                List.of(), "h264", "aac"), false, 0, "new-output", 5);
        Path outsideFile = Files.writeString(fixture.dataRoot.resolve("outside.mp4"), "outside-cache");
        PlaybackVariant escaped = readyVariant(54L, "../outside.mp4");
        when(fixture.variants.findAll()).thenReturn(List.of(escaped, fixture.variant));

        fixture.service.transcodeVariant(fixture.variant.getId());

        assertThat(fixture.variant.getStatus()).isEqualTo(PlaybackVariantStatus.FAILED);
        assertThat(fixture.variant.getErrorCode()).isEqualTo("PLAYBACK_CACHE_FULL");
        assertThat(Files.readString(outsideFile)).isEqualTo("outside-cache");
        assertThat(escaped.getStatus()).isEqualTo(PlaybackVariantStatus.STALE);
        verify(fixture.variants).save(escaped);
        verify(fixture.variants).save(fixture.variant);
    }

    @Test
    void capacityEvictionKeepsTheVariantBeingReplacedDuringAConcurrentRefresh() throws Exception {
        var fixture = transcodeFixture(new MediaProbe(120_000, 2, 0, true, "1920x1080",
                List.of(), "h264", "aac"), false, 0, "new-output", 5);
        Path cacheRoot = Files.createDirectories(fixture.dataRoot.resolve("playback-cache"));
        Path previousFile = Files.writeString(cacheRoot.resolve("previous.mp4"), "previous-cache");
        PlaybackVariant previous = readyVariant(fixture.variant.getId(), "previous.mp4");
        when(fixture.variants.findAll()).thenReturn(List.of(previous, fixture.variant));

        fixture.service.transcodeVariant(fixture.variant.getId());

        assertThat(fixture.variant.getStatus()).isEqualTo(PlaybackVariantStatus.FAILED);
        assertThat(fixture.variant.getErrorCode()).isEqualTo("PLAYBACK_CACHE_FULL");
        assertThat(Files.readString(previousFile)).isEqualTo("previous-cache");
        assertThat(previous.getStatus()).isEqualTo(PlaybackVariantStatus.READY);
        verify(fixture.variants, never()).save(previous);
    }

    @Test
    void capacityEvictionNeverDeletesFileReachedThroughAnExternalDirectorySymlink() throws Exception {
        var fixture = transcodeFixture(new MediaProbe(120_000, 2, 0, true, "1920x1080",
                List.of(), "h264", "aac"), false, 0, "new-output", 5);
        Path cacheRoot = Files.createDirectories(fixture.dataRoot.resolve("playback-cache"));
        Path outside = Files.createDirectory(fixture.dataRoot.resolve("outside-cache"));
        Path protectedFile = Files.writeString(outside.resolve("protected.mp4"), "outside-cache");
        Path link = cacheRoot.resolve("linked");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (IOException | UnsupportedOperationException | SecurityException failure) {
            Assumptions.assumeTrue(false, "symbolic links are unavailable in this test environment");
        }
        PlaybackVariant escaped = readyVariant(54L, "linked/protected.mp4");
        when(fixture.variants.findAll()).thenReturn(List.of(escaped));

        fixture.service.transcodeVariant(fixture.variant.getId());

        assertThat(fixture.variant.getStatus()).isEqualTo(PlaybackVariantStatus.FAILED);
        assertThat(fixture.variant.getErrorCode()).isEqualTo("PLAYBACK_CACHE_FULL");
        assertThat(Files.isSymbolicLink(link)).isTrue();
        assertThat(Files.readString(protectedFile)).isEqualTo("outside-cache");
        assertThat(escaped.getStatus()).isEqualTo(PlaybackVariantStatus.STALE);
    }

    @Test
    void failedCacheEvictionLeavesReadOnlyCacheAndVariantStateIntact() throws Exception {
        Assumptions.assumeTrue(System.getProperty("os.name", "").startsWith("Windows"),
                "read-only file deletion behavior is specific to Windows");
        var fixture = transcodeFixture(new MediaProbe(120_000, 2, 0, true, "1920x1080",
                List.of(), "h264", "aac"), false, 0, "new-output", 5);
        Path cacheRoot = Files.createDirectories(fixture.dataRoot.resolve("playback-cache"));
        Path protectedFile = Files.writeString(cacheRoot.resolve("protected.mp4"), "12345");
        PlaybackVariant protectedVariant = readyVariant(61L, "protected.mp4");
        when(fixture.variants.findAll()).thenReturn(List.of(protectedVariant, fixture.variant));
        Files.setAttribute(protectedFile, "dos:readonly", true);

        try {
            fixture.service.transcodeVariant(fixture.variant.getId());

            assertThat(fixture.variant.getStatus()).isEqualTo(PlaybackVariantStatus.FAILED);
            assertThat(fixture.variant.getErrorCode()).isEqualTo("PLAYBACK_CACHE_FULL");
            assertThat(Files.exists(protectedFile)).isTrue();
            assertThat(protectedVariant.getStatus()).isEqualTo(PlaybackVariantStatus.READY);
            verify(fixture.variants, never()).save(protectedVariant);
        } finally {
            Files.setAttribute(protectedFile, "dos:readonly", false);
        }
    }

    private static TranscodeFixture transcodeFixture(MediaProbe outputProbe, boolean timeout) throws Exception {
        return transcodeFixture(outputProbe, timeout, 0, "synthetic-transcode");
    }

    private static TranscodeFixture transcodeFixture(
            MediaProbe outputProbe,
            boolean timeout,
            int exitCode,
            String outputContents) throws Exception {
        return transcodeFixture(outputProbe, timeout, exitCode, outputContents, 20L * 1024 * 1024 * 1024);
    }

    private static TranscodeFixture transcodeFixture(
            MediaProbe outputProbe,
            boolean timeout,
            int exitCode,
            String outputContents,
            long maxCacheBytes) throws Exception {
        return transcodeFixture(outputProbe, timeout, exitCode, outputContents, maxCacheBytes, false);
    }

    private static TranscodeFixture transcodeFixture(
            MediaProbe outputProbe,
            boolean timeout,
            int exitCode,
            String outputContents,
            long maxCacheBytes,
            boolean failToStart) throws Exception {
        return transcodeFixture(outputProbe, timeout, exitCode, outputContents, maxCacheBytes,
                failToStart, "fingerprint-21");
    }

    private static TranscodeFixture transcodeFixture(
            MediaProbe outputProbe,
            boolean timeout,
            int exitCode,
            String outputContents,
            long maxCacheBytes,
            boolean failToStart,
            String fingerprint) throws Exception {
        return transcodeFixture(outputProbe, timeout, exitCode, outputContents, maxCacheBytes,
                failToStart, fingerprint, "");
    }

    private static TranscodeFixture transcodeFixture(
            MediaProbe outputProbe,
            boolean timeout,
            int exitCode,
            String outputContents,
            long maxCacheBytes,
            boolean failToStart,
            String fingerprint,
            String processOutput) throws Exception {
        return transcodeFixture(outputProbe, timeout, exitCode, outputContents, maxCacheBytes,
                failToStart, fingerprint, processOutput, false);
    }

    private static TranscodeFixture transcodeFixture(
            MediaProbe outputProbe,
            boolean timeout,
            int exitCode,
            String outputContents,
            long maxCacheBytes,
            boolean failToStart,
            String fingerprint,
            String processOutput,
            boolean failOutputRead) throws Exception {
        Path sourceRoot = Files.createTempDirectory("ktv-source-transcode-");
        Path dataRoot = Files.createTempDirectory("ktv-data-transcode-");
        Path sourcePath = Files.writeString(sourceRoot.resolve("source.mkv"), "immutable-source");
        SongFile source = new SongFile();
        source.setId(21L);
        source.setFilePath(sourcePath.toString());
        source.setAudioTracks(2);
        source.setAudioLayout(AudioLayout.DUAL_TRACK);
        source.setOriginalTrackIndex(0);
        source.setAccompanimentTrackIndex(1);

        PlaybackVariant variant = new PlaybackVariant(21L, fingerprint, PlaybackVariantProfile.H264_AAC_MP4_V1);
        variant.setId(41L);
        PlaybackVariantRepository variants = mock(PlaybackVariantRepository.class);
        when(variants.findById(41L)).thenReturn(Optional.of(variant));
        SongFileRepository files = mock(SongFileRepository.class);
        when(files.findById(21L)).thenReturn(Optional.of(source));
        FFprobeService ffprobe = mock(FFprobeService.class);
        Path outputTemp = dataRoot.resolve("playback-cache/41.part");
        when(ffprobe.probe(outputTemp)).thenReturn(outputProbe);
        MediaProbeStage probeStage = new MediaProbeStage(ffprobe, new ResourceBudget(2, 1, 4, () -> false));
        AtomicReference<List<String>> command = new AtomicReference<>();
        AtomicReference<SyntheticProcess> process = new AtomicReference<>();
        AtomicReference<Boolean> interruptOnWait = new AtomicReference<>(false);
        PlaybackVariantService service = new PlaybackVariantService(files, variants, probeStage, props(sourceRoot, dataRoot),
                "ffmpeg", runnable -> { }, args -> {
                    command.set(List.copyOf(args));
                    if (failToStart) throw new IOException();
                    if (!timeout && outputContents != null) {
                        Path output = Path.of(args.get(args.size() - 1));
                        Files.createDirectories(output.getParent());
                        Files.writeString(output, outputContents);
                    }
                    SyntheticProcess started = new SyntheticProcess(
                            timeout, exitCode, processOutput, interruptOnWait, failOutputRead);
                    process.set(started);
                    return started;
                }, Duration.ofSeconds(1), maxCacheBytes);
        return new TranscodeFixture(service, variants, files, variant, source, sourcePath, dataRoot,
                command, process, interruptOnWait);
    }

    private record TranscodeFixture(
            PlaybackVariantService service,
            PlaybackVariantRepository variants,
            SongFileRepository files,
            PlaybackVariant variant,
            SongFile source,
            Path sourcePath,
            Path dataRoot,
            AtomicReference<List<String>> command,
            AtomicReference<SyntheticProcess> process,
            AtomicReference<Boolean> interruptOnWait) {}

    private static final class SyntheticProcess extends Process {
        private final boolean timeoutFirstWait;
        private final int exitCode;
        private final InputStream stdout;
        private final AtomicReference<Boolean> interruptOnWait;
        private final boolean failOutputRead;
        private boolean alive = true;
        private int waits;
        private int forciblyDestroyed;

        private SyntheticProcess(
                boolean timeoutFirstWait,
                int exitCode,
                String stdout,
                AtomicReference<Boolean> interruptOnWait,
                boolean failOutputRead) {
            this.timeoutFirstWait = timeoutFirstWait;
            this.exitCode = exitCode;
            this.stdout = new ByteArrayInputStream(stdout.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            this.interruptOnWait = interruptOnWait;
            this.failOutputRead = failOutputRead;
        }

        @Override public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
        @Override public InputStream getInputStream() {
            if (failOutputRead) {
                return new InputStream() {
                    @Override public int read() throws IOException {
                        throw new IOException("synthetic ffmpeg output read failure");
                    }
                };
            }
            return stdout;
        }
        @Override public InputStream getErrorStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public int waitFor() { alive = false; return 0; }
        @Override public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            if (interruptOnWait.get()) throw new InterruptedException("synthetic process wait interrupted");
            waits++;
            if (timeoutFirstWait && waits == 1) return false;
            alive = false;
            return true;
        }
        @Override public int exitValue() {
            if (alive) throw new IllegalThreadStateException("process still running");
            return exitCode;
        }
        @Override public void destroy() { alive = false; }
        @Override public Process destroyForcibly() { forciblyDestroyed++; alive = false; return this; }
        @Override public boolean isAlive() { return alive; }
    }

    private PlaybackVariantService service(
            SongFileRepository files,
            PlaybackVariantRepository variants,
            Path sourceRoot,
            Path dataRoot) {
        return new PlaybackVariantService(files, variants, mock(FFprobeService.class), props(sourceRoot, dataRoot),
                "ffmpeg", idleExecutor,
                command -> { throw new AssertionError("cleanup must not start FFmpeg"); }, Duration.ofSeconds(5));
    }

    private static AppProperties props(Path sourceRoot, Path dataRoot) {
        AppProperties props = new AppProperties();
        props.setSourceLibraryPath(sourceRoot.toString());
        props.setDataPath(dataRoot.toString());
        props.setLibraryMode(com.homektv.library.LibraryMode.EXTERNAL_READ_ONLY);
        return props;
    }

    private static PlaybackVariant variant(Long id) {
        PlaybackVariant variant = new PlaybackVariant(id, "fingerprint-" + id, PlaybackVariantProfile.H264_AAC_MP4_V1);
        variant.setId(id);
        return variant;
    }

    private static PlaybackVariant readyVariant(Long id, String cachePath) {
        PlaybackVariant variant = variant(id);
        variant.markReady(cachePath, 8);
        return variant;
    }
}
