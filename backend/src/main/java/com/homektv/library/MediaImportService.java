package com.homektv.library;

import com.homektv.config.AppProperties;
import com.homektv.domain.MediaImportRecord;
import com.homektv.domain.SongFile;
import com.homektv.media.FFprobeService;
import com.homektv.media.MediaProbe;
import com.homektv.repo.MediaImportRecordRepository;
import com.homektv.repo.SongFileRepository;
import com.homektv.web.ApiException;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.dao.DataIntegrityViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Iterator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

@Service
public class MediaImportService {

    private static final int RECORD_BATCH_SIZE = 200;

    private static final Logger log = LoggerFactory.getLogger(MediaImportService.class);

    public static final String PENDING_TRANSCODE = "PENDING_TRANSCODE";
    public static final String COPIED = "COPIED";
    public static final String TRANSCODED = "TRANSCODED";
    public static final String UNRECOGNIZED = "UNRECOGNIZED";
    public static final String FAILED = "FAILED";
    public static final String SOURCE_DUPLICATE = "SKIPPED_SOURCE_MD5_DUPLICATE";
    public static final String OUTPUT_DUPLICATE = "SKIPPED_OUTPUT_MD5_DUPLICATE";

    private final AppProperties props;
    private final FFprobeService ffprobeService;
    private final TagReader tagReader;
    private final FileHashService hashService;
    private final MediaImportRecordRepository importRepo;
    private final SongFileRepository songFileRepo;
    private final LibraryScanService scanService;
    private final SettingService settingService;
    private final MediaTranscoder mediaTranscoder;
    private final ExecutorService transcodeExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "source-library-transcode");
        thread.setDaemon(true);
        return thread;
    });
    private final ExecutorService scanExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "source-library-scan");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicReference<SourceScanProgress> scanProgress = new AtomicReference<>(SourceScanProgress.idle());
    private final Object scanStartLock = new Object();
    private boolean scanScheduled;
    private final AtomicReference<TranscodeProgress> progress = new AtomicReference<>(TranscodeProgress.idle());
    private final Object transcodeLock = new Object();
    private final Deque<Long> transcodeQueue = new ArrayDeque<>();
    private final LinkedHashSet<Long> priorityRecordIds = new LinkedHashSet<>();
    private Long currentRecordId;
    /** Frozen per-manual-batch setting; direct-copy imports never set this flag. */
    private boolean deleteSourcesForRun;

    @Autowired
    public MediaImportService(AppProperties props, FFprobeService ffprobeService, FileHashService hashService,
                              MediaImportRecordRepository importRepo, SongFileRepository songFileRepo,
                              LibraryScanService scanService, SettingService settingService,
                              MediaTranscoder mediaTranscoder, TagReader tagReader) {
        this.props = props;
        this.ffprobeService = ffprobeService;
        this.tagReader = tagReader;
        this.hashService = hashService;
        this.importRepo = importRepo;
        this.songFileRepo = songFileRepo;
        this.scanService = scanService;
        this.settingService = settingService;
        this.mediaTranscoder = mediaTranscoder;
    }

    /** Compatibility constructor for existing unit tests and integrations. */
    public MediaImportService(AppProperties props, FFprobeService ffprobeService, FileHashService hashService,
                              MediaImportRecordRepository importRepo, SongFileRepository songFileRepo,
                              LibraryScanService scanService, SettingService settingService,
                              MediaTranscoder mediaTranscoder) {
        this(props, ffprobeService, hashService, importRepo, songFileRepo, scanService, settingService,
                mediaTranscoder, new TagReader());
    }

    public record SourceScanResult(int scanned, int copied, int pendingTranscode,
                                   int skippedSourceDuplicate, int skippedOutputDuplicate,
                                   int unrecognized, int failed) {}

    public record SourceScanProgress(boolean running, int total, int completed, String currentFile,
                                     int copied, int pendingTranscode, int skippedSourceDuplicate,
                                     int skippedOutputDuplicate, int unrecognized, int failed,
                                     OffsetDateTime startedAt, OffsetDateTime finishedAt) {
        static SourceScanProgress idle() {
            return new SourceScanProgress(false, 0, 0, null, 0, 0, 0, 0, 0, 0, null, null);
        }
    }

    public record TranscodeProgress(boolean running, int total, int completed, int transcoded,
                                    int copiedSkipped, int skippedSourceDuplicate,
                                    int skippedOutputDuplicate, int failed, String currentFile,
                                    Long currentRecordId, List<Long> priorityRecordIds,
                                    OffsetDateTime startedAt, OffsetDateTime finishedAt) {
        static TranscodeProgress idle() {
            return new TranscodeProgress(false, 0, 0, 0, 0, 0, 0, 0,
                    null, null, List.of(), null, null);
        }
    }

    public record PriorityResult(String status, Long recordId, TranscodeProgress progress) {}

    public record DeleteSourcesResult(int requested, int deleted, int alreadyDeleted, int failed) {}

    public record AutoCleanupResult(int scanned, int eligible, int deleted, int skipped, int failed) {}

    public synchronized SourceScanResult scanSourceLibrary() {
        LibraryModePolicy.requireManaged(props, "扫描、导入或移动");
        Path sourceRoot = sourceRoot();
        Path targetRoot = targetRoot();
        ensureDirectories(sourceRoot, targetRoot);

        OffsetDateTime startedAt = OffsetDateTime.now();
        scanProgress.set(new SourceScanProgress(true, 0, 0, null, 0, 0, 0, 0, 0, 0,
                startedAt, null));
        int copied = 0, pending = 0, sourceDup = 0, outputDup = 0, unrecognized = 0, failed = 0;
        int total = 0;
        int completed = 0;
        List<Path> candidateFiles;
        try (Stream<Path> files = Files.walk(sourceRoot)) {
            candidateFiles = files.filter(Files::isRegularFile)
                    .filter(LibraryScanService::isMediaFile)
                    .toList();
        } catch (IOException | UncheckedIOException e) {
            scanProgress.set(new SourceScanProgress(false, total, completed, null, copied, pending,
                    sourceDup, outputDup, unrecognized, failed + 1, startedAt, OffsetDateTime.now()));
            throw new ApiException("SOURCE_SCAN_FAILED", "遍历扫描源目录失败：" + e.getMessage());
        }

        for (Path source : candidateFiles) {
            if (!Files.exists(source)) {
                continue;
            }
            total++;
            scanProgress.set(new SourceScanProgress(true, total, completed,
                    source.getFileName().toString(), copied, pending, sourceDup, outputDup, unrecognized, failed,
                    startedAt, null));
            try {
                ScanOutcome outcome = analyzeAndMaybeCopy(source, targetRoot);
                switch (outcome) {
                    case COPIED -> copied++;
                    case PENDING -> pending++;
                    case SOURCE_DUPLICATE -> sourceDup++;
                    case OUTPUT_DUPLICATE -> outputDup++;
                    case UNRECOGNIZED -> unrecognized++;
                    case UNCHANGED -> { }
                }
            } catch (Exception e) {
                failed++;
                upsertRecord(source, null, null, null, null, FAILED, messageOf(e), false,
                        false, false, null, null);
            }
            completed++;
            scanProgress.set(new SourceScanProgress(true, total, completed,
                    source.getFileName().toString(), copied, pending, sourceDup, outputDup, unrecognized, failed,
                    startedAt, null));
        }
        SourceScanResult result = new SourceScanResult(total, copied, pending, sourceDup, outputDup,
                unrecognized, failed);
        scanProgress.set(new SourceScanProgress(false, total, completed, null, copied, pending,
                sourceDup, outputDup, unrecognized, failed, startedAt, OffsetDateTime.now()));
        return result;
    }

    public SourceScanProgress startSourceScan() {
        LibraryModePolicy.requireManaged(props, "启动源曲库扫描");
        synchronized (scanStartLock) {
            SourceScanProgress current = scanProgress.get();
            if (scanScheduled || current.running()) return current;
            scanScheduled = true;
            scanProgress.set(new SourceScanProgress(true, 0, 0, null, 0, 0, 0, 0, 0, 0,
                    OffsetDateTime.now(), null));
            scanExecutor.submit(() -> {
                try {
                    scanSourceLibrary();
                } catch (RuntimeException exception) {
                    SourceScanProgress failed = scanProgress.get();
                    scanProgress.set(new SourceScanProgress(false, failed.total(), failed.completed(), null,
                            failed.copied(), failed.pendingTranscode(), failed.skippedSourceDuplicate(),
                            failed.skippedOutputDuplicate(), failed.unrecognized(), failed.failed() + 1,
                            failed.startedAt(), OffsetDateTime.now()));
                } finally {
                    synchronized (scanStartLock) {
                        scanScheduled = false;
                    }
                }
            });
            return scanProgress.get();
        }
    }

    public SourceScanProgress getScanProgress() {
        return scanProgress.get();
    }

    public Page<MediaImportRecord> listSourceLibrary(String keyword, String status, String formatAnalysis,
                                                     Boolean sourceDeleted, int page, int size) {
        int safeSize = Math.max(1, Math.min(size, 200));
        int safePage = Math.max(0, page);
        SourceLibraryFilters filters = sourceLibraryFilters(status, formatAnalysis, sourceDeleted);
        return importRepo.searchSourceLibrary(normalizeKeyword(keyword), filters.action(),
                filters.duplicateFlag(), filters.transcodeRequired(), filters.sourceDeleted(),
                PageRequest.of(safePage, safeSize));
    }

    private record SourceLibraryFilters(String action, Boolean duplicateFlag,
                                        Boolean transcodeRequired, Boolean sourceDeleted) {}

    public TranscodeProgress startPendingTranscode(Collection<Long> recordIds, boolean all) {
        LibraryModePolicy.requireManaged(props, "启动源文件转码");
        if (!all && (recordIds == null || recordIds.isEmpty())) {
            throw new ApiException("TRANSCODE_SELECTION_REQUIRED", "请先选择需要转码的源文件");
        }
        synchronized (transcodeLock) {
            if (progress.get().running()) {
                throw new ApiException("TRANSCODE_ALREADY_RUNNING", "已有批量转码任务正在执行");
            }
            transcodeQueue.clear();
            priorityRecordIds.clear();
            currentRecordId = null;
            deleteSourcesForRun = Boolean.TRUE.equals(settingService.getAll().get(SettingService.DELETE_SOURCE_AFTER_TRANSCODE));
            int candidateCount = all
                    ? enqueueAllTranscodable(deleteSourcesForRun)
                    : enqueueSelectedTranscodable(recordIds, deleteSourcesForRun);
            boolean running = !transcodeQueue.isEmpty();
            TranscodeProgress initial = new TranscodeProgress(running, candidateCount, 0, 0, 0, 0, 0, 0,
                    null, null, List.of(), OffsetDateTime.now(), running ? null : OffsetDateTime.now());
            progress.set(initial);
            if (running) transcodeExecutor.submit(this::runTranscodeQueue);
            return initial;
        }
    }

    private int enqueueAllTranscodable(boolean deleteSources) {
        int count = 0;
        long afterId = 0;
        while (true) {
            Page<MediaImportRecord> page = importRepo.findByIdGreaterThanOrderByIdAsc(
                    afterId, PageRequest.of(0, RECORD_BATCH_SIZE));
            if (page == null || page.isEmpty()) break;
            for (MediaImportRecord record : page.getContent()) {
                count += enqueueTranscodable(record, deleteSources);
            }
            long nextId = page.getContent().stream()
                    .map(MediaImportRecord::getId).filter(Objects::nonNull)
                    .mapToLong(Long::longValue).max().orElse(afterId);
            if (nextId <= afterId || !page.hasNext()) break;
            afterId = nextId;
        }
        return count;
    }

    private int enqueueSelectedTranscodable(Collection<Long> recordIds, boolean deleteSources) {
        List<MediaImportRecord> records = importRepo.findByIdIn(recordIds);
        if (records == null) return 0;
        int count = 0;
        for (MediaImportRecord record : records) {
            count += enqueueTranscodable(record, deleteSources);
        }
        return count;
    }

    private int enqueueTranscodable(MediaImportRecord record, boolean deleteSources) {
        if (record == null || record.getId() == null || !isTranscodable(record)) return 0;
        record.setDeleteSourceRequested(deleteSources);
        record.setCleanupStatus(deleteSources ? "PENDING" : "NOT_REQUESTED");
        importRepo.save(record);
        transcodeQueue.addLast(record.getId());
        return 1;
    }

    public PriorityResult prioritizeTranscode(Long recordId) {
        LibraryModePolicy.requireManaged(props, "调整源文件转码队列");
        if (recordId == null) throw new ApiException("TRANSCODE_SELECTION_REQUIRED", "请选择需要插队的源文件");
        MediaImportRecord record = importRepo.findById(recordId)
                .orElseThrow(() -> new ApiException("IMPORT_RECORD_NOT_FOUND", "源素材记录不存在"));
        if (!isTranscodable(record)) {
            throw new ApiException("SOURCE_NOT_TRANSCODABLE", "该源文件当前不可转码");
        }
        synchronized (transcodeLock) {
            TranscodeProgress current = progress.get();
            if (!current.running()) {
                throw new ApiException("TRANSCODE_NOT_RUNNING", "当前没有正在运行的转码任务");
            }
            if (Objects.equals(currentRecordId, recordId)) {
                return new PriorityResult("CURRENT", recordId, current);
            }
            if (priorityRecordIds.contains(recordId)) {
                return new PriorityResult("ALREADY_PRIORITY", recordId, current);
            }
            boolean moved = transcodeQueue.remove(recordId);
            transcodeQueue.addFirst(recordId);
            priorityRecordIds.add(recordId);
            TranscodeProgress updated = copyProgress(current, current.total() + (moved ? 0 : 1),
                    current.completed(), current.transcoded(), current.copiedSkipped(),
                    current.skippedSourceDuplicate(), current.skippedOutputDuplicate(), current.failed(),
                    current.currentFile(), current.currentRecordId(), List.copyOf(priorityRecordIds), true, null);
            progress.set(updated);
            return new PriorityResult(moved ? "MOVED" : "QUEUED", recordId, updated);
        }
    }

    public TranscodeProgress getProgress() {
        return progress.get();
    }

    public DeleteSourcesResult deleteSources(Collection<Long> ids) {
        LibraryModePolicy.requireManaged(props, "删除源文件");
        List<MediaImportRecord> records = ids == null || ids.isEmpty()
                ? List.of()
                : importRepo.findByIdIn(ids);
        int deleted = 0, alreadyDeleted = 0, failed = 0;
        for (MediaImportRecord record : records) {
            if (record.isSourceDeleted()) {
                alreadyDeleted++;
                continue;
            }
            try {
                Path source = Path.of(record.getSourcePath()).toAbsolutePath().normalize();
                if (!source.startsWith(sourceRoot())) {
                    throw new IOException("拒绝删除扫描源目录以外的文件：" + source);
                }
                Files.deleteIfExists(source);
                removeSourceRecord(record);
                deleted++;
            } catch (Exception e) {
                failed++;
            }
        }
        return new DeleteSourcesResult(records.size(), deleted, alreadyDeleted, failed);
    }

    public AutoCleanupResult cleanupImportedSources() {
        LibraryModePolicy.requireManaged(props, "自动清理源文件");
        synchronized (transcodeLock) {
            if (progress.get().running()) {
                throw new ApiException("TRANSCODE_ALREADY_RUNNING", "批量转码进行中，暂时不能清理源文件");
            }
        }
        int eligible = 0, deleted = 0, skipped = 0, failed = 0;
        int scanned = 0;
        long afterId = 0;
        while (true) {
            Page<MediaImportRecord> page = importRepo.findByIdGreaterThanOrderByIdAsc(
                    afterId, PageRequest.of(0, RECORD_BATCH_SIZE));
            if (page == null || page.isEmpty()) break;
            for (MediaImportRecord record : page.getContent()) {
                scanned++;
                if (!isSafelyImported(record)) {
                    skipped++;
                    continue;
                }
                eligible++;
                try {
                    deleteSourceAndCompanions(record);
                    deleted++;
                } catch (Exception e) {
                    failed++;
                }
            }
            long nextId = page.getContent().stream()
                    .map(MediaImportRecord::getId).filter(Objects::nonNull)
                    .mapToLong(Long::longValue).max().orElse(afterId);
            if (nextId <= afterId || !page.hasNext()) break;
            afterId = nextId;
        }
        return new AutoCleanupResult(scanned, eligible, deleted, skipped, failed);
    }

    /** Clean source records associated with a manually transcoded single song. */
    public int cleanupSongSource(Long songId) {
        LibraryModePolicy.requireManaged(props, "清理歌曲源文件");
        int cleaned = 0;
        for (MediaImportRecord record : importRepo.findBySongId(songId)) {
            record.setDeleteSourceRequested(true);
            record.setCleanupStatus("PENDING");
            importRepo.save(record);
            if (isSafelyImported(record)) {
                if (cleanupImportedRecord(record.getId())) cleaned++;
            }
        }
        return cleaned;
    }

    private boolean isSafelyImported(MediaImportRecord record) {
        if (!record.isImportedFlag() || record.isSourceDeleted()
                || record.getSongFileId() == null || record.getOutputPath() == null) return false;
        Path source = Path.of(record.getSourcePath()).toAbsolutePath().normalize();
        Path output = Path.of(record.getOutputPath()).toAbsolutePath().normalize();
        if (!source.startsWith(sourceRoot()) || !output.startsWith(targetRoot()) || source.equals(output)
                || !Files.isRegularFile(source) || !Files.isRegularFile(output)) return false;
        return songFileRepo.findById(record.getSongFileId())
                .filter(SongFile::isValid)
                .filter(file -> Path.of(file.getFilePath()).toAbsolutePath().normalize().equals(output))
                .map(file -> {
                    try {
                        if (Files.size(output) <= 0) return false;
                        if (record.getOutputSize() != null && record.getOutputMtime() != null) {
                            SourceSnapshot snapshot = sourceSnapshot(output);
                            return snapshot != null
                                    && snapshot.size() == record.getOutputSize()
                                    && sameMtime(record.getOutputMtime(), snapshot.mtime());
                        }
                        String actual = hashService.md5(output);
                        if (record.getOutputMd5() == null || record.getOutputMd5().isBlank()) {
                            record.setOutputMd5(actual);
                            importRepo.save(record);
                        }
                        return actual.equals(record.getOutputMd5());
                    }
                    catch (Exception e) { return false; }
                }).orElse(false);
    }

    public DeleteSourcesResult deleteSourcesByFilter(String keyword, String status, String formatAnalysis,
                                                     Boolean sourceDeleted) {
        LibraryModePolicy.requireManaged(props, "按条件删除源文件");
        SourceLibraryFilters filters = sourceLibraryFilters(status, formatAnalysis, sourceDeleted);
        int requested = 0, deleted = 0, alreadyDeleted = 0, failed = 0;
        long afterId = 0;
        while (true) {
            Slice<MediaImportRecord> page = importRepo.searchSourceLibraryAfterId(afterId,
                    normalizeKeyword(keyword), filters.action(), filters.duplicateFlag(),
                    filters.transcodeRequired(), filters.sourceDeleted(),
                    PageRequest.of(0, RECORD_BATCH_SIZE));
            if (page == null || page.isEmpty()) break;
            List<Long> ids = page.getContent().stream().map(MediaImportRecord::getId)
                    .filter(Objects::nonNull).toList();
            DeleteSourcesResult result = deleteSources(ids);
            requested += result.requested();
            deleted += result.deleted();
            alreadyDeleted += result.alreadyDeleted();
            failed += result.failed();
            long nextId = ids.stream().mapToLong(Long::longValue).max().orElse(afterId);
            if (nextId <= afterId || !page.hasNext()) break;
            afterId = nextId;
        }
        return new DeleteSourcesResult(requested, deleted, alreadyDeleted, failed);
    }

    public void deleteSource(Long recordId) {
        LibraryModePolicy.requireManaged(props, "删除源文件");
        DeleteSourcesResult result = deleteSources(List.of(recordId));
        if (result.requested() == 0) throw new ApiException("IMPORT_RECORD_NOT_FOUND", "源素材记录不存在");
        if (result.failed() > 0) throw new ApiException("DELETE_SOURCE_FAILED", "删除源视频失败");
    }

    public Page<MediaImportRecord> listRecords(String action, int page, int size) {
        return listSourceLibrary("", action, "", null, page, size);
    }

    private ScanOutcome analyzeAndMaybeCopy(Path source, Path targetRoot) throws IOException {
        LibraryModePolicy.requireManaged(props, "导入、移动或转码");
        MediaImportRecord existing = importRepo.findBySourcePath(source.toString()).orElse(null);
        SourceSnapshot snapshot = sourceSnapshot(source);
        boolean unchangedSnapshot = existing != null && sameSourceSnapshot(existing, snapshot);
        String sourceMd5 = unchangedSnapshot && hasText(existing.getSourceMd5())
                ? existing.getSourceMd5() : hashService.md5(source);
        if (existing != null && Objects.equals(existing.getSourceMd5(), sourceMd5)) {
            if (!unchangedSnapshot && snapshot != null) {
                rememberSourceSnapshot(existing, snapshot);
                importRepo.save(existing);
            }
            if (existing.isImportedFlag()) return ScanOutcome.UNCHANGED;
            if (PENDING_TRANSCODE.equals(existing.getAction()) && hasStoredFormatAnalysis(existing)) {
                DirectCopyDecision previousDecision = directCopyDecision(
                        existing.getSourceFormat(), existing.getVideoCodec(), existing.getAudioCodec(),
                        !MediaClassifier.AUDIO.equals(existing.getMediaType()));
                if (previousDecision.transcodeRequired()) {
                    if (!Objects.equals(existing.getReason(), previousDecision.reason())) {
                        existing.setReason(previousDecision.reason());
                        importRepo.save(existing);
                    }
                    return ScanOutcome.UNCHANGED;
                }
            }
        }

        MediaProbe probe = ffprobeService.probe(source);
        TagInfo tag = LibraryScanService.isAudioFile(source) ? tagReader.read(source.toFile()) : new TagInfo();
        ParsedMeta parsed = tag.hasTitle()
                ? ParsedMeta.of(tag.getTitle(), tag.getArtist())
                : parseFilename(source);
        boolean recognized = parsed.recognized();
        DirectCopyDecision directCopy = directCopyDecision(source, probe);
        boolean transcodeRequired = directCopy.transcodeRequired();
        if (!recognized) {
            upsertRecord(source, sourceMd5, null, probe, null, UNRECOGNIZED,
                    "文件名无法识别歌名和歌手", transcodeRequired, false, false, null, null);
            return ScanOutcome.UNRECOGNIZED;
        }
        if (isSourceDuplicate(source, sourceMd5)) {
            upsertRecord(source, sourceMd5, null, probe, null, SOURCE_DUPLICATE,
                    "源文件 MD5 已存在，跳过", transcodeRequired, true, false, null, null);
            return ScanOutcome.SOURCE_DUPLICATE;
        }
        if (transcodeRequired) {
            upsertRecord(source, sourceMd5, null, probe, null, PENDING_TRANSCODE,
                    directCopy.reason(), true, false, false, null, null);
            return ScanOutcome.PENDING;
        }

        if (isOutputDuplicate(source, sourceMd5)) {
            upsertRecord(source, sourceMd5, null, probe, sourceMd5, OUTPUT_DUPLICATE,
                    "曲库中已存在相同 MD5，跳过移动", false, true, false, null, null);
            return ScanOutcome.OUTPUT_DUPLICATE;
        }
        Path output = moveToTarget(source, targetRoot);
        LibraryScanService.IngestResult ingest;
        List<MovedCompanion> movedCompanions = List.of();
        try {
            String outputMd5 = hashService.md5(output);
            if (!Objects.equals(sourceMd5, outputMd5)) {
                throw new IOException("移动后文件 MD5 校验失败");
            }
            movedCompanions = moveCompanions(source, output);
            ingest = scanService.ingestLibraryFile(output, source, sourceMd5, outputMd5, false);
            if (!ingest.imported() || ingest.songId() == null || ingest.songFileId() == null) {
                throw new IOException("移动后的文件未能写入 KTV 曲库");
            }
        } catch (Exception e) {
            restoreMovedCompanions(movedCompanions);
            restoreMovedFile(output, source);
            throw e;
        }
        markSongFileSourceDeleted(ingest.songFileId());
        if (existing != null) importRepo.delete(existing);
        return ScanOutcome.COPIED;
    }

    private void runTranscodeQueue() {
        try {
            while (true) {
                MediaImportRecord record;
                synchronized (transcodeLock) {
                    Long nextId = transcodeQueue.pollFirst();
                    if (nextId == null) {
                        currentRecordId = null;
                        priorityRecordIds.clear();
                        TranscodeProgress current = progress.get();
                        progress.set(copyProgress(current, current.total(), current.completed(), current.transcoded(),
                                current.copiedSkipped(), current.skippedSourceDuplicate(), current.skippedOutputDuplicate(),
                                current.failed(), null, null, List.of(), false, OffsetDateTime.now()));
                        return;
                    }
                    currentRecordId = nextId;
                    priorityRecordIds.remove(nextId);
                    record = importRepo.findById(nextId).orElse(null);
                    TranscodeProgress current = progress.get();
                    progress.set(copyProgress(current, current.total(), current.completed(), current.transcoded(),
                            current.copiedSkipped(), current.skippedSourceDuplicate(), current.skippedOutputDuplicate(),
                            current.failed(), record == null ? String.valueOf(nextId) : record.getSourceFilename(),
                            nextId, List.copyOf(priorityRecordIds), true, null));
                }

                TranscodeOutcome outcome = record == null ? TranscodeOutcome.FAILED : transcodeRecord(record);
                synchronized (transcodeLock) {
                    TranscodeProgress current = progress.get();
                    currentRecordId = null;
                    progress.set(copyProgress(current, current.total(), current.completed() + 1,
                            current.transcoded() + (outcome == TranscodeOutcome.TRANSCODED ? 1 : 0),
                            current.copiedSkipped(),
                            current.skippedSourceDuplicate() + (outcome == TranscodeOutcome.SOURCE_DUPLICATE ? 1 : 0),
                            current.skippedOutputDuplicate() + (outcome == TranscodeOutcome.OUTPUT_DUPLICATE ? 1 : 0),
                            current.failed() + (outcome == TranscodeOutcome.FAILED ? 1 : 0),
                            null, null, List.copyOf(priorityRecordIds), true, null));
                }
            }
        } catch (RuntimeException failure) {
            // A queue bookkeeping failure must not strand the public progress
            // state in running=true forever or prevent a later retry.
            synchronized (transcodeLock) {
                TranscodeProgress current = progress.get();
                currentRecordId = null;
                transcodeQueue.clear();
                priorityRecordIds.clear();
                progress.set(copyProgress(current, current.total(), current.completed(), current.transcoded(),
                        current.copiedSkipped(), current.skippedSourceDuplicate(), current.skippedOutputDuplicate(),
                        current.failed() + 1, null, null, List.of(), false, OffsetDateTime.now()));
            }
            log.error("源文件转码队列异常终止", failure);
        }
    }

    private TranscodeOutcome transcodeRecord(MediaImportRecord record) {
        Path source = null;
        Path output = null;
        List<Path> migratedCompanions = List.of();
        boolean ingested = false;
        try {
            LibraryModePolicy.requireManaged(props, "转码源文件");
            if (record.isSourceDeleted() || !Files.isRegularFile(Path.of(record.getSourcePath()))) {
                throw new ApiException("SOURCE_FILE_MISSING", "源文件不存在或已删除");
            }
            source = Path.of(record.getSourcePath());
            String sourceMd5 = hashService.md5(source);
            if (!Objects.equals(sourceMd5, record.getSourceMd5()) || isSourceDuplicate(source, sourceMd5)) {
                record.setAction(SOURCE_DUPLICATE);
                record.setDuplicateFlag(true);
                record.setReason("源文件 MD5 已存在或扫描后发生变化，跳过");
                importRepo.save(record);
                return TranscodeOutcome.SOURCE_DUPLICATE;
            }
            MediaProbe probe = ffprobeService.probe(source);
            output = transcodeToTarget(source, targetRoot(), probe);
            String outputMd5 = hashService.md5(output);
            if (isOutputDuplicate(source, outputMd5)) {
                Files.deleteIfExists(output);
                record.setAction(OUTPUT_DUPLICATE);
                record.setDuplicateFlag(true);
                record.setOutputMd5(outputMd5);
                record.setReason("转码后的 MD5 已存在，跳过");
                importRepo.save(record);
                return TranscodeOutcome.OUTPUT_DUPLICATE;
            }
            // Complete sidecar migration before creating the DB-owned library
            // row. A sidecar failure can therefore be rolled back as one file
            // operation without leaving a partially indexed output behind.
            migratedCompanions = migrateCompanions(source, output, record);
            LibraryScanService.IngestResult ingest = scanService.ingestLibraryFile(
                    output, source, sourceMd5, outputMd5, true);
            if (!ingest.imported() || ingest.songId() == null || ingest.songFileId() == null) {
                throw new IOException("转码后的文件未能写入 KTV 曲库");
            }
            ingested = true;
            upsertRecord(source, sourceMd5, output, probe, outputMd5, TRANSCODED,
                    "已转码入 KTV 曲库", true, false, true, ingest.songId(), ingest.songFileId());
            if (record.isDeleteSourceRequested()) cleanupImportedRecord(record.getId());
            return TranscodeOutcome.TRANSCODED;
        } catch (Exception e) {
            if (!ingested) deleteGeneratedFiles(output, migratedCompanions);
            record.setAction(FAILED);
            record.setReason(messageOf(e));
            importRepo.save(record);
            return TranscodeOutcome.FAILED;
        }
    }

    private TranscodeProgress copyProgress(TranscodeProgress current, int total, int completed, int transcoded,
                                           int copiedSkipped, int sourceDup, int outputDup, int failed,
                                           String currentFile, Long activeRecordId, List<Long> priorityIds,
                                           boolean running, OffsetDateTime finishedAt) {
        return new TranscodeProgress(running, total, completed, transcoded, copiedSkipped, sourceDup, outputDup,
                failed, currentFile, activeRecordId, priorityIds, current.startedAt(), finishedAt);
    }

    private void upsertRecord(Path source, String sourceMd5, Path output, MediaProbe probe, String outputMd5,
                              String action, String reason, boolean transcodeRequired, boolean duplicate,
                              boolean imported, Long songId, Long songFileId) {
        MediaImportRecord record = importRepo.findBySourcePath(source.toString()).orElseGet(MediaImportRecord::new);
        applyRecordValues(record, source, sourceMd5, output, probe, outputMd5, action, reason,
                transcodeRequired, duplicate, imported, songId, songFileId);
        try {
            importRepo.saveAndFlush(record);
        } catch (DataIntegrityViolationException conflict) {
            MediaImportRecord existing = importRepo.findBySourcePath(source.toString()).orElseThrow(() -> conflict);
            applyRecordValues(existing, source, sourceMd5, output, probe, outputMd5, action, reason,
                    transcodeRequired, duplicate, imported, songId, songFileId);
            importRepo.saveAndFlush(existing);
        }
    }

    private void applyRecordValues(MediaImportRecord record, Path source, String sourceMd5, Path output,
                                   MediaProbe probe, String outputMd5, String action, String reason,
                                   boolean transcodeRequired, boolean duplicate, boolean imported,
                                   Long songId, Long songFileId) {
        ParsedMeta parsed = parseFilename(source);
        record.setSourcePath(source.toString());
        record.setSourceFilename(source.getFileName().toString());
        record.setSourceMd5(sourceMd5 != null ? sourceMd5 : record.getSourceMd5() != null ? record.getSourceMd5() : "");
        record.setParsedTitle(parsed.title());
        record.setParsedArtist(parsed.artist());
        record.setSourceFormat(extOf(source));
        record.setMediaType(probe != null ? MediaClassifier.classify(probe) : record.getMediaType());
        record.setOutputPath(output != null ? output.toString() : record.getOutputPath());
        record.setOutputMd5(outputMd5);
        record.setOutputFormat(output != null ? extOf(output) : record.getOutputFormat());
        if (output != null) {
            SourceSnapshot outputSnapshot = sourceSnapshot(output);
            record.setOutputSize(outputSnapshot == null ? null : outputSnapshot.size());
            record.setOutputMtime(outputSnapshot == null ? null : outputSnapshot.mtime());
        }
        record.setVideoCodec(probe != null ? probe.videoCodec() : record.getVideoCodec());
        record.setAudioCodec(probe != null ? probe.audioCodec() : record.getAudioCodec());
        record.setAction(action);
        record.setReason(reason);
        record.setTranscodeRequired(transcodeRequired);
        record.setDuplicateFlag(duplicate);
        record.setImportedFlag(imported);
        record.setSongId(songId);
        record.setSongFileId(songFileId);
        record.setSourceDeleted(false);
        SourceSnapshot snapshot = sourceSnapshot(source);
        if (snapshot != null) {
            record.setSourceSize(snapshot.size());
            record.setSourceMtime(snapshot.mtime());
            record.setSourceFileIdentity(snapshot.fileIdentity());
        }
        if (record.getCleanupStatus() == null) record.setCleanupStatus("NOT_REQUESTED");
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static void rememberSourceSnapshot(MediaImportRecord record, SourceSnapshot snapshot) {
        record.setSourceSize(snapshot.size());
        record.setSourceMtime(snapshot.mtime());
        record.setSourceFileIdentity(snapshot.fileIdentity());
    }

    private static boolean sameSourceSnapshot(MediaImportRecord record, SourceSnapshot current) {
        if (record == null || current == null || record.getSourceSize() == null
                || record.getSourceMtime() == null) return false;
        if (record.getSourceSize() != current.size()
                || !sameMtime(record.getSourceMtime(), current.mtime())) return false;
        String storedIdentity = record.getSourceFileIdentity();
        String currentIdentity = current.fileIdentity();
        // Legacy rows with no identity can use the original path/size/mtime
        // contract; once one side has an identity, a missing identity is not trusted.
        return storedIdentity == null && currentIdentity == null
                || storedIdentity != null && currentIdentity != null
                && storedIdentity.equals(currentIdentity);
    }

    private static boolean sameMtime(OffsetDateTime left, OffsetDateTime right) {
        return left != null && right != null
                && mtimeKey(left) == mtimeKey(right);
    }

    private static long mtimeKey(OffsetDateTime value) {
        var instant = value.toInstant();
        return instant.getEpochSecond() * 1_000_000L + instant.getNano() / 1_000;
    }

    private static SourceSnapshot sourceSnapshot(Path source) {
        try {
            BasicFileAttributes attrs = Files.readAttributes(source, BasicFileAttributes.class);
            Object identity = attrs.fileKey();
            return new SourceSnapshot(attrs.size(), normalizeMtime(
                    attrs.lastModifiedTime().toInstant().atOffset(ZoneOffset.UTC)),
                    identity == null ? null : identity.toString());
        } catch (IOException failure) {
            return null;
        }
    }

    private static OffsetDateTime normalizeMtime(OffsetDateTime value) {
        return value == null ? null : value.withNano((value.getNano() / 1_000) * 1_000);
    }

    private record SourceSnapshot(long size, OffsetDateTime mtime, String fileIdentity) {}

    private ParsedMeta parseFilename(Path source) {
        String filename = source.getFileName().toString();
        ParsedMeta local = FilenameParser.parse(filename);
        if (local.recognized()) return local;

        // Only ask the shared scan service for ambiguous names; simple legacy names
        // keep their original no-extra-interaction path.
        ParsedMeta shared = scanService.parseFilename(filename);
        // Compatibility for isolated tests/legacy callers that provide a mock or older scan service.
        return shared == null ? local : shared;
    }

    private void removeSourceRecord(MediaImportRecord record) {
        markSongFileSourceDeleted(record.getSongFileId());
        importRepo.delete(record);
    }

    private void markSongFileSourceDeleted(Long songFileId) {
        if (songFileId == null) return;
        songFileRepo.findById(songFileId).ifPresent(file -> {
            file.setSourceDeleted(true);
            songFileRepo.save(file);
        });
    }

    private boolean isSourceDuplicate(Path source, String sourceMd5) {
        return songFileRepo.existsBySourceMd5(sourceMd5)
                || importRepo.existsBySourceMd5AndSourcePathNot(sourceMd5, source.toString());
    }

    private boolean isOutputDuplicate(Path source, String outputMd5) {
        return songFileRepo.existsByOutputMd5(outputMd5)
                || importRepo.existsByOutputMd5AndSourcePathNot(outputMd5, source.toString());
    }

    private boolean isTranscodable(MediaImportRecord record) {
        return record.isTranscodeRequired()
                && (PENDING_TRANSCODE.equals(record.getAction()) || FAILED.equals(record.getAction()))
                && !record.isSourceDeleted();
    }

    private static SourceLibraryFilters sourceLibraryFilters(String status, String formatAnalysis,
                                                             Boolean sourceDeleted) {
        String normalizedStatus = status == null ? "" : status.trim();
        String action = switch (normalizedStatus) {
            case "", "duplicate", "deleted" -> null;
            case "pending" -> PENDING_TRANSCODE;
            case "copied" -> COPIED;
            case "transcoded" -> TRANSCODED;
            case "unrecognized" -> UNRECOGNIZED;
            case "failed" -> FAILED;
            default -> normalizedStatus;
        };
        Boolean duplicateFlag = "duplicate".equals(normalizedStatus) ? Boolean.TRUE : null;
        Boolean effectiveSourceDeleted = "deleted".equals(normalizedStatus)
                ? Boolean.TRUE : sourceDeleted == null ? Boolean.FALSE : sourceDeleted;
        Boolean transcodeRequired = switch (formatAnalysis == null ? "" : formatAnalysis.trim()) {
            case "transcode" -> Boolean.TRUE;
            case "copy" -> Boolean.FALSE;
            default -> null;
        };
        return new SourceLibraryFilters(action, duplicateFlag, transcodeRequired, effectiveSourceDeleted);
    }

    private static String normalizeKeyword(String keyword) {
        return keyword == null ? "" : keyword.trim();
    }

    private DirectCopyDecision directCopyDecision(Path source, MediaProbe probe) {
        return directCopyDecision(extOf(source), probe.videoCodec(), probe.audioCodec(), probe.hasVideo());
    }

    private DirectCopyDecision directCopyDecision(String container, String videoCodecValue,
                                                   String audioCodecValue, boolean hasVideo) {
        SettingService.TranscodePolicy policy = settingService.transcodePolicy();
        if (!hasVideo) {
            return policy.transcodeAudioOnly()
                    ? new DirectCopyDecision(true, "已开启纯音频文件转码")
                    : new DirectCopyDecision(false, "纯音频文件按当前设置直接入库");
        }
        String ext = lower(container);
        String videoCodec = lower(videoCodecValue);
        String audioCodec = lower(audioCodecValue);
        List<String> mismatches = new ArrayList<>(3);
        if (!policy.directCopyContainers().contains(ext)) {
            mismatches.add(ext.isBlank() ? "未识别容器格式" : "容器 " + ext + " 未加入直拷白名单");
        }
        if (!policy.directCopyVideoCodecs().contains(videoCodec)) {
            mismatches.add(videoCodec.isBlank() ? "未识别视频编码" : "视频编码 " + videoCodec + " 未加入直拷白名单");
        }
        if (!policy.directCopyAudioCodecs().contains(audioCodec)) {
            mismatches.add(audioCodec.isBlank() ? "未识别音频编码" : "音频编码 " + audioCodec + " 未加入直拷白名单");
        }
        return mismatches.isEmpty()
                ? new DirectCopyDecision(false, "容器、视频编码和音频编码均满足直拷条件")
                : new DirectCopyDecision(true, String.join("；", mismatches));
    }

    private boolean hasStoredFormatAnalysis(MediaImportRecord record) {
        if (record.getMediaType() == null || record.getSourceFormat() == null) return false;
        return MediaClassifier.AUDIO.equals(record.getMediaType())
                || (record.getVideoCodec() != null && record.getAudioCodec() != null);
    }

    private record DirectCopyDecision(boolean transcodeRequired, String reason) {}

    private Path moveToTarget(Path source, Path targetRoot) throws IOException {
        LibraryModePolicy.requireManaged(props, "移动源文件");
        Path desired = targetRoot.resolve(source.getFileName());
        String filename = desired.getFileName().toString();
        int dot = filename.lastIndexOf('.');
        String base = dot > 0 ? filename.substring(0, dot) : filename;
        String extension = dot > 0 ? filename.substring(dot) : "";
        for (int index = 1; index < 10_000; index++) {
            Path candidate = index == 1 ? desired
                    : targetRoot.resolve(base + "-" + index + extension);
            try {
                // Files.move without REPLACE_EXISTING is the atomic no-overwrite
                // decision; if a concurrent mover wins, try the next name.
                return Files.move(source, candidate);
            } catch (FileAlreadyExistsException alreadyExists) {
                // Re-evaluate the next candidate after the collision.
            }
        }
        throw new ApiException("TARGET_NAME_EXHAUSTED", "无法为目标文件分配唯一文件名：" + desired);
    }

    private void restoreMovedFile(Path output, Path source) {
        try {
            if (Files.isRegularFile(output) && !Files.exists(source)) Files.move(output, source);
        } catch (IOException ignored) {
            // The original failure remains authoritative; the output is retained for manual recovery.
        }
    }

    private List<MovedCompanion> moveCompanions(Path source, Path output) throws IOException {
        List<MovedCompanion> moved = new ArrayList<>();
        try {
            for (String ext : new String[]{"lrc", "jpg", "jpeg", "png", "webp"}) {
                Path companion = source.resolveSibling(stripExtension(source.getFileName().toString()) + "." + ext);
                if (Files.isSymbolicLink(companion)) {
                    throw new IOException("拒绝移动符号链接伴随文件：" + companion);
                }
                if (!Files.isRegularFile(companion, LinkOption.NOFOLLOW_LINKS)) continue;
                Path target = output.resolveSibling(stripExtension(output.getFileName().toString()) + "." + ext);
                long size = Files.size(companion);
                try {
                    // No REPLACE_EXISTING: a concurrent import must not overwrite a
                    // user's existing lyric or cover.
                    Files.move(companion, target);
                } catch (FileAlreadyExistsException collision) {
                    throw new IOException("伴随文件目标已存在，拒绝覆盖：" + target, collision);
                }
                moved.add(new MovedCompanion(companion, target));
                if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) || Files.size(target) != size) {
                    throw new IOException("伴随文件迁移校验失败：" + companion);
                }
            }
            return moved;
        } catch (IOException failure) {
            restoreMovedCompanions(moved);
            throw failure;
        }
    }

    private void restoreMovedCompanions(List<MovedCompanion> moved) {
        if (moved == null) return;
        for (int index = moved.size() - 1; index >= 0; index--) {
            MovedCompanion companion = moved.get(index);
            try {
                if (!Files.exists(companion.target(), LinkOption.NOFOLLOW_LINKS)) continue;
                if (Files.exists(companion.source(), LinkOption.NOFOLLOW_LINKS)) {
                    log.warn("恢复伴随文件时源路径已有文件，保留目标文件：{}", companion.source());
                    continue;
                }
                Files.move(companion.target(), companion.source());
            } catch (IOException restoreFailure) {
                log.warn("恢复伴随文件失败 source={} target={}", companion.source(), companion.target(),
                        restoreFailure);
            }
        }
    }

    private Path transcodeToTarget(Path source, Path targetRoot, MediaProbe probe) {
        LibraryModePolicy.requireManaged(props, "转码源文件");
        SettingService.TranscodePolicy policy = settingService.transcodePolicy();
        String baseName = stripExtension(source.getFileName().toString());
        Path output = targetRoot.resolve(baseName + "." + policy.outputContainer());
        return mediaTranscoder.transcode(source, output, policy, probe.hasVideo());
    }

    private Path sourceRoot() {
        return Path.of(props.getSourceLibraryPath()).toAbsolutePath().normalize();
    }

    private Path targetRoot() {
        return Path.of(props.getKtvLibraryPath()).toAbsolutePath().normalize();
    }

    private static void ensureDirectories(Path sourceRoot, Path targetRoot) {
        if (!Files.isDirectory(sourceRoot)) {
            throw new ApiException("SOURCE_LIBRARY_MISSING", "扫描源目录不存在：" + sourceRoot);
        }
        try {
            Files.createDirectories(targetRoot);
        } catch (IOException e) {
            throw new ApiException("KTV_LIBRARY_CREATE_FAILED", "无法创建 KTV 曲库目录：" + targetRoot);
        }
    }

    private static String extOf(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private static String messageOf(Exception exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }

    private boolean cleanupImportedRecord(Long recordId) {
        LibraryModePolicy.requireManaged(props, "清理源文件");
        MediaImportRecord record = importRepo.findById(recordId).orElse(null);
        if (record == null || !isSafelyImported(record)) return false;
        try {
            deleteSourceAndCompanions(record);
            return true;
        } catch (Exception e) {
            record.setCleanupStatus("FAILED");
            record.setCleanupError(messageOf(e));
            record.setCleanupAttemptedAt(OffsetDateTime.now());
            importRepo.save(record);
            return false;
        }
    }

    private void deleteSourceAndCompanions(MediaImportRecord record) throws IOException {
        LibraryModePolicy.requireManaged(props, "删除源文件");
        Path source = Path.of(record.getSourcePath()).toAbsolutePath().normalize();
        if (!source.startsWith(sourceRoot())) throw new IOException("拒绝删除扫描源目录以外的文件");
        Files.deleteIfExists(source);
        if (record.getOutputPath() != null) {
            Path output = Path.of(record.getOutputPath()).toAbsolutePath().normalize();
            for (String ext : new String[]{"lrc", "jpg", "jpeg", "png", "webp"}) {
                Path companion = source.resolveSibling(stripExtension(source.getFileName().toString()) + "." + ext);
                Path target = output.resolveSibling(stripExtension(output.getFileName().toString()) + "." + ext);
                if (Files.isRegularFile(companion) && Files.isRegularFile(target)
                        && Files.size(companion) == Files.size(target)) Files.deleteIfExists(companion);
            }
        }
        removeSourceRecord(record);
    }

    private List<Path> migrateCompanions(Path source, Path output, MediaImportRecord record) throws IOException {
        LibraryModePolicy.requireManaged(props, "迁移源文件伴随资源");
        List<Path> copied = new ArrayList<>();
        List<String> migrated = new ArrayList<>();
        try {
            for (String ext : new String[]{"lrc", "jpg", "jpeg", "png", "webp"}) {
                Path companion = source.resolveSibling(stripExtension(source.getFileName().toString()) + "." + ext);
                if (!Files.isRegularFile(companion)) continue;
                Path target = output.resolveSibling(stripExtension(output.getFileName().toString()) + "." + ext);
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("伴随文件目标已存在，拒绝覆盖：" + target);
                }
                Files.copy(companion, target, StandardCopyOption.COPY_ATTRIBUTES);
                copied.add(target);
                if (!Files.isRegularFile(target) || Files.size(target) != Files.size(companion)) {
                    throw new IOException("伴随文件迁移校验失败：" + companion);
                }
                migrated.add(companion.getFileName().toString());
            }
        } catch (IOException failure) {
            deleteGeneratedFiles(null, copied);
            throw failure;
        }
        record.setCompanionFiles(migrated.isEmpty() ? "[]"
                : "[\"" + String.join("\",\"", migrated) + "\"]");
        return copied;
    }

    private void deleteGeneratedFiles(Path output, List<Path> companionFiles) {
        Path root = targetRoot().toAbsolutePath().normalize();
        List<Path> generated = new ArrayList<>();
        if (output != null) generated.add(output);
        if (companionFiles != null) generated.addAll(companionFiles);
        for (Path file : generated) {
            Path normalized = file.toAbsolutePath().normalize();
            if (!normalized.startsWith(root) || Files.isSymbolicLink(normalized)) continue;
            try {
                Files.deleteIfExists(normalized);
            } catch (IOException cleanupFailure) {
                log.warn("清理失败转码输出：{}", normalized, cleanupFailure);
            }
        }
    }

    @PreDestroy
    void shutdown() {
        scanExecutor.shutdownNow();
        transcodeExecutor.shutdownNow();
    }

    private enum ScanOutcome { COPIED, PENDING, SOURCE_DUPLICATE, OUTPUT_DUPLICATE, UNRECOGNIZED, UNCHANGED }
    private enum TranscodeOutcome { TRANSCODED, SOURCE_DUPLICATE, OUTPUT_DUPLICATE, FAILED }
    private record MovedCompanion(Path source, Path target) {}
}
