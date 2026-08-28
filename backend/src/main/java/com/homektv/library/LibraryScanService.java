package com.homektv.library;

import com.homektv.config.AppProperties;
import com.homektv.domain.AudioLayout;
import com.homektv.domain.Song;
import com.homektv.domain.SongFile;
import com.homektv.media.FFprobeService;
import com.homektv.media.MediaProbe;
import com.homektv.media.MediaProbeException;
import com.homektv.repo.SongFileRepository;
import com.homektv.repo.SongFileScanSnapshot;
import com.homektv.repo.SongRepository;
import com.homektv.web.ApiException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import jakarta.annotation.PreDestroy;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 曲库扫描入库管线（P1.1-P1.5，详设§9.3）。
 * 枚举文件 → ffprobe 探测 → 标签解析 → 文件名兜底 → 类型判定
 * → 指纹去重 → 拼音字段 → 歌词/封面落盘 → 写 songs/song_files。
 *
 * Library scanning and ingestion pipeline (P1.1-P1.5, Detailed Design §9.3).
 * Enumerates files, probes media, parses tags, applies filename fallbacks,
 * classifies media, deduplicates by fingerprint, generates Pinyin fields,
 * stores lyrics/covers, and persists songs/song_files.
 */
@Service
public class LibraryScanService {

    private static final Logger log = LoggerFactory.getLogger(LibraryScanService.class);

    private static final Set<String> MEDIA_EXT = Set.of(
            "mkv", "mp4", "m4v", "avi", "mov", "ts", "m2ts", "mts", "mpg", "mpeg",
            "vob", "webm", "wmv", "asf", "flv", "f4v", "3gp", "3g2", "rm", "rmvb", // 视频
            "mp3", "mp2", "aac", "flac", "wav", "m4a", "ape", "ogg", "oga", "opus",
            "ac3", "eac3", "dts", "mka", "wma", "aiff", "aif", "alac" // 音频
    );
    static final int FAST_INDEX_BATCH_SIZE = 500;
    static final int PROBE_PAGE_SIZE = 64;
    private static final String PHASE_DISCOVERING = "DISCOVERING";
    private static final String PHASE_FAST_INDEX = "FAST_INDEX";
    private static final String PHASE_MEDIA_PROBE = "MEDIA_PROBE";
    private static final String PHASE_COMPLETED = "COMPLETED";

    private final AppProperties props;
    private final FFprobeService ffprobe;
    private final TagReader tagReader;
    private final SongRepository songRepo;
    private final SongFileRepository fileRepo;
    private final AssetWriter assetWriter;
    private final SettingService settingService;
    private final ExecutorService scanExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "library-scan");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicReference<ScanProgress> scanProgress = new AtomicReference<>(ScanProgress.idle());
    private final Object scanLock = new Object();
    private final Object artistIndexLock = new Object();
    private final TransactionTemplate batchTransaction;
    @PersistenceContext
    private EntityManager entityManager;
    private volatile Set<String> cachedArtistNames = Set.of();
    private volatile FilenameParser.ArtistIndex cachedArtistIndex =
            FilenameParser.prepareKnownArtists(Set.of());

    public LibraryScanService(AppProperties props, FFprobeService ffprobe, TagReader tagReader,
                              SongRepository songRepo, SongFileRepository fileRepo, AssetWriter assetWriter) {
        this(props, ffprobe, tagReader, songRepo, fileRepo, assetWriter, null, null);
    }

    public LibraryScanService(AppProperties props, FFprobeService ffprobe, TagReader tagReader,
                              SongRepository songRepo, SongFileRepository fileRepo, AssetWriter assetWriter,
                              SettingService settingService) {
        this(props, ffprobe, tagReader, songRepo, fileRepo, assetWriter, settingService, null);
    }

    @Autowired
    public LibraryScanService(AppProperties props, FFprobeService ffprobe, TagReader tagReader,
                              SongRepository songRepo, SongFileRepository fileRepo, AssetWriter assetWriter,
                              SettingService settingService, PlatformTransactionManager transactionManager) {
        this.props = props;
        this.ffprobe = ffprobe;
        this.tagReader = tagReader;
        this.songRepo = songRepo;
        this.fileRepo = fileRepo;
        this.assetWriter = assetWriter;
        this.settingService = settingService;
        this.batchTransaction = transactionManager == null ? null : new TransactionTemplate(transactionManager);
    }

    public record ScanResult(int scanned, int added, int updated, int skipped, int unrecognized,
                             int fastIndexed, int probeQueued, int probeCalls, int hashCalls,
                             int dbUpdates, int missing) {
        public ScanResult(int scanned, int added, int updated, int skipped, int unrecognized) {
            this(scanned, added, updated, skipped, unrecognized, 0, 0, 0, 0, 0, 0);
        }
    }
    public record IngestResult(boolean imported, Long songId, Long songFileId) {}
    public record ScanProgress(boolean running, int total, int completed, String currentFile,
                               int added, int updated, int skipped, int unrecognized,
                               OffsetDateTime startedAt, OffsetDateTime finishedAt,
                               String phase, int discovered, int fastIndexed, int probeQueued,
                               int probeCompleted) {
        public ScanProgress(boolean running, int total, int completed, String currentFile,
                            int added, int updated, int skipped, int unrecognized,
                            OffsetDateTime startedAt, OffsetDateTime finishedAt) {
            this(running, total, completed, currentFile, added, updated, skipped, unrecognized,
                    startedAt, finishedAt,
                    finishedAt != null ? PHASE_COMPLETED
                            : running && total == 0 ? PHASE_DISCOVERING
                            : running ? PHASE_MEDIA_PROBE : PHASE_COMPLETED,
                    total, total, 0, Math.max(0, completed));
        }

        static ScanProgress idle() {
            return new ScanProgress(false, 0, 0, null, 0, 0, 0, 0,
                    null, null, "IDLE", 0, 0, 0, 0);
        }
    }

    /** 全量/增量扫描曲库根目录。Fast Index 与数据库持久化 Media Probe Queue 分阶段执行。 */
    public ScanResult scanAll() {
        synchronized (scanLock) {
            OffsetDateTime startedAt = OffsetDateTime.now();
            publishProgress(true, 0, 0, null, new ScanTotals(), PHASE_DISCOVERING,
                    0, 0, 0, startedAt, null);
            Path root = LibraryModePolicy.activeLibraryRoot(props).toAbsolutePath().normalize();
            if (!Files.isDirectory(root)) {
                log.warn("曲库目录不存在：{}", root);
                ScanResult result = new ScanResult(0, 0, 0, 0, 0);
                publishProgress(false, 0, 0, null, new ScanTotals(), PHASE_COMPLETED,
                        0, 0, 0, startedAt, OffsetDateTime.now());
                return result;
            }

            ScanRootContext rootContext;
            try {
                rootContext = ScanRootContext.create(root, LibraryModePolicy.isExternalReadOnly(props));
            } catch (RuntimeException failure) {
                log.error("无法安全解析曲库根目录，停止本轮扫描：{}", failure.getMessage());
                publishProgress(false, 0, 0, null, new ScanTotals(), PHASE_COMPLETED,
                        0, 0, 0, startedAt, OffsetDateTime.now());
                return new ScanResult(0, 0, 0, 0, 0);
            }

            Set<String> knownArtists = existingArtistNames();
            AudioLayout externalDefault = LibraryModePolicy.isExternalReadOnly(props)
                    ? configuredExternalDefaultAudioLayout() : null;
            FilenameParser.ArtistIndex artistIndex = artistIndexFor(knownArtists);
            String activeRole = activeFileRole();
            ScanStartSnapshot scanStart = loadScanStart(activeRole);
            Set<Long> trackedIdsAtScanStart = scanStart.ids();
            Set<String> trackedPathsAtScanStart = scanStart.paths();
            Set<Long> pendingIdsAtScanStart = scanStart.pendingIds();
            Set<String> pendingPathsAtScanStart = scanStart.pendingPaths();

            List<FastIndexEntry> batch = new ArrayList<>(FAST_INDEX_BATCH_SIZE);
            Set<String> currentPaths = new HashSet<>();
            ScanCounters counters = new ScanCounters();
            ScanTotals totals = new ScanTotals();
            int[] discovered = {0};
            boolean[] enumerationComplete = {true};
            try {
                Files.walkFileTree(root, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        return rootContext.allowsVisitedDirectory(dir)
                                ? FileVisitResult.CONTINUE : FileVisitResult.SKIP_SUBTREE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        if (!attrs.isRegularFile() || Files.isSymbolicLink(file)
                                || !isMediaFile(file) || !rootContext.allowsVisitedFile(file)) {
                            return FileVisitResult.CONTINUE;
                        }
                        try {
                            FastIndexEntry entry = fastIndex(file, attrs, artistIndex,
                                    Map.of(), Map.of(), rootContext.configuredRoot());
                            batch.add(entry);
                            currentPaths.add(entry.path());
                            counters.fastIndexed++;
                            discovered[0]++;
                            if (batch.size() >= FAST_INDEX_BATCH_SIZE) {
                                processFastIndexBatch(batch, externalDefault, counters, totals,
                                        startedAt, discovered[0], activeRole,
                                        trackedIdsAtScanStart, trackedPathsAtScanStart);
                                batch.clear();
                            }
                        } catch (RuntimeException failure) {
                            enumerationComplete[0] = false;
                            log.warn("快速索引失败，跳过：{} - {}", file, failure.getMessage());
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException failure) {
                        enumerationComplete[0] = false;
                        log.warn("读取曲库文件失败，保留待重试：{} - {}", file, failure.getMessage());
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException e) {
                enumerationComplete[0] = false;
                log.error("遍历曲库失败：{}", e.getMessage());
            }
            if (!batch.isEmpty()) {
                processFastIndexBatch(batch, externalDefault, counters, totals,
                        startedAt, discovered[0], activeRole,
                        trackedIdsAtScanStart, trackedPathsAtScanStart);
                batch.clear();
            }

            if (enumerationComplete[0]) {
                markMissingFiles(root, currentPaths, counters, activeRole);
            } else {
                log.warn("曲库枚举未完整结束，本轮不标记消失文件，等待下次扫描重试：{}", root);
            }

            long pendingCount = fileRepo.countByFileRoleAndProbePendingTrue(activeRole);
            counters.probeQueued = safeInt(pendingCount);
            publishProgress(true, discovered[0], counters.fastIndexed, null, totals,
                    PHASE_MEDIA_PROBE, counters.fastIndexed, counters.probeQueued, 0,
                    startedAt, null);
            processPendingProbes(rootContext, artistIndex, knownArtists, externalDefault,
                    activeRole, trackedIdsAtScanStart, trackedPathsAtScanStart,
                    pendingIdsAtScanStart, pendingPathsAtScanStart,
                    counters, totals, startedAt, discovered[0]);

            log.info("扫描完成：共 {} 文件，新增 {}，更新 {}，跳过 {}，未识别 {}",
                    discovered[0], totals.added, totals.updated, totals.skipped, totals.unrecognized);
            ScanResult result = new ScanResult(discovered[0], totals.added, totals.updated,
                    totals.skipped, totals.unrecognized, counters.fastIndexed,
                    counters.probeQueued, counters.probeCalls, counters.hashCalls,
                    counters.dbUpdates, counters.missing);
            publishProgress(false, discovered[0], discovered[0], null, totals, PHASE_COMPLETED,
                    counters.fastIndexed, counters.probeQueued, totals.probeCompleted,
                    startedAt, OffsetDateTime.now());
            return result;
        }
    }

    private void processFastIndexBatch(List<FastIndexEntry> batch, AudioLayout externalDefault,
                                       ScanCounters counters, ScanTotals totals,
                                       OffsetDateTime startedAt, int discovered, String activeRole,
                                       Set<Long> trackedIdsAtScanStart,
                                       Set<String> trackedPathsAtScanStart) {
        if (batch.isEmpty()) return;
        List<FastIndexEntry> resolvedBatch;
        try {
            resolvedBatch = resolveExistingEntries(batch, activeRole,
                    trackedIdsAtScanStart, trackedPathsAtScanStart);
        } catch (RuntimeException failure) {
            // Do not treat a failed lookup as "all new". That could create
            // duplicate provisional rows or bind the wrong remounted file.
            log.warn("读取当前扫描批次的已有记录失败，等待下次扫描重试：{}", failure.getMessage());
            publishProgress(true, discovered, counters.fastIndexed, null, totals,
                    PHASE_FAST_INDEX, counters.fastIndexed, 0, totals.probeCompleted,
                    startedAt, null);
            return;
        }
        Runnable work = () -> {
            for (FastIndexEntry entry : resolvedBatch) {
                switch (snapshotChange(entry)) {
                    case UNCHANGED -> {
                        reactivateIfNeeded(entry, counters);
                        totals.skipped++;
                    }
                    case LYRIC_ONLY -> {
                        LyricRefreshOutcome outcome = refreshSidecarLyric(entry, counters);
                        if (outcome == LyricRefreshOutcome.NEEDS_MEDIA_PROBE) {
                            prepareFastIndex(entry, counters, externalDefault, true);
                        } else if (outcome == LyricRefreshOutcome.UPDATED) {
                            totals.updated++;
                        } else {
                            totals.skipped++;
                        }
                    }
                    case MEDIA_CHANGED -> prepareFastIndex(entry, counters, externalDefault);
                }
            }
        };
        try {
            if (batchTransaction == null) {
                work.run();
            } else {
                batchTransaction.executeWithoutResult(status -> {
                    work.run();
                    if (entityManager != null) entityManager.flush();
                });
            }
        } catch (RuntimeException failure) {
            // The batch is intentionally isolated. A failed batch is retried by the
            // next scan; do not turn a single bad file or a transient DB error into a
            // destructive "all files disappeared" result.
            log.warn("快速索引批次失败，等待下次扫描重试：{}", failure.getMessage());
        }
        publishProgress(true, discovered, counters.fastIndexed, null, totals,
                PHASE_FAST_INDEX, counters.fastIndexed, 0, totals.probeCompleted,
                startedAt, null);
    }

    /**
     * Resolve only the current Fast Index batch.  The old implementation loaded
     * every SongFile entity before walking the filesystem; on a large NAS library
     * that duplicated the same unbounded-result-set failure as Song.findAll().
     */
    private List<FastIndexEntry> resolveExistingEntries(List<FastIndexEntry> batch,
                                                        String activeRole,
                                                        Set<Long> trackedIdsAtScanStart,
                                                        Set<String> trackedPathsAtScanStart) {
        Set<String> paths = batch.stream().map(FastIndexEntry::path)
                .filter(Objects::nonNull).collect(java.util.stream.Collectors.toSet());
        List<SongFile> exactRows = fileRepo.findByFileRoleAndFilePathIn(activeRole, paths);
        Map<String, SongFile> byPath = new HashMap<>();
        if (exactRows != null) {
            for (SongFile row : exactRows) {
                if (row != null && row.getFilePath() != null) byPath.put(row.getFilePath(), row);
            }
        }

        Set<String> relativePaths = batch.stream()
                .filter(entry -> !byPath.containsKey(entry.path()))
                .map(FastIndexEntry::relativePath)
                .filter(path -> !isBlank(path))
                .collect(java.util.stream.Collectors.toSet());
        Map<String, SongFile> byUniqueRelativePath = new HashMap<>();
        Set<String> ambiguous = new HashSet<>();
        if (!relativePaths.isEmpty()) {
            List<SongFile> relativeRows = fileRepo.findByFileRoleAndRelativePathIn(activeRole, relativePaths);
            if (relativeRows != null) {
                for (SongFile row : relativeRows) {
                    if (row == null || isBlank(row.getRelativePath())
                            || ambiguous.contains(row.getRelativePath())) continue;
                    SongFile previous = byUniqueRelativePath.putIfAbsent(row.getRelativePath(), row);
                    if (previous != null && !Objects.equals(previous.getId(), row.getId())) {
                        byUniqueRelativePath.remove(row.getRelativePath());
                        ambiguous.add(row.getRelativePath());
                    }
                }
            }
        }

        List<FastIndexEntry> resolved = new ArrayList<>(batch.size());
        for (FastIndexEntry entry : batch) {
            SongFile existing = byPath.get(entry.path());
            if (existing == null && !isBlank(entry.relativePath())) {
                existing = byUniqueRelativePath.get(entry.relativePath());
            }
            boolean existedAtScanStart = wasPresentAtScanStart(existing,
                    trackedIdsAtScanStart, trackedPathsAtScanStart);
            resolved.add(entry.withExisting(existing)
                    .withScanHistory(existedAtScanStart, existedAtScanStart
                            && existing != null && existing.isProbePending()));
        }
        return resolved;
    }

    /** Load only the scan-start identity set and pending flags, not full entities. */
    private ScanStartSnapshot loadScanStart(String activeRole) {
        Set<Long> ids = new HashSet<>();
        Set<String> paths = new HashSet<>();
        Set<Long> pendingIds = new HashSet<>();
        Set<String> pendingPaths = new HashSet<>();
        String afterPath = "";
        while (true) {
            Slice<SongFileScanSnapshot> page;
            try {
                page = fileRepo.findScanSnapshotsByFileRoleAndFilePathGreaterThanOrderByFilePath(
                        activeRole, afterPath, PageRequest.of(0, FAST_INDEX_BATCH_SIZE));
            } catch (RuntimeException failure) {
                log.warn("读取扫描快照失败，停止本轮扫描以避免误判已有记录：{}", failure.getMessage());
                return new ScanStartSnapshot(Set.of(), Set.of(), Set.of(), Set.of());
            }
            if (page == null || page.getContent().isEmpty()) break;
            for (SongFileScanSnapshot snapshot : page.getContent()) {
                if (snapshot == null || isBlank(snapshot.getFilePath())) continue;
                afterPath = snapshot.getFilePath();
                if (snapshot.getId() != null) ids.add(snapshot.getId());
                paths.add(snapshot.getFilePath());
                if (Boolean.TRUE.equals(snapshot.getProbePending())) {
                    if (snapshot.getId() != null) pendingIds.add(snapshot.getId());
                    pendingPaths.add(snapshot.getFilePath());
                }
            }
            if (page.getContent().size() < FAST_INDEX_BATCH_SIZE) break;
        }
        return new ScanStartSnapshot(Set.copyOf(ids), Set.copyOf(paths),
                Set.copyOf(pendingIds), Set.copyOf(pendingPaths));
    }

    private void processPendingProbes(ScanRootContext rootContext,
                                      FilenameParser.ArtistIndex artistIndex,
                                      Collection<String> knownArtists,
                                      AudioLayout externalDefault,
                                      String activeRole,
                                      Set<Long> trackedIdsAtScanStart,
                                      Set<String> trackedPathsAtScanStart,
                                      Set<Long> pendingIdsAtScanStart,
                                      Set<String> pendingPathsAtScanStart,
                                      ScanCounters counters,
                                      ScanTotals totals,
                                      OffsetDateTime startedAt,
                                      int discovered) {
        String afterPath = "";
        while (true) {
            Slice<SongFile> page;
            try {
                page = fileRepo.findByFileRoleAndProbePendingTrueAndFilePathGreaterThanOrderByFilePath(
                        activeRole, afterPath, PageRequest.of(0, PROBE_PAGE_SIZE));
            } catch (RuntimeException failure) {
                log.warn("读取媒体探测队列失败，保留 pending 记录等待重试：{}", failure.getMessage());
                return;
            }
            if (page == null || page.getContent().isEmpty()) return;

            for (SongFile tracked : page.getContent()) {
                if (tracked == null || isBlank(tracked.getFilePath())) continue;
                afterPath = tracked.getFilePath();
                Path file;
                try {
                    file = Path.of(tracked.getFilePath());
                } catch (RuntimeException invalidPath) {
                    log.warn("探测队列存在无效路径，保留 pending：{}", tracked.getFilePath());
                    continue;
                }
                if (!rootContext.allowsExistingFile(file) || !Files.isRegularFile(file)
                        || Files.isSymbolicLink(file) || !isMediaFile(file)) {
                    continue;
                }
                try {
                    BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class,
                            LinkOption.NOFOLLOW_LINKS);
                    Map<String, SongFile> byPath = Map.of(tracked.getFilePath(), tracked);
                    Map<String, SongFile> byRelativePath = isBlank(tracked.getRelativePath())
                            ? Map.of() : Map.of(tracked.getRelativePath(), tracked);
                    FastIndexEntry entry = fastIndex(file, attrs, artistIndex, byPath,
                            byRelativePath, rootContext.configuredRoot());
                    SongFile resolved = entry.existing().orElse(null);
                    boolean existedAtScanStart = wasPresentAtScanStart(resolved,
                            trackedIdsAtScanStart, trackedPathsAtScanStart);
                    boolean pendingAtScanStart = resolved != null
                            && (resolved.getId() != null && pendingIdsAtScanStart.contains(resolved.getId())
                            || resolved.getId() == null && pendingPathsAtScanStart.contains(resolved.getFilePath()));
                    entry = entry.withScanHistory(existedAtScanStart,
                            pendingAtScanStart);
                    IngestOutcome outcome = ingestInternal(entry, null, null, null, false,
                            knownArtists, counters, externalDefault).outcome();
                    recordOutcome(totals, outcome);
                } catch (Exception failure) {
                    // Failed media reads stay pending. Advancing the local cursor makes
                    // one bad file unable to starve the rest of this run; the next run
                    // starts at the beginning and retries it.
                    totals.skipped++;
                    log.warn("媒体探测失败，保留 pending：{} - {}", file, failure.getMessage());
                }
                totals.probeCompleted++;
                publishProgress(true, discovered, counters.fastIndexed, file.getFileName().toString(), totals,
                        PHASE_MEDIA_PROBE, counters.fastIndexed, counters.probeQueued,
                        totals.probeCompleted, startedAt, null);
            }
            if (page.getContent().size() < PROBE_PAGE_SIZE) return;
        }
    }

    private static void recordOutcome(ScanTotals totals, IngestOutcome outcome) {
        switch (outcome) {
            case ADDED -> totals.added++;
            case UPDATED -> totals.updated++;
            case SKIPPED -> totals.skipped++;
            case UNRECOGNIZED -> {
                totals.added++;
                totals.unrecognized++;
            }
        }
    }

    private static boolean wasPresentAtScanStart(SongFile file, Set<Long> ids, Set<String> paths) {
        if (file == null) return false;
        return file.getId() != null && ids.contains(file.getId())
                || file.getId() == null && paths.contains(file.getFilePath());
    }

    private void publishProgress(boolean running, int total, int completed, String currentFile,
                                 ScanTotals totals, String phase, int fastIndexed, int probeQueued,
                                 int probeCompleted, OffsetDateTime startedAt,
                                 OffsetDateTime finishedAt) {
        scanProgress.set(new ScanProgress(running, total, completed, currentFile,
                totals.added, totals.updated, totals.skipped, totals.unrecognized,
                startedAt, finishedAt, phase, total, fastIndexed, probeQueued, probeCompleted));
    }

    private static int safeInt(long value) {
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) Math.max(0, value);
    }

    /** Starts the active library scan asynchronously for the admin progress endpoint. */
    public ScanProgress startScan() {
        synchronized (scanLock) {
            ScanProgress current = scanProgress.get();
            if (current.running()) return current;
            scanProgress.set(new ScanProgress(true, 0, 0, null, 0, 0, 0, 0,
                    OffsetDateTime.now(), null));
            scanExecutor.submit(() -> {
                try {
                    scanAll();
                } catch (RuntimeException exception) {
                    ScanProgress failed = scanProgress.get();
                    scanProgress.set(new ScanProgress(false, failed.total(), failed.completed(), null,
                            failed.added(), failed.updated(), failed.skipped() + 1,
                            failed.unrecognized(), failed.startedAt(), OffsetDateTime.now()));
                }
            });
            return scanProgress.get();
        }
    }

    public ScanProgress getScanProgress() {
        return scanProgress.get();
    }

    /** Shared filename parsing entry point for Managed imports and active-library scans. */
    public ParsedMeta parseFilename(String filename) {
        return FilenameParser.parse(filename, artistIndexFor(existingArtistNames()));
    }

    /**
     * Fast Index 阶段只读取目录项属性和文件名元数据；不会打开媒体内容。
     * The fast-index phase reads directory attributes and filename metadata only;
     * it never opens the media payload.
     */
    private FastIndexEntry fastIndex(Path file, Collection<String> knownArtists) {
        try {
            BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
            return fastIndex(file, attrs, artistIndexFor(knownArtists), null, null,
                    LibraryModePolicy.activeLibraryRoot(props).toAbsolutePath().normalize());
        } catch (IOException e) {
            throw new IllegalStateException("读取文件属性失败：" + file, e);
        }
    }

    private FastIndexEntry fastIndex(Path file, BasicFileAttributes attrs,
                                     FilenameParser.ArtistIndex artistIndex,
                                     Map<String, SongFile> trackedByPath,
                                     Map<String, SongFile> trackedByRelativePath,
                                     Path scanRoot) {
        String path = file.toString();
        Path sidecarLyric = sidecarLyricOf(file);
        OffsetDateTime mediaMtime = attrs.lastModifiedTime().toInstant().atOffset(ZoneOffset.UTC);
        String mediaIdentity = mediaFileIdentity(attrs);
        LyricSnapshot lyricSnapshot = lyricSnapshotOf(sidecarLyric);
        String relativePath = relativePathOf(file, scanRoot);
        SongFile tracked = trackedByPath == null
                ? fileRepo.findByFilePath(path).orElse(null)
                : trackedByPath.get(path);
        if (tracked == null && trackedByRelativePath != null && !isBlank(relativePath)) {
            tracked = trackedByRelativePath.get(relativePath);
        }
        return new FastIndexEntry(file, path, relativePath, attrs.size(), newestMtime(mediaMtime, lyricSnapshot),
                legacyFileIdentity(mediaIdentity, lyricSnapshot.fileIdentity()),
                FilenameParser.parse(file.getFileName().toString(), artistIndex),
                mediaMtime, mediaIdentity, lyricSnapshot,
                Optional.ofNullable(tracked), tracked != null, tracked != null && tracked.isProbePending());
    }

    /**
     * Compare media and sidecar snapshots independently. A sidecar-only change must not
     * enqueue a media probe, while an old row without independent snapshots is handled
     * conservatively and upgraded during the next safe fast-index pass.
     */
    private static SnapshotChange snapshotChange(FastIndexEntry entry) {
        SongFile existing = entry.existing().orElse(null);
        if (existing == null || existing.isProbePending()) return SnapshotChange.MEDIA_CHANGED;
        if (!mediaSnapshotMatches(existing, entry)) return SnapshotChange.MEDIA_CHANGED;

        if (existing.getLyricSnapshotVersion() == null) {
            // Legacy rows do not tell us whether an in-place LRC edit occurred.
            // Probe only rows that actually have a sidecar; rows without one can be
            // upgraded without opening the media payload.
            if (!entry.lyricSnapshot().readable()) return SnapshotChange.LYRIC_ONLY;
            return entry.lyricSnapshot().present()
                    ? SnapshotChange.MEDIA_CHANGED : SnapshotChange.UNCHANGED;
        }
        if (!entry.lyricSnapshot().readable()
                || !lyricSnapshotMatches(existing, entry.lyricSnapshot())) {
            return SnapshotChange.LYRIC_ONLY;
        }
        return SnapshotChange.UNCHANGED;
    }

    private static boolean mediaSnapshotMatches(SongFile existing, FastIndexEntry entry) {
        if (existing.getMediaMtime() != null || existing.getMediaFileIdentity() != null) {
            return existing.getFileSize() == entry.size()
                    && sameMtime(existing.getMediaMtime(), entry.mediaMtime())
                    && sameFileIdentity(existing.getMediaFileIdentity(), entry.mediaIdentity());
        }
        // V20 is additive. Keep interpreting pre-V20 rows through their legacy
        // composite fields until their first safe fast-index pass.
        return existing.getFileSize() == entry.size()
                && sameMtime(existing.getFileMtime(), entry.mtime())
                && sameFileIdentity(existing.getFileIdentity(), entry.fileIdentity());
    }

    private static boolean lyricSnapshotMatches(SongFile existing, LyricSnapshot current) {
        return Objects.equals(existing.getLyricSize(), current.size())
                && sameNullableMtime(existing.getLyricMtime(), current.mtime())
                && sameFileIdentity(existing.getLyricFileIdentity(), current.fileIdentity());
    }

    private static boolean sameNullableMtime(OffsetDateTime left, OffsetDateTime right) {
        return left == null && right == null || sameMtime(left, right);
    }

    private static boolean sameFileIdentity(String stored, String current) {
        return stored == null && current == null
                || stored != null && current != null && stored.equals(current);
    }

    private static boolean sameMtime(OffsetDateTime left, OffsetDateTime right) {
        return left != null && right != null
                && mtimeKey(left) == mtimeKey(right);
    }

    private static long mtimeKey(OffsetDateTime value) {
        var instant = value.toInstant();
        return instant.getEpochSecond() * 1_000_000L + instant.getNano() / 1_000;
    }

    private static OffsetDateTime normalizeMtime(OffsetDateTime value) {
        if (value == null) return null;
        return value.withNano((value.getNano() / 1_000) * 1_000);
    }

    /** Persist filename metadata before opening the media file for FFprobe. */
    private FastIndexEntry prepareFastIndex(FastIndexEntry entry, ScanCounters counters,
                                            AudioLayout externalDefault) {
        return prepareFastIndex(entry, counters, externalDefault, false);
    }

    private FastIndexEntry prepareFastIndex(FastIndexEntry entry, ScanCounters counters,
                                            AudioLayout externalDefault, boolean forceMediaProbe) {
        SongFile existing = entry.existing().orElse(null);
        OffsetDateTime normalizedMtime = normalizeMtime(entry.mtime());
        if (existing != null) {
            boolean changed = forceMediaProbe
                    || snapshotChange(entry) == SnapshotChange.MEDIA_CHANGED
                    || !Objects.equals(existing.getFilePath(), entry.path())
                    || !existing.isValid();
            if (changed) {
                existing.setFilePath(entry.path());
                existing.setFileSize(entry.size());
                existing.setFileMtime(normalizedMtime);
                existing.setFileIdentity(entry.fileIdentity());
                applySnapshots(existing, entry);
                existing.setProbePending(true);
                existing.setValid(true);
            }
            String relativePath = entry.relativePath();
            if (isBlank(existing.getRelativePath()) && relativePath != null) {
                existing.setRelativePath(relativePath);
                changed = true;
            }
            reconcilePendingFilenameStatus(entry, existing, counters);
            if (changed) {
                fileRepo.save(existing);
                counters.dbUpdates++;
            }
            return entry.withExisting(existing);
        }

        Song provisional = new Song();
        ParsedMeta parsed = entry.filenameMeta();
        String title = parsed.title() == null || parsed.title().isBlank()
                ? entry.file().getFileName().toString() : parsed.title();
        String artist = parsed.artist() == null || parsed.artist().isBlank()
                ? "未知歌手" : parsed.artist();
        provisional.setTitle(title);
        provisional.setArtist(artist);
        provisional.setTitlePy(PinyinUtil.fullPinyin(title));
        provisional.setTitleInit(PinyinUtil.initials(title));
        provisional.setArtistPy(PinyinUtil.fullPinyin(artist));
        provisional.setArtistInit(PinyinUtil.initials(artist));
        provisional.setLanguage(parsed.language() == null || parsed.language().isBlank()
                ? "未知" : normalizeLanguage(parsed.language()));
        provisional.setMediaType(MediaClassifier.PENDING_PROBE);
        provisional.setHasVocalTrack(false);
        provisional.setDurationMs(0);
        provisional.setLyricType(LyricType.NONE);
        provisional.setLyricSource(Song.LYRIC_SOURCE_NONE);
        provisional.setFingerprint(MediaClassifier.fastIndexFingerprint(entry.path()));
        // Keep unrecognized filename metadata visible for review even if the
        // later media probe fails before it can apply the final status.
        provisional.setStatus(parsed.recognized() ? "ok" : "unrecognized");
        provisional.setTags(parsed.category() == null || parsed.category().isBlank()
                ? new String[0] : new String[]{parsed.category()});
        provisional.setMetadataProvenance("{\"title\":{\"source\":\"filename_fast_index\"},"
                + "\"artist\":{\"source\":\"filename_fast_index\"}}");
        provisional.setNeedsAiOptimization(!parsed.recognized());
        provisional = songRepo.save(provisional);
        counters.dbUpdates++;

        SongFile indexed = new SongFile();
        indexed.setSongId(provisional.getId());
        indexed.setFilePath(entry.path());
        indexed.setRelativePath(entry.relativePath());
        indexed.setFormat(extOf(entry.file()));
        indexed.setFileSize(entry.size());
        indexed.setFileMtime(normalizedMtime);
        indexed.setFileIdentity(entry.fileIdentity());
        applySnapshots(indexed, entry);
        indexed.setFileRole(activeFileRole());
        if (LibraryModePolicy.isExternalReadOnly(props)) {
            // Store the configured default on the pending row so a later retry
            // can distinguish it from any per-file override made in the admin UI.
            indexed.setAudioLayout(externalDefault == null
                    ? AudioLayout.NORMAL_STEREO : externalDefault);
        }
        indexed.setValid(true);
        indexed.setProbePending(true);
        indexed = fileRepo.save(indexed);
        counters.dbUpdates++;
        return entry.withExisting(indexed);
    }

    /**
     * Repair rows created by an older Fast Index implementation that marked an
     * unrecognized filename as {@code ok} before a failed probe left it pending.
     * Only provisional rows are eligible, so a manually completed song is not
     * rewritten merely because its filename is not parseable.
     */
    private void reconcilePendingFilenameStatus(FastIndexEntry entry, SongFile existing,
                                                ScanCounters counters) {
        if (!existing.isProbePending() || existing.getSongId() == null
                || entry.filenameMeta().recognized()) {
            return;
        }
        songRepo.findById(existing.getSongId()).ifPresent(song -> {
            if (!isProvisionalSong(song)) return;
            if (hasManualIdentityOverride(song)) return;
            boolean changed = !"unrecognized".equals(song.getStatus())
                    || !song.isNeedsAiOptimization();
            if (!changed) return;
            song.setStatus("unrecognized");
            song.setNeedsAiOptimization(true);
            songRepo.save(song);
            counters.dbUpdates++;
        });
    }

    private String activeFileRole() {
        return LibraryModePolicy.isExternalReadOnly(props)
                ? LibraryModePolicy.EXTERNAL_FILE_ROLE : "LIBRARY";
    }

    private void reactivateIfNeeded(FastIndexEntry entry, ScanCounters counters) {
        SongFile existing = entry.existing().orElse(null);
        if (existing == null) return;

        boolean wasInvalid = !existing.isValid();
        boolean changed = false;
        if (!Objects.equals(existing.getFilePath(), entry.path())) {
            existing.setFilePath(entry.path());
            changed = true;
        }
        String relativePath = entry.relativePath();
        if (isBlank(existing.getRelativePath()) && relativePath != null) {
            existing.setRelativePath(relativePath);
            changed = true;
        }
        if (existing.getMediaMtime() == null || existing.getLyricSnapshotVersion() == null) {
            applySnapshots(existing, entry);
            changed = true;
        }
        if (wasInvalid) {
            existing.setValid(true);
            changed = true;
        }
        if (changed) {
            fileRepo.save(existing);
            counters.dbUpdates++;
        }
        if (!wasInvalid) return;
        if (existing.getSongId() == null) return;
        songRepo.findById(existing.getSongId()).ifPresent(song -> {
            if ("file_missing".equals(song.getStatus())) {
                song.setStatus("ok");
                songRepo.save(song);
                counters.dbUpdates++;
            }
        });
    }

    /** Refreshes only the sidecar cache and snapshot; the media file is never opened. */
    private LyricRefreshOutcome refreshSidecarLyric(FastIndexEntry entry, ScanCounters counters) {
        SongFile file = entry.existing().orElse(null);
        if (file == null || file.getSongId() == null) return LyricRefreshOutcome.UNAVAILABLE;

        SidecarLyricContent sidecar = readSidecarLyric(sidecarLyricOf(entry.file()));
        if (!sidecar.readable()) {
            // Keep the old snapshot so a transient permissions/network failure is retried.
            return LyricRefreshOutcome.UNAVAILABLE;
        }
        if (sidecar.text() != null && containsIdentityTag(sidecar.text())) {
            // LRC title/artist tags participate in identity selection. Reuse the normal
            // media path so a changed tag cannot leave the song bound to stale metadata.
            return LyricRefreshOutcome.NEEDS_MEDIA_PROBE;
        }
        Song song = songRepo.findById(file.getSongId()).orElse(null);
        if (song == null) return LyricRefreshOutcome.UNAVAILABLE;

        boolean songChanged = false;
        if (sidecar.text() != null) {
            String fingerprint = isBlank(song.getFingerprint())
                    ? MediaClassifier.fastIndexFingerprint(entry.path()) : song.getFingerprint();
            String lyricPath = assetWriter.writeLyric(fingerprint, sidecar.text());
            if (!Objects.equals(song.getLyricPath(), lyricPath)
                    || !Objects.equals(song.getLyricType(), LyricType.detect(sidecar.text()))
                    || !Objects.equals(song.getLyricSource(), Song.LYRIC_SOURCE_SIDECAR)) {
                song.setLyricPath(lyricPath);
                song.setLyricType(LyricType.detect(sidecar.text()));
                song.setLyricSource(Song.LYRIC_SOURCE_SIDECAR);
                songChanged = true;
            }
        } else if (Song.LYRIC_SOURCE_SIDECAR.equals(song.getLyricSource())) {
            song.setLyricPath(null);
            song.setLyricType(LyricType.NONE);
            song.setLyricSource(Song.LYRIC_SOURCE_NONE);
            songChanged = true;
        }

        if (songChanged) {
            songRepo.save(song);
            counters.dbUpdates++;
        }
        file.setFileSize(entry.size());
        file.setFileMtime(normalizeMtime(entry.mtime()));
        file.setFileIdentity(entry.fileIdentity());
        applySnapshots(file, entry);
        fileRepo.save(file);
        counters.dbUpdates++;
        return LyricRefreshOutcome.UPDATED;
    }

    /** Mark disappeared records invalid; never delete or touch a source path. */
    private void markMissingFiles(Path root, Set<String> currentPaths, ScanCounters counters,
                                  String activeRole) {
        String afterPath = "";
        while (true) {
            Slice<SongFile> page;
            try {
                page = fileRepo.findByFileRoleAndFilePathGreaterThanOrderByFilePath(
                        activeRole, afterPath, PageRequest.of(0, FAST_INDEX_BATCH_SIZE));
            } catch (RuntimeException failure) {
                log.warn("读取消失文件候选失败，本轮不标记缺失：{}", failure.getMessage());
                return;
            }
            if (page == null || page.getContent().isEmpty()) return;
            for (SongFile file : page.getContent()) {
                if (file == null || isBlank(file.getFilePath())) continue;
                afterPath = file.getFilePath();
                String path = file.getFilePath();
                if (!file.isValid() || currentPaths.contains(path)
                        || !isPathInsideActiveRoot(root, path)) continue;
                file.setValid(false);
                fileRepo.save(file);
                counters.dbUpdates++;
                counters.missing++;
                markSongMissingIfNeeded(file.getSongId(), counters);
            }
            if (page.getContent().size() < FAST_INDEX_BATCH_SIZE) return;
        }
    }

    private boolean isPathInsideActiveRoot(Path root, String path) {
        try {
            Path candidate = Path.of(path);
            if (LibraryModePolicy.isExternalReadOnly(props)) {
                LibraryModePolicy.requireExternalPathInsideSource(props, candidate);
                return true;
            }
            return candidate.toAbsolutePath().normalize()
                    .startsWith(root.toAbsolutePath().normalize());
        } catch (RuntimeException rejected) {
            return false;
        }
    }

    private void markSongMissingIfNeeded(Long songId, ScanCounters counters) {
        if (songId == null) return;
        List<SongFile> validFiles = fileRepo.findBySongIdAndValidTrueOrderByPriorityDesc(songId);
        if (validFiles != null && !validFiles.isEmpty()) return;
        songRepo.findById(songId).ifPresent(song -> {
            if ("ok".equals(song.getStatus())) {
                song.setStatus("file_missing");
                songRepo.save(song);
                counters.dbUpdates++;
            }
        });
    }

    enum IngestOutcome { ADDED, UPDATED, SKIPPED, UNRECOGNIZED }

    /** 单文件入库（幂等：已存在的路径按 path + size + mtime 判断是否需更新） */
    @Transactional
    public IngestOutcome ingest(Path file) {
        LibraryModePolicy.requireExternalPathInsideSource(props, file);
        Collection<String> knownArtists = existingArtistNames();
        FastIndexEntry entry = fastIndex(file, knownArtists);
        switch (snapshotChange(entry)) {
            case UNCHANGED -> { return IngestOutcome.SKIPPED; }
            case LYRIC_ONLY -> {
                ScanCounters counters = new ScanCounters();
                LyricRefreshOutcome outcome = refreshSidecarLyric(entry, counters);
                if (outcome == LyricRefreshOutcome.UPDATED) return IngestOutcome.UPDATED;
                if (outcome == LyricRefreshOutcome.UNAVAILABLE) return IngestOutcome.SKIPPED;
                AudioLayout externalDefault = LibraryModePolicy.isExternalReadOnly(props)
                        ? configuredExternalDefaultAudioLayout() : null;
                FastIndexEntry prepared = prepareFastIndex(entry, counters, externalDefault, true);
                return ingest(prepared, knownArtists, counters, externalDefault);
            }
            case MEDIA_CHANGED -> { /* continue through the media probe path */ }
        }
        AudioLayout externalDefault = LibraryModePolicy.isExternalReadOnly(props)
                ? configuredExternalDefaultAudioLayout() : null;
        return ingest(entry, knownArtists, new ScanCounters(), externalDefault);
    }

    private IngestOutcome ingest(FastIndexEntry entry, Collection<String> knownArtists,
                                 ScanCounters counters, AudioLayout externalDefault) {
        LibraryModePolicy.requireExternalPathInsideSource(props, entry.file());
        return ingestInternal(entry, null, null, null, false, knownArtists, counters, externalDefault).outcome();
    }

    private boolean isExternalPathAllowed(Path path) {
        if (!LibraryModePolicy.isExternalReadOnly(props)) return true;
        try {
            LibraryModePolicy.requireExternalPathInsideSource(props, path);
            return true;
        } catch (ApiException e) {
            log.warn("外部曲库路径越界，跳过：{} - {}", path, e.getMessage());
            return false;
        }
    }

    @Transactional
    public IngestResult ingestLibraryFile(Path file, Path sourceFile, String sourceMd5, String outputMd5, boolean transcodeRequired) {
        LibraryModePolicy.requireManaged(props, "导入");
        Collection<String> knownArtists = existingArtistNames();
        FastIndexEntry entry = fastIndex(file, knownArtists);
        SnapshotChange change = snapshotChange(entry);
        if (change == SnapshotChange.UNCHANGED) {
            SongFile existing = entry.existing().orElse(null);
            return new IngestResult(false, existing == null ? null : existing.getSongId(),
                    existing == null ? null : existing.getId());
        }
        if (change == SnapshotChange.LYRIC_ONLY) {
            ScanCounters counters = new ScanCounters();
            LyricRefreshOutcome outcome = refreshSidecarLyric(entry, counters);
            SongFile existing = entry.existing().orElse(null);
            if (outcome == LyricRefreshOutcome.UPDATED || outcome == LyricRefreshOutcome.UNAVAILABLE) {
                return new IngestResult(outcome == LyricRefreshOutcome.UPDATED,
                        existing == null ? null : existing.getSongId(),
                        existing == null ? null : existing.getId());
            }
            FastIndexEntry prepared = prepareFastIndex(entry, counters, null, true);
            IngestState state = ingestInternal(prepared, sourceFile, sourceMd5, outputMd5,
                    transcodeRequired, knownArtists, counters, null);
            return new IngestResult(
                    state.outcome() == IngestOutcome.ADDED || state.outcome() == IngestOutcome.UPDATED,
                    state.songId(), state.songFileId());
        }
        IngestState state = ingestInternal(entry, sourceFile, sourceMd5, outputMd5,
                transcodeRequired, knownArtists, new ScanCounters(), null);
        return new IngestResult(
                state.outcome() == IngestOutcome.ADDED || state.outcome() == IngestOutcome.UPDATED,
                state.songId(),
                state.songFileId()
        );
    }

    private IngestState ingestInternal(FastIndexEntry entry, Path sourceFile, String sourceMd5, String outputMd5,
                                        boolean transcodeRequired, Collection<String> knownArtists,
                                        ScanCounters counters, AudioLayout externalDefault) {
        Path file = entry.file();
        String pathStr = entry.path();
        Path sidecarLyric = sidecarLyricOf(file);
        OffsetDateTime mtime = entry.mtime();
        Optional<SongFile> existing = entry.existing();

        // 1) ffprobe 探测
        MediaProbe probe;
        try {
            counters.probeCalls++;
            probe = ffprobe.probe(file);
        } catch (MediaProbeException e) {
            log.debug("ffprobe 失败：{} - {}", file.getFileName(), e.getMessage());
            return new IngestState(IngestOutcome.SKIPPED, null, null);
        }

        // 2) 标签解析 + 容器标签 + LRC + 3) 文件名兜底
        TagInfo tag = tagReader.read(file.toFile());
        SidecarLyricContent sidecarLyricContent = readSidecarLyric(sidecarLyric);
        String sidecarLyricText = sidecarLyricContent.text();
        String lrcTitle = lrcTag(sidecarLyricText, "ti");
        String lrcArtist = lrcTag(sidecarLyricText, "ar");
        boolean recognized;
        String title, artist;
        String identitySource;
        ParsedMeta filenameMeta = null;
        if (tag.hasTitle()) {
            title = tag.getTitle();
            artist = tag.getArtist() != null ? tag.getArtist() : "";
            recognized = true;
            identitySource = "audio_tag";
        } else if (probe.title() != null && !probe.title().isBlank()) {
            title = probe.title();
            artist = probe.artist() == null ? "" : probe.artist();
            recognized = true;
            identitySource = "container_tag";
        } else if (lrcTitle != null && !lrcTitle.isBlank()) {
            title = lrcTitle;
            artist = lrcArtist == null ? "" : lrcArtist;
            recognized = true;
            identitySource = "lrc_tag";
        } else {
            filenameMeta = entry.filenameMeta();
            title = filenameMeta.title();
            artist = filenameMeta.artist();
            recognized = filenameMeta.recognized();
            identitySource = "filename";
        }
        if (artist == null || artist.isBlank()) artist = "未知歌手";

        // 4) 类型判定 + 伴奏轨判定 + 指纹
        String mediaType = MediaClassifier.classify(probe);
        boolean hasVocal = MediaClassifier.hasVocalTrack(probe);
        VocalTrackDetector.Result vocalDetect = VocalTrackDetector.detect(probe);
        Song existingSong = existing.map(SongFile::getSongId)
                .filter(Objects::nonNull)
                .flatMap(songRepo::findById)
                .orElse(null);
        boolean manualIdentity = hasManualIdentityOverride(existingSong);
        String effectiveTitle = existingSong != null && existingSong.isMetadataLocked("title")
                ? existingSong.getTitle() : title;
        String effectiveArtist = existingSong != null && existingSong.isMetadataLocked("artist")
                ? existingSong.getArtist() : artist;
        String fingerprint = MediaClassifier.fingerprint(effectiveArtist, effectiveTitle, probe.durationMs());

        // 5) 指纹去重：同指纹已存在 → 作为多文件源加入，按 priority 择优
        Optional<Song> dup = songRepo.findByFingerprint(fingerprint);
        Song provisional = existing.map(SongFile::getSongId)
                .filter(Objects::nonNull)
                .flatMap(songRepo::findById)
                .filter(LibraryScanService::isProvisionalSong)
                .orElse(null);
        Song song;
        boolean isNew;
        Song provisionalToDelete = null;
        if (manualIdentity) {
            Optional<Song> conflict = dup.filter(other -> !sameSong(other, existingSong));
            if (conflict.isPresent()) {
                throw new ApiException("FINGERPRINT_CONFLICT",
                        "人工确认的歌曲身份与歌曲 #" + conflict.get().getId() + " 冲突");
            }
            song = existingSong;
            applyProbedMetadata(song, title, artist, mediaType, hasVocal, probe,
                    fingerprint, recognized, tag, filenameMeta, identitySource);
            isNew = false;
        } else if (dup.isPresent() && !sameSong(dup.get(), provisional)) {
            song = dup.get();
            isNew = false;
            provisionalToDelete = provisional;
        } else if (provisional != null) {
            song = provisional;
            applyProbedMetadata(song, title, artist, mediaType, hasVocal, probe,
                    fingerprint, recognized, tag, filenameMeta, identitySource);
            isNew = false;
        } else if (dup.isPresent()) {
            song = dup.get();
            isNew = false;
        } else {
            song = new Song();
            song.setTitle(title);
            song.setArtist(artist);
            song.setTitlePy(PinyinUtil.fullPinyin(title));
            song.setTitleInit(PinyinUtil.initials(title));
            song.setArtistPy(PinyinUtil.fullPinyin(artist));
            song.setArtistInit(PinyinUtil.initials(artist));
            song.setMediaType(mediaType);
            song.setHasVocalTrack(hasVocal);
            song.setDurationMs((int) probe.durationMs());
            song.setLyricType(LyricType.NONE);
            song.setFingerprint(fingerprint);
            // 未识别（标签+文件名均无有效歌名）标记 unrecognized，供后台筛选补录；否则 ok
            song.setStatus(recognized ? "ok" : "unrecognized");
            if (tag.getLanguage() != null && !tag.getLanguage().isBlank()) song.setLanguage(normalizeLanguage(tag.getLanguage()));
            else if (probe.language() != null && !probe.language().isBlank()) song.setLanguage(normalizeLanguage(probe.language()));
            else if (filenameMeta != null && !filenameMeta.language().isBlank()) {
                song.setLanguage(normalizeLanguage(filenameMeta.language()));
            }
            if (filenameMeta != null && !filenameMeta.category().isBlank()) {
                // 现有模型没有独立 category 列；沿用 Home KTV 的 tags 数组承载文件名分类。
                song.setTags(new String[]{filenameMeta.category()});
            }
            song.setMetadataProvenance("{\"title\":{\"source\":\"" + identitySource + "\"},\"artist\":{\"source\":\"" + identitySource + "\"}}");
            song.setNeedsAiOptimization(!recognized || "未知".equals(song.getLanguage()) || "未知歌手".equals(song.getArtist()));
            isNew = true;
        }

        // 6) 歌词/封面落盘。同名增强 LRC 优先，且允许侧车文件独立更新。
        if (sidecarLyricText != null) {
            String lyricPath = assetWriter.writeLyric(fingerprint, sidecarLyricText);
            song.setLyricPath(lyricPath);
            song.setLyricType(LyricType.detect(sidecarLyricText));
            song.setLyricSource(Song.LYRIC_SOURCE_SIDECAR);
        } else if (sidecarLyricContent.readable()
                && Song.LYRIC_SOURCE_SIDECAR.equals(song.getLyricSource())) {
            // Only clear a cache that this scanner previously established from this
            // sidecar. Legacy/embedded/manual lyric caches remain fail-closed.
            song.setLyricPath(null);
            song.setLyricType(LyricType.NONE);
            song.setLyricSource(Song.LYRIC_SOURCE_NONE);
        }
        if (isNew) {
            String lyricText = sidecarLyricText == null ? tag.getEmbeddedLyric() : null;
            if (lyricText != null && !lyricText.isBlank()) {
                String lyricPath = assetWriter.writeLyric(fingerprint, lyricText);
                song.setLyricPath(lyricPath);
                song.setLyricType(LyricType.detect(lyricText));
                song.setLyricSource(Song.LYRIC_SOURCE_EMBEDDED);
            } else if (sidecarLyricText == null && !sidecarLyricContent.readable()) {
                song.setLyricSource(Song.LYRIC_SOURCE_UNKNOWN);
            } else if (sidecarLyricText == null) {
                song.setLyricSource(Song.LYRIC_SOURCE_NONE);
            }
            if (tag.getCoverImage() != null) {
                String coverPath = assetWriter.writeCover(fingerprint, tag.getCoverImage(), tag.getCoverExt());
                song.setCoverPath(coverPath);
            }
        }
        song = songRepo.save(song);
        counters.dbUpdates++;

        // 7) 写 song_files（KTV 视频优先级高）
        int priority = switch (mediaType) {
            case MediaClassifier.KTV_VIDEO -> 100;
            case MediaClassifier.MV -> 50;
            default -> 10;
        };
        SongFile sf = existing.orElseGet(SongFile::new);
        sf.setSongId(song.getId());
        sf.setFilePath(pathStr);
        String relativePath = entry.relativePath();
        if (relativePath != null) sf.setRelativePath(relativePath);
        sf.setFormat(extOf(file));
        sf.setAudioTracks(probe.audioTracks());
        // External files keep the layout stored on the file row. The Fast Index
        // placeholder also stores the configured default, so a failed probe
        // retry cannot overwrite a per-file override made while it is pending.
        AudioLayout storedLayout = existing.map(SongFile::getAudioLayout).orElse(null);
        AudioLayout audioLayout;
        if (LibraryModePolicy.isExternalReadOnly(props) && storedLayout != null) {
            audioLayout = externalDefaultAudioLayout(probe.audioTracks(), storedLayout);
        } else if (LibraryModePolicy.isExternalReadOnly(props)) {
            audioLayout = externalDefaultAudioLayout(probe.audioTracks(), externalDefault);
        } else {
            // Managed-mode behavior remains the existing two-track detector.
            audioLayout = storedLayout == AudioLayout.DUAL_CHANNEL
                    ? AudioLayout.DUAL_CHANNEL
                    : hasVocal ? AudioLayout.DUAL_TRACK : AudioLayout.NORMAL_STEREO;
        }
        sf.setAudioLayout(audioLayout);
        // 伴奏轨 index（0-based 音频相对序号）。已有值优先（尊重人工/历史校正），
        // 否则用元数据判定，判不出再回落默认 1（多数双轨片源 track0=原唱、track1=伴奏）。
        Integer existingVocalTrackIndex = existing.map(SongFile::getVocalTrackIndex).orElse(null);
        Integer vocalTrackIndex = null;
        String vocalConfidence = null;
        if (audioLayout == AudioLayout.DUAL_TRACK) {
            if (existingVocalTrackIndex != null) {
                // 尊重人工/历史校正：index 不变，置信度视为已确认（HIGH）
                vocalTrackIndex = existingVocalTrackIndex;
                vocalConfidence = VocalTrackDetector.Confidence.HIGH.name();
            } else if (vocalDetect.accompanimentIndex() != null) {
                vocalTrackIndex = vocalDetect.accompanimentIndex();
                vocalConfidence = vocalDetect.confidence().name();
                log.debug("伴奏轨判定 {} → track#{}（{}，{}）", file.getFileName(),
                        vocalTrackIndex, vocalDetect.confidence(), vocalDetect.reason());
            } else {
                // 判不出：回落默认 track#1，标 LOW 供后台筛选人工复核
                vocalTrackIndex = 1;
                vocalConfidence = VocalTrackDetector.Confidence.LOW.name();
                log.info("伴奏轨无法确定，回落默认 track#1，建议人工复核：{}（{}）",
                        file.getFileName(), vocalDetect.reason());
            }
        }
        if (audioLayout == AudioLayout.DUAL_TRACK) {
            sf.setAccompanimentTrackIndex(vocalTrackIndex);
            Integer storedOriginalTrackIndex = existing.map(SongFile::getOriginalTrackIndex).orElse(null);
            sf.setOriginalTrackIndex(storedOriginalTrackIndex != null
                    ? storedOriginalTrackIndex
                    : vocalTrackIndex == null ? null : vocalTrackIndex == 0 ? 1 : 0);
        } else {
            sf.setVocalTrackIndex(null);
            sf.setOriginalTrackIndex(null);
            sf.setAccompanimentTrackIndex(null);
        }
        sf.setVocalConfidence(vocalConfidence);
        sf.setResolution(probe.resolution());
        sf.setFileSize(entry.size());
        sf.setFileMtime(normalizeMtime(mtime));
        sf.setFileIdentity(entry.fileIdentity());
        applySnapshots(sf, entry);
        sf.setPriority(priority);
        // 文件重新被成功探测，说明之前的瞬时播放失败不应永久屏蔽该源。
        sf.setValid(true);
        if (LibraryModePolicy.isExternalReadOnly(props)) {
            sf.setFileRole(LibraryModePolicy.EXTERNAL_FILE_ROLE);
            sf.setSourcePath(null);
            sf.setSourceMd5(null);
            sf.setOutputMd5(null);
            sf.setTranscodeRequired(false);
            sf.setImportedAt(null);
            sf.setSourceDeleted(false);
        } else {
            sf.setFileRole("LIBRARY");
            sf.setSourcePath(sourceFile != null ? sourceFile.toString() : sf.getSourcePath());
            sf.setSourceMd5(sourceMd5);
            sf.setOutputMd5(outputMd5);
            sf.setTranscodeRequired(transcodeRequired);
            sf.setImportedAt(OffsetDateTime.now());
        }
        sf.setSourceDeleted(false);
        sf.setProbePending(false);
        sf = fileRepo.save(sf);
        counters.dbUpdates++;
        if (provisionalToDelete != null) songRepo.delete(provisionalToDelete);

        IngestOutcome outcome;
        if (!recognized) outcome = IngestOutcome.UNRECOGNIZED;
        else if (!entry.existedBeforeScan() || entry.pendingBeforeScan()) outcome = IngestOutcome.ADDED;
        else outcome = IngestOutcome.UPDATED;
        return new IngestState(outcome, song.getId(), sf.getId());
    }

    private AudioLayout configuredExternalDefaultAudioLayout() {
        AudioLayout configured = settingService == null
                ? AudioLayout.NORMAL_STEREO : settingService.externalDefaultAudioLayout();
        if (configured == null) configured = AudioLayout.NORMAL_STEREO;
        return configured;
    }

    private static AudioLayout externalDefaultAudioLayout(int audioTracks, AudioLayout configured) {
        if (configured == null) configured = AudioLayout.NORMAL_STEREO;
        // A global DUAL_TRACK default cannot describe a one-track media file;
        // keep that file safe and playable rather than persisting invalid indices.
        return configured == AudioLayout.DUAL_TRACK && audioTracks < 2
                ? AudioLayout.NORMAL_STEREO : configured;
    }

    private static boolean sameSong(Song left, Song right) {
        if (left == null || right == null) return false;
        if (left == right) return true;
        return left.getId() != null && left.getId().equals(right.getId());
    }

    private static boolean isProvisionalSong(Song song) {
        return song != null && (MediaClassifier.PENDING_PROBE.equals(song.getMediaType())
                || song.getFingerprint() != null && song.getFingerprint().startsWith("fast-index-"));
    }

    private static boolean hasManualIdentityOverride(Song song) {
        return song != null && (song.isMetadataLocked("title") || song.isMetadataLocked("artist"));
    }

    private static void applyProbedMetadata(Song song, String title, String artist, String mediaType,
                                            boolean hasVocal, MediaProbe probe, String fingerprint,
                                            boolean recognized, TagInfo tag, ParsedMeta filenameMeta,
                                            String identitySource) {
        boolean titleLocked = song.isMetadataLocked("title");
        boolean artistLocked = song.isMetadataLocked("artist");
        boolean languageLocked = song.isMetadataLocked("language");
        boolean tagsLocked = song.isMetadataLocked("tags");
        if (!titleLocked) {
            song.setTitle(title);
            song.setTitlePy(PinyinUtil.fullPinyin(title));
            song.setTitleInit(PinyinUtil.initials(title));
        }
        if (!artistLocked) {
            song.setArtist(artist);
            song.setArtistPy(PinyinUtil.fullPinyin(artist));
            song.setArtistInit(PinyinUtil.initials(artist));
        }
        song.setMediaType(mediaType);
        song.setHasVocalTrack(hasVocal);
        song.setDurationMs((int) probe.durationMs());
        song.setFingerprint(fingerprint);
        if (!hasManualIdentityOverride(song)) {
            song.setStatus(recognized ? "ok" : "unrecognized");
        }
        if (!languageLocked) {
            if (tag.getLanguage() != null && !tag.getLanguage().isBlank()) {
                song.setLanguage(normalizeLanguage(tag.getLanguage()));
            } else if (probe.language() != null && !probe.language().isBlank()) {
                song.setLanguage(normalizeLanguage(probe.language()));
            } else if (filenameMeta != null && !filenameMeta.language().isBlank()) {
                song.setLanguage(normalizeLanguage(filenameMeta.language()));
            }
        }
        if (!tagsLocked && filenameMeta != null && !filenameMeta.category().isBlank()) {
            song.setTags(new String[]{filenameMeta.category()});
        }
        if (!titleLocked && !artistLocked) {
            song.setMetadataProvenance("{\"title\":{\"source\":\"" + identitySource
                    + "\"},\"artist\":{\"source\":\"" + identitySource + "\"}}");
        }
        if (!hasManualIdentityOverride(song)) {
            song.setNeedsAiOptimization(!recognized || "未知".equals(song.getLanguage())
                    || "未知歌手".equals(song.getArtist()));
        }
    }

    public static boolean isMediaFile(Path file) {
        return MEDIA_EXT.contains(extOf(file));
    }

    private static String extOf(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(dot + 1).toLowerCase() : "";
    }

    private static Path sidecarLyricOf(Path mediaFile) {
        String name = mediaFile.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        return mediaFile.resolveSibling(stem + ".lrc");
    }

    private static OffsetDateTime newestMtime(OffsetDateTime mediaMtime, LyricSnapshot lyricSnapshot) {
        OffsetDateTime lyricMtime = lyricSnapshot.mtime();
        return lyricMtime != null && lyricMtime.isAfter(mediaMtime) ? lyricMtime : mediaMtime;
    }

    /** Returns a portable relative path for files below the active library root. */
    private String relativePathOf(Path file) {
        Path root = LibraryModePolicy.activeLibraryRoot(props).toAbsolutePath().normalize();
        return relativePathOf(file, root);
    }

    private static String relativePathOf(Path file, Path root) {
        Path candidate = file.toAbsolutePath().normalize();
        if (!candidate.startsWith(root)) return null;
        return root.relativize(candidate).toString().replace('\\', '/');
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String mediaFileIdentity(BasicFileAttributes mediaAttrs) {
        Object mediaKey = mediaAttrs.fileKey();
        return mediaKey == null ? null : String.valueOf(mediaKey);
    }

    private static String legacyFileIdentity(String mediaIdentity, String lyricIdentity) {
        if (mediaIdentity == null && lyricIdentity == null) return null;
        return String.valueOf(mediaIdentity) + "|" + String.valueOf(lyricIdentity);
    }

    private static LyricSnapshot lyricSnapshotOf(Path sidecarLyric) {
        try {
            BasicFileAttributes attrs = Files.readAttributes(sidecarLyric, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (!attrs.isRegularFile()) return LyricSnapshot.missing();
            Object fileKey = attrs.fileKey();
            return new LyricSnapshot(true, true, attrs.size(),
                    attrs.lastModifiedTime().toInstant().atOffset(ZoneOffset.UTC),
                    fileKey == null ? null : String.valueOf(fileKey));
        } catch (NoSuchFileException e) {
            return LyricSnapshot.missing();
        } catch (IOException e) {
            log.warn("读取同名歌词属性失败：{} - {}", sidecarLyric, e.getMessage());
            return LyricSnapshot.unreadable();
        }
    }

    private static SidecarLyricContent readSidecarLyric(Path sidecarLyric) {
        LyricSnapshot snapshot = lyricSnapshotOf(sidecarLyric);
        if (!snapshot.present()) return SidecarLyricContent.absent();
        if (!snapshot.readable()) return SidecarLyricContent.unreadable();
        try {
            String text = Files.readString(sidecarLyric, StandardCharsets.UTF_8);
            return new SidecarLyricContent(true, true,
                    LyricType.NONE.equals(LyricType.detect(text)) ? null : text);
        } catch (IOException e) {
            log.warn("读取同名歌词失败：{} - {}", sidecarLyric, e.getMessage());
            return SidecarLyricContent.unreadable();
        }
    }

    private static void applySnapshots(SongFile file, FastIndexEntry entry) {
        file.setMediaMtime(normalizeMtime(entry.mediaMtime()));
        file.setMediaFileIdentity(entry.mediaIdentity());
        LyricSnapshot lyric = entry.lyricSnapshot();
        if (lyric.readable()) {
            file.setLyricSize(lyric.size());
            file.setLyricMtime(normalizeMtime(lyric.mtime()));
            file.setLyricFileIdentity(lyric.fileIdentity());
            file.setLyricSnapshotVersion(LYRIC_SNAPSHOT_VERSION);
        }
    }

    private static String lrcTag(String lyric, String key) {
        if (lyric == null) return null;
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(?im)^\\[" + key + "\\s*:\\s*(.+?)\\]\\s*$").matcher(lyric);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    private static boolean containsIdentityTag(String lyric) {
        return lrcTag(lyric, "ti") != null || lrcTag(lyric, "ar") != null;
    }

    private static String normalizeLanguage(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (value.isBlank()) return "未知";
        if (value.matches("zh(-|_)?cn|中文|mandarin|国语|普通话")) return "国语";
        if (value.matches("yue|zh(-|_)?hk|粤语|cantonese")) return "粤语";
        if (value.matches("nan|闽南语|台语|hokkien")) return "闽南语";
        if (value.matches("en|英语|英文|english")) return "英语";
        if (value.matches("ja|日语|日文|japanese")) return "日语";
        if (value.matches("ko|韩语|韩文|korean")) return "韩语";
        if (value.matches("instrumental|纯音乐|music")) return "纯音乐";
        return Set.of("国语", "粤语", "闽南语", "英语", "日语", "韩语", "纯音乐", "其他", "未知").contains(raw) ? raw : "其他";
    }

    private static final int LYRIC_SNAPSHOT_VERSION = 1;

    private enum SnapshotChange { UNCHANGED, LYRIC_ONLY, MEDIA_CHANGED }

    private enum LyricRefreshOutcome { UPDATED, NEEDS_MEDIA_PROBE, UNAVAILABLE }

    private record LyricSnapshot(boolean present, boolean readable, Long size,
                                 OffsetDateTime mtime, String fileIdentity) {
        private static LyricSnapshot missing() {
            return new LyricSnapshot(false, true, null, null, null);
        }

        private static LyricSnapshot unreadable() {
            return new LyricSnapshot(true, false, null, null, null);
        }
    }

    private record SidecarLyricContent(boolean present, boolean readable, String text) {
        private static SidecarLyricContent absent() {
            return new SidecarLyricContent(false, true, null);
        }

        private static SidecarLyricContent unreadable() {
            return new SidecarLyricContent(true, false, null);
        }
    }

    private record FastIndexEntry(Path file, String path, String relativePath, long size, OffsetDateTime mtime,
                                  String fileIdentity, ParsedMeta filenameMeta,
                                  OffsetDateTime mediaMtime, String mediaIdentity,
                                  LyricSnapshot lyricSnapshot,
                                  Optional<SongFile> existing, boolean existedBeforeScan,
                                  boolean pendingBeforeScan) {
        private FastIndexEntry withExisting(SongFile replacement) {
            return new FastIndexEntry(file, path, relativePath, size, mtime, fileIdentity, filenameMeta,
                    mediaMtime, mediaIdentity, lyricSnapshot,
                    Optional.ofNullable(replacement), existedBeforeScan, pendingBeforeScan);
        }

        private FastIndexEntry withScanHistory(boolean existed, boolean pending) {
            return new FastIndexEntry(file, path, relativePath, size, mtime, fileIdentity, filenameMeta,
                    mediaMtime, mediaIdentity, lyricSnapshot,
                    existing, existed, pending);
        }
    }

    private record IngestState(IngestOutcome outcome, Long songId, Long songFileId) {}

    private static final class ScanCounters {
        private int fastIndexed;
        private int probeQueued;
        private int probeCalls;
        private int hashCalls;
        private int dbUpdates;
        private int missing;
    }

    private static final class ScanTotals {
        private int added;
        private int updated;
        private int skipped;
        private int unrecognized;
        private int probeCompleted;
    }

    private record ScanStartSnapshot(Set<Long> ids, Set<String> paths,
                                     Set<Long> pendingIds, Set<String> pendingPaths) {}

    private record ScanRootContext(Path configuredRoot, Path realRoot, boolean external) {
        private static ScanRootContext create(Path root, boolean external) {
            try {
                Path configured = root.toAbsolutePath().normalize();
                Path real = external ? configured.toRealPath() : configured;
                return new ScanRootContext(configured, real, external);
            } catch (IOException failure) {
                throw new IllegalStateException("曲库根目录无法解析：" + root, failure);
            }
        }

        private boolean allowsVisitedDirectory(Path directory) {
            Path candidate = directory.toAbsolutePath().normalize();
            if (!candidate.startsWith(configuredRoot) || Files.isSymbolicLink(directory)) return false;
            if (!external) return true;
            try {
                return directory.toRealPath().startsWith(realRoot);
            } catch (IOException failure) {
                return false;
            }
        }

        /** Fast-path for walkFileTree: the parent directory was already boundary-checked. */
        private boolean allowsVisitedFile(Path file) {
            return file.toAbsolutePath().normalize().startsWith(configuredRoot)
                    && !Files.isSymbolicLink(file);
        }

        /** Queue rows may come from a previous process, so re-check their real target. */
        private boolean allowsExistingFile(Path file) {
            if (!file.toAbsolutePath().normalize().startsWith(configuredRoot)
                    || Files.isSymbolicLink(file)) return false;
            if (!external) return true;
            try {
                return file.toRealPath().startsWith(realRoot);
            } catch (IOException failure) {
                return false;
            }
        }
    }

    @PreDestroy
    void shutdown() {
        scanExecutor.shutdownNow();
    }

    private Set<String> existingArtistNames() {
        Set<String> artists = new LinkedHashSet<>();
        try {
            List<String> storedArtists = songRepo.findDistinctArtistByStatus("ok");
            if (storedArtists == null) return artists;
            for (String artist : storedArtists) {
                if (artist != null && !artist.isBlank() && !"未知歌手".equals(artist.trim())) {
                    artists.add(artist.trim());
                }
            }
        } catch (RuntimeException failure) {
            // 歌手库只是解析增强证据；数据库暂时不可用时仍可安全扫描并将复杂名称待审核。
            log.debug("读取已有歌手库失败，文件名复杂边界将进入待审核：{}", failure.getMessage());
        }
        return artists;
    }

    private FilenameParser.ArtistIndex artistIndexFor(Collection<String> artistNames) {
        Set<String> snapshot = artistNames == null || artistNames.isEmpty()
                ? Set.of() : Set.copyOf(artistNames);
        FilenameParser.ArtistIndex current = cachedArtistIndex;
        if (snapshot.equals(cachedArtistNames)) return current;
        synchronized (artistIndexLock) {
            if (!snapshot.equals(cachedArtistNames)) {
                cachedArtistIndex = FilenameParser.prepareKnownArtists(snapshot);
                cachedArtistNames = snapshot;
            }
            return cachedArtistIndex;
        }
    }
}
