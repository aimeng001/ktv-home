package com.homektv.library;

import com.homektv.config.AppProperties;
import com.homektv.domain.AudioLayout;
import com.homektv.domain.AudioLayoutSource;
import com.homektv.domain.Song;
import com.homektv.domain.SongFile;
import com.homektv.media.FFprobeService;
import com.homektv.media.MediaProbe;
import com.homektv.musicsource.CoverImageNormalizer;
import com.homektv.repo.SongFileRepository;
import com.homektv.repo.SongRepository;
import com.homektv.resource.ResourceBudget;
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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * 曲库扫描协调器：枚举、快索引、探测准入、租约和扫描状态由此管理；探测后的歌曲与文件入库交给
 * {@link LibraryIngestionWriter} 在独立写事务内完成。
 *
 * Library scan coordinator for filesystem enumeration, fast indexing, probe admission,
 * leases, and scan state. Probed-song reconciliation is delegated to LibraryIngestionWriter.
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
    private static final Set<String> AUDIO_EXT = Set.of(
            "mp3", "mp2", "aac", "flac", "wav", "m4a", "ape", "ogg", "oga", "opus",
            "ac3", "eac3", "dts", "mka", "wma", "aiff", "aif", "alac"
    );
    static final int FAST_INDEX_BATCH_SIZE = 500;
    static final int PROBE_PAGE_SIZE = 64;
    static final int PROBE_CONCURRENCY = 2;
    private static final String PHASE_DISCOVERING = "DISCOVERING";
    private static final String PHASE_FAST_INDEX = "FAST_INDEX";
    private static final String PHASE_MEDIA_PROBE = "MEDIA_PROBE";
    private static final String PHASE_COMPLETED = "COMPLETED";

    public static boolean isMediaFile(Path file) {
        return file != null && MEDIA_EXT.contains(extOf(file));
    }

    public static boolean isAudioFile(Path file) {
        return file != null && AUDIO_EXT.contains(extOf(file));
    }

    static String extOf(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(dot + 1).toLowerCase() : "";
    }

    private final AppProperties props;
    private final MediaProbeStage mediaProbeStage;
    private final LibraryIngestionWriter ingestionWriter;
    private final TagReader tagReader;
    private final SongRepository songRepo;
    private final SongFileRepository fileRepo;
    private final AssetWriter assetWriter;
    private final SettingService settingService;
    private final SongProjectionService songProjectionService;
    private final ExecutorService scanExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "library-scan");
        thread.setDaemon(true);
        return thread;
    });
    private final ExecutorService probeExecutor = Executors.newFixedThreadPool(PROBE_CONCURRENCY, r -> {
        Thread thread = new Thread(r, "media-probe-worker");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicReference<ScanProgress> scanProgress = new AtomicReference<>(ScanProgress.idle());
    private final AtomicBoolean scanRunning = new AtomicBoolean();
    private final TransactionTemplate batchTransaction;
    private final LibraryScanSeenPathStore seenPathStore;
    private CoverImageNormalizer coverImageNormalizer;
    private CatalogRevisionService catalogRevisionService;

    @PersistenceContext
    private EntityManager entityManager;
    public LibraryScanService(AppProperties props, FFprobeService ffprobe, TagReader tagReader,
                              SongRepository songRepo, SongFileRepository fileRepo, AssetWriter assetWriter,
                              LibraryScanSeenPathStore seenPathStore) {
        this(props, ffprobe, tagReader, songRepo, fileRepo, assetWriter, null, null, seenPathStore,
                new SongProjectionService(songRepo, fileRepo), standaloneProbeStage(ffprobe));
    }

    public LibraryScanService(AppProperties props, FFprobeService ffprobe, TagReader tagReader,
                              SongRepository songRepo, SongFileRepository fileRepo, AssetWriter assetWriter,
                              SettingService settingService, LibraryScanSeenPathStore seenPathStore) {
        this(props, ffprobe, tagReader, songRepo, fileRepo, assetWriter, settingService, null, seenPathStore,
                new SongProjectionService(songRepo, fileRepo), standaloneProbeStage(ffprobe));
    }

    public LibraryScanService(AppProperties props, FFprobeService ffprobe, TagReader tagReader,
                              SongRepository songRepo, SongFileRepository fileRepo, AssetWriter assetWriter,
                              SettingService settingService, PlatformTransactionManager transactionManager,
                              LibraryScanSeenPathStore seenPathStore) {
        this(props, ffprobe, tagReader, songRepo, fileRepo, assetWriter, settingService, transactionManager,
                seenPathStore, new SongProjectionService(songRepo, fileRepo), standaloneProbeStage(ffprobe));
    }

    @Autowired
    public LibraryScanService(AppProperties props, FFprobeService ffprobe, TagReader tagReader,
                              SongRepository songRepo, SongFileRepository fileRepo, AssetWriter assetWriter,
                              SettingService settingService, PlatformTransactionManager transactionManager,
                              LibraryScanSeenPathStore seenPathStore,
                              SongProjectionService songProjectionService,
                              MediaProbeStage mediaProbeStage) {
        this.props = props;
        this.mediaProbeStage = mediaProbeStage;
        this.tagReader = tagReader;
        this.songRepo = songRepo;
        this.fileRepo = fileRepo;
        this.assetWriter = assetWriter;
        this.settingService = settingService;
        this.songProjectionService = songProjectionService;
        this.batchTransaction = transactionManager == null ? null : new TransactionTemplate(transactionManager);
        this.seenPathStore = seenPathStore;
        this.ingestionWriter = new LibraryIngestionWriter(props, tagReader, songRepo, fileRepo,
                assetWriter, settingService, transactionManager, songProjectionService);
    }

    LibraryScanService(AppProperties props, FFprobeService ffprobe, TagReader tagReader,
                       SongRepository songRepo, SongFileRepository fileRepo, AssetWriter assetWriter,
                       LibraryScanSeenPathStore seenPathStore, ResourceBudget resourceBudget) {
        this(props, ffprobe, tagReader, songRepo, fileRepo, assetWriter, null, null, seenPathStore,
                new SongProjectionService(songRepo, fileRepo), new MediaProbeStage(ffprobe, resourceBudget));
    }

    /** Compatibility constructor for isolated fixtures that do not provide the shared Spring budget. */
    public LibraryScanService(AppProperties props, FFprobeService ffprobe, TagReader tagReader,
                              SongRepository songRepo, SongFileRepository fileRepo, AssetWriter assetWriter,
                              SettingService settingService, PlatformTransactionManager transactionManager,
                              LibraryScanSeenPathStore seenPathStore,
                              SongProjectionService songProjectionService) {
        this(props, ffprobe, tagReader, songRepo, fileRepo, assetWriter, settingService, transactionManager,
                seenPathStore, songProjectionService, standaloneProbeStage(ffprobe));
    }

    private static MediaProbeStage standaloneProbeStage(FFprobeService ffprobe) {
        return new MediaProbeStage(ffprobe,
                new ResourceBudget(PROBE_CONCURRENCY, PROBE_CONCURRENCY, 4, () -> false));
    }

    @Autowired
    void setCoverImageNormalizer(CoverImageNormalizer coverImageNormalizer) {
        this.coverImageNormalizer = coverImageNormalizer;
        ingestionWriter.setCoverImageNormalizer(coverImageNormalizer);
    }

    @Autowired(required = false)
    void setArtistCreditService(ArtistCreditService artistCreditService) {
        this.artistCreditService = artistCreditService;
        ingestionWriter.setArtistCreditService(artistCreditService);
    }

    @Autowired(required = false)
    void setCatalogRevisionService(CatalogRevisionService catalogRevisionService) {
        this.catalogRevisionService = catalogRevisionService;
    }

    public enum ScanState {
        IDLE,
        RUNNING,
        COMPLETED,
        PARTIAL,
        FAILED
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
                               int probeCompleted, double probedPerSecond, Long estimatedRemainingSeconds,
                               ScanState state, int failedPaths, String errorCode, String errorMessage) {
        public ScanProgress(boolean running, int total, int completed, String currentFile,
                            int added, int updated, int skipped, int unrecognized,
                            OffsetDateTime startedAt, OffsetDateTime finishedAt,
                            String phase, int discovered, int fastIndexed, int probeQueued,
                            int probeCompleted, double probedPerSecond, Long estimatedRemainingSeconds) {
            this(running, total, completed, currentFile, added, updated, skipped, unrecognized,
                    startedAt, finishedAt, phase, discovered, fastIndexed, probeQueued, probeCompleted,
                    probedPerSecond, estimatedRemainingSeconds,
                    running ? ScanState.RUNNING : finishedAt != null ? (phase != null && phase.contains("FAIL") ? ScanState.FAILED : ScanState.COMPLETED) : ScanState.IDLE,
                    0, null, null);
        }

        public ScanProgress(boolean running, int total, int completed, String currentFile,
                            int added, int updated, int skipped, int unrecognized,
                            OffsetDateTime startedAt, OffsetDateTime finishedAt,
                            String phase, int discovered, int fastIndexed, int probeQueued,
                            int probeCompleted) {
            this(running, total, completed, currentFile, added, updated, skipped, unrecognized,
                    startedAt, finishedAt, phase, discovered, fastIndexed, probeQueued, probeCompleted,
                    0.0, null);
        }

        public ScanProgress(boolean running, int total, int completed, String currentFile,
                            int added, int updated, int skipped, int unrecognized,
                            OffsetDateTime startedAt, OffsetDateTime finishedAt) {
            this(running, total, completed, currentFile, added, updated, skipped, unrecognized,
                    startedAt, finishedAt,
                    finishedAt != null ? PHASE_COMPLETED
                            : running && total == 0 ? PHASE_DISCOVERING
                            : running ? PHASE_MEDIA_PROBE : PHASE_COMPLETED,
                    total, total, 0, Math.max(0, completed), 0.0, null);
        }

        static ScanProgress idle() {
            return new ScanProgress(false, 0, 0, null, 0, 0, 0, 0,
                    null, null, "IDLE", 0, 0, 0, 0, 0.0, null,
                    ScanState.IDLE, 0, null, null);
        }
    }

    private ArtistCreditService artistCreditService;

    @Autowired(required = false)
    private ArtistProfileBootstrap artistProfileBootstrap;

    /** 全量/增量扫描曲库根目录。Fast Index 与数据库持久化 Media Probe Queue 分阶段执行。 */
    public ScanResult scanAll() {
        return scanAllWithLease(LibraryScanLeaseGuard.noop());
    }

    public ScanResult scanAllWithLease(LibraryScanLeaseGuard leaseGuard) {
        if (!scanRunning.compareAndSet(false, true)) {
            throw new ApiException("SCAN_ALREADY_RUNNING", "曲库扫描正在进行中");
        }
        try {
            return scanAllInternal(leaseGuard == null ? LibraryScanLeaseGuard.noop() : leaseGuard);
        } finally {
            scanRunning.set(false);
        }
    }

    private ScanResult scanAllInternal(LibraryScanLeaseGuard leaseGuard) {
            OffsetDateTime startedAt = OffsetDateTime.now();
            publishProgress(true, 0, 0, null, new ScanTotals(), PHASE_DISCOVERING,
                    0, 0, 0, startedAt, null);
            Path root = LibraryModePolicy.activeLibraryRoot(props).toAbsolutePath().normalize();
            if (!Files.isDirectory(root)) {
                log.warn("曲库目录不存在：{}", root);
                ScanResult result = new ScanResult(0, 0, 0, 0, 0);
                publishProgress(false, 0, 0, null, new ScanTotals(), "FAILED",
                        0, 0, 0, startedAt, OffsetDateTime.now(),
                        ScanState.FAILED, 1, "ROOT_DIRECTORY_NOT_FOUND", "曲库目录不存在：" + root);
                return result;
            }
            if (!Files.isReadable(root)) {
                log.warn("曲库根目录不可读：{}", root);
                ScanResult result = new ScanResult(0, 0, 0, 0, 0);
                publishProgress(false, 0, 0, null, new ScanTotals(), "FAILED",
                        0, 0, 0, startedAt, OffsetDateTime.now(),
                        ScanState.FAILED, 1, "ROOT_NOT_READABLE", "曲库根目录无读取权限：" + root);
                return result;
            }

            ScanRootContext rootContext;
            try {
                rootContext = ScanRootContext.create(root, LibraryModePolicy.isExternalReadOnly(props));
            } catch (RuntimeException failure) {
                log.error("无法安全解析曲库根目录，停止本轮扫描：{}", failure.getMessage());
                publishProgress(false, 0, 0, null, new ScanTotals(), "FAILED",
                        0, 0, 0, startedAt, OffsetDateTime.now(),
                        ScanState.FAILED, 1, "ROOT_PARSE_ERROR", failure.getMessage());
                return new ScanResult(0, 0, 0, 0, 0);
            }

            Set<String> knownArtists = existingArtistNames();
            AudioLayout externalDefault = LibraryModePolicy.isExternalReadOnly(props)
                    ? configuredExternalDefaultAudioLayout() : null;
            FilenameParser.ArtistIndex artistIndex = artistIndexFor(knownArtists);
            String activeRole = activeFileRole();
            UUID scanId = UUID.randomUUID();
            long maxIdAtScanStart = fileRepo.findMaxIdByFileRole(activeRole);
            long validFilesAtScanStart = countValidFilesAtScanStart(activeRole, root);

            List<FastIndexEntry> batch = new ArrayList<>(FAST_INDEX_BATCH_SIZE);
            ScanCounters counters = new ScanCounters();
            ScanTotals totals = new ScanTotals();
            int[] discovered = {0};
            boolean[] enumerationComplete = {true};
            String[] lastErrorMessage = {null};
            Deque<DirectoryLyricIndex> directoryLyrics = new ArrayDeque<>();
            try {
                try {
                Files.walkFileTree(root, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        PathAccessResult access = rootContext.checkVisitedDirectory(dir);
                        if (access.decision() == PathAccessDecision.SAFE_SKIP) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        if (access.decision() == PathAccessDecision.ACCESS_FAILURE) {
                            enumerationComplete[0] = false;
                            counters.failedPaths++;
                            lastErrorMessage[0] = access.failureReason();
                            log.warn("读取曲库目录受阻：{} - {}", dir, access.failureReason());
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        DirectoryLyricIndex index = DirectoryLyricIndex.load(dir);
                        directoryLyrics.push(index);
                        if (!index.complete()) {
                            enumerationComplete[0] = false;
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        if (!attrs.isRegularFile() || Files.isSymbolicLink(file)
                                || !isMediaFile(file) || !rootContext.allowsVisitedFile(file)) {
                            return FileVisitResult.CONTINUE;
                        }
                        try {
                            FastIndexEntry entry = fastIndex(file, attrs, artistIndex,
                                    Map.of(), Map.of(), rootContext.configuredRoot(), directoryLyrics.peek());
                            batch.add(entry);
                            counters.fastIndexed++;
                            discovered[0]++;
                            if (batch.size() >= FAST_INDEX_BATCH_SIZE) {
                                try {
                                    recordSeenPaths(scanId, activeRole, batch);
                                } catch (RuntimeException failure) {
                                    enumerationComplete[0] = false;
                                    log.warn("记录本轮扫描路径失败，本轮不标记缺失：{}", failure.getMessage());
                                }
                                processFastIndexBatch(batch, externalDefault, knownArtists, counters, totals,
                                        startedAt, discovered[0], activeRole,
                                        scanId, maxIdAtScanStart, leaseGuard);
                                batch.clear();
                            }
                        } catch (LeaseLostException lost) {
                            throw lost;
                        } catch (RuntimeException failure) {
                            enumerationComplete[0] = false;
                            counters.failedPaths++;
                            lastErrorMessage[0] = failure.getMessage();
                            log.warn("快速索引失败，跳过：{} - {}", file, failure.getMessage());
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult postVisitDirectory(Path dir, IOException failure) {
                        // NIO calls postVisitDirectory only after an accepted preVisitDirectory;
                        // that callback pushes this exact directory onto the traversal stack.
                        directoryLyrics.pop();
                        if (failure != null) {
                            enumerationComplete[0] = false;
                            counters.failedPaths++;
                            lastErrorMessage[0] = failure.getMessage();
                            log.warn("读取曲库目录失败，保留待重试：{} - {}", dir, failure.getMessage());
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException failure) {
                        enumerationComplete[0] = false;
                        counters.failedPaths++;
                        lastErrorMessage[0] = failure.getMessage();
                        log.warn("读取曲库文件失败，保留待重试：{} - {}", file, failure.getMessage());
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException e) {
                enumerationComplete[0] = false;
                counters.failedPaths++;
                lastErrorMessage[0] = e.getMessage();
                log.error("遍历曲库失败：{}", e.getMessage());
            }
            if (!batch.isEmpty()) {
                try {
                    recordSeenPaths(scanId, activeRole, batch);
                } catch (RuntimeException failure) {
                    enumerationComplete[0] = false;
                    log.warn("记录本轮扫描路径失败，本轮不标记缺失：{}", failure.getMessage());
                }
                processFastIndexBatch(batch, externalDefault, knownArtists, counters, totals,
                        startedAt, discovered[0], activeRole,
                        scanId, maxIdAtScanStart, leaseGuard);
                batch.clear();
            }

            if (enumerationComplete[0]) {
                reconcileMissingFiles(scanId, activeRole, counters,
                        validFilesAtScanStart, discovered[0], leaseGuard);
            } else {
                log.warn("曲库枚举未完整结束，本轮不标记消失文件，等待下次扫描重试：{}", root);
            }

            long pendingCount = fileRepo.countByFileRoleAndProbePendingTrue(activeRole);
            counters.probeQueued = safeInt(pendingCount);
            publishProgress(true, discovered[0], counters.fastIndexed, null, totals,
                    PHASE_MEDIA_PROBE, counters.fastIndexed, counters.probeQueued, 0,
                    startedAt, null);
            processPendingProbes(rootContext, artistIndex, knownArtists, externalDefault,
                    activeRole, scanId, maxIdAtScanStart,
                    counters, totals, startedAt, discovered[0], leaseGuard);

            if (artistProfileBootstrap != null) {
                try {
                    artistProfileBootstrap.refreshAfterScan();
                } catch (RuntimeException failure) {
                    // Profile reconciliation is asynchronous follow-up work;
                    // a rejected refresh must not turn a completed scan into
                    // a failed scan.
                    log.warn("歌手档案异步刷新未排队，等待定时补偿：{}", failure.getMessage());
                }
            }

            log.info("扫描完成：共 {} 文件，新增 {}，更新 {}，跳过 {}，未识别 {}",
                    discovered[0], totals.added, totals.updated, totals.skipped, totals.unrecognized);
            ScanResult result = new ScanResult(discovered[0], totals.added, totals.updated,
                    totals.skipped, totals.unrecognized, counters.fastIndexed,
                    counters.probeQueued, counters.probeCalls, counters.hashCalls,
                    counters.dbUpdates, counters.missing);
            ScanState finalState = counters.failedPaths > 0 && discovered[0] == 0
                    ? ScanState.FAILED
                    : counters.failedPaths > 0
                    ? ScanState.PARTIAL
                    : ScanState.COMPLETED;
            String finalErrorCode = counters.failedPaths > 0 ? "SCAN_PARTIAL_OR_FAILED" : null;
            String finalErrorMsg = counters.failedPaths > 0 ? lastErrorMessage[0] : null;
            publishProgress(false, discovered[0], discovered[0], null, totals,
                    finalState == ScanState.FAILED ? "FAILED" : PHASE_COMPLETED,
                    counters.fastIndexed, counters.probeQueued, totals.probeCompleted,
                    startedAt, OffsetDateTime.now(),
                    finalState, counters.failedPaths, finalErrorCode, finalErrorMsg);
            if (catalogRevisionService != null && result.dbUpdates() > 0) {
                catalogRevisionService.bumpIfChanged(true);
            }
            return result;
            } finally {
                cleanupSeenPaths(scanId);
            }
    }

    private void processFastIndexBatch(List<FastIndexEntry> batch, AudioLayout externalDefault,
                                       Collection<String> knownArtists,
                                       ScanCounters counters, ScanTotals totals,
                                       OffsetDateTime startedAt, int discovered, String activeRole,
                                       UUID scanId, long maxIdAtScanStart,
                                       LibraryScanLeaseGuard leaseGuard) {
        leaseGuard.assertOwned();
        List<FastIndexEntry> resolvedBatch;
        try {
            resolvedBatch = resolveExistingEntries(batch, activeRole, scanId, maxIdAtScanStart);
        } catch (RuntimeException failure) {
            // Do not treat a failed lookup as "all new". That could create
            // duplicate provisional rows or bind the wrong remounted file.
            log.warn("读取当前扫描批次的已有记录失败，等待下次扫描重试：{}", failure.getMessage());
            publishProgress(true, discovered, counters.fastIndexed, null, totals,
                    PHASE_FAST_INDEX, counters.fastIndexed, 0, totals.probeCompleted,
                    startedAt, null);
            return;
        }
        Map<Long, Song> songsForFilenameRepair = loadSongsForFilenameRepair(resolvedBatch);
        Runnable work = () -> {
            for (FastIndexEntry entry : resolvedBatch) {
                LibrarySnapshotPolicy.Change change = snapshotChange(entry);
                if (change == LibrarySnapshotPolicy.Change.UNCHANGED) {
                    Song song = entry.existing().map(SongFile::getSongId)
                            .map(songsForFilenameRepair::get)
                            .orElse(null);
                    boolean repaired = reconcileUnchangedFilenameMetadata(
                            entry, song, knownArtists, counters);
                    reactivateIfNeeded(entry, counters);
                    if (repaired) totals.updated++;
                    else totals.skipped++;
                } else if (change == LibrarySnapshotPolicy.Change.LYRIC_ONLY) {
                    LyricRefreshOutcome outcome = refreshSidecarLyric(entry, counters);
                    if (outcome == LyricRefreshOutcome.NEEDS_MEDIA_PROBE) {
                        prepareFastIndex(entry, counters, externalDefault, knownArtists);
                    } else if (outcome == LyricRefreshOutcome.UPDATED) {
                        totals.updated++;
                    } else {
                        totals.skipped++;
                    }
                } else {
                    // classify() returns MEDIA_CHANGED after the two states above.
                    prepareFastIndex(entry, counters, externalDefault, knownArtists);
                }
            }
        };
        try {
            if (batchTransaction == null) {
                work.run();
            } else {
                batchTransaction.executeWithoutResult(status -> {
                    leaseGuard.assertOwned();
                    work.run();
                    leaseGuard.assertOwned();
                    if (entityManager != null) {
                        entityManager.flush();
                        entityManager.clear();
                    }
                });
            }
        } catch (LeaseLostException lost) {
            throw lost;
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
     * Load only the songs that could benefit from the legacy filename repair.
     * The lookup is batched so a scan never turns this compatibility path into
     * one database query per unchanged file.
     */
    private Map<Long, Song> loadSongsForFilenameRepair(List<FastIndexEntry> batch) {
        Set<Long> songIds = new LinkedHashSet<>();
        for (FastIndexEntry entry : batch) {
            if (snapshotChange(entry) != LibrarySnapshotPolicy.Change.UNCHANGED
                    || !entry.filenameMeta().recognized()) {
                continue;
            }
            entry.existing().map(SongFile::getSongId).ifPresent(songIds::add);
        }
        if (songIds.isEmpty()) return Map.of();

        try {
            Iterable<Song> songs = songRepo.findAllById(songIds);
            Map<Long, Song> byId = new HashMap<>();
            if (songs != null) {
                for (Song song : songs) {
                    if (song != null && song.getId() != null) byId.put(song.getId(), song);
                }
            }
            return byId;
        } catch (RuntimeException failure) {
            // Filename repair is an optional compatibility enhancement. A
            // transient read failure must not turn a valid unchanged scan into
            // a failed batch or cause a re-probe.
            log.warn("读取待修复歌曲元数据失败，本批次跳过旧文件名修复：{}", failure.getMessage());
            return Map.of();
        }
    }

    /**
     * Repair only a previously unrecognized row when the current filename is
     * now unambiguous. This path intentionally reads no media bytes and never
     * touches the source file.
     */
    private boolean reconcileUnchangedFilenameMetadata(FastIndexEntry entry, Song song,
                                                       Collection<String> knownArtists,
                                                       ScanCounters counters) {
        ParsedMeta candidate = entry.filenameMeta();
        if (song == null || !candidate.recognized()
                || !"unrecognized".equalsIgnoreCase(song.getStatus())
                || hasManualIdentityOverride(song)) {
            return false;
        }

        String fingerprint = MediaClassifier.fingerprint(
                candidate.artist(), candidate.title(), song.getDurationMs());
        if (!Objects.equals(fingerprint, song.getFingerprint())) {
            try {
                Optional<Song> conflict = songRepo.findByFingerprint(fingerprint)
                        .filter(other -> !sameSong(other, song));
                if (conflict.isPresent()) {
                    log.warn("文件名修复发现歌曲指纹冲突，保留待审核：{} -> #{}",
                            entry.file().getFileName(), conflict.get().getId());
                    return false;
                }
            } catch (RuntimeException failure) {
                log.warn("检查文件名修复指纹冲突失败，保留待审核：{} - {}",
                        entry.file().getFileName(), failure.getMessage());
                return false;
            }
        }

        // Manual title/artist locks are rejected above by hasManualIdentityOverride.
        if (!Objects.equals(song.getTitle(), candidate.title())) {
            song.setTitle(candidate.title());
            song.setTitlePy(PinyinUtil.fullPinyin(candidate.title()));
            song.setTitleInit(PinyinUtil.initials(candidate.title()));
        }
        if (!Objects.equals(song.getArtist(), candidate.artist())) {
            song.setArtist(candidate.artist());
            song.setArtistPy(PinyinUtil.fullPinyin(candidate.artist()));
            song.setArtistInit(PinyinUtil.initials(candidate.artist()));
        }
        if (!song.isMetadataLocked("language")) {
            String language = normalizeLanguage(candidate.language());
            if (!Objects.equals(song.getLanguage(), language)) {
                song.setLanguage(language);
            }
        }
        if (!song.isMetadataLocked("tags")) {
            String[] tags = new String[]{candidate.category()};
            if (!Arrays.equals(song.getTags(), tags)) {
                song.setTags(tags);
            }
        }
        if (!song.isMetadataLocked("vocalForm")
                && !candidate.vocalForm().isBlank()
                && !Objects.equals(song.getVocalForm(), candidate.vocalForm())) {
            song.setVocalForm(candidate.vocalForm());
        }
        if (!Objects.equals(song.getCatalogNumber(), candidate.catalogNumber())) {
            song.setCatalogNumber(candidate.catalogNumber());
        }
        if (!Objects.equals(song.getFingerprint(), fingerprint)) {
            song.setFingerprint(fingerprint);
        }
        song.setStatus("ok");
        boolean needsAiOptimization = needsAiOptimization(candidate.recognized(), song);
        if (song.isNeedsAiOptimization() != needsAiOptimization) {
            song.setNeedsAiOptimization(needsAiOptimization);
        }

        song.setMetadataProvenance("{\"title\":{\"source\":\"filename_reparse\"},"
                + "\"artist\":{\"source\":\"filename_reparse\"}}");
        songRepo.save(song);
        syncArtistCredits(song, knownArtists);
        counters.dbUpdates++;
        return true;
    }

    /**
     * Resolve only the current Fast Index batch.  The old implementation loaded
     * every SongFile entity before walking the filesystem; on a large NAS library
     * that duplicated the same unbounded-result-set failure as Song.findAll().
     */
    private List<FastIndexEntry> resolveExistingEntries(List<FastIndexEntry> batch,
                                                        String activeRole,
                                                        UUID scanId,
                                                        long maxIdAtScanStart) {
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
                    if (previous != null) {
                        byUniqueRelativePath.remove(row.getRelativePath());
                        ambiguous.add(row.getRelativePath());
                    }
                }
            }
        }

        List<String> pendingPathsAtStart = new ArrayList<>();
        List<FastIndexEntry> resolved = new ArrayList<>(batch.size());
        for (FastIndexEntry entry : batch) {
            SongFile existing = byPath.get(entry.path());
            if (existing == null) {
                existing = byUniqueRelativePath.get(entry.relativePath());
            }
            boolean existedAtScanStart = wasPresentAtScanStart(existing, maxIdAtScanStart);
            boolean pendingAtScanStart = existedAtScanStart && existing.isProbePending();
            if (pendingAtScanStart) pendingPathsAtStart.add(entry.path());
            resolved.add(entry.withExisting(existing)
                    .withScanHistory(existedAtScanStart, pendingAtScanStart));
        }
        if (!pendingPathsAtStart.isEmpty()) {
            try {
                seenPathStore.recordPendingBatch(scanId, activeRole, pendingPathsAtStart);
            } catch (RuntimeException failure) {
                log.warn("记录扫描开始时的 pending 状态失败，本批次仍继续索引：{}", failure.getMessage());
            }
        }
        return resolved;
    }



    private void processPendingProbes(ScanRootContext rootContext,
                                      FilenameParser.ArtistIndex artistIndex,
                                      Collection<String> knownArtists,
                                      AudioLayout externalDefault,
                                      String activeRole,
                                      UUID scanId,
                                      long maxIdAtScanStart,
                                      ScanCounters counters,
                                      ScanTotals totals,
                                      OffsetDateTime startedAt,
                                      int discovered,
                                      LibraryScanLeaseGuard leaseGuard) {
        totals.probeStartedAt = OffsetDateTime.now();
        String afterPath = "";
        while (true) {
            leaseGuard.assertOwned();
            Slice<SongFile> page;
            try {
                page = fileRepo.findPendingForScan(activeRole, maxIdAtScanStart, scanId,
                        afterPath, PageRequest.of(0, PROBE_PAGE_SIZE));
            } catch (RuntimeException failure) {
                log.warn("读取媒体探测队列失败，保留 pending 记录等待重试：{}", failure.getMessage());
                return;
            }
            // Spring Data's Slice query returns an empty slice when no rows match;
            // a null page is outside the repository contract.
            if (page.getContent().isEmpty()) return;

            Set<String> pendingAtStartPaths;
            try {
                pendingAtStartPaths = seenPathStore.findPendingAtStart(scanId, activeRole,
                        page.getContent().stream().map(SongFile::getFilePath)
                                .filter(path -> !isBlank(path)).toList());
            } catch (RuntimeException failure) {
                log.warn("读取扫描开始时的 pending 状态失败，保留队列等待下次扫描：{}", failure.getMessage());
                pendingAtStartPaths = page.getContent().stream()
                        .filter(file -> file.getId() <= maxIdAtScanStart)
                        .map(SongFile::getFilePath)
                        .filter(path -> !isBlank(path))
                        .collect(java.util.stream.Collectors.toSet());
            }
            Set<String> pendingPathsForProbe = pendingAtStartPaths;

            CompletionService<ProbeResult> completionService = new ExecutorCompletionService<>(probeExecutor);
            List<Future<ProbeResult>> futures = new ArrayList<>();
            int submitted = 0;
            for (SongFile tracked : page.getContent()) {
                if (isBlank(tracked.getFilePath())) continue;
                afterPath = tracked.getFilePath();
                Path file;
                try {
                    file = Path.of(tracked.getFilePath());
                } catch (RuntimeException invalidPath) {
                    log.warn("探测队列存在无效路径，保留 pending：{}", tracked.getFilePath());
                    continue;
                }
                if (!rootContext.allowsExistingFile(file)
                        || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || !isMediaFile(file)) {
                    continue;
                }
                futures.add(completionService.submit(() -> probePendingFile(file, tracked, rootContext,
                        artistIndex, maxIdAtScanStart,
                        pendingPathsForProbe.contains(tracked.getFilePath()))));
                submitted++;
            }

            for (int completed = 0; completed < submitted; completed++) {
                ProbeResult result;
                try {
                    result = completionService.take().get();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    futures.forEach(future -> future.cancel(true));
                    return;
                } catch (ExecutionException failure) {
                    totals.skipped++;
                    totals.probeCompleted++;
                    log.warn("媒体探测任务异常，保留 pending：{}",
                            failure.getCause().getMessage());
                    continue;
                }

                counters.probeCalls += result.attempted() ? 1 : 0;
                IngestOutcome outcome = IngestOutcome.SKIPPED;
                if (result.probe() == null) {
                    if (result.failure() != null) {
                        if (!(result.failure() instanceof InterruptedException)) {
                            log.debug("ffprobe 失败，保留 pending：{} - {}",
                                    result.file(), result.failure().getMessage());
                        }
                    }
                } else {
                    try {
                        outcome = ingestionWriter.write(result.entry().toIngestionInput(), null, null, null, false,
                                knownArtists, counters, externalDefault, result.probe(), leaseGuard).outcome();
                    } catch (LeaseLostException lost) {
                        throw lost;
                    } catch (RuntimeException failure) {
                        log.warn("媒体结果入库失败，保留 pending：{} - {}",
                                result.file(), failure.getMessage());
                    }
                }
                recordOutcome(totals, outcome);
                totals.probeCompleted++;
                publishProgress(true, discovered, counters.fastIndexed, result.file().getFileName().toString(), totals,
                        PHASE_MEDIA_PROBE, counters.fastIndexed, counters.probeQueued,
                        totals.probeCompleted, startedAt, null);
            }
            if (page.getContent().size() < PROBE_PAGE_SIZE) return;
        }
    }

    /**
     * Probe workers are deliberately restricted to filesystem metadata and FFprobe.
     * JPA entities, asset writes and fingerprint reconciliation stay on the scan
     * thread so two equal fingerprints cannot race between find and save.
     */
    private ProbeResult probePendingFile(Path file, SongFile tracked, ScanRootContext rootContext,
                                         FilenameParser.ArtistIndex artistIndex,
                                         long maxIdAtScanStart,
                                         boolean pendingAtScanStart) {
        try {
            if (!rootContext.allowsExistingFile(file)) {
                return new ProbeResult(file, null, null, null, false);
            }
            BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (!attrs.isRegularFile()) {
                return new ProbeResult(file, null, null, null, false);
            }
            LyricSnapshot persistedLyric = lyricSnapshotForPendingProbe(tracked, sidecarLyricOf(file));
            FastIndexEntry entry = fastIndex(file, attrs, artistIndex, null,
                    null, rootContext.configuredRoot(), null, persistedLyric);
            SongFile resolved = tracked;
            boolean existedAtScanStart = wasPresentAtScanStart(resolved, maxIdAtScanStart);
            entry = entry.withExisting(resolved).withScanHistory(existedAtScanStart, pendingAtScanStart);

            MediaProbeStage.Result result = mediaProbeStage.probe(file);
            return new ProbeResult(file, entry, result.probe(), result.failure(), result.attempted());
        } catch (Exception failure) {
            return new ProbeResult(file, null, null, failure, false);
        }
    }

    private static void recordOutcome(ScanTotals totals, IngestOutcome outcome) {
        Objects.requireNonNull(outcome, "outcome");
        if (outcome == IngestOutcome.ADDED) {
            totals.added++;
        } else if (outcome == IngestOutcome.UPDATED) {
            totals.updated++;
        } else if (outcome == IngestOutcome.SKIPPED) {
            totals.skipped++;
        } else {
            // UNRECOGNIZED is the only remaining IngestOutcome.
            totals.added++;
            totals.unrecognized++;
        }
    }

    private static boolean wasPresentAtScanStart(SongFile file, long maxIdAtScanStart) {
        return file != null && file.getId() <= maxIdAtScanStart;
    }

    private void publishProgress(boolean running, int total, int completed, String currentFile,
                                 ScanTotals totals, String phase, int fastIndexed, int probeQueued,
                                 int probeCompleted, OffsetDateTime startedAt,
                                 OffsetDateTime finishedAt) {
        publishProgress(running, total, completed, currentFile, totals, phase, fastIndexed, probeQueued,
                probeCompleted, startedAt, finishedAt, null, 0, null, null);
    }

    private void publishProgress(boolean running, int total, int completed, String currentFile,
                                 ScanTotals totals, String phase, int fastIndexed, int probeQueued,
                                 int probeCompleted, OffsetDateTime startedAt,
                                 OffsetDateTime finishedAt,
                                 ScanState explicitState, int failedPaths, String errorCode, String errorMessage) {
        double rate = 0.0;
        Long eta = null;
        if (running && probeCompleted > 0) {
            long elapsedSeconds = Math.max(1,
                    Duration.between(totals.probeStartedAt, OffsetDateTime.now()).toSeconds());
            rate = Math.round((double) probeCompleted / elapsedSeconds * 10.0) / 10.0;
            if (rate > 0.05 && probeQueued > probeCompleted) {
                eta = (long) Math.ceil((probeQueued - probeCompleted) / rate);
            }
        }
        ScanState state = explicitState == null ? ScanState.RUNNING : explicitState;
        scanProgress.set(new ScanProgress(running, total, completed, currentFile,
                totals.added,
                totals.updated,
                totals.skipped,
                totals.unrecognized,
                startedAt, finishedAt, phase, total, fastIndexed, probeQueued, probeCompleted,
                rate, eta, state, failedPaths, errorCode, errorMessage));
    }

    private static int safeInt(long value) {
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) Math.max(0, value);
    }

    /** Starts the active library scan asynchronously for the admin progress endpoint. */
    public ScanProgress startScan() {
        if (!scanRunning.compareAndSet(false, true)) return scanProgress.get();
        scanProgress.set(new ScanProgress(true, 0, 0, null, 0, 0, 0, 0,
                OffsetDateTime.now(), null, PHASE_DISCOVERING, 0, 0, 0, 0, 0.0, null,
                ScanState.RUNNING, 0, null, null));
        try {
            scanExecutor.submit(() -> {
                try {
                    scanAllInternal(LibraryScanLeaseGuard.noop());
                } catch (RuntimeException exception) {
                    log.error("曲库扫描发生未捕获异常", exception);
                    ScanProgress failed = scanProgress.get();
                    scanProgress.set(new ScanProgress(false, failed.total(), failed.completed(), null,
                            failed.added(), failed.updated(), failed.skipped() + 1,
                            failed.unrecognized(), failed.startedAt(), OffsetDateTime.now(),
                            "FAILED", failed.discovered(), failed.fastIndexed(), failed.probeQueued(),
                            failed.probeCompleted(), 0.0, null,
                            ScanState.FAILED, failed.failedPaths() + 1, "SCAN_RUNTIME_ERROR", exception.getMessage()));
                } finally {
                    scanRunning.set(false);
                }
            });
            return scanProgress.get();
        } catch (RuntimeException exception) {
            scanRunning.set(false);
            throw exception;
        }
    }

    public ScanProgress getScanProgress() {
        return scanProgress.get();
    }

    /** Shared filename parsing entry point for Managed imports and active-library scans. */
    public ParsedMeta parseFilename(String filename) {
        return FilenameParser.parse(filename, artistIndexFor(existingArtistNames()));
    }

    private void syncArtistCredits(Song song, Collection<String> knownArtists) {
        if (artistCreditService == null) return;
        if (knownArtists.isEmpty()) {
            // Preserve the original service seam for scans without any
            // reliable artist evidence; explicit separators retain their
            // historical behavior in the two-argument parser.
            artistCreditService.replace(song.getId(), song.getArtist());
        } else {
            artistCreditService.replace(song.getId(), song.getArtist(), knownArtists);
        }
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
        return fastIndex(file, attrs, artistIndex, trackedByPath, trackedByRelativePath,
                scanRoot, null, null);
    }

    private FastIndexEntry fastIndex(Path file, BasicFileAttributes attrs,
                                     FilenameParser.ArtistIndex artistIndex,
                                     Map<String, SongFile> trackedByPath,
                                     Map<String, SongFile> trackedByRelativePath,
                                     Path scanRoot,
                                     DirectoryLyricIndex dirLyrics) {
        return fastIndex(file, attrs, artistIndex, trackedByPath, trackedByRelativePath,
                scanRoot, dirLyrics, null);
    }

    private FastIndexEntry fastIndex(Path file, BasicFileAttributes attrs,
                                     FilenameParser.ArtistIndex artistIndex,
                                     Map<String, SongFile> trackedByPath,
                                     Map<String, SongFile> trackedByRelativePath,
                                     Path scanRoot,
                                     DirectoryLyricIndex dirLyrics,
                                     LyricSnapshot lyricSnapshotOverride) {
        String path = file.toString();
        Path sidecarLyric = sidecarLyricOf(file);
        OffsetDateTime mediaMtime = attrs.lastModifiedTime().toInstant().atOffset(ZoneOffset.UTC);
        String mediaIdentity = mediaFileIdentity(attrs);
        String sidecarName = sidecarLyric.getFileName().toString().toLowerCase(Locale.ROOT);
        LyricSnapshot lyricSnapshot;
        if (lyricSnapshotOverride != null) {
            lyricSnapshot = lyricSnapshotOverride;
        } else if (dirLyrics != null && dirLyrics.complete()) {
            lyricSnapshot = dirLyrics.snapshots().getOrDefault(sidecarName, LyricSnapshot.missing());
        } else {
            // An incomplete directory listing must not turn an existing sidecar
            // into a false "missing" snapshot. Fall back to the exact sidecar
            // lookup for correctness and retry the directory on the next scan.
            lyricSnapshot = lyricSnapshotOf(sidecarLyric);
        }
        String relativePath = relativePathOf(file, scanRoot);
        SongFile tracked = trackedByPath == null
                ? fileRepo.findByFilePath(path).orElse(null)
                : trackedByPath.get(path);
        if (tracked == null && trackedByRelativePath != null) {
            tracked = trackedByRelativePath.get(relativePath);
        }
        return new FastIndexEntry(file, path, relativePath, attrs.size(), newestMtime(mediaMtime, lyricSnapshot),
                legacyFileIdentity(mediaIdentity, lyricSnapshot.fileIdentity()),
                FilenameParser.parse(file.getFileName().toString(), artistIndex),
                mediaMtime, mediaIdentity, lyricSnapshot,
                Optional.ofNullable(tracked), tracked != null, tracked != null && tracked.isProbePending());
    }

    /** Reuses the Fast Index sidecar snapshot instead of probing the same directory again. */
    private static LyricSnapshot lyricSnapshotForPendingProbe(SongFile tracked, Path sidecarLyric) {
        if (tracked.getLyricSnapshotVersion() == null) {
            // Rows created before the independent snapshot columns are retained
            // conservatively; one legacy lookup upgrades them safely.
            return lyricSnapshotOf(sidecarLyric);
        }
        if (tracked.getLyricSize() == null) return LyricSnapshot.missing();
        return new LyricSnapshot(true, true, tracked.getLyricSize(),
                tracked.getLyricMtime(), tracked.getLyricFileIdentity());
    }

    /**
     * Compare media and sidecar snapshots independently. A sidecar-only change must not
     * enqueue a media probe, while an old row without independent snapshots is handled
     * conservatively and upgraded during the next safe fast-index pass.
     */
    private static LibrarySnapshotPolicy.Change snapshotChange(FastIndexEntry entry) {
        SongFile existing = entry.existing().orElse(null);
        LyricSnapshot lyric = entry.lyricSnapshot();
        LibrarySnapshotPolicy.Facts current = new LibrarySnapshotPolicy.Facts(
                entry.size(), entry.mtime(), entry.fileIdentity(), entry.mediaMtime(), entry.mediaIdentity(),
                LYRIC_SNAPSHOT_VERSION,
                lyric.readable() ? lyric.size() : null,
                lyric.readable() ? lyric.mtime() : null,
                lyric.readable() ? lyric.fileIdentity() : null,
                false);
        return LibrarySnapshotPolicy.classify(LibrarySnapshotPolicy.fromSongFile(existing), current,
                lyric.readable(), lyric.present());
    }

    static OffsetDateTime normalizeMtime(OffsetDateTime value) {
        return LibrarySnapshotPolicy.normalizeMtime(value);
    }

    /** Persist a changed snapshot or an identity-triggered re-probe before FFprobe runs. */
    private FastIndexEntry prepareFastIndex(FastIndexEntry entry, ScanCounters counters,
                                            AudioLayout externalDefault, Collection<String> knownArtists) {
        SongFile existing = entry.existing().orElse(null);
        OffsetDateTime normalizedMtime = normalizeMtime(entry.mtime());
        if (existing != null) {
            // Every caller selects this path only for MEDIA_CHANGED or an
            // identity-bearing lyric update that explicitly requires a probe.
            existing.setFilePath(entry.path());
            existing.setFileSize(entry.size());
            existing.setFileMtime(normalizedMtime);
            existing.setFileIdentity(entry.fileIdentity());
            applySnapshots(existing, entry);
            existing.setProbePending(true);
            existing.setValid(true);
            String relativePath = entry.relativePath();
            if (isBlank(existing.getRelativePath())) {
                existing.setRelativePath(relativePath);
            }
            reconcilePendingFilenameStatus(entry, existing, counters);
            fileRepo.save(existing);
            counters.dbUpdates++;
            return entry.withExisting(existing);
        }

        Song provisional = new Song();
        ParsedMeta parsed = entry.filenameMeta();
        String title = parsed.title().isBlank()
                ? entry.file().getFileName().toString() : parsed.title();
        String artist = parsed.artist().isBlank()
                ? "未知歌手" : parsed.artist();
        provisional.setTitle(title);
        provisional.setArtist(artist);
        provisional.setTitlePy(PinyinUtil.fullPinyin(title));
        provisional.setTitleInit(PinyinUtil.initials(title));
        provisional.setArtistPy(PinyinUtil.fullPinyin(artist));
        provisional.setArtistInit(PinyinUtil.initials(artist));
        provisional.setLanguage(parsed.language().isBlank()
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
        provisional.setTags(parsed.category().isBlank()
                ? new String[0] : new String[]{parsed.category()});
        if (!parsed.vocalForm().isBlank()) {
            provisional.setVocalForm(parsed.vocalForm());
        }
        if (!parsed.catalogNumber().isBlank()) {
            provisional.setCatalogNumber(parsed.catalogNumber());
        }
        provisional.setMetadataProvenance("{\"title\":{\"source\":\"filename_fast_index\"},"
                + "\"artist\":{\"source\":\"filename_fast_index\"}}");
        provisional.setNeedsAiOptimization(!parsed.recognized());
        provisional = songRepo.save(provisional);
        if (artistCreditService != null) {
            artistCreditService.createInitial(provisional.getId(), provisional.getArtist(), knownArtists);
            counters.initialArtistCreditSongIds.add(provisional.getId());
        }
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
        indexed.setAudioLayoutSource(AudioLayoutSource.AUTO_DEFAULT);
        indexed.setMediaType(MediaClassifier.PENDING_PROBE);
        if (LibraryModePolicy.isExternalReadOnly(props)) {
            // Store the configured default on the pending row so a later retry
            // can distinguish it from any per-file override made in the admin UI.
            indexed.setAudioLayout(externalDefault);
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
        // prepareFastIndex marks the row pending immediately before this call.
        if (entry.filenameMeta().recognized()) {
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
        SongFile existing = entry.existing().orElseThrow();

        boolean wasInvalid = !existing.isValid();
        boolean changed = false;
        if (!Objects.equals(existing.getFilePath(), entry.path())) {
            existing.setFilePath(entry.path());
            changed = true;
        }
        String relativePath = entry.relativePath();
        if (isBlank(existing.getRelativePath())) {
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
        SongFile file = entry.existing().orElseThrow();

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

    private void recordSeenPaths(UUID scanId, String activeRole, Collection<FastIndexEntry> batch) {
        seenPathStore.recordBatch(scanId, activeRole,
                batch.stream().map(FastIndexEntry::path).filter(Objects::nonNull).toList());
    }

    private void reconcileMissingFiles(UUID scanId, String activeRole, ScanCounters counters,
                                       long validFilesAtScanStart, int currentSeenFiles,
                                       LibraryScanLeaseGuard leaseGuard) {
        leaseGuard.assertOwned();
        AppProperties.MissingGuard config = props.getScan().getMissingGuard();
        MissingFileGuard guard = new MissingFileGuard(
                config.getMinimumPreviousFiles(), config.getMinimumSeenRatio());
        if (guard.shouldBlock(validFilesAtScanStart, currentSeenFiles, true,
                config.isAllowMassMissing())) {
            log.warn("本轮疑似大规模曲库消失，跳过缺失对账：扫描前有效文件={}，本轮发现={}，根目录={}",
                    validFilesAtScanStart, currentSeenFiles,
                    LibraryModePolicy.activeLibraryRoot(props));
            return;
        }
        try {
            leaseGuard.assertOwned();
            LibraryScanSeenPathStore.MissingFiles missing = seenPathStore.markMissing(scanId, activeRole,
                    LibraryModePolicy.activeLibraryRoot(props).toAbsolutePath().normalize().toString());
            counters.dbUpdates += missing.filesMarked() + missing.songsMarked();
            counters.missing += missing.filesMarked();
        } catch (LeaseLostException lost) {
            throw lost;
        } catch (RuntimeException failure) {
            log.warn("本轮缺失文件对账失败，保留现有数据库状态：{}", failure.getMessage());
        }
    }

    private void cleanupSeenPaths(UUID scanId) {
        try {
            seenPathStore.delete(scanId);
        } catch (RuntimeException failure) {
            log.warn("清理本轮扫描暂存路径失败：{}", failure.getMessage());
        }
    }

    private long countValidFilesAtScanStart(String activeRole, Path root) {
        String normalizedRoot = root.toAbsolutePath().normalize().toString();
        String backslashPrefix;
        String slashPrefix;
        if (normalizedRoot.endsWith(root.getFileSystem().getSeparator())) {
            backslashPrefix = normalizedRoot;
            slashPrefix = normalizedRoot;
        } else {
            backslashPrefix = normalizedRoot + "\\";
            slashPrefix = normalizedRoot + "/";
        }
        try {
            return fileRepo.countValidByFileRoleAndRoot(activeRole, normalizedRoot,
                    backslashPrefix, slashPrefix);
        } catch (RuntimeException failure) {
            log.warn("读取扫描前有效文件数量失败，本轮不执行缺失对账：{}", failure.getMessage());
            return Long.MAX_VALUE;
        }
    }

    enum IngestOutcome { ADDED, UPDATED, SKIPPED, UNRECOGNIZED }

    /** 单文件入库（幂等：已存在的路径按 path + size + mtime 判断是否需更新） */
    public IngestOutcome ingest(Path file) {
        Path activeFile = requireActiveLibraryFile(file);
        Collection<String> knownArtists = existingArtistNames();
        FastIndexEntry entry = fastIndex(activeFile, knownArtists);
        LibrarySnapshotPolicy.Change change = snapshotChange(entry);
        if (change == LibrarySnapshotPolicy.Change.UNCHANGED) return IngestOutcome.SKIPPED;

        ScanCounters counters = new ScanCounters();
        AudioLayout externalDefault = LibraryModePolicy.isExternalReadOnly(props)
                ? configuredExternalDefaultAudioLayout() : null;
        if (change == LibrarySnapshotPolicy.Change.LYRIC_ONLY) {
            FastIndexEntry currentEntry = entry;
            LyricRefreshOutcome outcome = writeTransaction(() -> refreshSidecarLyric(currentEntry, counters));
            if (outcome == LyricRefreshOutcome.UPDATED) return IngestOutcome.UPDATED;
            if (outcome == LyricRefreshOutcome.UNAVAILABLE) return IngestOutcome.SKIPPED;
            FastIndexEntry lyricEntry = entry;
            entry = writeTransaction(() -> prepareFastIndex(lyricEntry, counters, externalDefault, knownArtists));
        }
        MediaProbe probe = probeForDirectIngest(entry.file(), counters);
        if (probe == null) return IngestOutcome.SKIPPED;
        FastIndexEntry preparedEntry = entry;
        return ingestionWriter.write(preparedEntry.toIngestionInput(), null, null, null, false,
                knownArtists, counters, externalDefault, probe, null).outcome();
    }

    public IngestResult ingestLibraryFile(Path file, Path sourceFile, String sourceMd5, String outputMd5, boolean transcodeRequired) {
        return ingestLibraryFile(file, sourceFile, sourceMd5, outputMd5, transcodeRequired, null);
    }

    /** Performs admission and probing before the write transaction; callers may reuse an identical probe. */
    public IngestResult ingestLibraryFile(Path file, Path sourceFile, String sourceMd5, String outputMd5,
                                          boolean transcodeRequired, MediaProbe preProbed) {
        LibraryModePolicy.requireManaged(props, "导入");
        Path managedOutput = requireActiveLibraryFile(file);
        Collection<String> knownArtists = existingArtistNames();
        FastIndexEntry entry = fastIndex(managedOutput, knownArtists);
        LibrarySnapshotPolicy.Change change = snapshotChange(entry);
        if (change == LibrarySnapshotPolicy.Change.UNCHANGED) {
            SongFile existing = entry.existing().orElseThrow();
            return new IngestResult(false, existing.getSongId(), existing.getId());
        }
        ScanCounters counters = new ScanCounters();
        if (change == LibrarySnapshotPolicy.Change.LYRIC_ONLY) {
            FastIndexEntry currentEntry = entry;
            LyricRefreshOutcome outcome = writeTransaction(() -> refreshSidecarLyric(currentEntry, counters));
            SongFile existing = entry.existing().orElseThrow();
            if (outcome == LyricRefreshOutcome.UPDATED || outcome == LyricRefreshOutcome.UNAVAILABLE) {
                return new IngestResult(outcome == LyricRefreshOutcome.UPDATED,
                        existing.getSongId(), existing.getId());
            }
            FastIndexEntry lyricEntry = entry;
            entry = writeTransaction(() -> prepareFastIndex(lyricEntry, counters, null, knownArtists));
        }
        MediaProbe probe = preProbed == null ? probeForDirectIngest(file, counters) : preProbed;
        if (probe == null) {
            SongFile existing = entry.existing().orElse(null);
            return new IngestResult(false, existing == null ? null : existing.getSongId(),
                    existing == null ? null : existing.getId());
        }
        FastIndexEntry preparedEntry = entry;
        IngestState state = ingestionWriter.write(preparedEntry.toIngestionInput(), sourceFile, sourceMd5, outputMd5,
                transcodeRequired, knownArtists, counters, null, probe, null);
        return asIngestResult(state);
    }

    private Path requireActiveLibraryFile(Path file) {
        Path root = LibraryModePolicy.activeLibraryRoot(props).toAbsolutePath().normalize();
        Path candidate = file.toAbsolutePath().normalize();
        if (!candidate.startsWith(root)) {
            if (LibraryModePolicy.isExternalReadOnly(props)) {
                throw new ApiException(LibraryModePolicy.EXTERNAL_READ_ONLY_CODE,
                        "EXTERNAL_READ_ONLY：媒体路径必须位于外部曲库目录内：" + candidate);
            }
            throw new ApiException("FILE_PATH_OUTSIDE_LIBRARY", "媒体路径必须位于当前曲库目录内：" + candidate);
        }
        if (LibraryModePolicy.isExternalReadOnly(props)) {
            LibraryModePolicy.requireExternalPathInsideSource(props, candidate);
        } else {
            LibraryModePolicy.requirePathInsideActiveLibrary(props, candidate);
        }
        if (!Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
            throw new ApiException("FILE_NOT_FOUND", "媒体路径不是普通文件：" + candidate);
        }
        return candidate;
    }

    private <T> T writeTransaction(Supplier<T> work) {
        return batchTransaction == null ? work.get() : batchTransaction.execute(status -> work.get());
    }

    private MediaProbe probeForDirectIngest(Path file, ScanCounters counters) {
        MediaProbeStage.Result result = mediaProbeStage.probe(file);
        if (result.attempted()) counters.probeCalls++;
        if (result.probe() != null) return result.probe();
        if (result.failure() != null && !(result.failure() instanceof InterruptedException)) {
            log.debug("ffprobe 失败：{} - {}", file.getFileName(), result.failure().getMessage());
        }
        return null;
    }

    private static IngestResult asIngestResult(IngestState state) {
        // The writer returns only after persisting the song and file rows. An
        // unrecognized filename is a review state, not a failed import.
        return new IngestResult(true, state.songId(), state.songFileId());
    }

    private AudioLayout configuredExternalDefaultAudioLayout() {
        AudioLayout configured = settingService == null
                ? AudioLayout.NORMAL_STEREO : settingService.externalDefaultAudioLayout();
        if (configured == null) configured = AudioLayout.NORMAL_STEREO;
        return configured;
    }

    static boolean sameSong(Song left, Song right) {
        if (left == null || right == null) return false;
        if (left == right) return true;
        return left.getId() != null && left.getId().equals(right.getId());
    }

    /** Null is not provisional; the public scan and import paths also pass only resolved songs. */
    static boolean isProvisionalSong(Song song) {
        return song != null && (MediaClassifier.PENDING_PROBE.equals(song.getMediaType())
                || song.getFingerprint() != null && song.getFingerprint().startsWith("fast-index-"));
    }

    static boolean hasManualIdentityOverride(Song song) {
        return song != null && (song.isMetadataLocked("title") || song.isMetadataLocked("artist"));
    }

    static boolean needsAiOptimization(boolean recognized, Song song) {
        return !recognized || "未知".equals(song.getLanguage()) || "未知歌手".equals(song.getArtist());
    }

    static Path sidecarLyricOf(Path mediaFile) {
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
        // Scan visitors, pending probes, and managed imports validate the active root first.
        Path candidate = file.toAbsolutePath().normalize();
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

    static SidecarLyricContent readSidecarLyric(Path sidecarLyric) {
        return readSidecarLyric(sidecarLyric, null);
    }

    static SidecarLyricContent readSidecarLyric(Path sidecarLyric, LyricSnapshot precomputedSnapshot) {
        LyricSnapshot snapshot = precomputedSnapshot != null ? precomputedSnapshot : lyricSnapshotOf(sidecarLyric);
        if (!snapshot.present()) return SidecarLyricContent.absent();
        if (!snapshot.readable()) return SidecarLyricContent.unreadable();
        if (snapshot.size() != null && snapshot.size() > AssetWriter.MAX_LYRIC_BYTES) {
            log.warn("同名歌词过大，保留旧缓存并跳过读取：{}", sidecarLyric);
            return SidecarLyricContent.unreadable();
        }
        try {
            byte[] bytes;
            try (var input = Files.newInputStream(sidecarLyric)) {
                bytes = input.readNBytes(AssetWriter.MAX_LYRIC_BYTES + 1);
            }
            if (bytes.length > AssetWriter.MAX_LYRIC_BYTES) {
                log.warn("同名歌词读取时超过上限，保留旧缓存并跳过读取：{}", sidecarLyric);
                return SidecarLyricContent.unreadable();
            }
            String text = new String(bytes, StandardCharsets.UTF_8);
            return new SidecarLyricContent(true, true,
                    LyricType.NONE.equals(LyricType.detect(text)) ? null : text);
        } catch (IOException e) {
            log.warn("读取同名歌词失败：{} - {}", sidecarLyric, e.getMessage());
            return SidecarLyricContent.unreadable();
        }
    }

    static void applySnapshots(SongFile file, FastIndexEntry entry) {
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

    static void applySnapshots(SongFile file, LibraryIngestionWriter.Input entry) {
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

    static String lrcTag(String lyric, String key) {
        if (lyric == null) return null;
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(?im)^\\[" + key + "\\s*:\\s*(.+?)\\]\\s*$").matcher(lyric);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    private static boolean containsIdentityTag(String lyric) {
        return lrcTag(lyric, "ti") != null || lrcTag(lyric, "ar") != null;
    }

    static String normalizeLanguage(String raw) {
        String value = raw.trim().toLowerCase(Locale.ROOT);
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

    private enum LyricRefreshOutcome { UPDATED, NEEDS_MEDIA_PROBE, UNAVAILABLE }

    private record DirectoryLyricIndex(Path directory, Map<String, LyricSnapshot> snapshots,
                                       boolean complete) {
        private static DirectoryLyricIndex load(Path directory) {
            Map<String, LyricSnapshot> snapshots = new HashMap<>();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*.lrc")) {
                for (Path lrc : stream) {
                    snapshots.put(lrc.getFileName().toString().toLowerCase(Locale.ROOT), lyricSnapshotOf(lrc));
                }
                return new DirectoryLyricIndex(directory, Map.copyOf(snapshots), true);
            } catch (IOException | DirectoryIteratorException failure) {
                log.warn("读取目录歌词索引失败，回退逐文件检查：{} - {}", directory, failure.getMessage());
                return new DirectoryLyricIndex(directory, Map.of(), false);
            }
        }
    }

    record LyricSnapshot(boolean present, boolean readable, Long size,
                                 OffsetDateTime mtime, String fileIdentity) {
        private static LyricSnapshot missing() {
            return new LyricSnapshot(false, true, null, null, null);
        }

        private static LyricSnapshot unreadable() {
            return new LyricSnapshot(true, false, null, null, null);
        }
    }

    record SidecarLyricContent(boolean present, boolean readable, String text) {
        private static SidecarLyricContent absent() {
            return new SidecarLyricContent(false, true, null);
        }

        private static SidecarLyricContent unreadable() {
            return new SidecarLyricContent(true, false, null);
        }
    }

    record FastIndexEntry(Path file, String path, String relativePath, long size, OffsetDateTime mtime,
                                  String fileIdentity, ParsedMeta filenameMeta,
                                  OffsetDateTime mediaMtime, String mediaIdentity,
                                  LyricSnapshot lyricSnapshot,
                                  Optional<SongFile> existing, boolean existedBeforeScan,
                                  boolean pendingBeforeScan) {
        LibraryIngestionWriter.Input toIngestionInput() {
            return new LibraryIngestionWriter.Input(file, path, relativePath, size, mtime, fileIdentity,
                    filenameMeta, mediaMtime, mediaIdentity, lyricSnapshot,
                    existing.map(SongFile::getId).orElse(null), existedBeforeScan, pendingBeforeScan);
        }

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

    private record ProbeResult(Path file, FastIndexEntry entry, MediaProbe probe,
                               Throwable failure, boolean attempted) {}

    record IngestState(IngestOutcome outcome, Long songId, Long songFileId) {}

    enum PathAccessDecision {
        ALLOW,
        SAFE_SKIP,
        ACCESS_FAILURE
    }

    record PathAccessResult(PathAccessDecision decision, String failureReason) {
        static PathAccessResult allow() { return new PathAccessResult(PathAccessDecision.ALLOW, null); }
        static PathAccessResult safeSkip() { return new PathAccessResult(PathAccessDecision.SAFE_SKIP, null); }
        static PathAccessResult failure(String reason) { return new PathAccessResult(PathAccessDecision.ACCESS_FAILURE, reason); }
    }

    static final class ScanCounters {
        final Set<Long> initialArtistCreditSongIds = new HashSet<>();
        private int fastIndexed;
        private int probeQueued;
        private int probeCalls;
        private int hashCalls;
        int dbUpdates;
        private int missing;
        private int failedPaths;
    }

    private static final class ScanTotals {
        private int added;
        private int updated;
        private int skipped;
        private int unrecognized;
        private int probeCompleted;
        private OffsetDateTime probeStartedAt;
    }

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

        private PathAccessResult checkVisitedDirectory(Path directory) {
            Path candidate = directory.toAbsolutePath().normalize();
            if (!candidate.startsWith(configuredRoot) || Files.isSymbolicLink(directory)) {
                return PathAccessResult.safeSkip();
            }
            if (!external) return PathAccessResult.allow();
            try {
                if (!directory.toRealPath().startsWith(realRoot)) {
                    return PathAccessResult.safeSkip();
                }
                return PathAccessResult.allow();
            } catch (AccessDeniedException failure) {
                return PathAccessResult.failure("访问权限被拒绝 (Access Denied): " + failure.getMessage());
            } catch (IOException failure) {
                return PathAccessResult.failure("IO异常: " + failure.getMessage());
            }
        }

        /**
         * walkFileTree only supplies descendants of configuredRoot, and every
         * visited directory has already passed the root boundary check. Keep a
         * final link check here to reject a file entry replaced during traversal.
         */
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
        probeExecutor.shutdownNow();
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
        Set<String> snapshot = artistNames.isEmpty() ? Set.of() : Set.copyOf(artistNames);
        // The index is scoped to the current scan/request. Keeping it in this
        // Spring singleton retained every historical artist name after a large
        // scan, even though the next scan builds its own snapshot.
        return FilenameParser.prepareKnownArtists(snapshot);
    }

    static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value.trim();
        }
        return "";
    }

    static String sourceOfArtist(String... values) {
        String[] sources = {"audio_tag", "container_tag", "lrc_tag", "filename"};
        int sourceCount = Math.min(values.length, sources.length);
        for (int i = 0; i < sourceCount; i++) {
            if (values[i] != null && !values[i].isBlank()) return sources[i];
        }
        return "default";
    }
}
