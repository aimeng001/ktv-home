package com.homektv.library;

import com.homektv.config.AppProperties;
import com.homektv.domain.MediaImportRecord;
import com.homektv.domain.SongFile;
import com.homektv.media.FFprobeService;
import com.homektv.media.MediaProbe;
import com.homektv.repo.MediaImportRecordRepository;
import com.homektv.repo.SongFileRepository;
import com.homektv.resource.ResourceBudget;
import com.homektv.web.ApiException;
import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
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
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class MediaImportServiceTest {

    @TempDir Path temp;
    private Path sourceDir;
    private Path targetDir;
    private AppProperties props;
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
        props = new AppProperties();
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
        when(scanService.ingestLibraryFile(any(), any(), anyString(), anyString(), anyBoolean(), any(MediaProbe.class)))
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

        assertThat(result.copied()).as("scan result=%s, saved reasons=%s", result,
                saved.stream().map(record -> record.getAction() + ": " + record.getReason()).toList()).isEqualTo(1);
        assertThat(result.pendingTranscode()).isZero();
        assertThat(source).doesNotExist();
        assertThat(Files.exists(targetDir.resolve(source.getFileName()))).isTrue();
        assertThat(songFile.isSourceDeleted()).isTrue();
        verify(scanService).ingestLibraryFile(any(), eq(source), anyString(), anyString(), eq(false), any(MediaProbe.class));
        verify(importRepo).delete(previousRecord);
        verify(importRepo, never()).saveAndFlush(any());
    }

    @Test
    void sourceScanFailsClosedWithoutCreatingAMissingConfiguredSourceDirectory() {
        Path missingSourceRoot = temp.resolve("missing-source-root");
        props.setSourceLibraryPath(missingSourceRoot.toString());

        assertThatThrownBy(service::scanSourceLibrary)
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.getCode()).isEqualTo("SOURCE_LIBRARY_MISSING"));

        assertThat(missingSourceRoot).doesNotExist();
        assertThat(targetDir).isDirectory();
        verify(importRepo, never()).save(any(MediaImportRecord.class));
    }

    @Test
    void scanSkipsACandidateThatDisappearsAfterTheDirectorySnapshot() throws Exception {
        Path source = Files.writeString(sourceDir.resolve("周杰伦-晴天.mkv"), "synthetic video");

        MediaImportService.SourceScanResult result;
        try (MockedStatic<Files> fileSystem = mockStatic(Files.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
            fileSystem.when(() -> Files.walk(sourceDir)).thenReturn(Stream.of(source));
            fileSystem.when(() -> Files.isRegularFile(source)).thenReturn(true);
            fileSystem.when(() -> Files.exists(source)).thenReturn(false);

            result = service.scanSourceLibrary();
        }

        assertThat(result.scanned()).isZero();
        assertThat(result.failed()).isZero();
        assertThat(service.getScanProgress().completed()).isZero();
        verifyNoInteractions(importRepo, probe, mediaTranscoder, scanService);
    }

    @Test
    void unsupportedOrMissingVideoFormatDimensionsStayPendingForTranscode() throws Exception {
        assertPendingTranscode("unsupported-container", "周杰伦 - 未支持容器.avi",
                new MediaProbe(1000, 2, 0, true, "1920x1080", List.of(), "h264", "aac"),
                "容器 avi 未加入直拷白名单");
        assertPendingTranscode("unsupported-video-codec", "周杰伦 - 未支持视频编码.mkv",
                new MediaProbe(1000, 2, 0, true, "1920x1080", List.of(), "vp9", "aac"),
                "视频编码 vp9 未加入直拷白名单");
        assertPendingTranscode("unsupported-audio-codec", "周杰伦 - 未支持音频编码.mkv",
                new MediaProbe(1000, 2, 0, true, "1920x1080", List.of(), "h264", "ac3"),
                "音频编码 ac3 未加入直拷白名单");
        assertPendingTranscode("unknown-video-codecs", "周杰伦 - 未识别视频编码.mkv",
                new MediaProbe(1000, 2, 0, true, "1920x1080", List.of(), null, null),
                "未识别视频编码；未识别音频编码");

        verify(scanService, never()).ingestLibraryFile(any(), any(), anyString(), anyString(), anyBoolean(), any());
        verify(mediaTranscoder, never()).transcode(any(), any(), any(), anyBoolean());
    }

    @Test
    void blankStoredContainerKeepsPreviouslyAnalyzedVideoPending() throws Exception {
        Path source = Files.writeString(sourceDir.resolve("周杰伦 - 缺失容器元数据.mkv"), "same-content");
        MediaImportService.SourceSnapshot snapshot = fileSnapshot(source, "stable-identity");
        MediaImportRecord previous = importedSnapshotRecord(source, snapshot, "stable-identity");
        previous.setImportedFlag(false);
        previous.setAction(MediaImportService.PENDING_TRANSCODE);
        previous.setTranscodeRequired(true);
        previous.setMediaType(MediaClassifier.KTV_VIDEO);
        previous.setSourceFormat("");
        previous.setVideoCodec("h264");
        previous.setAudioCodec("aac");
        previous.setReason("旧的转码原因");
        when(importRepo.findBySourcePath(source.toString())).thenReturn(Optional.of(previous));
        installSnapshotReader(snapshot);

        service.scanSourceLibrary();

        assertThat(previous.getReason()).contains("未识别容器格式");
        assertThat(source).hasContent("same-content");
        verify(importRepo).save(previous);
        verify(probe, never()).probe(source);
    }

    @Test
    void scanCopiesAudioOnlyWhenAudioTranscodingIsDisabled() throws Exception {
        Path source = sourceDir.resolve("草蜢 - 爱.mp3");
        Files.writeString(source, "synthetic-audio");
        when(probe.probe(source)).thenReturn(new MediaProbe(1000, 1, 0, false, null,
                List.of(), null, "mp3"));

        MediaImportService.SourceScanResult result = service.scanSourceLibrary();

        assertThat(result.copied()).isEqualTo(1);
        assertThat(result.pendingTranscode()).isZero();
        assertThat(source).doesNotExist();
        assertThat(targetDir.resolve(source.getFileName())).hasContent("synthetic-audio");
        verify(mediaTranscoder, never()).transcode(any(), any(), any(), anyBoolean());
    }

    @Test
    void scanKeepsAudioOnlyPendingWhenAudioTranscodingIsEnabled() throws Exception {
        Path source = sourceDir.resolve("韩红 - 天路.mp3");
        Files.writeString(source, "synthetic-audio");
        when(settingService.transcodePolicy()).thenReturn(new SettingService.TranscodePolicy(
                List.of("mp4", "m4v", "mkv"), List.of("h264", "hevc"), List.of("aac", "mp3"),
                true, "mkv", "h264", "aac", false));
        when(probe.probe(source)).thenReturn(new MediaProbe(1000, 1, 0, false, null,
                List.of(), null, "mp3"));

        MediaImportService.SourceScanResult result = service.scanSourceLibrary();

        assertThat(result.pendingTranscode()).isEqualTo(1);
        assertThat(result.copied()).isZero();
        assertThat(source).hasContent("synthetic-audio");
        assertThat(saved).singleElement().satisfies(record -> {
            assertThat(record.getAction()).isEqualTo(MediaImportService.PENDING_TRANSCODE);
            assertThat(record.getReason()).isEqualTo("已开启纯音频文件转码");
        });
        verify(mediaTranscoder, never()).transcode(any(), any(), any(), anyBoolean());
    }

    @Test
    void scanKeepsAnUnrecognizedFilenamePendingForManualReview() throws Exception {
        Path source = sourceDir.resolve("TRACK_00321.mp3");
        Files.writeString(source, "synthetic-audio");
        assertThat(FilenameParser.parse(source.getFileName().toString()).recognized()).isFalse();
        when(probe.probe(source)).thenReturn(new MediaProbe(1000, 1, 0, false, null,
                List.of(), null, "mp3"));

        MediaImportService.SourceScanResult result = service.scanSourceLibrary();

        assertThat(result.unrecognized()).isEqualTo(1);
        assertThat(result.copied()).isZero();
        assertThat(source).hasContent("synthetic-audio");
        assertThat(saved).singleElement().satisfies(record ->
                assertThat(record.getAction()).isEqualTo(MediaImportService.UNRECOGNIZED));
        verify(scanService, never()).ingestLibraryFile(any(), any(), anyString(), anyString(), anyBoolean(), any());
    }

    @Test
    void embeddedAudioTitleAllowsImportWhenTheFilenameCannotBeParsed() throws Exception {
        Path source = sourceDir.resolve("TRACK_00321.mp3");
        Files.writeString(source, "synthetic-audio");
        assertThat(FilenameParser.parse(source.getFileName().toString()).recognized()).isFalse();
        when(probe.probe(source)).thenReturn(new MediaProbe(1000, 1, 0, false, null,
                List.of(), null, "mp3"));
        AudioFile audioFile = mock(AudioFile.class);
        Tag tag = mock(Tag.class);
        when(audioFile.getTag()).thenReturn(tag);
        when(tag.getFirst(FieldKey.TITLE)).thenReturn("标签歌名");
        when(tag.getFirst(FieldKey.ARTIST)).thenReturn("标签歌手");
        TagReader tagReader = new TagReader(ignored -> audioFile, ignored -> TagBudget.SAFE);
        service.shutdown();
        service = new MediaImportService(props, probe, hashService, importRepo, songFileRepo,
                scanService, settingService, mediaTranscoder, tagReader,
                new MediaProbeStage(probe, new ResourceBudget(1, 1, 4, () -> false)));

        MediaImportService.SourceScanResult result = service.scanSourceLibrary();

        assertThat(result.copied()).isEqualTo(1);
        assertThat(result.unrecognized()).isZero();
        assertThat(source).doesNotExist();
        assertThat(targetDir.resolve(source.getFileName())).hasContent("synthetic-audio");
        verify(scanService).ingestLibraryFile(any(Path.class), eq(source), anyString(), anyString(),
                eq(false), any(MediaProbe.class));
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
    void directImportRejectsSymlinkSidecarAndRestoresTheMediaWithoutTouchingItsTarget() throws Exception {
        Path source = sourceDir.resolve("周杰伦 - 符号链接歌词保护.mp4");
        Path sidecar = source.resolveSibling("周杰伦 - 符号链接歌词保护.lrc");
        Path outsideTarget = temp.resolve("outside-lyrics.lrc");
        Files.writeString(source, "synthetic-video");
        Files.writeString(outsideTarget, "outside lyric must remain untouched");
        try {
            Files.createSymbolicLink(sidecar, outsideTarget);
        } catch (java.io.IOException | UnsupportedOperationException | SecurityException unavailable) {
            Assumptions.assumeTrue(false, "当前本机环境不支持创建符号链接");
        }
        when(probe.probe(source)).thenReturn(new MediaProbe(1000, 2, 0, true,
                "1920x1080", List.of(), "h264", "aac"));

        MediaImportService.SourceScanResult result = service.scanSourceLibrary();

        assertThat(result.failed()).isEqualTo(1);
        assertThat(source).hasContent("synthetic-video");
        assertThat(targetDir.resolve(source.getFileName())).doesNotExist();
        assertThat(Files.isSymbolicLink(sidecar)).isTrue();
        assertThat(outsideTarget).hasContent("outside lyric must remain untouched");
        assertThat(saved).singleElement().satisfies(record -> {
            assertThat(record.getAction()).isEqualTo(MediaImportService.FAILED);
            assertThat(record.getReason()).contains("拒绝移动符号链接伴随文件");
        });
        verifyNoInteractions(scanService);
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
    void directImportRestoresTheSourceWhenTheMovedMediaHashDoesNotMatch() throws Exception {
        Path source = sourceDir.resolve("歌手 - 移动校验失败.mp4");
        Files.writeString(source, "source-content");
        when(probe.probe(source)).thenReturn(new MediaProbe(1000, 2, 0, true,
                "1920x1080", List.of(), "h264", "aac"));
        when(hashService.md5(any(Path.class))).thenReturn("source-hash", "moved-hash");

        MediaImportService.SourceScanResult result = service.scanSourceLibrary();

        assertThat(result.failed()).isEqualTo(1);
        assertThat(source).hasContent("source-content");
        assertThat(targetDir.resolve(source.getFileName())).doesNotExist();
        assertThat(saved).singleElement().satisfies(record ->
                assertThat(record.getAction()).isEqualTo(MediaImportService.FAILED));
        verifyNoInteractions(scanService);
    }

    @Test
    void directImportKeepsMovedMediaWhenTheSourcePathIsRecreatedDuringFailedIngest() throws Exception {
        Path source = sourceDir.resolve("歌手 - 并发重建源文件.mp4");
        Path output = targetDir.resolve(source.getFileName());
        Path sourceLyric = source.resolveSibling("歌手 - 并发重建源文件.lrc");
        Path outputLyric = targetDir.resolve("歌手 - 并发重建源文件.lrc");
        Files.writeString(source, "original-media");
        Files.writeString(sourceLyric, "original-lyric");
        when(probe.probe(source)).thenReturn(new MediaProbe(1000, 2, 0, true,
                "1920x1080", List.of(), "h264", "aac"));
        when(scanService.ingestLibraryFile(any(Path.class), eq(source), anyString(), anyString(),
                eq(false), any(MediaProbe.class))).thenAnswer(invocation -> {
            Files.writeString(source, "recreated-source");
            Files.writeString(sourceLyric, "recreated-lyric");
            throw new IllegalStateException("database unavailable after concurrent source recreation");
        });

        MediaImportService.SourceScanResult result = service.scanSourceLibrary();

        assertThat(result.failed()).isEqualTo(1);
        assertThat(source).hasContent("recreated-source");
        assertThat(sourceLyric).hasContent("recreated-lyric");
        assertThat(output).hasContent("original-media");
        assertThat(outputLyric).hasContent("original-lyric");
        assertThat(saved).singleElement().satisfies(record -> {
            assertThat(record.getAction()).isEqualTo(MediaImportService.FAILED);
            assertThat(record.getReason()).contains("database unavailable after concurrent source recreation");
        });
    }

    @Test
    void directImportRollsBackEarlierSidecarsWhenALaterCompanionCollides() throws Exception {
        Path source = sourceDir.resolve("歌手 - 部分伴随文件.mp4");
        Path sourceLyric = source.resolveSibling("歌手 - 部分伴随文件.lrc");
        Path sourceCover = source.resolveSibling("歌手 - 部分伴随文件.jpg");
        Path targetLyric = targetDir.resolve("歌手 - 部分伴随文件.lrc");
        Path targetCover = targetDir.resolve("歌手 - 部分伴随文件.jpg");
        Files.writeString(source, "compatible-media");
        Files.writeString(sourceLyric, "source lyric");
        Files.writeString(sourceCover, "source cover");
        Files.writeString(targetCover, "existing cover");
        when(probe.probe(source)).thenReturn(new MediaProbe(1000, 2, 0, true,
                "1920x1080", List.of(), "h264", "aac"));

        MediaImportService.SourceScanResult result = service.scanSourceLibrary();

        assertThat(result.failed()).isEqualTo(1);
        assertThat(source).hasContent("compatible-media");
        assertThat(sourceLyric).hasContent("source lyric");
        assertThat(sourceCover).hasContent("source cover");
        assertThat(targetLyric).doesNotExist();
        assertThat(targetCover).hasContent("existing cover");
    }

    @Test
    void directImportChoosesANewTargetNameWhenTheOriginalFilenameAlreadyExists() throws Exception {
        Path firstDirectory = Files.createDirectories(sourceDir.resolve("first"));
        Path secondDirectory = Files.createDirectories(sourceDir.resolve("second"));
        Path first = firstDirectory.resolve("歌手 - 同名歌曲.mp4");
        Path second = secondDirectory.resolve(first.getFileName());
        Files.writeString(first, "first-content");
        Files.writeString(second, "second-content");
        when(probe.probe(any(Path.class))).thenReturn(new MediaProbe(1000, 2, 0, true,
                "1920x1080", List.of(), "h264", "aac"));

        MediaImportService.SourceScanResult result = service.scanSourceLibrary();

        assertThat(result.copied()).isEqualTo(2);
        assertThat(targetDir.resolve(first.getFileName())).exists();
        assertThat(targetDir.resolve("歌手 - 同名歌曲-2.mp4")).exists();
        assertThat(first).doesNotExist();
        assertThat(second).doesNotExist();
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
    void unchangedImportedSourceTrustsAMatchingInjectedFileIdentity() throws Exception {
        Path source = sourceDir.resolve("歌手 - identity-match.mkv");
        Files.writeString(source, "same");
        BasicFileAttributes attributes = Files.readAttributes(source, BasicFileAttributes.class);
        var mtime = attributes.lastModifiedTime().toInstant().atOffset(ZoneOffset.UTC);
        mtime = mtime.withNano((mtime.getNano() / 1_000) * 1_000);
        MediaImportService.SourceSnapshot snapshot = new MediaImportService.SourceSnapshot(
                attributes.size(), mtime, "stable-file-identity");
        MediaImportRecord previous = new MediaImportRecord();
        previous.setSourcePath(source.toString());
        previous.setSourceFilename(source.getFileName().toString());
        previous.setSourceMd5("stored-md5");
        previous.setSourceSize(attributes.size());
        previous.setSourceMtime(mtime);
        previous.setSourceFileIdentity("stable-file-identity");
        previous.setImportedFlag(true);
        when(importRepo.findBySourcePath(source.toString())).thenReturn(Optional.of(previous));
        service.shutdown();
        service = new MediaImportService(props, probe, hashService, importRepo, songFileRepo,
                scanService, settingService, mediaTranscoder, new TagReader(),
                new MediaProbeStage(probe, new ResourceBudget(1, 1, 4, () -> false)), path -> snapshot);

        MediaImportService.SourceScanResult result = service.scanSourceLibrary();

        assertThat(result.scanned()).isEqualTo(1);
        assertThat(result.copied()).isZero();
        assertThat(result.failed()).isZero();
        assertThat(source).exists();
        verify(hashService, never()).md5(source);
        verifyNoInteractions(probe, scanService);
    }

    @Test
    void changedInjectedFileIdentityForcesARehashEvenWhenSizeAndMtimeMatch() throws Exception {
        Path source = sourceDir.resolve("歌手 - identity-mismatch.mkv");
        Files.writeString(source, "same-content");
        var snapshot = fileSnapshot(source, "current-file-id");
        MediaImportRecord previous = importedSnapshotRecord(source, snapshot, "previous-file-id");
        when(importRepo.findBySourcePath(source.toString())).thenReturn(Optional.of(previous));
        installSnapshotReader(snapshot);

        MediaImportService.SourceScanResult result = service.scanSourceLibrary();

        assertThat(result.scanned()).isEqualTo(1);
        assertThat(result.failed()).isZero();
        assertThat(source).exists();
        assertThat(previous.getSourceFileIdentity()).isEqualTo("current-file-id");
        verify(hashService).md5(source);
        verify(importRepo).save(previous);
        verifyNoInteractions(probe, scanService);
    }

    @Test
    void legacySnapshotWithoutIdentityIsRehashedWhenTheCurrentFileHasAnIdentity() throws Exception {
        Path source = sourceDir.resolve("歌手 - legacy-identity.mkv");
        Files.writeString(source, "same-content");
        var snapshot = fileSnapshot(source, "current-file-id");
        MediaImportRecord previous = importedSnapshotRecord(source, snapshot, null);
        when(importRepo.findBySourcePath(source.toString())).thenReturn(Optional.of(previous));
        installSnapshotReader(snapshot);

        MediaImportService.SourceScanResult result = service.scanSourceLibrary();

        assertThat(result.failed()).isZero();
        assertThat(previous.getSourceFileIdentity()).isEqualTo("current-file-id");
        verify(hashService).md5(source);
        verify(importRepo).save(previous);
        verifyNoInteractions(probe, scanService);
    }

    @Test
    void legacySnapshotCanUseSizeAndMtimeWhenBothFileIdentitiesAreUnavailable() throws Exception {
        Path source = sourceDir.resolve("歌手 - legacy-no-identity.mkv");
        Files.writeString(source, "same-content");
        var snapshot = fileSnapshot(source, null);
        MediaImportRecord previous = importedSnapshotRecord(source, snapshot, null);
        when(importRepo.findBySourcePath(source.toString())).thenReturn(Optional.of(previous));
        installSnapshotReader(snapshot);

        MediaImportService.SourceScanResult result = service.scanSourceLibrary();

        assertThat(result.scanned()).isEqualTo(1);
        assertThat(result.failed()).isZero();
        verify(hashService, never()).md5(source);
        verifyNoInteractions(probe, scanService);
    }

    @Test
    void changedInjectedMtimeForcesARehashWhenIdentityAndSizeMatch() throws Exception {
        Path source = sourceDir.resolve("歌手 - mtime-changed.mkv");
        Files.writeString(source, "same-content");
        var snapshot = fileSnapshot(source, "same-file-id");
        MediaImportRecord previous = importedSnapshotRecord(source, snapshot, "same-file-id");
        previous.setSourceMtime(snapshot.mtime().minusSeconds(1));
        when(importRepo.findBySourcePath(source.toString())).thenReturn(Optional.of(previous));
        installSnapshotReader(snapshot);

        MediaImportService.SourceScanResult result = service.scanSourceLibrary();

        assertThat(result.failed()).isZero();
        assertThat(previous.getSourceMtime()).isEqualTo(snapshot.mtime());
        verify(hashService).md5(source);
        verify(importRepo).save(previous);
        verifyNoInteractions(probe, scanService);
    }

    @Test
    void scanContinuesWhenTheReadOnlySourceSnapshotCannotBeRead() throws Exception {
        Path source = Files.writeString(sourceDir.resolve("歌手 - 快照不可读.mpg"), "synthetic-source");
        when(probe.probe(source)).thenReturn(new MediaProbe(1000, 2, 0, true,
                "1920x1080", List.of(), "mpeg2video", "ac3"));

        MediaImportService.SourceScanResult result;
        try (MockedStatic<Files> fileSystem = mockStatic(Files.class,
                org.mockito.Mockito.CALLS_REAL_METHODS)) {
            fileSystem.when(() -> Files.readAttributes(source, BasicFileAttributes.class))
                    .thenThrow(new java.io.IOException("synthetic snapshot read failure"));

            result = service.scanSourceLibrary();
        }

        assertThat(result.scanned()).isEqualTo(1);
        assertThat(result.pendingTranscode()).isEqualTo(1);
        assertThat(result.failed()).isZero();
        assertThat(saved).hasSize(1);
        assertThat(saved.getFirst().getAction()).isEqualTo(MediaImportService.PENDING_TRANSCODE);
        assertThat(saved.getFirst().getSourceSize()).isNull();
        assertThat(saved.getFirst().getSourceMtime()).isNull();
        assertThat(source).hasContent("synthetic-source");
        verify(probe).probe(source);
    }

    @ParameterizedTest
    @ValueSource(strings = {"absent", "present"})
    void scanStoresSizeAndMtimeWithAnOptionalFileIdentity(String identityState) throws Exception {
        Path source = Files.writeString(sourceDir.resolve("歌手 - 无文件身份.mpg"), "synthetic-source");
        BasicFileAttributes attributes = mock(BasicFileAttributes.class);
        when(attributes.size()).thenReturn(Files.size(source));
        when(attributes.lastModifiedTime()).thenReturn(Files.getLastModifiedTime(source));
        String expectedIdentity = "present".equals(identityState) ? "synthetic-file-key" : null;
        when(attributes.fileKey()).thenReturn(expectedIdentity);
        when(probe.probe(source)).thenReturn(new MediaProbe(1000, 2, 0, true,
                "1920x1080", List.of(), "mpeg2video", "ac3"));

        MediaImportService.SourceScanResult result;
        try (MockedStatic<Files> fileSystem = mockStatic(Files.class,
                org.mockito.Mockito.CALLS_REAL_METHODS)) {
            fileSystem.when(() -> Files.readAttributes(source, BasicFileAttributes.class)).thenReturn(attributes);
            result = service.scanSourceLibrary();
        }

        assertThat(result.pendingTranscode()).isEqualTo(1);
        assertThat(result.failed()).isZero();
        assertThat(saved).singleElement().satisfies(record -> {
            assertThat(record.getSourceSize()).isEqualTo(Files.size(source));
            assertThat(record.getSourceMtime()).isNotNull();
            assertThat(record.getSourceFileIdentity()).isEqualTo(expectedIdentity);
            assertThat(record.getAction()).isEqualTo(MediaImportService.PENDING_TRANSCODE);
        });
        assertThat(source).hasContent("synthetic-source");
    }

    @Test
    void scanRehashesAnUnchangedFileWhenItsStoredDigestIsBlank() throws Exception {
        Path source = Files.writeString(sourceDir.resolve("歌手 - 空白旧指纹.mpg"), "same source bytes");
        MediaImportService.SourceSnapshot snapshot = fileSnapshot(source, null);
        MediaImportRecord previous = importedSnapshotRecord(source, snapshot, null);
        previous.setSourceMd5(" ");
        previous.setImportedFlag(false);
        previous.setAction(MediaImportService.FAILED);
        previous.setTranscodeRequired(true);
        String currentDigest = new FileHashService().md5(source);
        when(importRepo.findBySourcePath(source.toString())).thenReturn(Optional.of(previous));
        installSnapshotReader(snapshot);
        when(probe.probe(source)).thenReturn(new MediaProbe(1000, 2, 0, true,
                "1920x1080", List.of(), "mpeg2video", "ac3"));

        MediaImportService.SourceScanResult result = service.scanSourceLibrary();

        assertThat(result.pendingTranscode()).isEqualTo(1);
        assertThat(result.failed()).isZero();
        assertThat(previous.getSourceMd5()).isEqualTo(currentDigest);
        assertThat(previous.getAction()).isEqualTo(MediaImportService.PENDING_TRANSCODE);
        assertThat(source).hasContent("same source bytes");
        verify(hashService).md5(source);
        verify(probe).probe(source);
    }

    @Test
    void failedHashOnANewSourceStillRecordsTheReadableSnapshot() throws Exception {
        Path source = Files.writeString(sourceDir.resolve("歌手 - 新源哈希失败.mpg"), "synthetic source");
        when(hashService.md5(source)).thenThrow(new IllegalStateException("synthetic hash failure"));

        MediaImportService.SourceScanResult result = service.scanSourceLibrary();

        assertThat(result.failed()).isEqualTo(1);
        assertThat(saved).singleElement().satisfies(record -> {
            assertThat(record.getAction()).isEqualTo(MediaImportService.FAILED);
            assertThat(record.getSourceMd5()).isEmpty();
            assertThat(record.getCleanupStatus()).isEqualTo("NOT_REQUESTED");
            assertThat(record.getSourceSize()).isEqualTo(Files.size(source));
            assertThat(record.getSourceMtime()).isNotNull();
        });
        assertThat(source).hasContent("synthetic source");
        verifyNoInteractions(probe, scanService);
    }

    @Test
    void missingCurrentSnapshotMtimeForcesARehashInsteadOfTrustingTheImportedRecord() throws Exception {
        Path source = sourceDir.resolve("歌手 - missing-current-mtime.mkv");
        Files.writeString(source, "same-content");
        var completeSnapshot = fileSnapshot(source, "stable-file-id");
        var incompleteSnapshot = new MediaImportService.SourceSnapshot(
                completeSnapshot.size(), null, completeSnapshot.fileIdentity());
        MediaImportRecord previous = importedSnapshotRecord(source, completeSnapshot, "stable-file-id");
        when(importRepo.findBySourcePath(source.toString())).thenReturn(Optional.of(previous));
        installSnapshotReader(incompleteSnapshot);

        MediaImportService.SourceScanResult result = service.scanSourceLibrary();

        assertThat(result.scanned()).isEqualTo(1);
        assertThat(result.failed()).isZero();
        assertThat(source).hasContent("same-content");
        assertThat(previous.getSourceMtime()).isNull();
        verify(hashService).md5(source);
        verify(importRepo).save(previous);
        verifyNoInteractions(probe, scanService);
    }

    @Test
    void unchangedPendingVideoReusesStoredFormatAnalysisAndOnlySavesAChangedReason() throws Exception {
        Path source = sourceDir.resolve("歌手 - cached-video-analysis.mpg");
        Files.writeString(source, "same-content");
        var snapshot = fileSnapshot(source, null);
        MediaImportRecord previous = importedSnapshotRecord(source, snapshot, null);
        previous.setImportedFlag(false);
        previous.setAction(MediaImportService.PENDING_TRANSCODE);
        previous.setTranscodeRequired(true);
        previous.setMediaType(MediaClassifier.KTV_VIDEO);
        previous.setSourceFormat("mpg");
        previous.setVideoCodec("mpeg2video");
        previous.setAudioCodec("ac3");
        previous.setReason("outdated analysis reason");
        when(importRepo.findBySourcePath(source.toString())).thenReturn(Optional.of(previous));
        installSnapshotReader(snapshot);

        MediaImportService.SourceScanResult first = service.scanSourceLibrary();
        String refreshedReason = previous.getReason();
        MediaImportService.SourceScanResult second = service.scanSourceLibrary();

        assertThat(first.scanned()).isEqualTo(1);
        assertThat(second.scanned()).isEqualTo(1);
        assertThat(refreshedReason).isNotEqualTo("outdated analysis reason");
        assertThat(previous.getReason()).isEqualTo(refreshedReason);
        assertThat(source).exists();
        verify(importRepo, times(1)).save(previous);
        verify(hashService, never()).md5(source);
        verifyNoInteractions(probe, scanService);
    }

    @Test
    void unchangedPendingAudioUsesTheCurrentAudioOnlyPolicyWithoutReprobing() throws Exception {
        Path source = sourceDir.resolve("歌手 - cached-audio-analysis.mp3");
        Files.writeString(source, "same-content");
        var snapshot = fileSnapshot(source, null);
        MediaImportRecord previous = importedSnapshotRecord(source, snapshot, null);
        previous.setImportedFlag(false);
        previous.setAction(MediaImportService.PENDING_TRANSCODE);
        previous.setTranscodeRequired(true);
        previous.setMediaType(MediaClassifier.AUDIO);
        previous.setSourceFormat("mp3");
        previous.setReason("已开启纯音频文件转码");
        when(settingService.transcodePolicy()).thenReturn(new SettingService.TranscodePolicy(
                List.of("mp4", "m4v", "mkv"), List.of("h264", "hevc"), List.of("aac", "mp3"),
                true, "mkv", "h264", "aac", false));
        when(importRepo.findBySourcePath(source.toString())).thenReturn(Optional.of(previous));
        installSnapshotReader(snapshot);

        MediaImportService.SourceScanResult result = service.scanSourceLibrary();

        assertThat(result.scanned()).isEqualTo(1);
        assertThat(result.pendingTranscode()).isZero();
        assertThat(previous.getAction()).isEqualTo(MediaImportService.PENDING_TRANSCODE);
        assertThat(source).exists();
        verify(hashService, never()).md5(source);
        verifyNoInteractions(probe, scanService);
    }

    @Test
    void pendingRecordWithoutCompleteFormatAnalysisIsReprobed() throws Exception {
        Path source = sourceDir.resolve("歌手 - incomplete-analysis.mpg");
        Files.writeString(source, "same-content");
        var snapshot = fileSnapshot(source, null);
        MediaImportRecord previous = importedSnapshotRecord(source, snapshot, null);
        previous.setImportedFlag(false);
        previous.setAction(MediaImportService.PENDING_TRANSCODE);
        previous.setTranscodeRequired(true);
        previous.setMediaType(MediaClassifier.KTV_VIDEO);
        previous.setSourceFormat("mpg");
        previous.setVideoCodec(null);
        previous.setAudioCodec("ac3");
        when(importRepo.findBySourcePath(source.toString())).thenReturn(Optional.of(previous));
        when(probe.probe(source)).thenReturn(new MediaProbe(1000, 2, 0, true,
                "1920x1080", List.of(), "mpeg2video", "ac3"));
        installSnapshotReader(snapshot);

        MediaImportService.SourceScanResult result = service.scanSourceLibrary();

        assertThat(result.pendingTranscode()).isEqualTo(1);
        assertThat(result.failed()).isZero();
        assertThat(source).exists();
        verify(probe).probe(source);
    }

    @Test
    void pendingRecordWithoutMediaTypeIsReprobedEvenWhenOtherFormatFieldsExist() throws Exception {
        Path source = sourceDir.resolve("歌手 - missing-media-type.mpg");
        Files.writeString(source, "same-content");
        var snapshot = fileSnapshot(source, null);
        MediaImportRecord previous = importedSnapshotRecord(source, snapshot, null);
        previous.setImportedFlag(false);
        previous.setAction(MediaImportService.PENDING_TRANSCODE);
        previous.setTranscodeRequired(true);
        previous.setMediaType(null);
        previous.setSourceFormat("mpg");
        previous.setVideoCodec("mpeg2video");
        previous.setAudioCodec("ac3");
        when(importRepo.findBySourcePath(source.toString())).thenReturn(Optional.of(previous));
        when(probe.probe(source)).thenReturn(new MediaProbe(1000, 2, 0, true,
                "1920x1080", List.of(), "mpeg2video", "ac3"));
        installSnapshotReader(snapshot);

        MediaImportService.SourceScanResult result = service.scanSourceLibrary();

        assertThat(result.pendingTranscode()).isEqualTo(1);
        assertThat(result.failed()).isZero();
        verify(probe).probe(source);
    }

    @Test
    void pendingRecordWithoutSourceFormatIsReprobedEvenWhenCodecFieldsExist() throws Exception {
        Path source = sourceDir.resolve("歌手 - missing-source-format.mpg");
        Files.writeString(source, "same-content");
        var snapshot = fileSnapshot(source, null);
        MediaImportRecord previous = importedSnapshotRecord(source, snapshot, null);
        previous.setImportedFlag(false);
        previous.setAction(MediaImportService.PENDING_TRANSCODE);
        previous.setTranscodeRequired(true);
        previous.setMediaType(MediaClassifier.KTV_VIDEO);
        previous.setSourceFormat(null);
        previous.setVideoCodec("mpeg2video");
        previous.setAudioCodec("ac3");
        when(importRepo.findBySourcePath(source.toString())).thenReturn(Optional.of(previous));
        when(probe.probe(source)).thenReturn(new MediaProbe(1000, 2, 0, true,
                "1920x1080", List.of(), "mpeg2video", "ac3"));
        installSnapshotReader(snapshot);

        MediaImportService.SourceScanResult result = service.scanSourceLibrary();

        assertThat(result.pendingTranscode()).isEqualTo(1);
        assertThat(result.failed()).isZero();
        verify(probe).probe(source);
    }

    @Test
    void unavailableSnapshotFallsBackToContentHashWithoutReprobingAnImportedSource() throws Exception {
        Path source = sourceDir.resolve("歌手 - unavailable-snapshot.mkv");
        Files.writeString(source, "same-content");
        MediaImportService.SourceSnapshot snapshot = fileSnapshot(source, null);
        MediaImportRecord previous = importedSnapshotRecord(source, snapshot, null);
        when(importRepo.findBySourcePath(source.toString())).thenReturn(Optional.of(previous));
        installSnapshotReader(null);

        MediaImportService.SourceScanResult result = service.scanSourceLibrary();

        assertThat(result.scanned()).isEqualTo(1);
        assertThat(result.failed()).isZero();
        assertThat(source).exists();
        verify(hashService).md5(source);
        verifyNoInteractions(probe, scanService);
    }

    @Test
    void changedSourceSizeWithTheSameMtimeForcesARehashAndRefreshesItsSnapshot() throws Exception {
        Path source = sourceDir.resolve("歌手 - size-changed.mkv");
        Files.writeString(source, "old");
        var oldMtime = Files.getLastModifiedTime(source).toInstant().atOffset(ZoneOffset.UTC);
        oldMtime = oldMtime.withNano((oldMtime.getNano() / 1_000) * 1_000);
        long oldSize = Files.size(source);
        Files.writeString(source, "current-content-longer");
        Files.setLastModifiedTime(source, java.nio.file.attribute.FileTime.from(oldMtime.toInstant()));
        var snapshot = fileSnapshot(source, "same-file-id");
        MediaImportRecord previous = importedSnapshotRecord(source, snapshot, "same-file-id");
        previous.setSourceSize(oldSize);
        previous.setSourceMtime(oldMtime);
        when(importRepo.findBySourcePath(source.toString())).thenReturn(Optional.of(previous));
        installSnapshotReader(snapshot);

        MediaImportService.SourceScanResult result = service.scanSourceLibrary();

        assertThat(result.failed()).isZero();
        assertThat(previous.getSourceSize()).isEqualTo(Files.size(source));
        assertThat(previous.getSourceMtime()).isEqualTo(snapshot.mtime());
        verify(hashService).md5(source);
        verify(importRepo).save(previous);
        verifyNoInteractions(probe, scanService);
    }

    @Test
    void legacyImportedRowsMissingEitherSnapshotFieldAreRehashedAndUpgraded() throws Exception {
        Path missingSizeSource = Files.writeString(sourceDir.resolve("歌手 - 缺少size.mkv"), "same-bytes");
        Path missingMtimeSource = Files.writeString(sourceDir.resolve("歌手 - 缺少time.mkv"), "same-bytes");
        var fixedTime = java.nio.file.attribute.FileTime.from(java.time.Instant.parse("2024-01-02T03:04:05Z"));
        Files.setLastModifiedTime(missingSizeSource, fixedTime);
        Files.setLastModifiedTime(missingMtimeSource, fixedTime);
        var snapshot = fileSnapshot(missingSizeSource, null);
        MediaImportRecord missingSize = importedSnapshotRecord(missingSizeSource, snapshot, null);
        missingSize.setSourceSize(null);
        MediaImportRecord missingMtime = importedSnapshotRecord(missingMtimeSource, snapshot, null);
        missingMtime.setSourceMtime(null);
        when(importRepo.findBySourcePath(missingSizeSource.toString())).thenReturn(Optional.of(missingSize));
        when(importRepo.findBySourcePath(missingMtimeSource.toString())).thenReturn(Optional.of(missingMtime));
        installSnapshotReader(snapshot);

        MediaImportService.SourceScanResult result = service.scanSourceLibrary();

        assertThat(result.scanned()).isEqualTo(2);
        assertThat(result.failed()).isZero();
        assertThat(missingSize.getSourceSize()).isEqualTo(Files.size(missingSizeSource));
        assertThat(missingMtime.getSourceMtime()).isEqualTo(snapshot.mtime());
        assertThat(missingSizeSource).exists();
        assertThat(missingMtimeSource).exists();
        verify(hashService).md5(missingSizeSource);
        verify(hashService).md5(missingMtimeSource);
        verify(importRepo).save(missingSize);
        verify(importRepo).save(missingMtime);
        verifyNoInteractions(probe, scanService);
    }

    @Test
    void importedSourceWithStaleFileIdentityIsRehashedBeforeItsSnapshotIsRefreshed() throws Exception {
        Path source = sourceDir.resolve("歌手 - identity-changed.mkv");
        Files.writeString(source, "same-content");
        BasicFileAttributes attributes = Files.readAttributes(source, BasicFileAttributes.class);
        MediaImportRecord previous = new MediaImportRecord();
        previous.setSourcePath(source.toString());
        previous.setSourceFilename(source.getFileName().toString());
        previous.setSourceMd5(new FileHashService().md5(source));
        previous.setSourceSize(attributes.size());
        previous.setSourceMtime(attributes.lastModifiedTime().toInstant().atOffset(ZoneOffset.UTC));
        previous.setSourceFileIdentity("stale-file-identity");
        previous.setImportedFlag(true);
        when(importRepo.findBySourcePath(source.toString())).thenReturn(Optional.of(previous));

        MediaImportService.SourceScanResult result = service.scanSourceLibrary();

        String currentIdentity = attributes.fileKey() == null ? null : attributes.fileKey().toString();
        assertThat(result.scanned()).isEqualTo(1);
        assertThat(result.copied()).isZero();
        assertThat(result.failed()).isZero();
        assertThat(source).exists();
        assertThat(previous.getSourceFileIdentity()).isEqualTo(currentIdentity);
        verify(hashService).md5(source);
        verify(importRepo).save(previous);
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
        verify(scanService).ingestLibraryFile(any(), eq(source), anyString(), anyString(), eq(false), any(MediaProbe.class));
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
    void scanExplainsUnknownAudioAndVideoCodecsAndKeepsTheSourcePending() throws Exception {
        Path source = sourceDir.resolve("周杰伦 - 未知编码.mkv");
        Files.writeString(source, "unknown-codecs");
        when(probe.probe(source)).thenReturn(new MediaProbe(1000, 2, 0, true,
                "1920x1080", List.of(), "", ""));

        MediaImportService.SourceScanResult result = service.scanSourceLibrary();

        assertThat(result.pendingTranscode()).isEqualTo(1);
        assertThat(result.failed()).isZero();
        assertThat(saved).hasSize(1);
        assertThat(saved.getFirst().getReason()).contains("未识别视频编码", "未识别音频编码");
        assertThat(source).exists();
        assertThat(targetDir.resolve(source.getFileName())).doesNotExist();
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

    @ParameterizedTest
    @ValueSource(strings = {"video", "audio"})
    void rescanReprobesPendingVideoWhenStoredFormatAnalysisIsIncomplete(String missingCodec) throws Exception {
        Path source = sourceDir.resolve("歌手 - 编码待补齐.mpg");
        Files.writeString(source, "same-source-bytes");
        MediaImportRecord pending = new MediaImportRecord();
        pending.setId(31L);
        pending.setSourcePath(source.toString());
        pending.setSourceFilename(source.getFileName().toString());
        pending.setSourceMd5(new FileHashService().md5(source));
        pending.setSourceFormat("mpg");
        pending.setMediaType(MediaClassifier.MV);
        pending.setVideoCodec("video".equals(missingCodec) ? null : "mpeg2video");
        pending.setAudioCodec("audio".equals(missingCodec) ? null : "ac3");
        pending.setAction(MediaImportService.PENDING_TRANSCODE);
        pending.setTranscodeRequired(true);
        when(importRepo.findBySourcePath(source.toString())).thenReturn(Optional.of(pending));
        when(probe.probe(source)).thenReturn(new MediaProbe(1000, 2, 0, true,
                "1920x1080", List.of(), "mpeg2video", "ac3"));

        MediaImportService.SourceScanResult result = service.scanSourceLibrary();

        assertThat(result.scanned()).isEqualTo(1);
        assertThat(result.pendingTranscode()).isEqualTo(1);
        assertThat(result.failed()).isZero();
        assertThat(pending.getVideoCodec()).isEqualTo("mpeg2video");
        assertThat(pending.getAudioCodec()).isEqualTo("ac3");
        assertThat(source).hasContent("same-source-bytes");
        verify(probe).probe(source);
        assertThat(pending.getAction()).isEqualTo(MediaImportService.PENDING_TRANSCODE);
        assertThat(pending.getVideoCodec()).isEqualTo("mpeg2video");
        assertThat(pending.getAudioCodec()).isEqualTo("ac3");
        assertThat(source).hasContent("same-source-bytes");
        assertThat(targetDir.resolve(source.getFileName())).doesNotExist();
        verify(probe).probe(source);
        verify(importRepo).saveAndFlush(pending);
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
        when(scanService.ingestLibraryFile(any(), eq(source), anyString(), anyString(), eq(false), any(MediaProbe.class)))
                .thenThrow(new IllegalStateException("database unavailable"));

        MediaImportService.SourceScanResult result = service.scanSourceLibrary();

        assertThat(result.failed()).isEqualTo(1);
        assertThat(source).exists();
        assertThat(target).doesNotExist();
        assertThat(saved.getLast().getAction()).isEqualTo(MediaImportService.FAILED);
        verify(importRepo, never()).delete(any());
    }

    @Test
    void scanPreservesReplacementSourceAndSidecarWhenMovedTargetsDisappear() throws Exception {
        Path source = Files.writeString(sourceDir.resolve("歌手 - 导入期间替换.mp4"), "original-media");
        Path output = targetDir.resolve(source.getFileName());
        Path sourceLyric = source.resolveSibling("歌手 - 导入期间替换.lrc");
        Path outputLyric = targetDir.resolve(sourceLyric.getFileName());
        Files.writeString(sourceLyric, "original lyrics");
        when(probe.probe(source)).thenReturn(new MediaProbe(1000, 2, 0, true,
                "1920x1080", List.of(), "h264", "aac"));
        when(scanService.ingestLibraryFile(any(Path.class), eq(source), anyString(), anyString(),
                eq(false), any(MediaProbe.class))).thenAnswer(invocation -> {
            Files.delete(outputLyric);
            Files.writeString(sourceLyric, "replacement lyrics");
            Files.writeString(source, "replacement media");
            return new LibraryScanService.IngestResult(false, 1L, 2L);
        });

        MediaImportService.SourceScanResult result = service.scanSourceLibrary();

        assertThat(result.failed()).isEqualTo(1);
        assertThat(source).hasContent("replacement media");
        assertThat(sourceLyric).hasContent("replacement lyrics");
        assertThat(output).hasContent("original-media");
        assertThat(outputLyric).doesNotExist();
        assertThat(saved).singleElement().satisfies(record ->
                assertThat(record.getAction()).isEqualTo(MediaImportService.FAILED));
        assertThat(service.getScanProgress().running()).isFalse();
    }

    @Test
    void scanDoesNotMoveAReplacementDirectoryBackAsMediaAfterIngestFailure() throws Exception {
        Path source = Files.writeString(sourceDir.resolve("歌手 - 目标变成目录.mp4"), "original media bytes");
        Path output = targetDir.resolve(source.getFileName());
        Path concurrentMedia = targetDir.resolve("concurrent-media-held-for-recovery.bin");
        when(probe.probe(source)).thenReturn(new MediaProbe(1000, 2, 0, true,
                "1920x1080", List.of(), "h264", "aac"));
        when(scanService.ingestLibraryFile(any(Path.class), eq(source), anyString(), anyString(),
                eq(false), any(MediaProbe.class))).thenAnswer(invocation -> {
            Files.move(output, concurrentMedia);
            Files.createDirectory(output);
            Files.writeString(output.resolve("marker"), "concurrent directory");
            return new LibraryScanService.IngestResult(false, 1L, 2L);
        });

        MediaImportService.SourceScanResult result = service.scanSourceLibrary();

        assertThat(result.failed()).isEqualTo(1);
        assertThat(source).doesNotExist();
        assertThat(concurrentMedia).hasContent("original media bytes");
        assertThat(output).isDirectory();
        assertThat(output.resolve("marker")).hasContent("concurrent directory");
        assertThat(saved).singleElement().satisfies(record ->
                assertThat(record.getAction()).isEqualTo(MediaImportService.FAILED));
    }

    @Test
    void scanStoresExceptionTypeWhenProbeFailureHasNoMessage() throws Exception {
        Path source = Files.writeString(sourceDir.resolve("歌手 - 无错误信息.mp4"), "synthetic media");
        when(probe.probe(source)).thenThrow(new IllegalStateException((String) null));

        MediaImportService.SourceScanResult result = service.scanSourceLibrary();

        assertThat(result.failed()).isEqualTo(1);
        assertThat(saved).singleElement().satisfies(record -> {
            assertThat(record.getAction()).isEqualTo(MediaImportService.FAILED);
            assertThat(record.getReason()).isEqualTo("IllegalStateException");
        });
        assertThat(source).exists();
        verify(scanService, never()).ingestLibraryFile(any(), any(), anyString(), anyString(), anyBoolean(), any());
    }

    @Test
    void scanKeepsFreshDigestAcrossProbeFailureAndRetriesChangedSource() throws Exception {
        Path source = sourceDir.resolve("歌手 - 探测失败后重试.mp4");
        Files.writeString(source, "old-source-bytes");
        MediaImportService.SourceSnapshot oldSnapshot = fileSnapshot(source, "stable-source-id");
        MediaImportRecord previous = importedSnapshotRecord(source, oldSnapshot, "stable-source-id");
        previous.setImportedFlag(false);
        previous.setAction(MediaImportService.FAILED);
        String oldDigest = previous.getSourceMd5();
        Files.writeString(source, "new-source-bytes-after-change");
        String currentDigest = new FileHashService().md5(source);
        when(importRepo.findBySourcePath(source.toString())).thenReturn(Optional.of(previous));
        when(probe.probe(source))
                .thenThrow(new IllegalStateException("synthetic probe failure"))
                .thenReturn(new MediaProbe(1000, 2, 0, true, "1920x1080", List.of(), "h264", "aac"));
        SongFile libraryFile = new SongFile();
        libraryFile.setId(2L);
        when(songFileRepo.findById(2L)).thenReturn(Optional.of(libraryFile));

        MediaImportService.SourceScanResult failed = service.scanSourceLibrary();

        assertThat(failed.failed()).isEqualTo(1);
        assertThat(previous.getAction()).isEqualTo(MediaImportService.FAILED);
        assertThat(previous.getSourceMd5()).isEqualTo(currentDigest).isNotEqualTo(oldDigest);
        assertThat(source).hasContent("new-source-bytes-after-change");

        MediaImportService.SourceScanResult retried = service.scanSourceLibrary();

        assertThat(retried.copied()).isEqualTo(1);
        assertThat(retried.failed()).isZero();
        assertThat(source).doesNotExist();
        assertThat(targetDir.resolve(source.getFileName())).hasContent("new-source-bytes-after-change");
        verify(scanService).ingestLibraryFile(targetDir.resolve(source.getFileName()), source,
                currentDigest, currentDigest, false, new MediaProbe(1000, 2, 0, true,
                        "1920x1080", List.of(), "h264", "aac"));
    }

    @Test
    void failedHashDoesNotAdvanceTheSnapshotPastTheLastTrustedDigest() throws Exception {
        Path source = sourceDir.resolve("歌手 - 指纹失败保留快照.mp4");
        Files.writeString(source, "previous-source-bytes");
        MediaImportService.SourceSnapshot oldSnapshot = fileSnapshot(source, "trusted-source-id");
        MediaImportRecord previous = importedSnapshotRecord(source, oldSnapshot, "trusted-source-id");
        previous.setImportedFlag(false);
        previous.setCleanupStatus("PENDING");
        String oldDigest = previous.getSourceMd5();
        Files.writeString(source, "replacement-source-with-different-size");
        when(importRepo.findBySourcePath(source.toString())).thenReturn(Optional.of(previous));
        when(hashService.md5(source)).thenThrow(new IllegalStateException("synthetic hash failure"));

        MediaImportService.SourceScanResult result = service.scanSourceLibrary();

        assertThat(result.failed()).isEqualTo(1);
        assertThat(previous.getAction()).isEqualTo(MediaImportService.FAILED);
        assertThat(previous.getSourceMd5()).isEqualTo(oldDigest);
        assertThat(previous.getSourceSize()).isEqualTo(oldSnapshot.size());
        assertThat(previous.getSourceMtime()).isEqualTo(oldSnapshot.mtime());
        assertThat(previous.getSourceFileIdentity()).isEqualTo("trusted-source-id");
        assertThat(previous.getCleanupStatus()).isEqualTo("PENDING");
        assertThat(source).hasContent("replacement-source-with-different-size");
        verifyNoInteractions(probe, scanService);
    }

    @Test
    void directImportRestoresFilesWhenTheIngestResultIsIncomplete() throws Exception {
        List<Path> sources = List.of(
                sourceDir.resolve("歌手 - 入库未创建.mp4"),
                sourceDir.resolve("歌手 - 入库缺少歌曲ID.mp4"),
                sourceDir.resolve("歌手 - 入库缺少文件ID.mp4"));
        for (int index = 0; index < sources.size(); index++) {
            Files.writeString(sources.get(index), "compatible-media-" + index);
        }
        when(probe.probe(any(Path.class))).thenReturn(new MediaProbe(1000, 2, 0, true,
                "1920x1080", List.of(), "h264", "aac"));
        when(scanService.ingestLibraryFile(any(Path.class), any(Path.class), anyString(), anyString(),
                eq(false), any(MediaProbe.class)))
                .thenReturn(new LibraryScanService.IngestResult(false, 1L, 2L),
                        new LibraryScanService.IngestResult(true, null, 2L),
                        new LibraryScanService.IngestResult(true, 1L, null));

        MediaImportService.SourceScanResult result = service.scanSourceLibrary();

        assertThat(result.failed()).isEqualTo(3);
        for (Path source : sources) {
            assertThat(source).exists();
            assertThat(targetDir.resolve(source.getFileName())).doesNotExist();
        }
        assertThat(saved).hasSize(3).allSatisfy(record ->
                assertThat(record.getAction()).isEqualTo(MediaImportService.FAILED));
        verify(scanService, times(3)).ingestLibraryFile(any(Path.class), any(Path.class), anyString(), anyString(),
                eq(false), any(MediaProbe.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"directory", "changed-bytes"})
    void directImportRestoresMovedSidecarWhenTargetChangesDuringMove(String replacement) throws Exception {
        Path source = Files.writeString(sourceDir.resolve("歌手 - 侧文件迁移校验.mp4"), "source-media");
        Path target = targetDir.resolve(source.getFileName());
        Path sourceLyric = source.resolveSibling("歌手 - 侧文件迁移校验.lrc");
        Path targetLyric = targetDir.resolve(sourceLyric.getFileName());
        Files.writeString(sourceLyric, "original lyrics");
        when(probe.probe(source)).thenReturn(new MediaProbe(1000, 2, 0, true,
                "1920x1080", List.of(), "h264", "aac"));

        MediaImportService.SourceScanResult result;
        try (MockedStatic<Files> fileSystem = mockStatic(Files.class,
                org.mockito.Mockito.CALLS_REAL_METHODS)) {
            fileSystem.when(() -> Files.move(sourceLyric, targetLyric)).thenAnswer(invocation -> {
                Path moved = (Path) invocation.callRealMethod();
                if ("directory".equals(replacement)) {
                    Files.delete(targetLyric);
                    Files.createDirectories(targetLyric);
                    Files.writeString(targetLyric.resolve("concurrent-entry"), "preserve this entry");
                } else {
                    Files.writeString(targetLyric, "replacement lyrics with a different size");
                }
                return moved;
            });
            // CALLS_REAL_METHODS executes the move while the static stub is
            // declared; restore the fixture before entering the public scan.
            Files.deleteIfExists(targetLyric);
            Files.writeString(sourceLyric, "original lyrics");
            result = service.scanSourceLibrary();
        }

        assertThat(result.failed()).isEqualTo(1);
        assertThat(source).hasContent("source-media");
        assertThat(target).doesNotExist();
        if ("directory".equals(replacement)) {
            assertThat(sourceLyric).isDirectory();
            assertThat(sourceLyric.resolve("concurrent-entry")).hasContent("preserve this entry");
        } else {
            assertThat(sourceLyric).hasContent("replacement lyrics with a different size");
        }
        assertThat(targetLyric).doesNotExist();
        assertThat(saved).singleElement().satisfies(record ->
                assertThat(record.getAction()).isEqualTo(MediaImportService.FAILED));
        verifyNoInteractions(scanService);
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
    void directImportSkipsContentAlreadyPresentInTheTargetLibrary() throws Exception {
        Path source = sourceDir.resolve("歌手 - 曲库已有相同内容.mp4");
        Files.writeString(source, "already-in-library");
        String sourceMd5 = new FileHashService().md5(source);
        when(probe.probe(source)).thenReturn(new MediaProbe(1000, 1, 0, true,
                "1920x1080", List.of(), "h264", "aac"));
        when(songFileRepo.existsByOutputMd5(sourceMd5)).thenReturn(true);

        MediaImportService.SourceScanResult result = service.scanSourceLibrary();

        assertThat(result.scanned()).isEqualTo(1);
        assertThat(result.skippedOutputDuplicate()).isEqualTo(1);
        assertThat(source).hasContent("already-in-library");
        assertThat(targetDir.resolve(source.getFileName())).doesNotExist();
        assertThat(saved).hasSize(1);
        assertThat(saved.getFirst().getAction()).isEqualTo(MediaImportService.OUTPUT_DUPLICATE);
        assertThat(saved.getFirst().getOutputMd5()).isEqualTo(sourceMd5);
        verifyNoInteractions(scanService);
    }

    @Test
    void directImportChecksManagedRecordsWhenNoLibrarySongFileMatchesOutputHash() throws Exception {
        Path source = sourceDir.resolve("歌手 - 导入记录重复内容.mp4");
        Files.writeString(source, "managed-record-duplicate");
        String sourceMd5 = new FileHashService().md5(source);
        when(probe.probe(source)).thenReturn(new MediaProbe(1000, 1, 0, true,
                "1920x1080", List.of(), "h264", "aac"));
        when(importRepo.existsByOutputMd5AndSourcePathNot(sourceMd5, source.toString())).thenReturn(true);

        MediaImportService.SourceScanResult result = service.scanSourceLibrary();

        assertThat(result.scanned()).isEqualTo(1);
        assertThat(result.skippedOutputDuplicate()).isEqualTo(1);
        assertThat(result.failed()).isZero();
        assertThat(source).hasContent("managed-record-duplicate");
        assertThat(targetDir.resolve(source.getFileName())).doesNotExist();
        assertThat(saved).hasSize(1);
        assertThat(saved.getFirst().getAction()).isEqualTo(MediaImportService.OUTPUT_DUPLICATE);
        assertThat(saved.getFirst().getOutputMd5()).isEqualTo(sourceMd5);
        verify(importRepo).existsByOutputMd5AndSourcePathNot(sourceMd5, source.toString());
        verifyNoInteractions(scanService);
    }

    @Test
    void directImportFailsClosedAfterTheConfiguredTargetNameCandidatesAreExhausted() throws Exception {
        service.shutdown();
        service = new MediaImportService(props, probe, hashService, importRepo, songFileRepo,
                scanService, settingService, mediaTranscoder, 3, 3);
        Path source = sourceDir.resolve("歌手 - 文件名碰撞.mp4");
        Path firstCollision = targetDir.resolve(source.getFileName());
        Path secondCollision = targetDir.resolve("歌手 - 文件名碰撞-2.mp4");
        Files.writeString(source, "source-must-not-be-overwritten");
        Files.writeString(firstCollision, "existing-first-target");
        Files.writeString(secondCollision, "existing-second-target");
        when(probe.probe(source)).thenReturn(new MediaProbe(1000, 1, 0, true,
                "1920x1080", List.of(), "h264", "aac"));

        MediaImportService.SourceScanResult result = service.scanSourceLibrary();

        assertThat(result.scanned()).isEqualTo(1);
        assertThat(result.copied()).isZero();
        assertThat(result.failed()).isEqualTo(1);
        assertThat(source).hasContent("source-must-not-be-overwritten");
        assertThat(firstCollision).hasContent("existing-first-target");
        assertThat(secondCollision).hasContent("existing-second-target");
        assertThat(saved).hasSize(1);
        assertThat(saved.getFirst().getAction()).isEqualTo(MediaImportService.FAILED);
        assertThat(saved.getFirst().getReason()).contains("无法为目标文件分配唯一文件名");
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
        imported.setSourceMd5(new FileHashService().md5(importedSource));
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
    void autoCleanupRejectsWhileATranscodeBatchIsRunning() throws Exception {
        MediaImportRecord record = pendingRecord(85L, "歌手 - 转码期间不清理.mpg");
        CountDownLatch transcodeStarted = new CountDownLatch(1);
        CountDownLatch finishTranscode = new CountDownLatch(1);
        when(importRepo.findByIdIn(List.of(85L))).thenReturn(List.of(record));
        when(importRepo.findById(85L)).thenReturn(Optional.of(record));
        when(probe.probe(any(Path.class))).thenReturn(new MediaProbe(1000, 1, 0, true,
                "1920x1080", List.of(), "mpeg2video", "ac3"));
        when(scanService.ingestLibraryFile(any(Path.class), any(Path.class), anyString(), anyString(), eq(true)))
                .thenReturn(new LibraryScanService.IngestResult(true, 86L, 87L));
        when(mediaTranscoder.transcode(any(), any(), any(), eq(true))).thenAnswer(invocation -> {
            Path output = invocation.getArgument(1);
            transcodeStarted.countDown();
            if (!finishTranscode.await(3, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test did not release transcode");
            }
            Files.writeString(output, "transcoded-output");
            return output;
        });

        service.startPendingTranscode(List.of(85L), false);
        assertThat(transcodeStarted.await(3, TimeUnit.SECONDS)).isTrue();
        try {
            assertThatThrownBy(service::cleanupImportedSources)
                    .isInstanceOf(ApiException.class)
                    .extracting(exception -> ((ApiException) exception).getCode())
                    .isEqualTo("TRANSCODE_ALREADY_RUNNING");
        } finally {
            finishTranscode.countDown();
        }
        awaitFinished();

        assertThat(service.getProgress().transcoded()).isEqualTo(1);
        assertThat(service.getProgress().failed()).isZero();
    }

    @Test
    void cleanupKeepsSourcesWhenImportRecordsAreNotEligibleForDeletion() throws Exception {
        List<MediaImportRecord> records = new ArrayList<>();
        List<Path> sources = new ArrayList<>();
        for (int index = 0; index < 4; index++) {
            Path source = sourceDir.resolve("歌手 - 未满足清理条件" + index + ".mp4");
            Files.writeString(source, "source-" + index);
            sources.add(source);

            MediaImportRecord record = new MediaImportRecord();
            record.setId(40L + index);
            record.setSongId(40L);
            record.setSourcePath(source.toString());
            record.setSourceMd5(new FileHashService().md5(source));
            record.setOutputPath(targetDir.resolve(source.getFileName()).toString());
            record.setSongFileId(50L + index);
            record.setImportedFlag(true);
            switch (index) {
                case 0 -> record.setImportedFlag(false);
                case 1 -> record.setSourceDeleted(true);
                case 2 -> record.setSongFileId(null);
                case 3 -> record.setOutputPath(null);
                default -> throw new AssertionError("unexpected fixture index");
            }
            records.add(record);
        }
        when(importRepo.findBySongId(40L)).thenReturn(records);

        int cleaned = service.cleanupSongSource(40L);

        assertThat(cleaned).isZero();
        assertThat(sources).allSatisfy(source -> assertThat(source).exists());
        assertThat(records).allSatisfy(record -> {
            assertThat(record.isDeleteSourceRequested()).isTrue();
            assertThat(record.getCleanupStatus()).isEqualTo("PENDING");
        });
        verify(importRepo, times(records.size())).save(any(MediaImportRecord.class));
        verifyNoInteractions(songFileRepo);
        verify(hashService, never()).md5(any(Path.class));
    }

    @Test
    void cleanupKeepsSourceWhenTheImportedOutputEscapesTheManagedLibrary() throws Exception {
        Path source = sourceDir.resolve("歌手 - 输出路径越界.mp4");
        Path outsideOutput = temp.resolve("outside-output.mp4");
        Files.writeString(source, "source-content");
        Files.writeString(outsideOutput, "library-output");

        MediaImportRecord imported = new MediaImportRecord();
        imported.setId(47L);
        imported.setSongId(47L);
        imported.setSourcePath(source.toString());
        imported.setSourceMd5(new FileHashService().md5(source));
        imported.setOutputPath(outsideOutput.toString());
        imported.setSongFileId(48L);
        imported.setImportedFlag(true);
        when(importRepo.findBySongId(47L)).thenReturn(List.of(imported));

        int cleaned = service.cleanupSongSource(47L);

        assertThat(cleaned).isZero();
        assertThat(source).hasContent("source-content");
        assertThat(outsideOutput).hasContent("library-output");
        assertThat(imported.isDeleteSourceRequested()).isTrue();
        assertThat(imported.getCleanupStatus()).isEqualTo("PENDING");
        verifyNoInteractions(songFileRepo);
        verify(hashService, never()).md5(any(Path.class));
    }

    @Test
    void cleanupKeepsSourceWhenItsImportedFingerprintIsMissing() throws Exception {
        Path source = sourceDir.resolve("歌手 - 缺少源指纹.mp4");
        Path output = targetDir.resolve(source.getFileName());
        Files.writeString(source, "source-content");
        Files.writeString(output, "library-output");

        MediaImportRecord imported = new MediaImportRecord();
        imported.setId(49L);
        imported.setSongId(49L);
        imported.setSourcePath(source.toString());
        imported.setSourceMd5(" ");
        imported.setOutputPath(output.toString());
        imported.setSongFileId(50L);
        imported.setImportedFlag(true);
        when(importRepo.findBySongId(49L)).thenReturn(List.of(imported));

        int cleaned = service.cleanupSongSource(49L);

        assertThat(cleaned).isZero();
        assertThat(source).hasContent("source-content");
        assertThat(output).hasContent("library-output");
        assertThat(imported.getCleanupStatus()).isEqualTo("PENDING");
        verifyNoInteractions(songFileRepo);
        verify(hashService, never()).md5(any(Path.class));
    }

    @Test
    void cleanupKeepsSourceWhenSourceAndOutputResolveToTheSameFile() throws Exception {
        Path sameFile = sourceDir.resolve("歌手 - 源和输出相同.mp4");
        Files.writeString(sameFile, "source-content");
        props.setKtvLibraryPath(sourceDir.toString());

        MediaImportRecord imported = new MediaImportRecord();
        imported.setId(51L);
        imported.setSongId(51L);
        imported.setSourcePath(sameFile.toString());
        imported.setSourceMd5(new FileHashService().md5(sameFile));
        imported.setOutputPath(sameFile.toString());
        imported.setSongFileId(52L);
        imported.setImportedFlag(true);
        when(importRepo.findBySongId(51L)).thenReturn(List.of(imported));

        int cleaned = service.cleanupSongSource(51L);

        assertThat(cleaned).isZero();
        assertThat(sameFile).hasContent("source-content");
        assertThat(imported.getCleanupStatus()).isEqualTo("PENDING");
        verifyNoInteractions(songFileRepo);
        verify(hashService, never()).md5(any(Path.class));
    }

    @Test
    void cleanupKeepsSourceWhenTheImportedOutputIsEmpty() throws Exception {
        Path source = sourceDir.resolve("歌手 - 空输出文件.mp4");
        Path output = targetDir.resolve(source.getFileName());
        Files.writeString(source, "source-content");
        Files.createFile(output);

        MediaImportRecord imported = new MediaImportRecord();
        imported.setId(53L);
        imported.setSongId(53L);
        imported.setSourcePath(source.toString());
        imported.setSourceMd5(new FileHashService().md5(source));
        imported.setOutputPath(output.toString());
        imported.setSongFileId(54L);
        imported.setImportedFlag(true);
        SongFile libraryFile = new SongFile();
        libraryFile.setId(54L);
        libraryFile.setFilePath(output.toString());
        libraryFile.setValid(true);
        when(importRepo.findBySongId(53L)).thenReturn(List.of(imported));
        when(songFileRepo.findById(54L)).thenReturn(Optional.of(libraryFile));

        int cleaned = service.cleanupSongSource(53L);

        assertThat(cleaned).isZero();
        assertThat(source).hasContent("source-content");
        assertThat(Files.size(output)).isZero();
        assertThat(imported.getCleanupStatus()).isEqualTo("PENDING");
        verify(hashService, never()).md5(eq(output));
        verify(importRepo, never()).delete(imported);
    }

    @Test
    void cleanupKeepsSourceWhenTheOutputSnapshotCannotBeRead() throws Exception {
        Path source = sourceDir.resolve("歌手 - 输出快照不可读.mp4");
        Path output = targetDir.resolve(source.getFileName());
        Files.writeString(source, "source-content");
        Files.writeString(output, "library-output");
        var outputMtime = Files.getLastModifiedTime(output).toInstant().atOffset(ZoneOffset.UTC);

        MediaImportRecord imported = new MediaImportRecord();
        imported.setId(55L);
        imported.setSongId(55L);
        imported.setSourcePath(source.toString());
        imported.setSourceMd5(new FileHashService().md5(source));
        imported.setOutputPath(output.toString());
        imported.setOutputSize(Files.size(output));
        imported.setOutputMtime(outputMtime);
        imported.setSongFileId(56L);
        imported.setImportedFlag(true);
        SongFile libraryFile = new SongFile();
        libraryFile.setId(56L);
        libraryFile.setFilePath(output.toString());
        libraryFile.setValid(true);
        when(importRepo.findBySongId(55L)).thenReturn(List.of(imported));
        when(songFileRepo.findById(56L)).thenReturn(Optional.of(libraryFile));
        MediaImportService snapshotUnavailableService = new MediaImportService(
                props, probe, hashService, importRepo, songFileRepo, scanService, settingService,
                mediaTranscoder, new TagReader(), mock(MediaProbeStage.class), ignored -> null);

        try {
            int cleaned = snapshotUnavailableService.cleanupSongSource(55L);

            assertThat(cleaned).isZero();
            assertThat(source).hasContent("source-content");
            assertThat(output).hasContent("library-output");
            assertThat(imported.getCleanupStatus()).isEqualTo("PENDING");
            verify(hashService, never()).md5(eq(output));
            verify(importRepo, never()).delete(imported);
        } finally {
            snapshotUnavailableService.shutdown();
        }
    }

    @Test
    void cleanupKeepsSourceWhenOutputMtimeChangedWithoutChangingItsSize() throws Exception {
        Path source = sourceDir.resolve("歌手 - 输出时间已变化.mp4");
        Path output = targetDir.resolve(source.getFileName());
        Files.writeString(source, "source-content");
        Files.writeString(output, "stable-output-size");
        var importedMtime = Files.getLastModifiedTime(output).toInstant().atOffset(ZoneOffset.UTC);
        Files.setLastModifiedTime(output, java.nio.file.attribute.FileTime.from(
                importedMtime.toInstant().plusSeconds(10)));

        MediaImportRecord imported = new MediaImportRecord();
        imported.setId(57L);
        imported.setSongId(57L);
        imported.setSourcePath(source.toString());
        imported.setSourceMd5(new FileHashService().md5(source));
        imported.setOutputPath(output.toString());
        imported.setOutputSize(Files.size(output));
        imported.setOutputMtime(importedMtime);
        imported.setSongFileId(58L);
        imported.setImportedFlag(true);
        SongFile libraryFile = new SongFile();
        libraryFile.setId(58L);
        libraryFile.setFilePath(output.toString());
        libraryFile.setValid(true);
        when(importRepo.findBySongId(57L)).thenReturn(List.of(imported));
        when(songFileRepo.findById(58L)).thenReturn(Optional.of(libraryFile));

        int cleaned = service.cleanupSongSource(57L);

        assertThat(cleaned).isZero();
        assertThat(source).hasContent("source-content");
        assertThat(output).hasContent("stable-output-size");
        assertThat(imported.getCleanupStatus()).isEqualTo("PENDING");
        verify(hashService, never()).md5(eq(output));
        verify(importRepo, never()).delete(imported);
    }

    @Test
    void cleanupFallsBackToOutputHashWhenOnlyPartOfItsSnapshotWasStored() throws Exception {
        List<MediaImportRecord> records = new ArrayList<>();
        List<Path> sources = new ArrayList<>();
        List<Path> outputs = new ArrayList<>();
        for (int index = 0; index < 2; index++) {
            Path source = sourceDir.resolve("歌手 - 部分输出快照" + index + ".mp4");
            Path output = targetDir.resolve(source.getFileName());
            Files.writeString(source, "source-content-" + index);
            Files.writeString(output, "library-output-" + index);
            sources.add(source);
            outputs.add(output);

            MediaImportRecord record = new MediaImportRecord();
            record.setId(61L + index);
            record.setSongId(61L);
            record.setSourcePath(source.toString());
            record.setSourceMd5(new FileHashService().md5(source));
            record.setOutputPath(output.toString());
            record.setOutputMd5(new FileHashService().md5(output));
            record.setSongFileId(71L + index);
            record.setImportedFlag(true);
            if (index == 0) {
                record.setOutputSize(Files.size(output));
            } else {
                record.setOutputMtime(Files.getLastModifiedTime(output).toInstant().atOffset(ZoneOffset.UTC));
            }
            SongFile libraryFile = new SongFile();
            libraryFile.setId(71L + index);
            libraryFile.setFilePath(output.toString());
            libraryFile.setValid(true);
            when(songFileRepo.findById(71L + index)).thenReturn(Optional.of(libraryFile));
            when(importRepo.findById(61L + index)).thenReturn(Optional.of(record));
            records.add(record);
        }
        when(importRepo.findBySongId(61L)).thenReturn(records);

        int cleaned = service.cleanupSongSource(61L);

        assertThat(cleaned).isEqualTo(2);
        assertThat(sources).allSatisfy(source -> assertThat(source).doesNotExist());
        assertThat(outputs).allSatisfy(output -> assertThat(output).exists());
        records.forEach(record -> verify(importRepo).delete(record));
    }

    @Test
    void cleanupRecordsOutputHashForLegacyRecordWithBlankDigest() throws Exception {
        Path source = sourceDir.resolve("歌手 - 输出指纹为空白.mp4");
        Path output = targetDir.resolve(source.getFileName());
        Files.writeString(source, "source-content");
        Files.writeString(output, "library-output");

        MediaImportRecord imported = new MediaImportRecord();
        imported.setId(59L);
        imported.setSongId(59L);
        imported.setSourcePath(source.toString());
        imported.setSourceMd5(new FileHashService().md5(source));
        imported.setOutputPath(output.toString());
        imported.setOutputMd5(" ");
        imported.setSongFileId(60L);
        imported.setImportedFlag(true);
        SongFile libraryFile = new SongFile();
        libraryFile.setId(60L);
        libraryFile.setFilePath(output.toString());
        libraryFile.setValid(true);
        when(importRepo.findBySongId(59L)).thenReturn(List.of(imported));
        when(importRepo.findById(59L)).thenReturn(Optional.of(imported));
        when(songFileRepo.findById(60L)).thenReturn(Optional.of(libraryFile));

        int cleaned = service.cleanupSongSource(59L);

        assertThat(cleaned).isEqualTo(1);
        assertThat(source).doesNotExist();
        assertThat(output).hasContent("library-output");
        assertThat(imported.getOutputMd5()).isEqualTo(new FileHashService().md5(output));
        assertThat(libraryFile.isSourceDeleted()).isTrue();
        verify(importRepo).delete(imported);
    }

    @Test
    void songCleanupDeletesOnlyVerifiedSourceAndMatchingSidecar() throws Exception {
        Path source = sourceDir.resolve("歌手 - 单曲清理.mp4");
        Path output = targetDir.resolve(source.getFileName());
        Path sourceLyric = source.resolveSibling("歌手 - 单曲清理.lrc");
        Path outputLyric = output.resolveSibling("歌手 - 单曲清理.lrc");
        Files.writeString(source, "verified-source");
        Files.writeString(output, "library-output");
        Files.writeString(sourceLyric, "[00:01.00]same lyrics");
        Files.writeString(outputLyric, "[00:01.00]same lyrics");
        MediaImportRecord imported = new MediaImportRecord();
        imported.setId(31L);
        imported.setSongId(7L);
        imported.setSourcePath(source.toString());
        imported.setSourceMd5(new FileHashService().md5(source));
        imported.setOutputPath(output.toString());
        imported.setOutputMd5(new FileHashService().md5(output));
        imported.setSongFileId(32L);
        imported.setImportedFlag(true);
        SongFile libraryFile = new SongFile();
        libraryFile.setId(32L);
        libraryFile.setFilePath(output.toString());
        libraryFile.setValid(true);
        when(importRepo.findBySongId(7L)).thenReturn(List.of(imported));
        when(importRepo.findById(31L)).thenReturn(Optional.of(imported));
        when(songFileRepo.findById(32L)).thenReturn(Optional.of(libraryFile));

        int cleaned = service.cleanupSongSource(7L);

        assertThat(cleaned).isEqualTo(1);
        assertThat(source).doesNotExist();
        assertThat(sourceLyric).doesNotExist();
        assertThat(output).exists();
        assertThat(outputLyric).hasContent("[00:01.00]same lyrics");
        assertThat(libraryFile.isSourceDeleted()).isTrue();
        verify(importRepo).save(imported);
        verify(importRepo).delete(imported);
        verify(songFileRepo).save(libraryFile);
    }

    @Test
    void songCleanupKeepsSourceWhenItsContentNoLongerMatchesImportedSnapshot() throws Exception {
        Path source = sourceDir.resolve("歌手 - 内容已变化.mp4");
        Path output = targetDir.resolve(source.getFileName());
        Files.writeString(source, "changed-source");
        Files.writeString(output, "library-output");
        MediaImportRecord imported = new MediaImportRecord();
        imported.setId(33L);
        imported.setSongId(8L);
        imported.setSourcePath(source.toString());
        imported.setSourceMd5("hash-from-before-change");
        imported.setOutputPath(output.toString());
        imported.setSongFileId(34L);
        imported.setImportedFlag(true);
        when(importRepo.findBySongId(8L)).thenReturn(List.of(imported));

        int cleaned = service.cleanupSongSource(8L);

        assertThat(cleaned).isZero();
        assertThat(source).hasContent("changed-source");
        assertThat(imported.isDeleteSourceRequested()).isTrue();
        assertThat(imported.getCleanupStatus()).isEqualTo("PENDING");
        verify(importRepo).save(imported);
        verify(importRepo, never()).delete(imported);
        verifyNoInteractions(songFileRepo);
    }

    @Test
    void songCleanupLeavesSourceWhenImportedRecordDisappearsBeforeDeletion() throws Exception {
        Path source = sourceDir.resolve("歌手 - 记录已消失.mp4");
        Path output = targetDir.resolve(source.getFileName());
        Files.writeString(source, "verified-source");
        Files.writeString(output, "library-output");
        MediaImportRecord imported = new MediaImportRecord();
        imported.setId(35L);
        imported.setSongId(9L);
        imported.setSourcePath(source.toString());
        imported.setSourceMd5(new FileHashService().md5(source));
        imported.setOutputPath(output.toString());
        imported.setOutputMd5(new FileHashService().md5(output));
        imported.setSongFileId(36L);
        imported.setImportedFlag(true);
        SongFile libraryFile = new SongFile();
        libraryFile.setId(36L);
        libraryFile.setFilePath(output.toString());
        libraryFile.setValid(true);
        when(importRepo.findBySongId(9L)).thenReturn(List.of(imported));
        when(importRepo.findById(35L)).thenReturn(Optional.empty());
        when(songFileRepo.findById(36L)).thenReturn(Optional.of(libraryFile));

        int cleaned = service.cleanupSongSource(9L);

        assertThat(cleaned).isZero();
        assertThat(source).hasContent("verified-source");
        assertThat(imported.isDeleteSourceRequested()).isTrue();
        verify(importRepo, never()).delete(imported);
        verify(songFileRepo, never()).save(any());
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
    void singleSourceDeletionCompletesWhenTheSourceIsSafelyRemoved() throws Exception {
        MediaImportRecord record = pendingRecord(13L, "歌手 - 单个删除.mp4");
        Path source = Path.of(record.getSourcePath());
        when(importRepo.findByIdIn(List.of(13L))).thenReturn(List.of(record));

        service.deleteSource(13L);

        assertThat(source).doesNotExist();
        verify(importRepo).delete(record);
    }

    @Test
    void bulkSourceDeletionReportsAlreadyDeletedAndRejectsPathsOutsideSourceRoot() throws Exception {
        MediaImportRecord alreadyDeleted = pendingRecord(60L, "already-deleted.mp4");
        alreadyDeleted.setSourceDeleted(true);
        Path outsideSource = temp.resolve("outside-source.mp4");
        Files.writeString(outsideSource, "outside-source");
        MediaImportRecord outside = pendingRecord(61L, "outside-record.mp4");
        outside.setSourcePath(outsideSource.toString());
        MediaImportRecord deletable = pendingRecord(62L, "deletable.mp4");
        when(importRepo.findByIdIn(List.of(60L, 61L, 62L)))
                .thenReturn(List.of(alreadyDeleted, outside, deletable));

        MediaImportService.DeleteSourcesResult result = service.deleteSources(List.of(60L, 61L, 62L));

        assertThat(result.requested()).isEqualTo(3);
        assertThat(result.deleted()).isEqualTo(1);
        assertThat(result.alreadyDeleted()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(sourceDir.resolve("already-deleted.mp4")).exists();
        assertThat(outsideSource).hasContent("outside-source");
        assertThat(sourceDir.resolve("deletable.mp4")).doesNotExist();
        verify(importRepo).delete(deletable);
        verify(importRepo, never()).delete(alreadyDeleted);
        verify(importRepo, never()).delete(outside);
    }

    @Test
    void bulkSourceDeletionTreatsNullAndEmptySelectionsAsNoOps() {
        MediaImportService.DeleteSourcesResult nullSelection = service.deleteSources(null);
        MediaImportService.DeleteSourcesResult emptySelection = service.deleteSources(List.of());

        assertThat(nullSelection.requested()).isZero();
        assertThat(emptySelection.requested()).isZero();
        verify(importRepo, never()).findByIdIn(any());
    }

    @Test
    void singleSourceDeletionReportsMissingImportRecord() {
        assertThatThrownBy(() -> service.deleteSource(999L))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("源素材记录不存在");

        verify(importRepo).findByIdIn(List.of(999L));
    }

    @Test
    void singleSourceDeletionReportsWhenTheSourcePathCannotBeSafelyDeleted() throws Exception {
        Path outside = temp.resolve("outside-source.mp4");
        Files.writeString(outside, "outside-source");
        MediaImportRecord record = pendingRecord(998L, "safe-placeholder.mp4");
        record.setSourcePath(outside.toString());
        when(importRepo.findByIdIn(List.of(998L))).thenReturn(List.of(record));

        assertThatThrownBy(() -> service.deleteSource(998L))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).getCode())
                .isEqualTo("DELETE_SOURCE_FAILED");

        assertThat(outside).hasContent("outside-source");
        verify(importRepo, never()).delete(record);
    }

    @Test
    void filteredSourceDeletionContinuesAcrossSlicesAndStopsAtTheLastSlice() {
        MediaImportRecord first = pendingRecordWithoutSource(996L);
        first.setSourceDeleted(true);
        MediaImportRecord second = pendingRecordWithoutSource(997L);
        second.setSourceDeleted(true);
        when(importRepo.searchSourceLibraryAfterId(eq(0L), eq(""), isNull(), isNull(), isNull(),
                eq(false), any(Pageable.class)))
                .thenReturn(new SliceImpl<>(List.of(first), PageRequest.of(0, 200), true));
        when(importRepo.searchSourceLibraryAfterId(eq(996L), eq(""), isNull(), isNull(), isNull(),
                eq(false), any(Pageable.class)))
                .thenReturn(new SliceImpl<>(List.of(second), PageRequest.of(0, 200), false));
        when(importRepo.findByIdIn(List.of(996L))).thenReturn(List.of(first));
        when(importRepo.findByIdIn(List.of(997L))).thenReturn(List.of(second));

        MediaImportService.DeleteSourcesResult result =
                service.deleteSourcesByFilter(null, null, null, null);

        assertThat(result.requested()).isEqualTo(2);
        assertThat(result.alreadyDeleted()).isEqualTo(2);
        assertThat(result.deleted()).isZero();
        verify(importRepo).searchSourceLibraryAfterId(eq(0L), eq(""), isNull(), isNull(), isNull(),
                eq(false), any(Pageable.class));
        verify(importRepo).searchSourceLibraryAfterId(eq(996L), eq(""), isNull(), isNull(), isNull(),
                eq(false), any(Pageable.class));
    }

    @Test
    void filteredSourceDeletionStopsWhenASliceCannotAdvanceItsKeyset() {
        MediaImportRecord withoutId = pendingRecordWithoutSource(null);
        when(importRepo.searchSourceLibraryAfterId(eq(0L), eq(""), isNull(), isNull(), isNull(),
                eq(false), any(Pageable.class)))
                .thenReturn(new SliceImpl<>(List.of(withoutId), PageRequest.of(0, 200), true));

        MediaImportService.DeleteSourcesResult result =
                service.deleteSourcesByFilter(null, null, null, null);

        assertThat(result.requested()).isZero();
        verify(importRepo, times(1)).searchSourceLibraryAfterId(eq(0L), eq(""), isNull(), isNull(), isNull(),
                eq(false), any(Pageable.class));
        verify(importRepo, never()).findByIdIn(any());
    }

    @Test
    void sourceLibraryHidesRemovedSourceRecordsByDefault() {
        service.listSourceLibrary("", "", "", null, 0, 20);

        verify(importRepo).searchSourceLibrary(eq(""), isNull(), isNull(), isNull(), eq(false), any());
    }

    @Test
    void sourceLibraryFiltersTranslateEverySupportedStatusAndFormatToken() {
        record FilterCase(String status, String format, Boolean requestedDeleted, String action,
                          Boolean duplicate, Boolean transcode, boolean effectiveDeleted) {}
        List<FilterCase> cases = List.of(
                new FilterCase(null, null, null, null, null, null, false),
                new FilterCase("", "copy", true, null, null, false, true),
                new FilterCase("duplicate", null, null, null, true, null, false),
                new FilterCase("deleted", null, false, null, null, null, true),
                new FilterCase("pending", "transcode", null, MediaImportService.PENDING_TRANSCODE,
                        null, true, false),
                new FilterCase(" pending ", "copy", true, MediaImportService.PENDING_TRANSCODE,
                        null, false, true),
                new FilterCase("copied", "copy", false, MediaImportService.COPIED,
                        null, false, false),
                new FilterCase("transcoded", "other", null, MediaImportService.TRANSCODED,
                        null, null, false),
                new FilterCase("unrecognized", null, null, MediaImportService.UNRECOGNIZED,
                        null, null, false),
                new FilterCase("failed", "transcode", null, MediaImportService.FAILED,
                        null, true, false),
                new FilterCase(" archived ", null, false, "archived", null, null, false));

        for (FilterCase filter : cases) {
            service.listSourceLibrary("  keyword  ", filter.status(), filter.format(),
                    filter.requestedDeleted(), -2, 500);
            verify(importRepo).searchSourceLibrary(eq("keyword"), eq(filter.action()),
                    eq(filter.duplicate()), eq(filter.transcode()), eq(filter.effectiveDeleted()),
                    argThat(page -> page.getPageNumber() == 0 && page.getPageSize() == 200));
        }

        verify(importRepo, times(cases.size())).searchSourceLibrary(anyString(), any(), any(), any(), any(), any());
    }

    @Test
    void startingAnotherTranscodeBatchWhileOneIsRunningIsRejected() throws Exception {
        List<Long> ids = List.of(501L);
        MediaImportRecord record = pendingRecord(501L, "歌手 - 正在转码.mpg");
        CountDownLatch transcodeStarted = new CountDownLatch(1);
        CountDownLatch releaseTranscode = new CountDownLatch(1);
        when(importRepo.findByIdIn(ids)).thenReturn(List.of(record));
        when(importRepo.findById(501L)).thenReturn(Optional.of(record));
        when(probe.probe(any(Path.class))).thenReturn(new MediaProbe(1000, 1, 0, true,
                "1920x1080", List.of(), "mpeg2video", "ac3"));
        when(scanService.ingestLibraryFile(any(Path.class), any(Path.class), anyString(), anyString(), eq(true)))
                .thenReturn(new LibraryScanService.IngestResult(true, 10L, 20L));
        when(mediaTranscoder.transcode(any(), any(), any(), eq(true))).thenAnswer(invocation -> {
            Path output = invocation.getArgument(1);
            transcodeStarted.countDown();
            if (!releaseTranscode.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test did not release the transcode worker");
            }
            Files.writeString(output, "synthetic-transcoded-output");
            return output;
        });

        MediaImportService.TranscodeProgress first = service.startPendingTranscode(ids, false);
        assertThat(first.running()).isTrue();
        try {
            assertThat(transcodeStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> service.startPendingTranscode(ids, false))
                    .isInstanceOfSatisfying(ApiException.class,
                            error -> assertThat(error.getCode()).isEqualTo("TRANSCODE_ALREADY_RUNNING"));
        } finally {
            releaseTranscode.countDown();
        }
        awaitFinished();

        assertThat(service.getProgress().completed()).isEqualTo(1);
        assertThat(service.getProgress().transcoded()).isEqualTo(1);
        assertThat(service.getProgress().failed()).isZero();
        verify(importRepo, times(1)).findByIdIn(ids);
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
        when(scanService.ingestLibraryFile(any(Path.class), any(Path.class), anyString(), anyString(), eq(true)))
                .thenReturn(new LibraryScanService.IngestResult(true, 10L, 20L));
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
        MediaImportService.PriorityResult current = service.prioritizeTranscode(1L);
        assertThat(current.status()).isEqualTo("CURRENT");
        MediaImportService.PriorityResult priority = service.prioritizeTranscode(3L);
        assertThat(priority.status()).isEqualTo("MOVED");
        assertThat(priority.progress().total()).isEqualTo(3);
        assertThat(priority.progress().priorityRecordIds()).containsExactly(3L);
        MediaImportService.PriorityResult alreadyPriority = service.prioritizeTranscode(3L);
        assertThat(alreadyPriority.status()).isEqualTo("ALREADY_PRIORITY");
        assertThat(alreadyPriority.progress().total()).isEqualTo(3);
        releaseFirst.countDown();
        awaitFinished();

        assertThat(executionOrder).containsExactly(
                "歌手 - 第一首.mpg", "歌手 - 第三首.mpg", "歌手 - 第二首.mpg");
        assertThat(service.getProgress().completed()).isEqualTo(3);
        assertThat(service.getProgress().transcoded()).isEqualTo(3);
        assertThat(service.getProgress().failed()).isZero();
    }

    @Test
    void priorityQueuesAnEligibleRecordThatWasNotInTheOriginalSelection() throws Exception {
        List<String> executionOrder = new ArrayList<>();
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        MediaImportRecord first = pendingRecord(11L, "歌手 - 当前任务.mpg");
        MediaImportRecord second = pendingRecord(12L, "歌手 - 原队列.mpg");
        MediaImportRecord latePriority = pendingRecord(13L, "歌手 - 临时插队.mpg");
        Map<Long, MediaImportRecord> records = Map.of(11L, first, 12L, second, 13L, latePriority);
        when(importRepo.findByIdIn(List.of(11L, 12L))).thenReturn(List.of(first, second));
        when(importRepo.findById(anyLong())).thenAnswer(invocation ->
                Optional.ofNullable(records.get(invocation.getArgument(0))));
        when(probe.probe(any())).thenReturn(new MediaProbe(1000, 1, 0, true, "1920x1080",
                List.of(), "mpeg2video", "ac3"));
        when(scanService.ingestLibraryFile(any(Path.class), any(Path.class), anyString(), anyString(), eq(true)))
                .thenReturn(new LibraryScanService.IngestResult(true, 10L, 20L));
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

        service.startPendingTranscode(List.of(11L, 12L), false);
        assertThat(firstStarted.await(5, TimeUnit.SECONDS)).isTrue();
        try {
            MediaImportService.PriorityResult result = service.prioritizeTranscode(13L);

            assertThat(result.status()).isEqualTo("QUEUED");
            assertThat(result.progress().total()).isEqualTo(3);
            assertThat(result.progress().priorityRecordIds()).containsExactly(13L);
        } finally {
            releaseFirst.countDown();
        }
        awaitFinished();

        assertThat(executionOrder).containsExactly(
                "歌手 - 当前任务.mpg", "歌手 - 临时插队.mpg", "歌手 - 原队列.mpg");
        assertThat(service.getProgress().completed()).isEqualTo(3);
        assertThat(service.getProgress().failed()).isZero();
    }

    @Test
    void latePriorityRequestsCannotGrowTheRunningQueuePastItsLimit() throws Exception {
        service.shutdown();
        service = new MediaImportService(props, probe, hashService, importRepo, songFileRepo,
                scanService, settingService, mediaTranscoder, 3);

        List<MediaImportRecord> records = List.of(
                pendingRecord(1L, "歌手 - 当前任务.mpg"),
                pendingRecord(2L, "歌手 - 原队列一.mpg"),
                pendingRecord(3L, "歌手 - 原队列二.mpg"),
                pendingRecord(4L, "歌手 - 插队任务.mpg"),
                pendingRecord(5L, "歌手 - 超限任务.mpg"));
        List<Long> selectedIds = List.of(1L, 2L, 3L);
        Map<Long, MediaImportRecord> recordsById = records.stream()
                .collect(java.util.stream.Collectors.toMap(MediaImportRecord::getId, record -> record));
        when(importRepo.findByIdIn(selectedIds)).thenReturn(records.subList(0, 3));
        when(importRepo.findById(anyLong())).thenAnswer(invocation ->
                Optional.ofNullable(recordsById.get(invocation.getArgument(0))));
        Path firstSource = sourceDir.resolve(records.get(0).getSourceFilename());
        CountDownLatch firstSourceHashStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstSourceHash = new CountDownLatch(1);
        doAnswer(invocation -> {
            Path source = invocation.getArgument(0);
            if (source.getFileName().equals(firstSource.getFileName())) {
                firstSourceHashStarted.countDown();
                if (!releaseFirstSourceHash.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test did not release the first source hash");
                }
            }
            return new FileHashService().md5(source);
        }).when(hashService).md5(any(Path.class));
        when(probe.probe(any(Path.class))).thenReturn(new MediaProbe(1000, 1, 0, true,
                "1920x1080", List.of(), "mpeg2video", "ac3"));
        when(mediaTranscoder.transcode(any(), any(), any(), eq(true))).thenAnswer(invocation -> {
            Path output = invocation.getArgument(1);
            Files.writeString(output, "synthetic-transcoded-media");
            return output;
        });
        when(scanService.ingestLibraryFile(any(Path.class), any(Path.class), anyString(), anyString(), eq(true)))
                .thenReturn(new LibraryScanService.IngestResult(true, 10L, 20L));

        MediaImportService.TranscodeProgress started = service.startPendingTranscode(selectedIds, false);
        assertThat(started.total()).isEqualTo(3);
        try {
            assertThat(firstSourceHashStarted.await(5, TimeUnit.SECONDS))
                    .as("progress before prioritization=%s", service.getProgress())
                    .isTrue();
            MediaImportRecord firstLatePriority = records.get(3);
            MediaImportRecord overflowPriority = records.get(4);
            MediaImportService.PriorityResult queued = service.prioritizeTranscode(firstLatePriority.getId());
            assertThat(queued.status()).isEqualTo("QUEUED");
            assertThat(queued.progress().total()).isEqualTo(4);
            assertThat(queued.progress().priorityRecordIds()).containsExactly(firstLatePriority.getId());

            assertThatThrownBy(() -> service.prioritizeTranscode(overflowPriority.getId()))
                    .isInstanceOfSatisfying(ApiException.class,
                            failure -> assertThat(failure.getCode()).isEqualTo("TRANSCODE_QUEUE_LIMIT"));
            assertThat(service.getProgress().priorityRecordIds()).containsExactly(firstLatePriority.getId());
            assertThat(service.getProgress().total()).isEqualTo(4);
            assertThat(overflowPriority.getAction()).isEqualTo(MediaImportService.PENDING_TRANSCODE);
        } finally {
            releaseFirstSourceHash.countDown();
        }
        awaitFinished();

        assertThat(service.getProgress().running()).isFalse();
        assertThat(service.getProgress().completed()).isEqualTo(4);
        assertThat(service.getProgress().transcoded()).isEqualTo(4);
        assertThat(service.getProgress().failed()).isZero();
        assertThat(service.getProgress().priorityRecordIds()).isEmpty();
        records.subList(0, 4).forEach(record -> {
            Path source = Path.of(record.getSourcePath());
            Path output = Path.of(record.getOutputPath());
            assertThat(record.getAction()).isEqualTo(MediaImportService.TRANSCODED);
            assertThat(source).hasContent("source-" + record.getId());
            assertThat(output).hasContent("synthetic-transcoded-media");
        });
        assertThat(records.get(4).getAction()).isEqualTo(MediaImportService.PENDING_TRANSCODE);
        assertThat(records.get(4).getOutputPath()).isNull();
    }

    @Test
    void prioritizationRejectsMissingIneligibleAndNotRunningRequests() {
        ApiException noSelection = org.junit.jupiter.api.Assertions.assertThrows(ApiException.class,
                () -> service.prioritizeTranscode(null));
        assertThat(noSelection.getCode()).isEqualTo("TRANSCODE_SELECTION_REQUIRED");

        when(importRepo.findById(80L)).thenReturn(Optional.empty());
        ApiException missing = org.junit.jupiter.api.Assertions.assertThrows(ApiException.class,
                () -> service.prioritizeTranscode(80L));
        assertThat(missing.getCode()).isEqualTo("IMPORT_RECORD_NOT_FOUND");

        MediaImportRecord copied = new MediaImportRecord();
        copied.setId(81L);
        copied.setAction(MediaImportService.COPIED);
        when(importRepo.findById(81L)).thenReturn(Optional.of(copied));
        ApiException ineligible = org.junit.jupiter.api.Assertions.assertThrows(ApiException.class,
                () -> service.prioritizeTranscode(81L));
        assertThat(ineligible.getCode()).isEqualTo("SOURCE_NOT_TRANSCODABLE");

        MediaImportRecord pending = transcodableRecord(82L);
        when(importRepo.findById(82L)).thenReturn(Optional.of(pending));
        ApiException notRunning = org.junit.jupiter.api.Assertions.assertThrows(ApiException.class,
                () -> service.prioritizeTranscode(82L));
        assertThat(notRunning.getCode()).isEqualTo("TRANSCODE_NOT_RUNNING");

        MediaImportRecord failed = transcodableRecord(83L);
        failed.setAction(MediaImportService.FAILED);
        when(importRepo.findById(83L)).thenReturn(Optional.of(failed));
        ApiException failedRetryNotRunning = org.junit.jupiter.api.Assertions.assertThrows(ApiException.class,
                () -> service.prioritizeTranscode(83L));
        assertThat(failedRetryNotRunning.getCode()).isEqualTo("TRANSCODE_NOT_RUNNING");
    }

    @Test
    void sourceScanSchedulerReturnsCurrentProgressInsteadOfStartingADuplicateScan() throws Exception {
        Path source = sourceDir.resolve("歌手 - 后台扫描导入.mp4");
        Files.writeString(source, "background-scan");
        CountDownLatch probeEntered = new CountDownLatch(1);
        CountDownLatch releaseProbe = new CountDownLatch(1);
        when(probe.probe(source)).thenAnswer(invocation -> {
            probeEntered.countDown();
            if (!releaseProbe.await(3, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test did not release the source scan");
            }
            return new MediaProbe(1000, 1, 0, true, "1920x1080", List.of(), "h264", "aac");
        });

        try {
            MediaImportService.SourceScanProgress started = service.startSourceScan();
            assertThat(started.running()).isTrue();
            assertThat(probeEntered.await(2, TimeUnit.SECONDS)).isTrue();
            MediaImportService.SourceScanProgress duplicate = service.startSourceScan();

            assertThat(duplicate.running()).isTrue();
            assertThat(duplicate.startedAt()).isNotNull();
        } finally {
            releaseProbe.countDown();
        }

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (service.getScanProgress().running() && System.nanoTime() < deadline) Thread.sleep(10);
        assertThat(service.getScanProgress().running()).isFalse();
        assertThat(service.getScanProgress().total()).isEqualTo(1);
        assertThat(service.getScanProgress().completed()).isEqualTo(1);
        assertThat(service.getScanProgress().finishedAt()).isNotNull();
        verify(probe, times(1)).probe(source);
    }

    @Test
    void schedulerReturnsAnAlreadyRunningSynchronousScanWithoutStartingAnother() throws Exception {
        Path source = sourceDir.resolve("歌手 - 同步扫描导入.mp4");
        Files.writeString(source, "synchronous-scan");
        CountDownLatch probeEntered = new CountDownLatch(1);
        CountDownLatch releaseProbe = new CountDownLatch(1);
        when(probe.probe(source)).thenAnswer(invocation -> {
            probeEntered.countDown();
            if (!releaseProbe.await(3, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test did not release the synchronous source scan");
            }
            return new MediaProbe(1000, 1, 0, true, "1920x1080", List.of(), "h264", "aac");
        });
        var scanExecutor = Executors.newSingleThreadExecutor();
        try {
            var scanFuture = scanExecutor.submit(service::scanSourceLibrary);
            assertThat(probeEntered.await(3, TimeUnit.SECONDS)).isTrue();

            MediaImportService.SourceScanProgress existing = service.startSourceScan();

            assertThat(existing.running()).isTrue();
            assertThat(existing.total()).isEqualTo(1);
            assertThat(existing.currentFile()).isEqualTo(source.getFileName().toString());
            releaseProbe.countDown();
            assertThat(scanFuture.get(5, TimeUnit.SECONDS).copied()).isEqualTo(1);
        } finally {
            releaseProbe.countDown();
            scanExecutor.shutdownNow();
        }

        assertThat(service.getScanProgress().running()).isFalse();
        assertThat(service.getScanProgress().completed()).isEqualTo(1);
        verify(probe, times(1)).probe(source);
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
    void transcodeQueueFailsMissingSourcesAndSkipsSourcesChangedAfterScan() throws Exception {
        MediaImportRecord missing = pendingRecord(70L, "歌手 - 源文件已消失.mpg");
        Files.delete(Path.of(missing.getSourcePath()));
        MediaImportRecord changed = pendingRecord(71L, "歌手 - 扫描后变化.mpg");
        Files.writeString(Path.of(changed.getSourcePath()), "changed-after-scan");
        Map<Long, MediaImportRecord> records = Map.of(70L, missing, 71L, changed);
        when(importRepo.findByIdIn(List.of(70L, 71L))).thenReturn(List.of(missing, changed));
        when(importRepo.findById(anyLong())).thenAnswer(invocation ->
                Optional.ofNullable(records.get(invocation.getArgument(0))));

        service.startPendingTranscode(List.of(70L, 71L), false);
        awaitFinished();

        assertThat(missing.getAction()).isEqualTo(MediaImportService.FAILED);
        assertThat(missing.getReason()).isNotBlank();
        assertThat(changed.getAction()).isEqualTo(MediaImportService.SOURCE_DUPLICATE);
        assertThat(changed.isDuplicateFlag()).isTrue();
        assertThat(service.getProgress().failed()).isEqualTo(1);
        assertThat(service.getProgress().skippedSourceDuplicate()).isEqualTo(1);
        verifyNoInteractions(probe, mediaTranscoder, scanService);
        verify(importRepo, times(2)).save(missing);
        verify(importRepo, times(2)).save(changed);
    }

    @Test
    void transcodeQueueFailsIfAQueuedSourceWasDeletedAndSkipsKnownDuplicateContent() throws Exception {
        MediaImportRecord queuedRecord = pendingRecord(75L, "歌手 - 排队后删除.mpg");
        MediaImportRecord deletedRecord = pendingRecordWithoutSource(75L);
        deletedRecord.setSourceDeleted(true);
        MediaImportRecord duplicate = pendingRecord(76L, "歌手 - 已知重复内容.mpg");
        when(importRepo.findByIdIn(List.of(75L, 76L))).thenReturn(List.of(queuedRecord, duplicate));
        when(importRepo.findById(75L)).thenReturn(Optional.of(deletedRecord));
        when(importRepo.findById(76L)).thenReturn(Optional.of(duplicate));
        when(songFileRepo.existsBySourceMd5(duplicate.getSourceMd5())).thenReturn(true);

        service.startPendingTranscode(List.of(75L, 76L), false);
        awaitFinished();

        assertThat(deletedRecord.getAction()).isEqualTo(MediaImportService.FAILED);
        assertThat(deletedRecord.getReason()).contains("源文件不存在");
        assertThat(duplicate.getAction()).isEqualTo(MediaImportService.SOURCE_DUPLICATE);
        assertThat(duplicate.isDuplicateFlag()).isTrue();
        assertThat(service.getProgress().failed()).isEqualTo(1);
        assertThat(service.getProgress().skippedSourceDuplicate()).isEqualTo(1);
        verifyNoInteractions(probe, mediaTranscoder, scanService);
    }

    @Test
    void transcodeQueueDeletesOutputWhenTheConvertedContentAlreadyExists() throws Exception {
        MediaImportRecord record = pendingRecord(72L, "歌手 - 转码后重复.mpg");
        when(importRepo.findByIdIn(List.of(72L))).thenReturn(List.of(record));
        when(importRepo.findById(72L)).thenReturn(Optional.of(record));
        when(probe.probe(any(Path.class))).thenReturn(new MediaProbe(1000, 1, 0, true,
                "1920x1080", List.of(), "mpeg2video", "ac3"));
        when(songFileRepo.existsByOutputMd5(anyString())).thenReturn(true);
        AtomicReference<Path> outputPath = new AtomicReference<>();
        when(mediaTranscoder.transcode(any(), any(), any(), eq(true))).thenAnswer(invocation -> {
            Path output = invocation.getArgument(1);
            outputPath.set(output);
            Files.writeString(output, "duplicate-output");
            return output;
        });

        service.startPendingTranscode(List.of(72L), false);
        awaitFinished();

        assertThat(record.getAction()).isEqualTo(MediaImportService.OUTPUT_DUPLICATE);
        assertThat(record.isDuplicateFlag()).isTrue();
        assertThat(outputPath.get()).doesNotExist();
        assertThat(service.getProgress().skippedOutputDuplicate()).isEqualTo(1);
        verify(scanService, never()).ingestLibraryFile(any(), any(), anyString(), anyString(), anyBoolean());
        verify(importRepo, times(2)).save(record);
    }

    @Test
    void successfulTranscodeDeletesSourceOnlyWhenRequestedAndOutputIsVerified() throws Exception {
        MediaImportRecord record = pendingRecord(74L, "歌手 - 转码后清理源文件.mpg");
        Path source = Path.of(record.getSourcePath()).toRealPath();
        record.setSourcePath(source.toString());
        record.setSourceFilename(source.getFileName().toString());
        when(settingService.getAll()).thenReturn(Map.of(SettingService.DELETE_SOURCE_AFTER_TRANSCODE, true));
        when(importRepo.findByIdIn(List.of(74L))).thenReturn(List.of(record));
        when(importRepo.findById(74L)).thenReturn(Optional.of(record));
        when(importRepo.findBySourcePath(source.toString())).thenReturn(Optional.of(record));
        when(probe.probe(any(Path.class))).thenReturn(new MediaProbe(1000, 1, 0, true,
                "1920x1080", List.of(), "mpeg2video", "ac3"));
        when(scanService.ingestLibraryFile(any(Path.class), any(Path.class), anyString(), anyString(), eq(true)))
                .thenReturn(new LibraryScanService.IngestResult(true, 75L, 76L));
        AtomicReference<Path> outputPath = new AtomicReference<>();
        when(mediaTranscoder.transcode(any(), any(), any(), eq(true))).thenAnswer(invocation -> {
            Path output = invocation.getArgument(1);
            outputPath.set(output);
            Files.writeString(output, "verified-transcode-output");
            return output;
        });
        SongFile libraryFile = new SongFile();
        libraryFile.setId(76L);
        libraryFile.setValid(true);
        when(songFileRepo.findById(76L)).thenAnswer(invocation -> {
            libraryFile.setFilePath(outputPath.get().toString());
            return Optional.of(libraryFile);
        });

        service.startPendingTranscode(List.of(74L), false);
        awaitFinished();

        assertThat(service.getProgress().transcoded()).as(record.getReason()).isEqualTo(1);
        assertThat(service.getProgress().failed()).isZero();
        assertThat(record.getAction()).isEqualTo(MediaImportService.TRANSCODED);
        assertThat(source).doesNotExist();
        assertThat(outputPath.get()).exists();
        assertThat(libraryFile.isSourceDeleted()).isTrue();
        verify(importRepo).delete(record);
        verify(songFileRepo).save(libraryFile);
    }

    @Test
    void transcodeQueueRollsBackGeneratedOutputWhenCompanionTargetAlreadyExists() throws Exception {
        String filename = "歌手 - 伴随目标冲突.mpg";
        MediaImportRecord record = pendingRecord(73L, filename);
        Path source = Path.of(record.getSourcePath());
        Path sourceLyric = source.resolveSibling("歌手 - 伴随目标冲突.lrc");
        Files.writeString(sourceLyric, "source lyric");
        when(importRepo.findByIdIn(List.of(73L))).thenReturn(List.of(record));
        when(importRepo.findById(73L)).thenReturn(Optional.of(record));
        when(probe.probe(any(Path.class))).thenReturn(new MediaProbe(1000, 1, 0, true,
                "1920x1080", List.of(), "mpeg2video", "ac3"));
        AtomicReference<Path> outputPath = new AtomicReference<>();
        AtomicReference<Path> existingTargetLyric = new AtomicReference<>();
        when(mediaTranscoder.transcode(any(), any(), any(), eq(true))).thenAnswer(invocation -> {
            Path output = invocation.getArgument(1);
            outputPath.set(output);
            Files.writeString(output, "transcoded-output");
            Path targetLyric = output.resolveSibling("歌手 - 伴随目标冲突.lrc");
            existingTargetLyric.set(targetLyric);
            Files.writeString(targetLyric, "existing target lyric");
            return output;
        });

        service.startPendingTranscode(List.of(73L), false);
        awaitFinished();

        assertThat(record.getAction()).isEqualTo(MediaImportService.FAILED);
        assertThat(source).exists();
        assertThat(sourceLyric).hasContent("source lyric");
        assertThat(outputPath.get()).doesNotExist();
        assertThat(existingTargetLyric.get()).hasContent("existing target lyric");
        assertThat(service.getProgress().failed()).isEqualTo(1);
        verify(scanService, never()).ingestLibraryFile(any(), any(), anyString(), anyString(), anyBoolean());
    }

    @Test
    void transcodeQueueRollsBackEarlierCopiedSidecarsWhenALaterTargetAlreadyExists() throws Exception {
        String filename = "歌手 - 部分伴随迁移.mpg";
        MediaImportRecord record = pendingRecord(77L, filename);
        Path source = Path.of(record.getSourcePath());
        Path sourceLyric = source.resolveSibling("歌手 - 部分伴随迁移.lrc");
        Path sourceCover = source.resolveSibling("歌手 - 部分伴随迁移.jpg");
        Files.writeString(sourceLyric, "source lyric");
        Files.writeString(sourceCover, "source cover");
        when(importRepo.findByIdIn(List.of(77L))).thenReturn(List.of(record));
        when(importRepo.findById(77L)).thenReturn(Optional.of(record));
        when(probe.probe(any(Path.class))).thenReturn(new MediaProbe(1000, 1, 0, true,
                "1920x1080", List.of(), "mpeg2video", "ac3"));
        AtomicReference<Path> outputPath = new AtomicReference<>();
        AtomicReference<Path> copiedLyric = new AtomicReference<>();
        AtomicReference<Path> existingTargetCover = new AtomicReference<>();
        when(mediaTranscoder.transcode(any(), any(), any(), eq(true))).thenAnswer(invocation -> {
            Path output = invocation.getArgument(1);
            outputPath.set(output);
            Files.writeString(output, "transcoded-output");
            Path lyric = output.resolveSibling("歌手 - 部分伴随迁移.lrc");
            Path cover = output.resolveSibling("歌手 - 部分伴随迁移.jpg");
            copiedLyric.set(lyric);
            existingTargetCover.set(cover);
            Files.writeString(cover, "existing target cover");
            return output;
        });

        service.startPendingTranscode(List.of(77L), false);
        awaitFinished();

        assertThat(record.getAction()).isEqualTo(MediaImportService.FAILED);
        assertThat(source).exists();
        assertThat(sourceLyric).hasContent("source lyric");
        assertThat(sourceCover).hasContent("source cover");
        assertThat(outputPath.get()).doesNotExist();
        assertThat(copiedLyric.get()).doesNotExist();
        assertThat(existingTargetCover.get()).hasContent("existing target cover");
        assertThat(service.getProgress().failed()).isEqualTo(1);
        verify(scanService, never()).ingestLibraryFile(any(), any(), anyString(), anyString(), anyBoolean());
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
    void allPendingTranscodeProcessesEligibleRecordsAcrossKeysetPages() throws Exception {
        MediaImportRecord first = pendingRecord(601L, "歌手 - 全量第一首.mpg");
        MediaImportRecord second = pendingRecord(602L, "歌手 - 全量第二首.mpg");
        Map<Long, MediaImportRecord> records = Map.of(601L, first, 602L, second);
        List<String> transcodedFiles = new ArrayList<>();
        when(importRepo.findByIdGreaterThanOrderByIdAsc(anyLong(), any(Pageable.class)))
                .thenAnswer(invocation -> {
                    long afterId = invocation.getArgument(0);
                    if (afterId == 0L) {
                        return new PageImpl<>(List.of(first), PageRequest.of(0, 200), 201);
                    }
                    return new PageImpl<>(List.of(second), PageRequest.of(0, 200), 2);
                });
        when(importRepo.findById(anyLong())).thenAnswer(invocation ->
                Optional.ofNullable(records.get(invocation.getArgument(0))));
        when(probe.probe(any(Path.class))).thenReturn(new MediaProbe(1000, 1, 0, true,
                "1920x1080", List.of(), "mpeg2video", "ac3"));
        when(scanService.ingestLibraryFile(any(Path.class), any(Path.class), anyString(), anyString(), eq(true)))
                .thenReturn(new LibraryScanService.IngestResult(true, 10L, 20L));
        when(mediaTranscoder.transcode(any(), any(), any(), eq(true))).thenAnswer(invocation -> {
            Path source = invocation.getArgument(0);
            Path output = invocation.getArgument(1);
            transcodedFiles.add(source.getFileName().toString());
            Files.writeString(output, "synthetic-output-" + source.getFileName());
            return output;
        });

        MediaImportService.TranscodeProgress initial = service.startPendingTranscode(null, true);
        awaitFinished();

        assertThat(initial.running()).isTrue();
        assertThat(initial.total()).isEqualTo(2);
        assertThat(transcodedFiles).containsExactly(first.getSourceFilename(), second.getSourceFilename());
        assertThat(service.getProgress().completed()).isEqualTo(2);
        assertThat(service.getProgress().transcoded()).isEqualTo(2);
        assertThat(service.getProgress().failed()).isZero();
        verify(importRepo).findByIdGreaterThanOrderByIdAsc(eq(0L), any(Pageable.class));
        verify(importRepo).findByIdGreaterThanOrderByIdAsc(eq(601L), any(Pageable.class));
    }

    @Test
    void allPendingTranscodeReturnsIdleForNullAndEmptyRepositoryPages() {
        when(importRepo.findByIdGreaterThanOrderByIdAsc(eq(0L), any(Pageable.class)))
                .thenReturn(null)
                .thenReturn(new PageImpl<>(List.of()));

        for (int attempt = 0; attempt < 2; attempt++) {
            MediaImportService.TranscodeProgress result = service.startPendingTranscode(null, true);

            assertThat(result.running()).isFalse();
            assertThat(result.total()).isZero();
            assertThat(result.completed()).isZero();
            assertThat(result.finishedAt()).isNotNull();
        }

        verify(importRepo, times(2)).findByIdGreaterThanOrderByIdAsc(eq(0L), any(Pageable.class));
        verify(importRepo, never()).save(any(MediaImportRecord.class));
    }

    @Test
    void allPendingTranscodeStopsWhenARepositoryPageCannotAdvance() {
        MediaImportRecord nonTranscodable = new MediaImportRecord();
        nonTranscodable.setId(1L);
        when(importRepo.findByIdGreaterThanOrderByIdAsc(eq(0L), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(nonTranscodable), PageRequest.of(0, 200), 201));
        when(importRepo.findByIdGreaterThanOrderByIdAsc(eq(1L), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(nonTranscodable), PageRequest.of(0, 200), 201));

        MediaImportService.TranscodeProgress result = service.startPendingTranscode(null, true);

        assertThat(result.running()).isFalse();
        assertThat(result.total()).isZero();
        verify(importRepo).findByIdGreaterThanOrderByIdAsc(eq(0L), any(Pageable.class));
        verify(importRepo).findByIdGreaterThanOrderByIdAsc(eq(1L), any(Pageable.class));
        verify(importRepo, never()).save(any(MediaImportRecord.class));
    }

    @Test
    void selectedTranscodeReturnsIdleWhenRepositoryReturnsNoList() {
        when(importRepo.findByIdIn(List.of(91L))).thenReturn(null);

        MediaImportService.TranscodeProgress result = service.startPendingTranscode(List.of(91L), false);

        assertThat(result.running()).isFalse();
        assertThat(result.total()).isZero();
        verify(importRepo).findByIdIn(List.of(91L));
        verify(importRepo, never()).save(any(MediaImportRecord.class));
    }

    @Test
    void selectedTranscodeRequiresASelectionBeforeReadingSettingsOrRecords() {
        assertThatThrownBy(() -> service.startPendingTranscode(null, false))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.getCode()).isEqualTo("TRANSCODE_SELECTION_REQUIRED"));
        assertThatThrownBy(() -> service.startPendingTranscode(List.of(), false))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.getCode()).isEqualTo("TRANSCODE_SELECTION_REQUIRED"));

        verifyNoInteractions(importRepo, settingService);
    }

    @Test
    void selectedTranscodeSkipsCompletedNonRequiredAndAlreadyDeletedSources() {
        MediaImportRecord nonRequired = transcodableRecord(101L);
        nonRequired.setTranscodeRequired(false);
        MediaImportRecord completed = transcodableRecord(102L);
        completed.setAction(MediaImportService.COPIED);
        MediaImportRecord deleted = transcodableRecord(103L);
        deleted.setSourceDeleted(true);
        List<Long> ids = List.of(101L, 102L, 103L);
        when(importRepo.findByIdIn(ids)).thenReturn(List.of(nonRequired, completed, deleted));

        MediaImportService.TranscodeProgress result = service.startPendingTranscode(ids, false);

        assertThat(result.running()).isFalse();
        assertThat(result.total()).isZero();
        verify(importRepo, never()).save(any(MediaImportRecord.class));
        verifyNoInteractions(probe, mediaTranscoder, scanService);
    }

    @Test
    void selectedTranscodeDeduplicatesIdsAndIgnoresNullEntries() {
        List<Long> uniqueIds = List.of(111L, 112L);
        List<Long> requestedIds = new ArrayList<>();
        requestedIds.add(null);
        requestedIds.add(111L);
        requestedIds.add(111L);
        requestedIds.add(112L);
        when(importRepo.findByIdIn(uniqueIds)).thenReturn(List.of());

        MediaImportService.TranscodeProgress result = service.startPendingTranscode(requestedIds, false);

        assertThat(result.running()).isFalse();
        assertThat(result.total()).isZero();
        verify(importRepo).findByIdIn(uniqueIds);
        verifyNoInteractions(probe, mediaTranscoder, scanService);
    }

    @Test
    void selectedTranscodeReturnsIdleWhenEverySelectedIdIsNull() {
        List<Long> ids = new ArrayList<>();
        ids.add(null);
        ids.add(null);

        MediaImportService.TranscodeProgress result = service.startPendingTranscode(ids, false);

        assertThat(result.running()).isFalse();
        assertThat(result.total()).isZero();
        verify(importRepo, never()).findByIdIn(any());
        verifyNoInteractions(probe, mediaTranscoder, scanService);
    }

    @Test
    void selectedTranscodeIgnoresNullRepositoryRowsAndRecordsWithoutIdentity() {
        List<Long> ids = List.of(114L, 115L);
        List<MediaImportRecord> repositoryRows = new ArrayList<>();
        repositoryRows.add(null);
        repositoryRows.add(new MediaImportRecord());
        when(importRepo.findByIdIn(ids)).thenReturn(repositoryRows);

        MediaImportService.TranscodeProgress result = service.startPendingTranscode(ids, false);

        assertThat(result.running()).isFalse();
        assertThat(result.total()).isZero();
        verify(importRepo).findByIdIn(ids);
        verify(importRepo, never()).save(any(MediaImportRecord.class));
        verifyNoInteractions(probe, mediaTranscoder, scanService);
    }

    @Test
    void queuedTranscodeCompletesAsFailedWhenTheRecordDisappearsBeforeWorkerLoad() throws Exception {
        MediaImportRecord selected = transcodableRecord(116L);
        when(importRepo.findByIdIn(List.of(116L))).thenReturn(List.of(selected));
        when(importRepo.findById(116L)).thenReturn(Optional.empty());

        MediaImportService.TranscodeProgress queued = service.startPendingTranscode(List.of(116L), false);
        awaitFinished();

        assertThat(queued.total()).isEqualTo(1);
        assertThat(service.getProgress().running()).isFalse();
        assertThat(service.getProgress().completed()).isEqualTo(1);
        assertThat(service.getProgress().failed()).isEqualTo(1);
        verify(importRepo).save(selected);
        verifyNoInteractions(probe, mediaTranscoder, scanService, hashService);
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
    void importRecordFlushFailureAfterLibraryIngestRetainsOutputAndSource() throws Exception {
        MediaImportRecord record = pendingRecord(48L, "歌手 - 入库后记录写入失败.mpg");
        installSnapshotReader(null);
        when(importRepo.findByIdIn(List.of(48L))).thenReturn(List.of(record));
        when(importRepo.findById(48L)).thenReturn(Optional.of(record));
        when(probe.probe(any(Path.class))).thenReturn(new MediaProbe(1000, 1, 0, true,
                "1920x1080", List.of(), "mpeg2video", "ac3"));
        when(mediaTranscoder.transcode(any(), any(), any(), eq(true))).thenAnswer(invocation -> {
            Path output = invocation.getArgument(1);
            Files.writeString(output, "ingested-output-must-remain");
            return output;
        });
        when(scanService.ingestLibraryFile(any(), any(), anyString(), anyString(), eq(true)))
                .thenReturn(new LibraryScanService.IngestResult(true, 480L, 481L));
        doThrow(new IllegalStateException("record flush unavailable"))
                .when(importRepo).saveAndFlush(record);

        service.startPendingTranscode(List.of(48L), false);
        awaitFinished();

        Path source = sourceDir.resolve(record.getSourceFilename());
        Path output = Path.of(record.getOutputPath());
        Path canonicalSource = source.toRealPath();
        assertThat(service.getProgress().completed()).isEqualTo(1);
        assertThat(service.getProgress().transcoded()).isZero();
        assertThat(service.getProgress().failed()).isEqualTo(1);
        assertThat(service.getProgress().running()).isFalse();
        assertThat(record.getAction()).isEqualTo(MediaImportService.FAILED);
        assertThat(record.getSourcePath()).isEqualTo(source.toString());
        assertThat(record.getOutputSize()).isNull();
        assertThat(record.getOutputMtime()).isNull();
        assertThat(source).hasContent("source-48");
        assertThat(output).hasContent("ingested-output-must-remain");
        verify(scanService).ingestLibraryFile(output, canonicalSource, record.getSourceMd5(),
                new FileHashService().md5(output), true);
    }

    @Test
    void failedTranscodeCleanupPreservesAnExistingFileReferencedByItsOutputSymlink() throws Exception {
        Path protectedOutput = Files.writeString(targetDir.resolve("已有曲库文件.mp4"), "must-survive-cleanup");
        Path symlinkCapabilityProbe = targetDir.resolve("symlink-capability-probe");
        try {
            Files.createSymbolicLink(symlinkCapabilityProbe, protectedOutput.getFileName());
        } catch (java.io.IOException | UnsupportedOperationException | SecurityException unavailable) {
            Assumptions.assumeTrue(false, "当前本机环境不支持创建符号链接");
        }
        Files.delete(symlinkCapabilityProbe);
        MediaImportRecord record = pendingRecord(45L, "歌手 - 转码输出链接.mpg");
        AtomicReference<Path> outputPath = new AtomicReference<>();
        when(importRepo.findByIdIn(List.of(45L))).thenReturn(List.of(record));
        when(importRepo.findById(45L)).thenReturn(Optional.of(record));
        when(probe.probe(any(Path.class))).thenReturn(new MediaProbe(1000, 1, 0, true,
                "1920x1080", List.of(), "mpeg2video", "ac3"));
        when(mediaTranscoder.transcode(any(), any(), any(), eq(true))).thenAnswer(invocation -> {
            Path output = invocation.getArgument(1);
            Files.createSymbolicLink(output, protectedOutput.getFileName());
            outputPath.set(output);
            return output;
        });
        when(scanService.ingestLibraryFile(any(Path.class), any(Path.class), anyString(), anyString(), eq(true)))
                .thenReturn(new LibraryScanService.IngestResult(false, null, null));

        service.startPendingTranscode(List.of(45L), false);
        awaitFinished();

        assertThat(outputPath.get()).as("record action=%s reason=%s progress=%s",
                record.getAction(), record.getReason(), service.getProgress()).isNotNull();
        assertThat(Files.exists(outputPath.get(), java.nio.file.LinkOption.NOFOLLOW_LINKS)).isFalse();
        assertThat(protectedOutput).hasContent("must-survive-cleanup");
        assertThat(record.getAction()).isEqualTo(MediaImportService.FAILED);
        assertThat(service.getProgress().failed()).isEqualTo(1);
    }

    @Test
    void failedTranscodeCleanupPreservesAFileReplacingTheSymlinkDuringRecheck() throws Exception {
        Path protectedOutput = Files.writeString(targetDir.resolve("race-protected.mp4"), "protected bytes");
        MediaImportRecord record = pendingRecord(141L, "歌手 - 清理重检竞争.mpg");
        Path output = targetDir.resolve("歌手 - 清理重检竞争.mkv");
        int[] checks = {0};
        AtomicReference<Path> generatedOutput = new AtomicReference<>();
        AtomicBoolean regularReplacementCreated = new AtomicBoolean();
        when(importRepo.findByIdIn(List.of(141L))).thenReturn(List.of(record));
        when(importRepo.findById(141L)).thenReturn(Optional.of(record));
        when(probe.probe(any(Path.class))).thenReturn(new MediaProbe(1000, 1, 0, true,
                "1920x1080", List.of(), "mpeg2video", "ac3"));
        when(mediaTranscoder.transcode(any(), any(), any(), eq(true))).thenAnswer(invocation -> {
            Path generated = invocation.getArgument(1);
            Path normalizedGenerated = generated.toAbsolutePath().normalize();
            generatedOutput.set(normalizedGenerated);
            Files.writeString(generated, "generated output");
            MockedStatic<Files> fileSystem = mockStatic(Files.class,
                    org.mockito.Mockito.CALLS_REAL_METHODS);
            fileSystem.when(() -> Files.isSymbolicLink(any(Path.class))).thenAnswer(check -> {
                Path checked = ((Path) check.getArgument(0)).toAbsolutePath().normalize();
                if (!checked.equals(normalizedGenerated)) return check.callRealMethod();
                boolean wasSymbolicLink = (boolean) check.callRealMethod();
                checks[0]++;
                if (checks[0] == 1) {
                    Files.deleteIfExists(normalizedGenerated);
                    Files.createSymbolicLink(normalizedGenerated, protectedOutput.getFileName());
                    return false;
                }
                if (checks[0] == 2) {
                    Files.deleteIfExists(normalizedGenerated);
                    Files.writeString(normalizedGenerated, "concurrent regular replacement");
                    regularReplacementCreated.set(true);
                    return true;
                }
                fileSystem.close();
                return wasSymbolicLink;
            });
            return generated;
        });
        when(scanService.ingestLibraryFile(any(Path.class), any(Path.class), anyString(), anyString(), eq(true)))
                .thenReturn(new LibraryScanService.IngestResult(false, null, null));

        service.startPendingTranscode(List.of(141L), false);
        awaitFinished();

        assertThat(generatedOutput.get()).isEqualTo(
                output.getParent().toRealPath().resolve(output.getFileName()).normalize());
        assertThat(regularReplacementCreated).isTrue();
        assertThat(record.getAction()).isEqualTo(MediaImportService.FAILED);
        assertThat(service.getProgress().failed()).isEqualTo(1);
        assertThat(service.getProgress().running()).isFalse();
        assertThat(generatedOutput.get()).hasContent("concurrent regular replacement");
        assertThat(protectedOutput).hasContent("protected bytes");
        assertThat(Files.isSymbolicLink(generatedOutput.get())).isFalse();
    }

    @Test
    void successfulTranscodeCopiesExistingLyricsAndCoverAndRecordsTheirNames() throws Exception {
        MediaImportRecord record = pendingRecord(46L, "歌手 - 转码侧文件.mpg");
        Path source = sourceDir.resolve("歌手 - 转码侧文件.mpg");
        Path storedSourceIdentity = sourceDir.resolve("legacy-path-segment")
                .resolve("..").resolve(source.getFileName());
        record.setSourcePath(storedSourceIdentity.toString());
        Path sourceLyric = source.resolveSibling("歌手 - 转码侧文件.lrc");
        Path sourceCover = source.resolveSibling("歌手 - 转码侧文件.jpg");
        Path output = targetDir.resolve("歌手 - 转码侧文件.mkv");
        Path outputLyric = targetDir.resolve("歌手 - 转码侧文件.lrc");
        Path outputCover = targetDir.resolve("歌手 - 转码侧文件.jpg");
        Map<String, MediaImportRecord> recordsBySourcePath = new HashMap<>();
        recordsBySourcePath.put(storedSourceIdentity.toString(), record);
        when(importRepo.findBySourcePath(anyString())).thenAnswer(invocation ->
                Optional.ofNullable(recordsBySourcePath.get(invocation.getArgument(0))));
        when(importRepo.saveAndFlush(any())).thenAnswer(invocation -> {
            MediaImportRecord savedRecord = invocation.getArgument(0);
            recordsBySourcePath.put(savedRecord.getSourcePath(), savedRecord);
            saved.add(savedRecord);
            return savedRecord;
        });
        Files.writeString(sourceLyric, "[00:01.00]lyrics");
        Files.writeString(sourceCover, "synthetic-cover");
        when(importRepo.findByIdIn(List.of(46L))).thenReturn(List.of(record));
        when(importRepo.findById(46L)).thenReturn(Optional.of(record));
        when(importRepo.findBySourcePath(storedSourceIdentity.toString())).thenReturn(Optional.of(record));
        when(probe.probe(any(Path.class))).thenReturn(new MediaProbe(1000, 1, 0, true,
                "1920x1080", List.of(), "mpeg2video", "ac3"));
        when(mediaTranscoder.transcode(any(), any(), any(), eq(true))).thenAnswer(invocation -> {
            Path generatedOutput = invocation.getArgument(1);
            Files.writeString(generatedOutput, "transcoded-output");
            return generatedOutput;
        });
        when(scanService.ingestLibraryFile(any(Path.class), any(Path.class), anyString(), anyString(), eq(true)))
                .thenReturn(new LibraryScanService.IngestResult(true, 460L, 461L));

        service.startPendingTranscode(List.of(46L), false);
        awaitFinished();

        assertThat(service.getProgress().transcoded()).isEqualTo(1);
        assertThat(service.getProgress().failed()).isZero();
        assertThat(record.getAction()).isEqualTo(MediaImportService.TRANSCODED);
        assertThat(record.getSourcePath()).isEqualTo(storedSourceIdentity.toString());
        assertThat(recordsBySourcePath).hasSize(1).containsEntry(storedSourceIdentity.toString(), record);
        assertThat(record.getCompanionFiles())
                .isEqualTo("[\"歌手 - 转码侧文件.lrc\",\"歌手 - 转码侧文件.jpg\"]");
        assertThat(output).hasContent("transcoded-output");
        assertThat(outputLyric).hasContent("[00:01.00]lyrics");
        assertThat(outputCover).hasContent("synthetic-cover");
        assertThat(source).hasContent("source-46");
        assertThat(sourceLyric).hasContent("[00:01.00]lyrics");
        assertThat(sourceCover).hasContent("synthetic-cover");
        verify(importRepo).saveAndFlush(record);
    }

    @ParameterizedTest
    @ValueSource(strings = {"directory", "changed-bytes"})
    void transcodeRollsBackCopiedSidecarWhenTheTargetFailsIntegrityCheck(String replacement) throws Exception {
        MediaImportRecord record = pendingRecord(140L, "歌手 - 转码侧文件校验.mpg");
        Path source = Path.of(record.getSourcePath());
        Path sourceLyric = source.resolveSibling("歌手 - 转码侧文件校验.lrc");
        Path output = targetDir.resolve("歌手 - 转码侧文件校验.mkv");
        Path outputLyric = targetDir.resolve("歌手 - 转码侧文件校验.lrc");
        Path realOutputLyric = outputLyric.getParent().toRealPath()
                .resolve(outputLyric.getFileName()).normalize();
        Files.writeString(sourceLyric, "source lyrics");
        Path realSourceLyric = sourceLyric.toRealPath();
        when(importRepo.findByIdIn(List.of(140L))).thenReturn(List.of(record));
        when(importRepo.findById(140L)).thenReturn(Optional.of(record));
        when(probe.probe(any(Path.class))).thenReturn(new MediaProbe(1000, 1, 0, true,
                "1920x1080", List.of(), "mpeg2video", "ac3"));
        when(mediaTranscoder.transcode(any(), any(), any(), eq(true))).thenAnswer(invocation -> {
            Path generated = invocation.getArgument(1);
            Files.writeString(generated, "transcoded output");
            MockedStatic<Files> fileSystem = mockStatic(Files.class,
                    org.mockito.Mockito.CALLS_REAL_METHODS);
            fileSystem.when(() -> Files.copy(realSourceLyric, realOutputLyric,
                    java.nio.file.StandardCopyOption.COPY_ATTRIBUTES)).thenAnswer(copyInvocation -> {
                Path copied = (Path) copyInvocation.callRealMethod();
                if ("directory".equals(replacement)) {
                    Files.delete(realOutputLyric);
                    Files.createDirectories(realOutputLyric);
                    Files.writeString(realOutputLyric.resolve("concurrent-entry"), "preserve this entry");
                } else {
                    Files.writeString(realOutputLyric, "replacement lyrics with a different size");
                }
                fileSystem.close();
                return copied;
            });
            // The real method runs while the static stub is declared; reset it
            // before the worker proceeds to the actual transcode copy.
            Files.deleteIfExists(realOutputLyric);
            return generated;
        });

        service.startPendingTranscode(List.of(140L), false);
        awaitFinished();

        assertThat(service.getProgress().completed()).isEqualTo(1);
        assertThat(service.getProgress().failed()).isEqualTo(1);
        assertThat(service.getProgress().running()).isFalse();
        assertThat(record.getAction()).isEqualTo(MediaImportService.FAILED);
        assertThat(source).hasContent("source-140");
        assertThat(sourceLyric).hasContent("source lyrics");
        assertThat(output).doesNotExist();
        if ("directory".equals(replacement)) {
            assertThat(outputLyric).isDirectory();
            assertThat(outputLyric.resolve("concurrent-entry")).hasContent("preserve this entry");
        } else {
            assertThat(outputLyric).doesNotExist();
        }
        verify(scanService, never()).ingestLibraryFile(any(), any(), anyString(), anyString(), anyBoolean());
    }

    @Test
    void selectedTranscodeRetainsFormatSemanticsForALegacySourceWithoutAnExtension() throws Exception {
        MediaImportRecord record = pendingRecord(142L, "legacy-source-142");
        Path source = Path.of(record.getSourcePath());
        Path output = targetDir.resolve("legacy-source-142.mkv");
        when(importRepo.findByIdIn(List.of(142L))).thenReturn(List.of(record));
        when(importRepo.findById(142L)).thenReturn(Optional.of(record));
        Path realSource = source.toRealPath();
        Path realOutput = output.getParent().toRealPath().resolve(output.getFileName()).normalize();
        when(probe.probe(any(Path.class))).thenReturn(new MediaProbe(1000, 1, 0, true,
                "1920x1080", List.of(), "mpeg2video", "ac3"));
        when(mediaTranscoder.transcode(any(), any(), any(), eq(true))).thenAnswer(invocation -> {
            Path generated = invocation.getArgument(1);
            Files.writeString(generated, "converted legacy source");
            return generated;
        });
        when(scanService.ingestLibraryFile(any(Path.class), eq(realSource), anyString(), anyString(), eq(true)))
                .thenReturn(new LibraryScanService.IngestResult(true, 1420L, 1421L));

        service.startPendingTranscode(List.of(142L), false);
        awaitFinished();

        assertThat(service.getProgress().transcoded()).isEqualTo(1);
        assertThat(service.getProgress().failed()).isZero();
        assertThat(record.getAction()).isEqualTo(MediaImportService.TRANSCODED);
        assertThat(record.getSourceFormat()).isEmpty();
        assertThat(record.getOutputFormat()).isEqualTo("mkv");
        assertThat(record.getOutputPath()).isEqualTo(realOutput.toString());
        assertThat(source).hasContent("source-142");
        assertThat(output).hasContent("converted legacy source");
        verify(scanService).ingestLibraryFile(realOutput, realSource, record.getSourceMd5(),
                new FileHashService().md5(realOutput), true);
    }

    @Test
    void transcodeRejectsIncompleteIngestResultsAndPreservesEverySource() throws Exception {
        List<MediaImportRecord> records = List.of(
                pendingRecord(42L, "歌手 - 入库未导入.mpg"),
                pendingRecord(43L, "歌手 - 入库缺歌曲ID.mpg"),
                pendingRecord(44L, "歌手 - 入库缺媒体ID.mpg"));
        Map<Long, MediaImportRecord> recordsById = records.stream()
                .collect(java.util.stream.Collectors.toMap(MediaImportRecord::getId, record -> record));
        List<Long> ids = records.stream().map(MediaImportRecord::getId).toList();
        when(importRepo.findByIdIn(ids)).thenReturn(records);
        when(importRepo.findById(anyLong())).thenAnswer(invocation ->
                Optional.ofNullable(recordsById.get(invocation.getArgument(0))));
        when(probe.probe(any(Path.class))).thenReturn(new MediaProbe(1000, 1, 0, true,
                "1920x1080", List.of(), "mpeg2video", "ac3"));
        when(mediaTranscoder.transcode(any(), any(), any(), eq(true))).thenAnswer(invocation -> {
            Path output = invocation.getArgument(1);
            Files.writeString(output, "converted-" + output.getFileName());
            return output;
        });
        when(scanService.ingestLibraryFile(any(Path.class), any(Path.class), anyString(), anyString(), eq(true)))
                .thenReturn(new LibraryScanService.IngestResult(false, null, null))
                .thenReturn(new LibraryScanService.IngestResult(true, null, 430L))
                .thenReturn(new LibraryScanService.IngestResult(true, 440L, null));

        service.startPendingTranscode(ids, false);
        awaitFinished();

        assertThat(service.getProgress().failed()).isEqualTo(3);
        assertThat(service.getProgress().transcoded()).isZero();
        assertThat(records).allSatisfy(record -> {
            assertThat(record.getAction()).isEqualTo(MediaImportService.FAILED);
            assertThat(Path.of(record.getSourcePath())).exists();
        });
        try (Stream<Path> outputs = Files.list(targetDir)) {
            assertThat(outputs).isEmpty();
        }
        verify(scanService, times(3)).ingestLibraryFile(any(Path.class), any(Path.class),
                anyString(), anyString(), eq(true));
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
        record.setSourceMd5(new FileHashService().md5(importedSource));
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
    void cleanupPreservesSourceReplacedAfterSuccessfulImport() throws Exception {
        Path source = sourceDir.resolve("歌手 - 已替换.mp4");
        Path output = targetDir.resolve("歌手 - 已替换.mp4");
        Files.writeString(source, "original-source-content");
        Files.writeString(output, "verified-library-content");

        MediaImportRecord record = new MediaImportRecord();
        record.setId(44L);
        record.setSourcePath(source.toString());
        record.setSourceFilename(source.getFileName().toString());
        record.setSourceMd5(new FileHashService().md5(source));
        record.setOutputPath(output.toString());
        record.setOutputMd5(new FileHashService().md5(output));
        record.setOutputSize(Files.size(output));
        var outputMtime = Files.getLastModifiedTime(output).toInstant()
                .atOffset(ZoneOffset.UTC);
        record.setOutputMtime(outputMtime.withNano((outputMtime.getNano() / 1_000) * 1_000));
        record.setSongFileId(44L);
        record.setImportedFlag(true);
        SongFile libraryFile = new SongFile();
        libraryFile.setId(44L);
        libraryFile.setFilePath(output.toString());
        libraryFile.setValid(true);
        when(importRepo.findByIdGreaterThanOrderByIdAsc(eq(0L), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(record)));
        when(songFileRepo.findById(44L)).thenReturn(Optional.of(libraryFile));

        Files.writeString(source, "replacement-source-content");

        MediaImportService.AutoCleanupResult result = service.cleanupImportedSources();

        assertThat(result.eligible()).isZero();
        assertThat(result.deleted()).isZero();
        assertThat(result.skipped()).isEqualTo(1);
        assertThat(source).hasContent("replacement-source-content");
        assertThat(output).exists();
    }

    @Test
    void cleanupPreservesTheSourceWhenTheVerifiedLibraryOutputHasChanged() throws Exception {
        Path source = sourceDir.resolve("歌手 - 曲库输出已变化.mp4");
        Path output = targetDir.resolve(source.getFileName());
        Files.writeString(source, "verified-source");
        Files.writeString(output, "original-library-output");
        MediaImportRecord record = new MediaImportRecord();
        record.setId(46L);
        record.setSourcePath(source.toString());
        record.setSourceFilename(source.getFileName().toString());
        record.setSourceMd5(new FileHashService().md5(source));
        record.setOutputPath(output.toString());
        record.setOutputMd5(new FileHashService().md5(output));
        record.setOutputSize(Files.size(output));
        var originalOutputMtime = Files.getLastModifiedTime(output).toInstant().atOffset(ZoneOffset.UTC);
        record.setOutputMtime(originalOutputMtime.withNano((originalOutputMtime.getNano() / 1_000) * 1_000));
        record.setSongFileId(46L);
        record.setImportedFlag(true);
        SongFile libraryFile = new SongFile();
        libraryFile.setId(46L);
        libraryFile.setFilePath(output.toString());
        libraryFile.setValid(true);
        when(importRepo.findByIdGreaterThanOrderByIdAsc(eq(0L), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(record)));
        when(songFileRepo.findById(46L)).thenReturn(Optional.of(libraryFile));
        Files.writeString(output, "replacement library output with a different size");

        MediaImportService.AutoCleanupResult result = service.cleanupImportedSources();

        assertThat(result.eligible()).isZero();
        assertThat(result.deleted()).isZero();
        assertThat(result.skipped()).isEqualTo(1);
        assertThat(source).hasContent("verified-source");
        assertThat(output).hasContent("replacement library output with a different size");
        verify(importRepo, never()).delete(record);
    }

    @Test
    void cleanupDeletesOnlyCompanionsWhoseContentsWereCopied() throws Exception {
        Path source = sourceDir.resolve("歌手 - 伴随文件.mp4");
        Path output = targetDir.resolve("歌手 - 伴随文件.mp4");
        Path changedLyric = sourceDir.resolve("歌手 - 伴随文件.lrc");
        Path copiedLyric = targetDir.resolve("歌手 - 伴随文件.lrc");
        Path unchangedCover = sourceDir.resolve("歌手 - 伴随文件.jpg");
        Path copiedCover = targetDir.resolve("歌手 - 伴随文件.jpg");
        Files.writeString(source, "source-content");
        Files.writeString(output, "library-content");
        Files.writeString(changedLyric, "new lyric");
        Files.writeString(copiedLyric, "old lyric");
        Files.writeString(unchangedCover, "same-cover");
        Files.writeString(copiedCover, "same-cover");

        MediaImportRecord record = new MediaImportRecord();
        record.setId(45L);
        record.setSourcePath(source.toString());
        record.setSourceFilename(source.getFileName().toString());
        record.setSourceMd5(new FileHashService().md5(source));
        record.setOutputPath(output.toString());
        record.setOutputMd5(new FileHashService().md5(output));
        record.setOutputSize(Files.size(output));
        var outputMtime = Files.getLastModifiedTime(output).toInstant()
                .atOffset(ZoneOffset.UTC);
        record.setOutputMtime(outputMtime.withNano((outputMtime.getNano() / 1_000) * 1_000));
        record.setSongFileId(45L);
        record.setImportedFlag(true);
        SongFile libraryFile = new SongFile();
        libraryFile.setId(45L);
        libraryFile.setFilePath(output.toString());
        libraryFile.setValid(true);
        when(importRepo.findByIdGreaterThanOrderByIdAsc(eq(0L), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(record)));
        when(songFileRepo.findById(45L)).thenReturn(Optional.of(libraryFile));

        MediaImportService.AutoCleanupResult result = service.cleanupImportedSources();

        assertThat(result.deleted()).isEqualTo(1);
        assertThat(source).doesNotExist();
        assertThat(changedLyric).hasContent("new lyric");
        assertThat(unchangedCover).doesNotExist();
    }

    @Test
    void autoCleanupPreservesASourceSidecarWhenItsLibraryCopyIsMissing() throws Exception {
        Path source = Files.writeString(sourceDir.resolve("歌手 - 曲库缺少歌词副本.mp4"), "source-media");
        Path output = Files.writeString(targetDir.resolve(source.getFileName()), "library-media");
        Path sourceLyric = Files.writeString(source.resolveSibling("歌手 - 曲库缺少歌词副本.lrc"), "source lyrics");
        Path outputLyric = output.resolveSibling("歌手 - 曲库缺少歌词副本.lrc");
        MediaImportRecord record = new MediaImportRecord();
        record.setId(97L);
        record.setSourcePath(source.toString());
        record.setSourceFilename(source.getFileName().toString());
        record.setSourceMd5(new FileHashService().md5(source));
        record.setOutputPath(output.toString());
        record.setOutputMd5(new FileHashService().md5(output));
        record.setOutputSize(Files.size(output));
        record.setOutputMtime(fileSnapshot(output, null).mtime());
        record.setSongFileId(971L);
        record.setImportedFlag(true);
        SongFile libraryFile = new SongFile();
        libraryFile.setId(971L);
        libraryFile.setFilePath(output.toString());
        libraryFile.setValid(true);
        when(importRepo.findByIdGreaterThanOrderByIdAsc(eq(0L), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(record)));
        when(songFileRepo.findById(971L)).thenReturn(Optional.of(libraryFile));

        MediaImportService.AutoCleanupResult result = service.cleanupImportedSources();

        assertThat(result).isEqualTo(new MediaImportService.AutoCleanupResult(1, 1, 1, 0, 0));
        assertThat(source).doesNotExist();
        assertThat(sourceLyric).hasContent("source lyrics");
        assertThat(output).hasContent("library-media");
        assertThat(outputLyric).doesNotExist();
        verify(importRepo).delete(record);
    }

    @Test
    void cleanupSkipsAnImportedRecordWithoutAStoredSourceHash() throws Exception {
        Path source = Files.writeString(sourceDir.resolve("歌手 - 缺少源hash.mp4"), "source-without-hash");
        Path output = Files.writeString(targetDir.resolve(source.getFileName()), "library-output");
        MediaImportRecord record = new MediaImportRecord();
        record.setId(91L);
        record.setSourcePath(source.toString());
        record.setOutputPath(output.toString());
        record.setSongFileId(91L);
        record.setImportedFlag(true);
        when(importRepo.findByIdGreaterThanOrderByIdAsc(eq(0L), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(record)));

        MediaImportService.AutoCleanupResult result = service.cleanupImportedSources();

        assertThat(result.scanned()).isEqualTo(1);
        assertThat(result.eligible()).isZero();
        assertThat(result.deleted()).isZero();
        assertThat(result.skipped()).isEqualTo(1);
        assertThat(result.failed()).isZero();
        assertThat(source).hasContent("source-without-hash");
        assertThat(output).hasContent("library-output");
        verify(hashService, never()).md5(any(Path.class));
        verify(importRepo, never()).delete(record);
    }

    @Test
    void cleanupSongSourcePreservesFilesWhenTheImportRowDisappears() throws Exception {
        Path source = Files.writeString(sourceDir.resolve("歌手 - 导入行消失.mp4"), "source-retained");
        Path output = Files.writeString(targetDir.resolve(source.getFileName()), "library-retained");
        MediaImportRecord record = new MediaImportRecord();
        record.setId(92L);
        record.setSongId(920L);
        record.setSongFileId(921L);
        record.setSourcePath(source.toString());
        record.setSourceMd5(new FileHashService().md5(source));
        record.setOutputPath(output.toString());
        record.setOutputMd5(new FileHashService().md5(output));
        record.setImportedFlag(true);
        SongFile libraryFile = new SongFile();
        libraryFile.setId(921L);
        libraryFile.setFilePath(output.toString());
        libraryFile.setValid(true);
        when(importRepo.findBySongId(920L)).thenReturn(List.of(record));
        when(songFileRepo.findById(921L)).thenReturn(Optional.of(libraryFile));

        int cleaned = service.cleanupSongSource(920L);

        assertThat(cleaned).isZero();
        assertThat(record.getCleanupStatus()).isEqualTo("PENDING");
        assertThat(source).hasContent("source-retained");
        assertThat(output).hasContent("library-retained");
        verify(importRepo).save(record);
        verify(importRepo, never()).delete(record);
    }

    @Test
    void sourceDeletionWithoutAnOutputPathLeavesItsSidecarUntouched() throws Exception {
        Path source = Files.writeString(sourceDir.resolve("歌手 - 无输出记录.mp4"), "source-to-delete");
        Path lyric = Files.writeString(source.resolveSibling("歌手 - 无输出记录.lrc"), "unmatched-lyric");
        MediaImportRecord record = new MediaImportRecord();
        record.setId(93L);
        record.setSourcePath(source.toString());
        when(importRepo.findByIdIn(List.of(93L))).thenReturn(List.of(record));

        MediaImportService.DeleteSourcesResult result = service.deleteSources(List.of(93L));

        assertThat(result).isEqualTo(new MediaImportService.DeleteSourcesResult(1, 1, 0, 0));
        assertThat(source).doesNotExist();
        assertThat(lyric).hasContent("unmatched-lyric");
        verify(importRepo).delete(record);
    }

    @Test
    void sourceDeletionPreservesASidecarWhenItsLibraryCopyIsMissing() throws Exception {
        Path source = Files.writeString(sourceDir.resolve("歌手 - 缺曲库侧文件.mp4"), "source-to-delete");
        Path output = Files.writeString(targetDir.resolve(source.getFileName()), "library-output");
        Path lyric = Files.writeString(source.resolveSibling("歌手 - 缺曲库侧文件.lrc"), "source-lyric");
        MediaImportRecord record = new MediaImportRecord();
        record.setId(94L);
        record.setSourcePath(source.toString());
        record.setOutputPath(output.toString());
        when(importRepo.findByIdIn(List.of(94L))).thenReturn(List.of(record));

        MediaImportService.DeleteSourcesResult result = service.deleteSources(List.of(94L));

        assertThat(result).isEqualTo(new MediaImportService.DeleteSourcesResult(1, 1, 0, 0));
        assertThat(source).doesNotExist();
        assertThat(output).hasContent("library-output");
        assertThat(lyric).hasContent("source-lyric");
        assertThat(targetDir.resolve("歌手 - 缺曲库侧文件.lrc")).doesNotExist();
        verify(importRepo).delete(record);
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
    void autoCleanupAdvancesThroughKeysetPagesWithoutLoadingAllRecords() {
        MediaImportRecord first = pendingRecordWithoutSource(501L);
        MediaImportRecord second = pendingRecordWithoutSource(502L);
        when(importRepo.findByIdGreaterThanOrderByIdAsc(eq(0L), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(first), PageRequest.of(0, 1), 2));
        when(importRepo.findByIdGreaterThanOrderByIdAsc(eq(501L), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(second), PageRequest.of(1, 1), 2));

        MediaImportService.AutoCleanupResult result = service.cleanupImportedSources();

        assertThat(result.scanned()).isEqualTo(2);
        assertThat(result.skipped()).isEqualTo(2);
        assertThat(result.deleted()).isZero();
        verify(importRepo).findByIdGreaterThanOrderByIdAsc(eq(0L), any(Pageable.class));
        verify(importRepo).findByIdGreaterThanOrderByIdAsc(eq(501L), any(Pageable.class));
        verify(importRepo, never()).findAllByOrderByCreatedAtDesc();
    }

    @Test
    void autoCleanupStopsWhenAKeysetPageCannotAdvance() {
        MediaImportRecord withoutId = pendingRecordWithoutSource(null);
        when(importRepo.findByIdGreaterThanOrderByIdAsc(eq(0L), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(withoutId), PageRequest.of(0, 1), 2));

        MediaImportService.AutoCleanupResult result = service.cleanupImportedSources();

        assertThat(result.scanned()).isEqualTo(1);
        assertThat(result.skipped()).isEqualTo(1);
        verify(importRepo, times(1)).findByIdGreaterThanOrderByIdAsc(eq(0L), any(Pageable.class));
        verify(importRepo, never()).findByIdGreaterThanOrderByIdAsc(eq(1L), any(Pageable.class));
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
    void sourceCleanupAndFilteredDeletionTreatNullRepositoryPagesAsEmpty() {
        when(importRepo.findByIdGreaterThanOrderByIdAsc(eq(0L), any(Pageable.class))).thenReturn(null);
        when(importRepo.searchSourceLibraryAfterId(eq(0L), eq(""), isNull(), isNull(), isNull(),
                eq(false), any(Pageable.class))).thenReturn(null);

        MediaImportService.AutoCleanupResult cleanup = service.cleanupImportedSources();
        MediaImportService.DeleteSourcesResult deletion =
                service.deleteSourcesByFilter(null, null, null, null);

        assertThat(cleanup).isEqualTo(new MediaImportService.AutoCleanupResult(0, 0, 0, 0, 0));
        assertThat(deletion).isEqualTo(new MediaImportService.DeleteSourcesResult(0, 0, 0, 0));
        verify(importRepo).findByIdGreaterThanOrderByIdAsc(eq(0L), any(Pageable.class));
        verify(importRepo).searchSourceLibraryAfterId(eq(0L), eq(""), isNull(), isNull(), isNull(),
                eq(false), any(Pageable.class));
        verify(importRepo, never()).delete(any(MediaImportRecord.class));
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
        record.setSourceMd5(new FileHashService().md5(importedSource));
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

    @Test
    void songCleanupSkipsWhenTheImportedRowBecomesUnsafeBeforeDeletion() throws Exception {
        Path source = Files.writeString(sourceDir.resolve("歌手 - 入库记录已变化.mp4"), "source-bytes");
        Path output = Files.writeString(targetDir.resolve(source.getFileName()), "library-bytes");
        MediaImportRecord originallySafe = new MediaImportRecord();
        originallySafe.setId(95L);
        originallySafe.setSongId(950L);
        originallySafe.setSongFileId(951L);
        originallySafe.setSourcePath(source.toString());
        originallySafe.setSourceMd5(new FileHashService().md5(source));
        originallySafe.setOutputPath(output.toString());
        originallySafe.setOutputMd5(new FileHashService().md5(output));
        originallySafe.setOutputSize(Files.size(output));
        originallySafe.setOutputMtime(fileSnapshot(output, null).mtime());
        originallySafe.setImportedFlag(true);
        MediaImportRecord changedRow = new MediaImportRecord();
        changedRow.setId(95L);
        changedRow.setImportedFlag(false);
        SongFile libraryFile = new SongFile();
        libraryFile.setId(951L);
        libraryFile.setFilePath(output.toString());
        libraryFile.setValid(true);
        when(importRepo.findBySongId(950L)).thenReturn(List.of(originallySafe));
        when(importRepo.findById(95L)).thenReturn(Optional.of(changedRow));
        when(songFileRepo.findById(951L)).thenReturn(Optional.of(libraryFile));

        int cleaned = service.cleanupSongSource(950L);

        assertThat(cleaned).isZero();
        assertThat(source).hasContent("source-bytes");
        assertThat(output).hasContent("library-bytes");
        assertThat(originallySafe.getCleanupStatus()).isEqualTo("PENDING");
        verify(importRepo).save(originallySafe);
        verify(importRepo, never()).delete(any(MediaImportRecord.class));
    }

    @Test
    void sourceCleanupHandlesLegacyPathsWithoutExtensions() throws Exception {
        Path source = Files.writeString(sourceDir.resolve("legacy-source"), "verified source");
        Path output = Files.writeString(targetDir.resolve("legacy-library-output"), "verified output");
        Path sourceLyric = Files.writeString(source.resolveSibling("legacy-source.lrc"), "same lyrics");
        Path outputLyric = Files.writeString(output.resolveSibling("legacy-library-output.lrc"), "same lyrics");
        MediaImportRecord record = new MediaImportRecord();
        record.setId(96L);
        record.setSourcePath(source.toString());
        record.setSourceFilename(source.getFileName().toString());
        record.setSourceMd5(new FileHashService().md5(source));
        record.setOutputPath(output.toString());
        record.setOutputMd5(new FileHashService().md5(output));
        record.setOutputSize(Files.size(output));
        record.setOutputMtime(fileSnapshot(output, null).mtime());
        record.setSongFileId(961L);
        record.setImportedFlag(true);
        SongFile libraryFile = new SongFile();
        libraryFile.setId(961L);
        libraryFile.setFilePath(output.toString());
        libraryFile.setValid(true);
        when(importRepo.findByIdGreaterThanOrderByIdAsc(eq(0L), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(record)));
        when(songFileRepo.findById(961L)).thenReturn(Optional.of(libraryFile));

        MediaImportService.AutoCleanupResult result = service.cleanupImportedSources();

        assertThat(result).isEqualTo(new MediaImportService.AutoCleanupResult(1, 1, 1, 0, 0));
        assertThat(source).doesNotExist();
        assertThat(sourceLyric).doesNotExist();
        assertThat(output).hasContent("verified output");
        assertThat(outputLyric).hasContent("same lyrics");
        verify(importRepo).delete(record);
    }

    private MediaImportService.SourceSnapshot fileSnapshot(Path source, String identity) throws Exception {
        BasicFileAttributes attributes = Files.readAttributes(source, BasicFileAttributes.class);
        var mtime = attributes.lastModifiedTime().toInstant().atOffset(ZoneOffset.UTC);
        mtime = mtime.withNano((mtime.getNano() / 1_000) * 1_000);
        return new MediaImportService.SourceSnapshot(attributes.size(), mtime, identity);
    }

    private void assertPendingTranscode(String directory, String filename, MediaProbe mediaProbe,
                                       String expectedReason) throws Exception {
        Path caseDirectory = Files.createDirectories(temp.resolve(directory));
        props.setSourceLibraryPath(caseDirectory.toString());
        Path source = Files.writeString(caseDirectory.resolve(filename), "synthetic-media");
        when(probe.probe(source)).thenReturn(mediaProbe);

        MediaImportService.SourceScanResult result = service.scanSourceLibrary();

        assertThat(result.pendingTranscode())
                .as("case=%s, scanResult=%s, saved=%s", directory, result,
                        saved.stream().map(record -> record.getAction() + ":" + record.getReason()).toList())
                .isEqualTo(1);
        assertThat(result.copied()).isZero();
        assertThat(source).hasContent("synthetic-media");
        assertThat(saved.get(saved.size() - 1).getReason()).contains(expectedReason);
    }

    private MediaImportRecord importedSnapshotRecord(Path source, MediaImportService.SourceSnapshot snapshot,
                                                     String identity) throws Exception {
        MediaImportRecord record = new MediaImportRecord();
        record.setSourcePath(source.toString());
        record.setSourceFilename(source.getFileName().toString());
        record.setSourceMd5(new FileHashService().md5(source));
        record.setSourceSize(snapshot.size());
        record.setSourceMtime(snapshot.mtime());
        record.setSourceFileIdentity(identity);
        record.setImportedFlag(true);
        return record;
    }

    private void installSnapshotReader(MediaImportService.SourceSnapshot snapshot) {
        service.shutdown();
        service = new MediaImportService(props, probe, hashService, importRepo, songFileRepo,
                scanService, settingService, mediaTranscoder, new TagReader(),
                new MediaProbeStage(probe, new ResourceBudget(1, 1, 4, () -> false)), path -> snapshot);
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

    private MediaImportRecord pendingRecordWithoutSource(Long id) {
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
