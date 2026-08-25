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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class MediaImportService {

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

        List<Path> files = mediaFiles(sourceRoot);
        OffsetDateTime startedAt = OffsetDateTime.now();
        scanProgress.set(new SourceScanProgress(true, files.size(), 0, null, 0, 0, 0, 0, 0, 0,
                startedAt, null));
        int copied = 0, pending = 0, sourceDup = 0, outputDup = 0, unrecognized = 0, failed = 0;
        for (int index = 0; index < files.size(); index++) {
            Path source = files.get(index);
            scanProgress.set(new SourceScanProgress(true, files.size(), index,
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
            scanProgress.set(new SourceScanProgress(true, files.size(), index + 1,
                    source.getFileName().toString(), copied, pending, sourceDup, outputDup, unrecognized, failed,
                    startedAt, null));
        }
        SourceScanResult result = new SourceScanResult(files.size(), copied, pending, sourceDup, outputDup,
                unrecognized, failed);
        scanProgress.set(new SourceScanProgress(false, files.size(), files.size(), null, copied, pending,
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
        List<MediaImportRecord> records = all
                ? importRepo.findAllByOrderByCreatedAtDesc()
                : importRepo.findByIdIn(recordIds);
        List<MediaImportRecord> candidates = records.stream()
                .filter(this::isTranscodable)
                .toList();
        synchronized (transcodeLock) {
            if (progress.get().running()) {
                throw new ApiException("TRANSCODE_ALREADY_RUNNING", "已有批量转码任务正在执行");
            }
            transcodeQueue.clear();
            priorityRecordIds.clear();
            currentRecordId = null;
            deleteSourcesForRun = Boolean.TRUE.equals(settingService.getAll().get(SettingService.DELETE_SOURCE_AFTER_TRANSCODE));
            candidates.forEach(record -> {
                record.setDeleteSourceRequested(deleteSourcesForRun);
                record.setCleanupStatus(deleteSourcesForRun ? "PENDING" : "NOT_REQUESTED");
                importRepo.save(record);
            });
            candidates.stream().map(MediaImportRecord::getId).forEach(transcodeQueue::addLast);
            boolean running = !transcodeQueue.isEmpty();
            TranscodeProgress initial = new TranscodeProgress(running, candidates.size(), 0, 0, 0, 0, 0, 0,
                    null, null, List.of(), OffsetDateTime.now(), running ? null : OffsetDateTime.now());
            progress.set(initial);
            if (running) transcodeExecutor.submit(this::runTranscodeQueue);
            return initial;
        }
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
        List<MediaImportRecord> records = importRepo.findAllByOrderByCreatedAtDesc();
        int eligible = 0, deleted = 0, skipped = 0, failed = 0;
        for (MediaImportRecord record : records) {
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
        return new AutoCleanupResult(records.size(), eligible, deleted, skipped, failed);
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
                        String actual = hashService.md5(output);
                        if (record.getOutputMd5() == null) record.setOutputMd5(actual);
                        return actual.equals(record.getOutputMd5());
                    }
                    catch (Exception e) { return false; }
                }).orElse(false);
    }

    public DeleteSourcesResult deleteSourcesByFilter(String keyword, String status, String formatAnalysis,
                                                     Boolean sourceDeleted) {
        LibraryModePolicy.requireManaged(props, "按条件删除源文件");
        SourceLibraryFilters filters = sourceLibraryFilters(status, formatAnalysis, sourceDeleted);
        List<Long> ids = importRepo.searchSourceLibrary(normalizeKeyword(keyword), filters.action(),
                        filters.duplicateFlag(), filters.transcodeRequired(), filters.sourceDeleted(), Pageable.unpaged())
                .getContent().stream()
                .map(MediaImportRecord::getId)
                .toList();
        return deleteSources(ids);
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
        String sourceMd5 = hashService.md5(source);
        MediaImportRecord existing = importRepo.findBySourcePath(source.toString()).orElse(null);
        if (existing != null && Objects.equals(existing.getSourceMd5(), sourceMd5)) {
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
        TagInfo tag = tagReader.read(source.toFile());
        ParsedMeta parsed = tag.hasTitle()
                ? ParsedMeta.of(tag.getTitle(), tag.getArtist())
                : FilenameParser.parse(source.getFileName().toString());
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
        try {
            String outputMd5 = hashService.md5(output);
            if (!Objects.equals(sourceMd5, outputMd5)) {
                throw new IOException("移动后文件 MD5 校验失败");
            }
            ingest = scanService.ingestLibraryFile(output, source, sourceMd5, outputMd5, false);
            if (!ingest.imported() || ingest.songId() == null || ingest.songFileId() == null) {
                throw new IOException("移动后的文件未能写入 KTV 曲库");
            }
        } catch (Exception e) {
            restoreMovedFile(output, source);
            throw e;
        }
        markSongFileSourceDeleted(ingest.songFileId());
        if (existing != null) importRepo.delete(existing);
        return ScanOutcome.COPIED;
    }

    private void runTranscodeQueue() {
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
    }

    private TranscodeOutcome transcodeRecord(MediaImportRecord record) {
        try {
            LibraryModePolicy.requireManaged(props, "转码源文件");
            if (record.isSourceDeleted() || !Files.isRegularFile(Path.of(record.getSourcePath()))) {
                throw new ApiException("SOURCE_FILE_MISSING", "源文件不存在或已删除");
            }
            Path source = Path.of(record.getSourcePath());
            String sourceMd5 = hashService.md5(source);
            if (!Objects.equals(sourceMd5, record.getSourceMd5()) || isSourceDuplicate(source, sourceMd5)) {
                record.setAction(SOURCE_DUPLICATE);
                record.setDuplicateFlag(true);
                record.setReason("源文件 MD5 已存在或扫描后发生变化，跳过");
                importRepo.save(record);
                return TranscodeOutcome.SOURCE_DUPLICATE;
            }
            MediaProbe probe = ffprobeService.probe(source);
            Path output = transcodeToTarget(source, targetRoot(), probe);
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
            LibraryScanService.IngestResult ingest = scanService.ingestLibraryFile(
                    output, source, sourceMd5, outputMd5, true);
            migrateCompanions(source, output, record);
            upsertRecord(source, sourceMd5, output, probe, outputMd5, TRANSCODED,
                    "已转码入 KTV 曲库", true, false, true, ingest.songId(), ingest.songFileId());
            if (record.isDeleteSourceRequested()) cleanupImportedRecord(record.getId());
            return TranscodeOutcome.TRANSCODED;
        } catch (Exception e) {
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
        ParsedMeta parsed = FilenameParser.parse(source.getFileName().toString());
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
        if (record.getCleanupStatus() == null) record.setCleanupStatus("NOT_REQUESTED");
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
        Path output = uniqueTarget(targetRoot.resolve(source.getFileName()));
        Files.move(source, output);
        return output;
    }

    private void restoreMovedFile(Path output, Path source) {
        try {
            if (Files.isRegularFile(output) && !Files.exists(source)) Files.move(output, source);
        } catch (IOException ignored) {
            // The original failure remains authoritative; the output is retained for manual recovery.
        }
    }

    private Path transcodeToTarget(Path source, Path targetRoot, MediaProbe probe) {
        LibraryModePolicy.requireManaged(props, "转码源文件");
        SettingService.TranscodePolicy policy = settingService.transcodePolicy();
        String baseName = stripExtension(source.getFileName().toString());
        Path output = uniqueTarget(targetRoot.resolve(baseName + "." + policy.outputContainer()));
        return mediaTranscoder.transcode(source, output, policy, probe.hasVideo());
    }

    private Path uniqueTarget(Path desired) {
        if (!Files.exists(desired)) return desired;
        String base = stripExtension(desired.getFileName().toString());
        String ext = extOf(desired);
        for (int index = 2; index < 10000; index++) {
            Path candidate = desired.getParent().resolve(base + "-" + index + (ext.isBlank() ? "" : "." + ext));
            if (!Files.exists(candidate)) return candidate;
        }
        throw new ApiException("TARGET_NAME_EXHAUSTED", "无法为输出文件分配唯一文件名：" + desired);
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

    private static List<Path> mediaFiles(Path root) {
        List<Path> files = new ArrayList<>();
        try (var stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile).filter(LibraryScanService::isMediaFile).forEach(files::add);
        } catch (IOException e) {
            throw new ApiException("SOURCE_SCAN_FAILED", "遍历扫描源目录失败：" + e.getMessage());
        }
        return files;
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

    private void migrateCompanions(Path source, Path output, MediaImportRecord record) throws IOException {
        LibraryModePolicy.requireManaged(props, "迁移源文件伴随资源");
        List<String> migrated = new ArrayList<>();
        for (String ext : new String[]{"lrc", "jpg", "jpeg", "png", "webp"}) {
            Path companion = source.resolveSibling(stripExtension(source.getFileName().toString()) + "." + ext);
            if (!Files.isRegularFile(companion)) continue;
            Path target = output.resolveSibling(stripExtension(output.getFileName().toString()) + "." + ext);
            Files.copy(companion, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            if (!Files.isRegularFile(target) || Files.size(target) != Files.size(companion)) throw new IOException("伴随文件迁移校验失败：" + companion);
            migrated.add(companion.getFileName().toString());
        }
        record.setCompanionFiles("[\"" + String.join("\",\"", migrated).replace("\"", "") + "\"]");
    }

    @PreDestroy
    void shutdown() {
        scanExecutor.shutdownNow();
        transcodeExecutor.shutdownNow();
    }

    private enum ScanOutcome { COPIED, PENDING, SOURCE_DUPLICATE, OUTPUT_DUPLICATE, UNRECOGNIZED, UNCHANGED }
    private enum TranscodeOutcome { TRANSCODED, SOURCE_DUPLICATE, OUTPUT_DUPLICATE, FAILED }
}
