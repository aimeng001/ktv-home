package com.homektv.library;

import com.homektv.config.AppProperties;
import com.homektv.domain.MediaImportRecord;
import com.homektv.domain.SongFile;
import com.homektv.media.FFprobeService;
import com.homektv.media.MediaProbe;
import com.homektv.repo.MediaImportRecordRepository;
import com.homektv.repo.SongFileRepository;
import com.homektv.web.ApiException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.SliceImpl;
import org.springframework.dao.DataIntegrityViolationException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class MediaImportServiceTest {

    @TempDir Path temp;
    private Path sourceDir;
    private Path targetDir;
    private FFprobeService probe;
    private MediaImportRecordRepository importRepo;
    private SongFileRepository songFileRepo;
    private LibraryScanService scanService;
    private MediaImportService service;
    private SettingService settingService;
    private MediaTranscoder mediaTranscoder;
    private FileHashService hashService;
    private final List<MediaImportRecord> saved = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        sourceDir = Files.createDirectories(temp.resolve("source"));
        targetDir = Files.createDirectories(temp.resolve("music"));
        AppProperties props = new AppProperties();
        props.setSourceLibraryPath(sourceDir.toString());
        props.setKtvLibraryPath(targetDir.toString());
        probe = mock(FFprobeService.class);
        importRepo = mock(MediaImportRecordRepository.class);
        songFileRepo = mock(SongFileRepository.class);
        scanService = mock(LibraryScanService.class);
        settingService = mock(SettingService.class);
        mediaTranscoder = mock(MediaTranscoder.class);
        hashService = mock(FileHashService.class);
        when(hashService.md5(any(Path.class))).thenAnswer(invocation ->
                new FileHashService().md5(invocation.getArgument(0)));
        when(settingService.transcodePolicy()).thenReturn(new SettingService.TranscodePolicy(
                List.of("mp4", "m4v", "mkv"), List.of("h264", "hevc"), List.of("aac", "mp3"),
                false, "mkv", "h264", "aac", false));
        when(importRepo.findBySourcePath(anyString())).thenReturn(Optional.empty());
        when(importRepo.saveAndFlush(any())).thenAnswer(invocation -> {
            MediaImportRecord record = invocation.getArgument(0);
            saved.add(record);
            return record;
        });
        when(scanService.ingestLibraryFile(any(), any(), anyString(), anyString(), anyBoolean()))
                .thenReturn(new LibraryScanService.IngestResult(true, 1L, 2L));
        service = new MediaImportService(props, probe, hashService, importRepo, songFileRepo,
                scanService, settingService, mediaTranscoder);
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
    }

    @Test
    void scanAutomaticallyMovesCompatibleVideo() throws Exception {
        Path source = sourceDir.resolve("周杰伦 - 晴天.mp4");
        Files.writeString(source, "compatible-media");
        MediaImportRecord previousRecord = new MediaImportRecord();
        previousRecord.setSourcePath(source.toString());
        when(importRepo.findBySourcePath(source.toString())).thenReturn(Optional.of(previousRecord));
        SongFile songFile = new SongFile();
        songFile.setId(2L);
        when(songFileRepo.findById(2L)).thenReturn(Optional.of(songFile));
        when(probe.probe(source)).thenReturn(new MediaProbe(1000, 2, 0, true, "1920x1080",
                List.of(), "h264", "aac"));

        MediaImportService.SourceScanResult result = service.scanSourceLibrary();

        assertThat(result.copied()).isEqualTo(1);
        assertThat(result.pendingTranscode()).isZero();
        assertThat(source).doesNotExist();
        assertThat(Files.exists(targetDir.resolve(source.getFileName()))).isTrue();
        assertThat(songFile.isSourceDeleted()).isTrue();
        verify(scanService).ingestLibraryFile(any(), eq(source), anyString(), anyString(), eq(false));
        verify(importRepo).delete(previousRecord);
        verify(importRepo, never()).saveAndFlush(any());
    }

    @Test
    void directImportMovesSidecarsWithTheMediaWithoutLeavingSourceCopies() throws Exception {
        Path source = sourceDir.resolve("周杰伦 - 带歌词封面.mp4");
        Path sourceLyric = sourceDir.resolve("周杰伦 - 带歌词封面.lrc");
        Path sourceCover = sourceDir.resolve("周杰伦 - 带歌词封面.jpg");
        Files.writeString(source, "compatible-media");
        Files.writeString(sourceLyric, "[00:01.00]歌词");
        Files.writeString(sourceCover, "cover");
        when(scanService.parseFilename(source.getFileName().toString())).thenReturn(
                new ParsedMeta("带歌词封面", "周杰伦", "国语", "流行", ParsedMeta.RECOGNIZED));
        when(probe.probe(source)).thenReturn(new MediaProbe(1000, 2, 0, true, "1920x1080",
                List.of(), "h264", "aac"));

        MediaImportService.SourceScanResult result = service.scanSourceLibrary();

        Path output = targetDir.resolve(source.getFileName());
        assertThat(result.copied()).isEqualTo(1);
        assertThat(output).exists();
        assertThat(output.resolveSibling("周杰伦 - 带歌词封面.lrc")).exists();
        assertThat(output.resolveSibling("周杰伦 - 带歌词封面.jpg")).exists();
        assertThat(source).doesNotExist();
        assertThat(sourceLyric).doesNotExist();
        assertThat(sourceCover).doesNotExist();
    }

    @Test
    void directImportRestoresMediaAndSidecarsWhenACompanionTargetCollides() throws Exception {
        Path source = sourceDir.resolve("周杰伦 - 目标冲突.mp4");
        Path sourceLyric = sourceDir.resolve("周杰伦 - 目标冲突.lrc");
        Path targetLyric = targetDir.resolve("周杰伦 - 目标冲突.lrc");
        Files.writeString(source, "compatible-media");
        Files.writeString(sourceLyric, "source-lyric");
        Files.writeString(targetLyric, "existing-lyric");
        when(scanService.parseFilename(source.getFileName().toString())).thenReturn(
                new ParsedMeta("目标冲突", "周杰伦", "国语", "流行", ParsedMeta.RECOGNIZED));
        when(probe.probe(source)).thenReturn(new MediaProbe(1000, 2, 0, true, "1920x1080",
                List.of(), "h264", "aac"));

        MediaImportService.SourceScanResult result = service.scanSourceLibrary();

        assertThat(result.failed()).isEqualTo(1);
        assertThat(source).exists();
        assertThat(sourceLyric).exists();
        assertThat(targetDir.resolve(source.getFileName())).doesNotExist();
        assertThat(targetLyric).hasContent("existing-lyric");
    }

    @Test
    void unchangedImportedSourceUsesSnapshotWithoutReadingFullHash() throws Exception {
        Path source = sourceDir.resolve("歌手 - unchanged.mkv");
        Files.writeString(source, "same");
        BasicFileAttributes attributes = Files.readAttributes(source, BasicFileAttributes.class);
        MediaImportRecord previous = new MediaImportRecord();
        previous.setSourcePath(source.toString());
        previous.setSourceFilename(source.getFileName().toString());
        previous.setSourceMd5("stored-md5");
        previous.setSourceSize(attributes.size());
        previous.setSourceMtime(attributes.lastModifiedTime().toInstant().atOffset(ZoneOffset.UTC));
        previous.setSourceFileIdentity(attributes.fileKey() == null ? null : attributes.fileKey().toString());
        previous.setImportedFlag(true);
        when(importRepo.findBySourcePath(source.toString())).thenReturn(Optional.of(previous));

        MediaImportService.SourceScanResult result = service.scanSourceLibrary();

        assertThat(result.scanned()).isEqualTo(1);
        assertThat(result.copied()).isZero();
        assertThat(result.pendingTranscode()).isZero();
        assertThat(result.failed()).isZero();
        verify(hashService, never()).md5(any(Path.class));
        verifyNoInteractions(probe, scanService);
    }

    @Test
    void managedSourceScanUsesTheSharedFilenameParserBeforeMove() throws Exception {
        Path source = sourceDir.resolve("A-Lin-给我一个理由忘记-国语-流行.mp4");
        Files.writeString(source, "compatible-media");
        when(scanService.parseFilename(source.getFileName().toString())).thenReturn(
                new ParsedMeta("给我一个理由忘记", "A-Lin", "国语", "流行", ParsedMeta.RECOGNIZED));
        when(probe.probe(source)).thenReturn(new MediaProbe(1000, 2, 0, true, "1920x1080",
                List.of(), "h264", "aac"));

        MediaImportService.SourceScanResult result = service.scanSourceLibrary();

        assertThat(result.copied()).isEqualTo(1);
        assertThat(result.unrecognized()).isZero();
        verify(scanService).parseFilename(source.getFileName().toString());
        verify(scanService).ingestLibraryFile(any(), eq(source), anyString(), anyString(), eq(false));
    }

    @Test
    void scanLeavesIncompatibleVideoPending() throws Exception {
        Path source = sourceDir.resolve("薛之谦 - 绅士.mpg");
        Files.writeString(source, "legacy-media");
        when(probe.probe(source)).thenReturn(new MediaProbe(1000, 2, 0, true, "1920x1080",
                List.of(), "mpeg2video", "ac3"));

        MediaImportService.SourceScanResult result = service.scanSourceLibrary();

        assertThat(result.pendingTranscode()).isEqualTo(1);
        assertThat(result.copied()).isZero();
        assertThat(saved.getLast().getAction()).isEqualTo(MediaImportService.PENDING_TRANSCODE);
        assertThat(saved.getLast().getReason()).contains(
                "容器 mpg 未加入直拷白名单",
                "视频编码 mpeg2video 未加入直拷白名单",
                "音频编码 ac3 未加入直拷白名单");
        try (Stream<Path> files = Files.list(targetDir)) {
            assertThat(files).isEmpty();
        }
        verifyNoInteractions(scanService);
    }

    @Test
    void rescanMovesExistingPendingFileWhenCurrentWhitelistNowAllowsIt() throws Exception {
        Path source = sourceDir.resolve("歌手 - 规则已放行.mpg");
        Files.writeString(source, "compatible-after-policy-change");
        MediaImportRecord pending = new MediaImportRecord();
        pending.setId(30L);
        pending.setSourcePath(source.toString());
        pending.setSourceFilename(source.getFileName().toString());
        pending.setSourceMd5(new FileHashService().md5(source));
        pending.setSourceFormat("mpg");
        pending.setMediaType(MediaClassifier.MV);
        pending.setVideoCodec("mpeg2video");
        pending.setAudioCodec("ac3");
        pending.setAction(MediaImportService.PENDING_TRANSCODE);
        pending.setTranscodeRequired(true);
        when(importRepo.findBySourcePath(source.toString())).thenReturn(Optional.of(pending));
        when(settingService.transcodePolicy()).thenReturn(new SettingService.TranscodePolicy(
                List.of("mp4", "mpg"), List.of("h264", "mpeg2video"), List.of("aac", "ac3"),
                false, "mkv", "h264", "aac", false));
        when(probe.probe(source)).thenReturn(new MediaProbe(1000, 1, 0, true, "1920x1080",
                List.of(), "mpeg2video", "ac3"));
        SongFile songFile = new SongFile();
        songFile.setId(2L);
        when(songFileRepo.findById(2L)).thenReturn(Optional.of(songFile));

        MediaImportService.SourceScanResult result = service.scanSourceLibrary();

        assertThat(result.copied()).isEqualTo(1);
        assertThat(result.pendingTranscode()).isZero();
        assertThat(source).doesNotExist();
        assertThat(targetDir.resolve(source.getFileName())).exists();
        verify(importRepo).delete(pending);
    }

    @Test
    void concurrentScansDoNotInsertTheSameSourcePathTwice() throws Exception {
        Path source = sourceDir.resolve("戴玉强 - 无悔的选择.mpg");
        Files.writeString(source, "legacy-media");
        MediaProbe mediaProbe = new MediaProbe(1000, 2, 0, true, "1920x1080",
                List.of(), "mpeg2video", "ac3");
        CountDownLatch firstProbeStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstProbe = new CountDownLatch(1);
        AtomicReference<MediaImportRecord> persisted = new AtomicReference<>();
        when(importRepo.findBySourcePath(source.toString()))
                .thenAnswer(invocation -> Optional.ofNullable(persisted.get()));
        when(probe.probe(source)).thenAnswer(invocation -> {
            firstProbeStarted.countDown();
            assertThat(releaseFirstProbe.await(5, TimeUnit.SECONDS)).isTrue();
            return mediaProbe;
        });
        when(importRepo.saveAndFlush(any())).thenAnswer(invocation -> {
            MediaImportRecord record = invocation.getArgument(0);
            persisted.set(record);
            saved.add(record);
            return record;
        });

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(service::scanSourceLibrary);
            assertThat(firstProbeStarted.await(5, TimeUnit.SECONDS)).isTrue();
            var second = executor.submit(service::scanSourceLibrary);
            releaseFirstProbe.countDown();

            assertThat(first.get(5, TimeUnit.SECONDS).pendingTranscode()).isEqualTo(1);
            assertThat(second.get(5, TimeUnit.SECONDS).pendingTranscode()).isZero();
        }
        verify(importRepo, times(1)).saveAndFlush(any());
    }

    @Test
    void scanRecoversWhenAnotherInstanceInsertsTheRecordFirst() throws Exception {
        Path source = sourceDir.resolve("歌手 - 并发导入.mpg");
        Files.writeString(source, "legacy-media");
        when(probe.probe(source)).thenReturn(new MediaProbe(1000, 2, 0, true, "1920x1080",
                List.of(), "mpeg2video", "ac3"));
        MediaImportRecord winner = new MediaImportRecord();
        when(importRepo.findBySourcePath(source.toString()))
                .thenReturn(Optional.empty(), Optional.of(winner));
        when(importRepo.saveAndFlush(any()))
                .thenThrow(new DataIntegrityViolationException("duplicate source_path"))
                .thenAnswer(invocation -> invocation.getArgument(0));

        MediaImportService.SourceScanResult result = service.scanSourceLibrary();

        assertThat(result.pendingTranscode()).isEqualTo(1);
        assertThat(winner.getSourcePath()).isEqualTo(source.toString());
        assertThat(winner.getAction()).isEqualTo(MediaImportService.PENDING_TRANSCODE);
        verify(importRepo, times(2)).saveAndFlush(any());
    }

    @Test
    void successfulRescanUpdatesThePreviousFailedRecordWithoutCreatingAnother() throws Exception {
        Path source = sourceDir.resolve("歌手 - 重试成功.mp4");
        Files.writeString(source, "compatible-media");
        AtomicReference<MediaImportRecord> persisted = new AtomicReference<>();
        when(importRepo.findBySourcePath(source.toString()))
                .thenAnswer(invocation -> Optional.ofNullable(persisted.get()));
        when(importRepo.saveAndFlush(any())).thenAnswer(invocation -> {
            MediaImportRecord record = invocation.getArgument(0);
            persisted.compareAndSet(null, record);
            return record;
        });
        when(probe.probe(source))
                .thenThrow(new IllegalStateException("temporary probe failure"))
                .thenReturn(new MediaProbe(1000, 2, 0, true, "1920x1080", List.of(), "h264", "aac"));

        MediaImportService.SourceScanResult failed = service.scanSourceLibrary();
        MediaImportRecord original = persisted.get();
        MediaImportService.SourceScanResult succeeded = service.scanSourceLibrary();

        assertThat(failed.failed()).isEqualTo(1);
        assertThat(succeeded.copied()).isEqualTo(1);
        assertThat(persisted.get()).isSameAs(original);
        assertThat(source).doesNotExist();
        assertThat(targetDir.resolve(source.getFileName())).exists();
        assertThat(service.getScanProgress().running()).isFalse();
        assertThat(service.getScanProgress().completed()).isEqualTo(1);
        verify(importRepo).saveAndFlush(same(original));
        verify(importRepo).delete(original);
    }

    @Test
    void scanRestoresMovedFileWhenLibraryIngestFails() throws Exception {
        Path source = sourceDir.resolve("歌手 - 入库失败.mp4");
        Path target = targetDir.resolve(source.getFileName());
        Files.writeString(source, "compatible-media");
        when(probe.probe(source)).thenReturn(new MediaProbe(1000, 2, 0, true, "1920x1080",
                List.of(), "h264", "aac"));
        when(scanService.ingestLibraryFile(any(), eq(source), anyString(), anyString(), eq(false)))
                .thenThrow(new IllegalStateException("database unavailable"));

        MediaImportService.SourceScanResult result = service.scanSourceLibrary();

        assertThat(result.failed()).isEqualTo(1);
        assertThat(source).exists();
        assertThat(target).doesNotExist();
        assertThat(saved.getLast().getAction()).isEqualTo(MediaImportService.FAILED);
        verify(importRepo, never()).delete(any());
    }

    @Test
    void scanMarksSourceMd5Duplicate() throws Exception {
        Path source = sourceDir.resolve("Beyond - 海阔天空.mp4");
        Files.writeString(source, "duplicate-media");
        when(probe.probe(source)).thenReturn(new MediaProbe(1000, 1, 0, true, "1920x1080",
                List.of(), "h264", "aac"));
        when(importRepo.existsBySourceMd5AndSourcePathNot(anyString(), eq(source.toString()))).thenReturn(true);

        MediaImportService.SourceScanResult result = service.scanSourceLibrary();

        assertThat(result.skippedSourceDuplicate()).isEqualTo(1);
        assertThat(saved.getLast().isDuplicateFlag()).isTrue();
        assertThat(saved.getLast().getAction()).isEqualTo(MediaImportService.SOURCE_DUPLICATE);
        verifyNoInteractions(scanService);
    }

    @Test
    void autoCleanupDeletesOnlySourcesWithVerifiedLibraryOutput() throws Exception {
        Path importedSource = sourceDir.resolve("歌手 - 已入库.mp4");
        Path importedOutput = targetDir.resolve("歌手 - 已入库.mp4");
        Path pendingSource = sourceDir.resolve("歌手 - 待转码.mpg");
        Files.writeString(importedSource, "source");
        Files.writeString(importedOutput, "output");
        Files.writeString(pendingSource, "pending");

        MediaImportRecord imported = new MediaImportRecord();
        imported.setId(10L);
        imported.setSourcePath(importedSource.toString());
        imported.setSourceFilename(importedSource.getFileName().toString());
        imported.setOutputPath(importedOutput.toString());
        imported.setOutputMd5(new FileHashService().md5(importedOutput));
        imported.setSongFileId(20L);
        imported.setImportedFlag(true);
        MediaImportRecord pending = pendingRecord(11L, pendingSource.getFileName().toString());
        SongFile libraryFile = new SongFile();
        libraryFile.setId(20L);
        libraryFile.setFilePath(importedOutput.toString());
        libraryFile.setValid(true);
        when(importRepo.findByIdGreaterThanOrderByIdAsc(eq(0L), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(imported, pending)));
        when(songFileRepo.findById(20L)).thenReturn(Optional.of(libraryFile));

        MediaImportService.AutoCleanupResult result = service.cleanupImportedSources();

        assertThat(result.scanned()).isEqualTo(2);
        assertThat(result.eligible()).isEqualTo(1);
        assertThat(result.deleted()).isEqualTo(1);
        assertThat(result.skipped()).isEqualTo(1);
        assertThat(result.failed()).isZero();
        assertThat(importedSource).doesNotExist();
        assertThat(importedOutput).exists();
        assertThat(pendingSource).exists();
        verify(importRepo).delete(imported);
        verify(importRepo, never()).save(imported);
    }

    @Test
    void deleteSourcesRemovesRecordAfterDeletingFile() throws Exception {
        Path source = sourceDir.resolve("歌手 - 待删除.mp4");
        Files.writeString(source, "source");
        MediaImportRecord record = pendingRecord(12L, source.getFileName().toString());
        when(importRepo.findByIdIn(List.of(12L))).thenReturn(List.of(record));

        MediaImportService.DeleteSourcesResult result = service.deleteSources(List.of(12L));

        assertThat(result.requested()).isEqualTo(1);
        assertThat(result.deleted()).isEqualTo(1);
        assertThat(result.failed()).isZero();
        assertThat(source).doesNotExist();
        verify(importRepo).delete(record);
        verify(importRepo, never()).save(record);
    }

    @Test
    void sourceLibraryHidesRemovedSourceRecordsByDefault() {
        service.listSourceLibrary("", "", "", null, 0, 20);

        verify(importRepo).searchSourceLibrary(eq(""), isNull(), isNull(), isNull(), eq(false), any());
    }

    @Test
    void priorityMovesQueuedRecordBehindCurrentTranscode() throws Exception {
        List<String> executionOrder = new ArrayList<>();
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        MediaImportRecord first = pendingRecord(1L, "歌手 - 第一首.mpg");
        MediaImportRecord second = pendingRecord(2L, "歌手 - 第二首.mpg");
        MediaImportRecord third = pendingRecord(3L, "歌手 - 第三首.mpg");
        Map<Long, MediaImportRecord> records = Map.of(1L, first, 2L, second, 3L, third);
        when(importRepo.findByIdIn(any())).thenReturn(List.of(first, second, third));
        when(importRepo.findById(anyLong())).thenAnswer(invocation -> Optional.ofNullable(records.get(invocation.getArgument(0))));
        when(probe.probe(any())).thenReturn(new MediaProbe(1000, 1, 0, true, "1920x1080",
                List.of(), "mpeg2video", "ac3"));
        when(mediaTranscoder.transcode(any(), any(), any(), eq(true))).thenAnswer(invocation -> {
            Path source = invocation.getArgument(0);
            Path output = invocation.getArgument(1);
            executionOrder.add(source.getFileName().toString());
            if (executionOrder.size() == 1) {
                firstStarted.countDown();
                assertThat(releaseFirst.await(5, TimeUnit.SECONDS)).isTrue();
            }
            Files.writeString(output, "output-" + executionOrder.size());
            return output;
        });

        service.startPendingTranscode(List.of(1L, 2L, 3L), false);
        assertThat(firstStarted.await(5, TimeUnit.SECONDS)).isTrue();
        MediaImportService.PriorityResult priority = service.prioritizeTranscode(3L);
        assertThat(priority.status()).isEqualTo("MOVED");
        assertThat(priority.progress().total()).isEqualTo(3);
        assertThat(priority.progress().priorityRecordIds()).containsExactly(3L);
        releaseFirst.countDown();
        awaitFinished();

        assertThat(executionOrder).containsExactly(
                "歌手 - 第一首.mpg", "歌手 - 第三首.mpg", "歌手 - 第二首.mpg");
        assertThat(service.getProgress().completed()).isEqualTo(3);
    }

    @Test
    void transcodeWorkerMarksTheBatchFinishedWhenQueueLookupFails() throws Exception {
        MediaImportRecord record = pendingRecord(40L, "歌手 - 数据库暂时不可用.mpg");
        when(importRepo.findByIdIn(List.of(40L))).thenReturn(List.of(record));
        when(importRepo.findById(40L)).thenThrow(new IllegalStateException("database unavailable"));

        service.startPendingTranscode(List.of(40L), false);

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (service.getProgress().running() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(service.getProgress().running()).isFalse();
        assertThat(service.getProgress().currentRecordId()).isNull();
        assertThat(service.getProgress().currentFile()).isNull();
        assertThat(service.getProgress().failed()).isEqualTo(1);
    }

    @Test
    void allPendingTranscodeUsesKeysetPagesInsteadOfLoadingAllRecords() {
        MediaImportRecord first = new MediaImportRecord();
        first.setId(1L);
        MediaImportRecord second = new MediaImportRecord();
        second.setId(201L);
        when(importRepo.findByIdGreaterThanOrderByIdAsc(anyLong(), any(Pageable.class)))
                .thenAnswer(invocation -> {
                    long afterId = invocation.getArgument(0);
                    if (afterId == 0L) {
                        return new PageImpl<>(List.of(first), PageRequest.of(0, 200), 201);
                    }
                    return new PageImpl<>(List.of(second), PageRequest.of(0, 200), 2);
                });

        MediaImportService.TranscodeProgress result = service.startPendingTranscode(null, true);

        assertThat(result.total()).isZero();
        verify(importRepo).findByIdGreaterThanOrderByIdAsc(eq(0L), any(Pageable.class));
        verify(importRepo).findByIdGreaterThanOrderByIdAsc(eq(1L), any(Pageable.class));
        verify(importRepo, never()).findAllByOrderByCreatedAtDesc();
    }

    @Test
    void allPendingTranscodeRejectsAnUnboundedQueueBeforeMutatingRecords() {
        List<MediaImportRecord> records = new ArrayList<>();
        for (long id = 1; id <= MediaImportService.MAX_TRANSCODE_QUEUE + 1L; id++) {
            records.add(transcodableRecord(id));
        }
        when(importRepo.findByIdGreaterThanOrderByIdAsc(anyLong(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(records));

        ApiException exception = org.junit.jupiter.api.Assertions.assertThrows(ApiException.class,
                () -> service.startPendingTranscode(null, true));

        assertThat(exception.getCode()).isEqualTo("TRANSCODE_QUEUE_LIMIT");
        verify(importRepo, never()).save(any(MediaImportRecord.class));
        assertThat(service.getProgress().running()).isFalse();
    }

    @Test
    void selectedTranscodeRejectsAnOversizedIdCollectionBeforeTheRepositoryQuery() {
        List<Long> ids = new ArrayList<>();
        for (long id = 1; id <= MediaImportService.MAX_TRANSCODE_QUEUE + 1L; id++) {
            ids.add(id);
        }

        ApiException exception = org.junit.jupiter.api.Assertions.assertThrows(ApiException.class,
                () -> service.startPendingTranscode(ids, false));

        assertThat(exception.getCode()).isEqualTo("TRANSCODE_QUEUE_LIMIT");
        verify(importRepo, never()).findByIdIn(any());
        assertThat(service.getProgress().running()).isFalse();
    }

    @Test
    void failedLibraryIngestRemovesTheNewTranscodeOutput() throws Exception {
        MediaImportRecord record = pendingRecord(41L, "歌手 - 入库异常.mpg");
        when(importRepo.findByIdIn(List.of(41L))).thenReturn(List.of(record));
        when(importRepo.findById(41L)).thenReturn(Optional.of(record));
        when(probe.probe(any(Path.class))).thenReturn(new MediaProbe(1000, 1, 0, true,
                "1920x1080", List.of(), "mpeg2video", "ac3"));
        when(mediaTranscoder.transcode(any(), any(), any(), eq(true))).thenAnswer(invocation -> {
            Path output = invocation.getArgument(1);
            Files.writeString(output, "new-output");
            return output;
        });
        when(scanService.ingestLibraryFile(any(), any(), anyString(), anyString(), eq(true)))
                .thenThrow(new IllegalStateException("database unavailable"));

        service.startPendingTranscode(List.of(41L), false);
        awaitFinished();

        try (Stream<Path> files = Files.list(targetDir)) {
            assertThat(files).isEmpty();
        }
        assertThat(service.getProgress().failed()).isEqualTo(1);
    }

    @Test
    void pendingTranscodeRejectsSourcePathOutsideConfiguredSourceRoot() throws Exception {
        Path outside = temp.resolve("outside-source.mpg");
        Files.writeString(outside, "outside-source");
        MediaImportRecord record = pendingRecord(42L, "placeholder.mpg");
        record.setSourcePath(outside.toString());
        record.setSourceMd5(new FileHashService().md5(outside));
        when(importRepo.findByIdIn(List.of(42L))).thenReturn(List.of(record));
        when(importRepo.findById(42L)).thenReturn(Optional.of(record));

        service.startPendingTranscode(List.of(42L), false);
        awaitFinished();

        assertThat(service.getProgress().failed()).isEqualTo(1);
        verify(hashService, never()).md5(eq(outside));
        verifyNoInteractions(probe, mediaTranscoder);
    }

    @Test
    void verifiedOutputSnapshotAvoidsRehashingDuringCleanup() throws Exception {
        Path importedSource = sourceDir.resolve("歌手 - 快速清理.mp4");
        Path importedOutput = targetDir.resolve("歌手 - 快速清理.mp4");
        Files.writeString(importedSource, "source");
        Files.writeString(importedOutput, "output");

        MediaImportRecord record = new MediaImportRecord();
        record.setId(42L);
        record.setSourcePath(importedSource.toString());
        record.setSourceFilename(importedSource.getFileName().toString());
        record.setOutputPath(importedOutput.toString());
        record.setOutputMd5(new FileHashService().md5(importedOutput));
        record.setOutputSize(Files.size(importedOutput));
        var outputMtime = Files.getLastModifiedTime(importedOutput).toInstant()
                .atOffset(ZoneOffset.UTC);
        record.setOutputMtime(outputMtime.withNano((outputMtime.getNano() / 1_000) * 1_000));
        record.setSongFileId(42L);
        record.setImportedFlag(true);
        SongFile libraryFile = new SongFile();
        libraryFile.setId(42L);
        libraryFile.setFilePath(importedOutput.toString());
        libraryFile.setValid(true);
        when(importRepo.findByIdGreaterThanOrderByIdAsc(eq(0L), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(record)));
        when(songFileRepo.findById(42L)).thenReturn(Optional.of(libraryFile));

        MediaImportService.AutoCleanupResult result = service.cleanupImportedSources();

        assertThat(result.deleted()).isEqualTo(1);
        verify(hashService, never()).md5(importedOutput);
    }

    @Test
    void cleanupUsesKeysetPagesInsteadOfLoadingAllRecords() {
        when(importRepo.findByIdGreaterThanOrderByIdAsc(eq(0L), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        MediaImportService.AutoCleanupResult result = service.cleanupImportedSources();

        assertThat(result.scanned()).isZero();
        verify(importRepo).findByIdGreaterThanOrderByIdAsc(eq(0L), any(Pageable.class));
        verify(importRepo, never()).findAllByOrderByCreatedAtDesc();
    }

    @Test
    void filteredSourceDeletionUsesSlicesInsteadOfAnUnpagedQuery() {
        when(importRepo.searchSourceLibraryAfterId(eq(0L), eq(""), isNull(), isNull(), isNull(),
                eq(false), any(Pageable.class))).thenReturn(new SliceImpl<>(List.of()));

        MediaImportService.DeleteSourcesResult result =
                service.deleteSourcesByFilter(null, null, null, null);

        assertThat(result.requested()).isZero();
        verify(importRepo).searchSourceLibraryAfterId(eq(0L), eq(""), isNull(), isNull(), isNull(),
                eq(false), any(Pageable.class));
        verify(importRepo, never()).findAllByOrderByCreatedAtDesc();
    }

    @Test
    void legacyCleanupPersistsTheOutputHashBeforeUsingItForFutureSafetyChecks() throws Exception {
        Path importedSource = sourceDir.resolve("歌手 - 旧记录.mp4");
        Path importedOutput = targetDir.resolve("歌手 - 旧记录.mp4");
        Files.writeString(importedSource, "source");
        Files.writeString(importedOutput, "output");

        MediaImportRecord record = new MediaImportRecord();
        record.setId(43L);
        record.setSourcePath(importedSource.toString());
        record.setSourceFilename(importedSource.getFileName().toString());
        record.setOutputPath(importedOutput.toString());
        record.setSongFileId(43L);
        record.setImportedFlag(true);
        SongFile libraryFile = new SongFile();
        libraryFile.setId(43L);
        libraryFile.setFilePath(importedOutput.toString());
        libraryFile.setValid(true);
        when(importRepo.findByIdGreaterThanOrderByIdAsc(eq(0L), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(record)));
        when(songFileRepo.findById(43L)).thenReturn(Optional.of(libraryFile));

        MediaImportService.AutoCleanupResult result = service.cleanupImportedSources();

        assertThat(result.deleted()).isEqualTo(1);
        assertThat(record.getOutputMd5()).isNotBlank();
        verify(importRepo).save(record);
    }

    private MediaImportRecord pendingRecord(Long id, String filename) throws Exception {
        Path source = sourceDir.resolve(filename);
        Files.writeString(source, "source-" + id);
        MediaImportRecord record = new MediaImportRecord();
        record.setId(id);
        record.setSourcePath(source.toString());
        record.setSourceFilename(filename);
        record.setSourceMd5(new FileHashService().md5(source));
        record.setAction(MediaImportService.PENDING_TRANSCODE);
        record.setTranscodeRequired(true);
        return record;
    }

    private MediaImportRecord transcodableRecord(long id) {
        MediaImportRecord record = new MediaImportRecord();
        record.setId(id);
        record.setAction(MediaImportService.PENDING_TRANSCODE);
        record.setTranscodeRequired(true);
        return record;
    }

    private void awaitFinished() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (service.getProgress().running() && System.nanoTime() < deadline) Thread.sleep(20);
        assertThat(service.getProgress().running()).isFalse();
    }
}
