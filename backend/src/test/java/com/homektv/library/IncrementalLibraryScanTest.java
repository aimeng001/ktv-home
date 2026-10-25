package com.homektv.library;

import com.homektv.config.AppProperties;
import com.homektv.domain.AudioLayout;
import com.homektv.domain.AudioLayoutSource;
import com.homektv.domain.Song;
import com.homektv.domain.SongFile;
import com.homektv.media.AudioStreamInfo;
import com.homektv.media.FFprobeService;
import com.homektv.media.MediaProbe;
import com.homektv.media.MediaProbeException;
import com.homektv.musicsource.CoverImageNormalizer;
import com.homektv.repo.SongFileRepository;
import com.homektv.repo.SongFileScanSnapshot;
import com.homektv.repo.SongRepository;
import com.homektv.resource.ResourceBudget;
import com.homektv.web.ApiException;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.eq;

@ExtendWith(MockitoExtension.class)
class IncrementalLibraryScanTest {

    @TempDir
    Path tempDir;

    @Mock
    private FFprobeService ffprobe;
    @Mock
    private TagReader tagReader;
    @Mock
    private SongRepository songRepository;
    @Mock
    private SongFileRepository songFileRepository;
    @Mock
    private ArtistCreditService artistCreditService;
    @Mock
    private CoverImageNormalizer coverImageNormalizer;

    private final Map<String, Song> songsByFingerprint = new ConcurrentHashMap<>();
    private final Map<Long, Song> songsById = new ConcurrentHashMap<>();
    private final Map<String, SongFile> filesByPath = new ConcurrentHashMap<>();
    private final List<String> repositorySaveThreads = Collections.synchronizedList(new ArrayList<>());
    private final AtomicLong ids = new AtomicLong(100);

    private LibraryScanService scanService;
    private InMemoryLibraryScanSeenPathStore seenPathStore;
    private Path sourceDir;
    private AppProperties props;

    @BeforeEach
    void setUp() throws Exception {
        sourceDir = Files.createDirectory(tempDir.resolve("library"));
        props = new AppProperties();
        props.setSourceLibraryPath(sourceDir.toString());
        props.setKtvLibraryPath(tempDir.resolve("managed").toString());
        props.setDataPath(tempDir.resolve("data").toString());
        props.setLibraryMode(LibraryMode.EXTERNAL_READ_ONLY);

        lenient().when(songRepository.findAll()).thenAnswer(invocation -> new ArrayList<>(songsById.values()));
        lenient().when(songRepository.findDistinctArtistByStatus(anyString()))
                .thenAnswer(invocation -> songsById.values().stream()
                        .filter(song -> invocation.getArgument(0).equals(song.getStatus()))
                        .map(Song::getArtist)
                        .filter(java.util.Objects::nonNull)
                        .toList());
        lenient().when(songRepository.findByFingerprint(anyString())).thenAnswer(invocation ->
                Optional.ofNullable(songsByFingerprint.get(invocation.getArgument(0))));
        lenient().when(songRepository.findById(anyLong())).thenAnswer(invocation ->
                Optional.ofNullable(songsById.get(invocation.getArgument(0))));
        lenient().when(songRepository.findAllById(org.mockito.ArgumentMatchers.any(Iterable.class)))
                .thenAnswer(invocation -> {
                    Iterable<Long> requested = invocation.getArgument(0);
                    List<Song> result = new ArrayList<>();
                    requested.forEach(id -> {
                        Song song = songsById.get(id);
                        if (song != null) result.add(song);
                    });
                    return result;
                });
        lenient().when(songRepository.save(any(Song.class))).thenAnswer(invocation -> {
            repositorySaveThreads.add(Thread.currentThread().getName());
            Song song = invocation.getArgument(0);
            if (song.getId() == null) song.setId(ids.getAndIncrement());
            songsByFingerprint.entrySet().removeIf(entry -> entry.getValue() == song);
            songsById.put(song.getId(), song);
            songsByFingerprint.put(song.getFingerprint(), song);
            return song;
        });

        lenient().when(songFileRepository.findByFilePath(anyString())).thenAnswer(invocation ->
                Optional.ofNullable(filesByPath.get(invocation.getArgument(0))));
        lenient().when(songFileRepository.findByFileRoleAndFilePath(anyString(), anyString()))
                .thenAnswer(invocation -> Optional.ofNullable(filesByPath.get(invocation.getArgument(1)))
                        .filter(file -> invocation.getArgument(0).equals(file.getFileRole())));
        lenient().when(songFileRepository.findById(anyLong())).thenAnswer(invocation ->
                filesByPath.values().stream()
                        .filter(file -> invocation.getArgument(0).equals(file.getId()))
                        .findFirst());
        lenient().when(songFileRepository.findMaxIdByFileRole(anyString()))
                .thenAnswer(invocation -> {
                    String role = invocation.getArgument(0);
                    return filesByPath.values().stream()
                            .filter(file -> role.equals(file.getFileRole()) && file.getId() != null)
                            .mapToLong(SongFile::getId)
                            .max()
                            .orElse(0L);
                });
        lenient().when(songFileRepository.findByFileRoleOrderByImportedAtDesc(anyString())).thenAnswer(invocation ->
                filesByPath.values().stream().filter(file -> invocation.getArgument(0).equals(file.getFileRole())).toList());
        lenient().when(songFileRepository.findByFileRoleAndFilePathIn(anyString(), org.mockito.ArgumentMatchers.anyCollection()))
                .thenAnswer(invocation -> {
                    String role = invocation.getArgument(0);
                    Collection<String> paths = invocation.getArgument(1);
                    return filesByPath.values().stream()
                            .filter(file -> role.equals(file.getFileRole()) && paths.contains(file.getFilePath()))
                            .toList();
                });
        lenient().when(songFileRepository.findByFileRoleAndRelativePathIn(anyString(), org.mockito.ArgumentMatchers.anyCollection()))
                .thenAnswer(invocation -> {
                    String role = invocation.getArgument(0);
                    Collection<String> relativePaths = invocation.getArgument(1);
                    return filesByPath.values().stream()
                            .filter(file -> role.equals(file.getFileRole()) && relativePaths.contains(file.getRelativePath()))
                            .toList();
                });
        lenient().when(songFileRepository.findScanSnapshotsByFileRoleAndFilePathGreaterThanOrderByFilePath(
                anyString(), anyString(), org.mockito.ArgumentMatchers.any(Pageable.class)))
                .thenAnswer(invocation -> {
                    String role = invocation.getArgument(0);
                    String after = invocation.getArgument(1);
                    Pageable page = invocation.getArgument(2);
                    List<SongFileScanSnapshot> snapshots = filesByPath.values().stream()
                            .filter(file -> role.equals(file.getFileRole()))
                            .filter(file -> file.getFilePath() != null && file.getFilePath().compareTo(after) > 0)
                            .sorted(java.util.Comparator.comparing(SongFile::getFilePath))
                            .<SongFileScanSnapshot>map(file -> new SongFileScanSnapshot() {
                                public Long getId() { return file.getId(); }
                                public String getFilePath() { return file.getFilePath(); }
                                public Boolean getProbePending() { return file.isProbePending(); }
                            }).toList();
                    int from = Math.min((int) page.getOffset(), snapshots.size());
                    int to = Math.min(from + page.getPageSize(), snapshots.size());
                    return new PageImpl<>(snapshots.subList(from, to), page, snapshots.size());
                });
        lenient().when(songFileRepository.findByFileRoleAndFilePathGreaterThanOrderByFilePath(
                anyString(), anyString(), org.mockito.ArgumentMatchers.any(Pageable.class)))
                .thenAnswer(invocation -> {
                    String role = invocation.getArgument(0);
                    String after = invocation.getArgument(1);
                    Pageable page = invocation.getArgument(2);
                    List<SongFile> rows = filesByPath.values().stream()
                            .filter(file -> role.equals(file.getFileRole()))
                            .filter(file -> file.getFilePath() != null && file.getFilePath().compareTo(after) > 0)
                            .sorted(java.util.Comparator.comparing(SongFile::getFilePath))
                            .toList();
                    int from = Math.min((int) page.getOffset(), rows.size());
                    int to = Math.min(from + page.getPageSize(), rows.size());
                    return new PageImpl<>(rows.subList(from, to), page, rows.size());
                });
        lenient().when(songFileRepository
                .findPendingForScan(anyString(), anyLong(), any(UUID.class), anyString(), any(Pageable.class)))
                .thenAnswer(invocation -> {
                    String role = invocation.getArgument(0);
                    Long maxId = invocation.getArgument(1);
                    UUID scanId = invocation.getArgument(2);
                    String after = invocation.getArgument(3);
                    Pageable page = invocation.getArgument(4);
                    List<SongFile> pending = filesByPath.values().stream()
                            .filter(file -> role.equals(file.getFileRole()))
                            .filter(file -> file.getId() != null && file.getId() <= maxId
                                    || seenPathStore.isSeen(scanId, file.getFilePath()))
                            .filter(SongFile::isProbePending)
                            .filter(file -> file.getFilePath() != null && file.getFilePath().compareTo(after) > 0)
                            .sorted(java.util.Comparator.comparing(SongFile::getFilePath))
                            .toList();
                    int from = Math.min((int) page.getOffset(), pending.size());
                    int to = Math.min(from + page.getPageSize(), pending.size());
                    return new PageImpl<>(pending.subList(from, to), page, pending.size());
                });
        lenient().when(songFileRepository.countByFileRoleAndProbePendingTrue(anyString()))
                .thenAnswer(invocation -> filesByPath.values().stream()
                        .filter(file -> invocation.getArgument(0).equals(file.getFileRole()))
                        .filter(SongFile::isProbePending)
                        .count());
        lenient().when(songFileRepository.countValidByFileRoleAndRoot(
                anyString(), anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    String role = invocation.getArgument(0);
                    String root = invocation.getArgument(1);
                    Path normalizedRoot = Path.of(root).toAbsolutePath().normalize();
                    return filesByPath.values().stream()
                            .filter(file -> role.equals(file.getFileRole()) && file.isValid())
                            .filter(file -> {
                                try {
                                    return Path.of(file.getFilePath()).toAbsolutePath().normalize()
                                            .startsWith(normalizedRoot);
                                } catch (RuntimeException invalidPath) {
                                    return false;
                                }
                            })
                            .count();
                });
        lenient().when(songFileRepository.findBySongIdAndValidTrueOrderByPriorityDesc(anyLong())).thenAnswer(invocation ->
                filesByPath.values().stream()
                        .filter(file -> invocation.getArgument(0).equals(file.getSongId()) && file.isValid())
                        .toList());
        lenient().when(songFileRepository.save(any(SongFile.class))).thenAnswer(invocation -> {
            repositorySaveThreads.add(Thread.currentThread().getName());
            SongFile file = invocation.getArgument(0);
            if (file.getId() == null) file.setId(ids.getAndIncrement());
            filesByPath.entrySet().removeIf(entry -> entry.getValue() == file);
            filesByPath.put(file.getFilePath(), file);
            return file;
        });

        lenient().when(tagReader.read(any())).thenReturn(new TagInfo());
        lenient().when(ffprobe.probe(any(Path.class))).thenReturn(probe());

        seenPathStore = new InMemoryLibraryScanSeenPathStore(songFileRepository, songRepository);
        scanService = new LibraryScanService(props, ffprobe, tagReader, songRepository,
                songFileRepository, new AssetWriter(props),
                seenPathStore);
        scanService.setArtistCreditService(artistCreditService);
    }

    @AfterEach
    void tearDown() {
        scanService.shutdown();
    }

    @Test
    void legacyScanProgressConstructorsPreserveLifecycleMappingAndDefaults() {
        OffsetDateTime startedAt = OffsetDateTime.parse("2026-01-01T00:00:00Z");
        OffsetDateTime finishedAt = startedAt.plusMinutes(1);

        LibraryScanService.ScanProgress running = new LibraryScanService.ScanProgress(
                true, 2, 1, null, 1, 0, 0, 0, startedAt, null,
                "MEDIA_PROBE", 2, 2, 1, 1, 0.0, null);
        assertThat(running.state()).isEqualTo(LibraryScanService.ScanState.RUNNING);
        assertThat(running.failedPaths()).isZero();
        assertThat(running.errorCode()).isNull();
        assertThat(running.errorMessage()).isNull();

        LibraryScanService.ScanProgress failed = new LibraryScanService.ScanProgress(
                false, 0, 0, null, 0, 0, 0, 0, startedAt, finishedAt,
                "SCAN_FAILED", 0, 0, 0, 0, 0.0, null);
        assertThat(failed.state()).isEqualTo(LibraryScanService.ScanState.FAILED);

        LibraryScanService.ScanProgress completedWithNullPhase = new LibraryScanService.ScanProgress(
                false, 1, 1, null, 1, 0, 0, 0, startedAt, finishedAt,
                null, 1, 1, 1, 1, 0.0, null);
        assertThat(completedWithNullPhase.state()).isEqualTo(LibraryScanService.ScanState.COMPLETED);

        LibraryScanService.ScanProgress idle = new LibraryScanService.ScanProgress(
                false, 0, 0, null, 0, 0, 0, 0, startedAt, null,
                "DISCOVERING", 0, 0, 0, 0, 0.0, null);
        assertThat(idle.state()).isEqualTo(LibraryScanService.ScanState.IDLE);

        LibraryScanService.ScanProgress discovering = new LibraryScanService.ScanProgress(
                true, 0, 0, null, 0, 0, 0, 0, startedAt, null);
        assertThat(discovering.phase()).isEqualTo("DISCOVERING");
        assertThat(discovering.state()).isEqualTo(LibraryScanService.ScanState.RUNNING);

        LibraryScanService.ScanProgress probing = new LibraryScanService.ScanProgress(
                true, 1, 0, null, 0, 0, 0, 0, startedAt, null);
        assertThat(probing.phase()).isEqualTo("MEDIA_PROBE");

        LibraryScanService.ScanProgress finished = new LibraryScanService.ScanProgress(
                false, 1, 1, null, 1, 0, 0, 0, startedAt, finishedAt);
        assertThat(finished.phase()).isEqualTo("COMPLETED");
        assertThat(finished.state()).isEqualTo(LibraryScanService.ScanState.COMPLETED);

        LibraryScanService.ScanProgress idlePhase = new LibraryScanService.ScanProgress(
                false, 0, 0, null, 0, 0, 0, 0, null, null);
        assertThat(idlePhase.phase()).isEqualTo("COMPLETED");
        assertThat(idlePhase.state()).isEqualTo(LibraryScanService.ScanState.IDLE);
    }

    @Test
    void slowProbeRateOmitsEtaWithoutWaitingOnWallClock() throws Exception {
        OffsetDateTime startedAt = OffsetDateTime.parse("2026-01-01T00:00:00Z");
        AtomicReference<OffsetDateTime> now = new AtomicReference<>(startedAt);
        CountDownLatch probesStarted = new CountDownLatch(2);
        Semaphore releaseProbes = new Semaphore(0);
        CountDownLatch progressCaptured = new CountDownLatch(1);
        AtomicReference<LibraryScanService.ScanProgress> slowProgress = new AtomicReference<>();
        Files.write(sourceDir.resolve("周杰伦-慢速进度一-国语-流行.mkv"), new byte[]{1});
        Files.write(sourceDir.resolve("周杰伦-慢速进度二-国语-流行.mkv"), new byte[]{2});
        when(ffprobe.probe(any(Path.class))).thenAnswer(invocation -> {
            probesStarted.countDown();
            if (!releaseProbes.tryAcquire(5, TimeUnit.SECONDS)) {
                throw new AssertionError("test did not release synthetic probes");
            }
            return singleTrackProbe();
        });

        Thread progressObserver = new Thread(() -> {
            try {
                if (!probesStarted.await(5, TimeUnit.SECONDS)) return;
                now.set(startedAt.plusSeconds(30));
                // Release one probe, observe its published progress, then release the
                // second. Releasing both together can skip the intermediate state.
                releaseProbes.release();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (System.nanoTime() < deadline) {
                    LibraryScanService.ScanProgress progress = scanService.getScanProgress();
                    if (progress != null && progress.running() && "MEDIA_PROBE".equals(progress.phase())
                            && progress.probeCompleted() > 0
                            && progress.probeCompleted() < progress.probeQueued()) {
                        slowProgress.set(progress);
                        progressCaptured.countDown();
                        releaseProbes.release();
                        return;
                    }
                    Thread.onSpinWait();
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }, "scan-progress-test-observer");

        try (MockedStatic<OffsetDateTime> clock = mockStatic(OffsetDateTime.class, CALLS_REAL_METHODS)) {
            clock.when(OffsetDateTime::now).thenAnswer(invocation -> now.get());
            progressObserver.start();
            LibraryScanService.ScanResult result = scanService.scanAll();

            assertThat(result.scanned()).isEqualTo(2);
            assertThat(result.probeCalls()).isEqualTo(2);
            assertThat(progressCaptured.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(slowProgress.get().probeQueued()).isEqualTo(2);
            assertThat(slowProgress.get().probeCompleted()).isEqualTo(1);
            assertThat(slowProgress.get().probedPerSecond()).isZero();
            assertThat(slowProgress.get().estimatedRemainingSeconds()).isNull();
        } finally {
            releaseProbes.release(2);
            progressObserver.interrupt();
            progressObserver.join(TimeUnit.SECONDS.toMillis(5));
        }
    }

    @Test
    void queuedProbeProgressReportsEtaWhileAnotherProbeIsRunning() throws Exception {
        OffsetDateTime startedAt = OffsetDateTime.parse("2026-01-01T00:00:00Z");
        AtomicReference<OffsetDateTime> now = new AtomicReference<>(startedAt);
        CountDownLatch secondProbeStarted = new CountDownLatch(1);
        CountDownLatch releaseSecondProbe = new CountDownLatch(1);
        CountDownLatch progressCaptured = new CountDownLatch(1);
        AtomicReference<LibraryScanService.ScanProgress> etaProgress = new AtomicReference<>();
        Files.write(sourceDir.resolve("周杰伦-进度队列A-国语-流行.mkv"), new byte[]{1});
        Files.write(sourceDir.resolve("周杰伦-进度队列Z-国语-流行.mkv"), new byte[]{2});
        when(ffprobe.probe(any(Path.class))).thenAnswer(invocation -> {
            Path file = invocation.getArgument(0);
            if (file.getFileName().toString().contains("进度队列Z")) {
                secondProbeStarted.countDown();
                if (!releaseSecondProbe.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("test did not release the second synthetic probe");
                }
            }
            return singleTrackProbe();
        });

        Thread progressObserver = new Thread(() -> {
            try {
                if (!secondProbeStarted.await(5, TimeUnit.SECONDS)) return;
                now.set(startedAt.plusSeconds(1));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (System.nanoTime() < deadline) {
                    LibraryScanService.ScanProgress progress = scanService.getScanProgress();
                    if (progress != null && progress.running() && "MEDIA_PROBE".equals(progress.phase())
                            && progress.probeCompleted() == 1 && progress.probeQueued() == 2) {
                        etaProgress.set(progress);
                        progressCaptured.countDown();
                        releaseSecondProbe.countDown();
                        return;
                    }
                    Thread.onSpinWait();
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                releaseSecondProbe.countDown();
            }
        }, "scan-eta-test-observer");

        try (MockedStatic<OffsetDateTime> clock = mockStatic(OffsetDateTime.class, CALLS_REAL_METHODS)) {
            clock.when(OffsetDateTime::now).thenAnswer(invocation -> now.get());
            progressObserver.start();
            LibraryScanService.ScanResult result = scanService.scanAll();

            assertThat(result.scanned()).isEqualTo(2);
            assertThat(result.probeCalls()).isEqualTo(2);
            assertThat(progressCaptured.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(etaProgress.get().probedPerSecond()).isGreaterThan(0.05);
            assertThat(etaProgress.get().estimatedRemainingSeconds()).isEqualTo(1L);
            assertThat(scanService.getScanProgress().probeCompleted()).isEqualTo(2);
            assertThat(scanService.getScanProgress().running()).isFalse();
        } finally {
            releaseSecondProbe.countDown();
            progressObserver.interrupt();
            progressObserver.join(TimeUnit.SECONDS.toMillis(5));
        }
    }

    @Test
    void scanBumpsCatalogRevisionOnlyWhenItChangesDatabaseRows() throws Exception {
        useTransactionManager();
        CatalogRevisionService catalogRevision = org.mockito.Mockito.mock(CatalogRevisionService.class);
        scanService.setCatalogRevisionService(catalogRevision);
        Files.write(sourceDir.resolve("周杰伦-晴天-国语-流行.mkv"), new byte[]{1, 2, 3});

        LibraryScanService.ScanResult first = scanService.scanAll();

        assertThat(first.dbUpdates()).isGreaterThan(0);
        verify(catalogRevision).bumpIfChanged(true);

        LibraryScanService.ScanResult unchanged = scanService.scanAll();

        assertThat(unchanged.dbUpdates()).isZero();
        verify(catalogRevision, times(1)).bumpIfChanged(true);
    }

    @Test
    void firstScanBuildsFastIndexThenQueuesOneProbeAndDoesNotHash() throws Exception {
        Files.write(sourceDir.resolve("周杰伦-晴天-国语-流行.mkv"), new byte[]{1, 2, 3});

        LibraryScanService.ScanResult result = scanService.scanAll();

        assertThat(result.fastIndexed()).isEqualTo(1);
        assertThat(result.probeQueued()).isEqualTo(1);
        assertThat(result.probeCalls()).isEqualTo(1);
        assertThat(result.hashCalls()).isZero();
        assertThat(result.dbUpdates()).isEqualTo(4);
        verify(ffprobe, times(1)).probe(any(Path.class));
    }

    @Test
    void runningProgressReportsRateAndEtaWhileProbeWorkRemainsQueued() throws Exception {
        Files.write(sourceDir.resolve("周杰伦-进度一-国语-流行.mkv"), new byte[]{1});
        Files.write(sourceDir.resolve("周杰伦-进度二-国语-流行.mkv"), new byte[]{2});
        Path blockedFile = Files.write(sourceDir.resolve("周杰伦-进度三-国语-流行.mkv"), new byte[]{3});
        CountDownLatch blockedProbeStarted = new CountDownLatch(1);
        CountDownLatch releaseBlockedProbe = new CountDownLatch(1);
        when(ffprobe.probe(any(Path.class))).thenAnswer(invocation -> {
            Path file = invocation.getArgument(0);
            if (file.equals(blockedFile)) {
                blockedProbeStarted.countDown();
                if (!releaseBlockedProbe.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test did not release the final synthetic probe");
                }
            }
            return probe();
        });
        ExecutorService scanCaller = Executors.newSingleThreadExecutor();
        Future<LibraryScanService.ScanResult> scan = scanCaller.submit(scanService::scanAll);

        try {
            assertThat(blockedProbeStarted.await(5, TimeUnit.SECONDS)).isTrue();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (scanService.getScanProgress().probeCompleted() == 0 && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }

            LibraryScanService.ScanProgress running = scanService.getScanProgress();
            assertThat(running.running()).isTrue();
            assertThat(running.phase()).isEqualTo("MEDIA_PROBE");
            assertThat(running.probeQueued()).isEqualTo(3);
            assertThat(running.probeCompleted()).isBetween(1, 2);
            assertThat(running.probedPerSecond()).isGreaterThan(0.05);
            assertThat(running.estimatedRemainingSeconds()).isNotNull();

            releaseBlockedProbe.countDown();
            assertThat(scan.get(5, TimeUnit.SECONDS).scanned()).isEqualTo(3);
            LibraryScanService.ScanProgress completed = scanService.getScanProgress();
            assertThat(completed.state()).isEqualTo(LibraryScanService.ScanState.COMPLETED);
            assertThat(completed.probeCompleted()).isEqualTo(3);
            assertThat(completed.probedPerSecond()).isZero();
            assertThat(completed.estimatedRemainingSeconds()).isNull();
        } finally {
            releaseBlockedProbe.countDown();
            scanCaller.shutdownNow();
        }
    }

    @Test
    void pendingProbeCountIsSaturatedAtIntegerMaxInProgress() {
        when(songFileRepository.countByFileRoleAndProbePendingTrue(anyString()))
                .thenReturn((long) Integer.MAX_VALUE + 1L);

        scanService.scanAll();

        assertThat(scanService.getScanProgress().probeQueued()).isEqualTo(Integer.MAX_VALUE);
        assertThat(scanService.getScanProgress().state()).isEqualTo(LibraryScanService.ScanState.COMPLETED);
        verify(ffprobe, never()).probe(any(Path.class));
    }

    @Test
    void inFlightProbeProgressPublishesRateAndEtaWhileAnotherProbeIsPending() throws Exception {
        Files.write(sourceDir.resolve("A-第一首-国语-流行.mkv"), new byte[]{1});
        Path blockedProbe = Files.write(sourceDir.resolve("Z-第二首-国语-流行.mkv"), new byte[]{2});
        CountDownLatch blockedProbeStarted = new CountDownLatch(1);
        CountDownLatch releaseBlockedProbe = new CountDownLatch(1);
        when(ffprobe.probe(any(Path.class))).thenAnswer(invocation -> {
            if (blockedProbe.equals(invocation.getArgument(0))) {
                blockedProbeStarted.countDown();
                if (!releaseBlockedProbe.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("timed out waiting for test to release the second probe");
                }
            }
            return probe();
        });

        ExecutorService scanCaller = Executors.newSingleThreadExecutor();
        Future<LibraryScanService.ScanResult> scan = scanCaller.submit(scanService::scanAll);
        try {
            assertThat(blockedProbeStarted.await(3, TimeUnit.SECONDS)).isTrue();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            LibraryScanService.ScanProgress progress;
            do {
                progress = scanService.getScanProgress();
                if (progress.probeCompleted() > 0) break;
                Thread.sleep(10);
            } while (System.nanoTime() < deadline);

            assertThat(progress.probeCompleted()).isEqualTo(1);
            assertThat(progress.probeQueued()).isEqualTo(2);
            assertThat(progress.probedPerSecond()).isPositive();
            assertThat(progress.estimatedRemainingSeconds()).isPositive();
            releaseBlockedProbe.countDown();
            assertThat(scan.get(5, TimeUnit.SECONDS).probeCalls()).isEqualTo(2);
        } finally {
            releaseBlockedProbe.countDown();
            scanCaller.shutdownNow();
        }
    }

    @Test
    void fastIndexPersistsCatalogNumberAndVocalFormBeforeProbe() throws Exception {
        Path file = Files.write(sourceDir.resolve("12345678-周杰伦-爱上幼儿园-合唱-国语-儿歌.mkv"),
                new byte[]{1, 2, 3});
        when(ffprobe.probe(file)).thenAnswer(invocation -> {
            SongFile indexed = filesByPath.get(file.toString());
            assertThat(indexed).as("Fast Index must persist the file row before FFprobe").isNotNull();
            Song provisional = songsById.get(indexed.getSongId());
            assertThat(provisional).as("Fast Index must persist the provisional song before FFprobe").isNotNull();
            assertThat(provisional.getCatalogNumber()).isEqualTo("12345678");
            assertThat(provisional.getVocalForm()).isEqualTo("合唱");
            assertThat(provisional.getTags()).containsExactly("儿歌");
            return probe();
        });

        LibraryScanService.ScanResult result = scanService.scanAll();

        assertThat(result.probeCalls()).isEqualTo(1);
        verify(ffprobe, times(1)).probe(file);
    }

    @Test
    void successfulProbeReconcilesAStaleCatalogNumberFromTheFilename() throws Exception {
        byte[] sourceBytes = {4, 6, 8, 10};
        Path file = Files.write(sourceDir.resolve("12345678-周杰伦-幼儿园之歌-国语-儿歌.mkv"),
                new byte[]{1, 3, 5});
        Song provisional = new Song();
        provisional.setId(92L);
        provisional.setTitle("幼儿园之歌");
        provisional.setArtist("周杰伦");
        provisional.setMediaType(MediaClassifier.PENDING_PROBE);
        provisional.setDurationMs(0);
        provisional.setFingerprint(MediaClassifier.fastIndexFingerprint(file.toString()));
        provisional.setCatalogNumber("stale-number");
        provisional.setStatus("ok");
        songsById.put(provisional.getId(), provisional);
        songsByFingerprint.put(provisional.getFingerprint(), provisional);
        registerUnchangedFile(file, provisional);
        SongFile pending = filesByPath.get(file.toString());
        pending.setProbePending(true);
        Files.write(file, sourceBytes);
        Files.setLastModifiedTime(file, FileTime.fromMillis(
                Files.getLastModifiedTime(file).toMillis() + 2_000));

        LibraryScanService.ScanResult result = scanService.scanAll();

        assertThat(result.probeCalls()).isEqualTo(1);
        assertThat(result.added()).isEqualTo(1);
        assertThat(result.updated()).isZero();
        assertThat(pending.isProbePending()).isFalse();
        assertThat(pending.getSongId()).isEqualTo(provisional.getId());
        assertThat(provisional.getCatalogNumber()).isEqualTo("12345678");
        assertThat(Files.readAllBytes(file)).containsExactly(sourceBytes);
        verify(ffprobe).probe(file);
    }

    @Test
    void blankFilenameMetadataRemainsReviewableWhenInitialProbeFails() throws Exception {
        byte[] originalBytes = {1, 2, 3};
        Path file = Files.write(sourceDir.resolve(" .mkv"), originalBytes);
        when(ffprobe.probe(file)).thenThrow(new MediaProbeException("synthetic probe failure"));

        LibraryScanService.ScanResult result = scanService.scanAll();

        SongFile indexed = filesByPath.get(file.toString());
        assertThat(result.fastIndexed()).isEqualTo(1);
        assertThat(result.probeCalls()).isEqualTo(1);
        assertThat(indexed).isNotNull();
        assertThat(indexed.isValid()).isTrue();
        assertThat(indexed.isProbePending()).isTrue();
        assertThat(indexed.getMediaType()).isEqualTo(MediaClassifier.PENDING_PROBE);

        Song provisional = songsById.get(indexed.getSongId());
        assertThat(provisional.getTitle()).isEqualTo(" .mkv");
        assertThat(provisional.getArtist()).isEqualTo("未知歌手");
        assertThat(provisional.getLanguage()).isEqualTo("未知");
        assertThat(provisional.getTags()).isEmpty();
        assertThat(provisional.getStatus()).isEqualTo("unrecognized");
        assertThat(provisional.isNeedsAiOptimization()).isTrue();
        assertThat(provisional.getMetadataProvenance()).contains("filename_fast_index");
        assertThat(Files.readAllBytes(file)).containsExactly(originalBytes);
        verify(tagReader, never()).read(any());
    }

    @Test
    void missingLibraryRootPublishesFailedProgressWithoutTouchingRepositories() {
        props.setSourceLibraryPath(tempDir.resolve("missing-library").toString());

        LibraryScanService.ScanResult result = scanService.scanAll();

        assertThat(result.scanned()).isZero();
        assertThat(result.fastIndexed()).isZero();
        assertThat(result.probeCalls()).isZero();
        LibraryScanService.ScanProgress progress = scanService.getScanProgress();
        assertThat(progress.running()).isFalse();
        assertThat(progress.state()).isEqualTo(LibraryScanService.ScanState.FAILED);
        assertThat(progress.failedPaths()).isEqualTo(1);
        assertThat(progress.errorCode()).isEqualTo("ROOT_DIRECTORY_NOT_FOUND");
        verifyNoInteractions(ffprobe, tagReader, songRepository, songFileRepository, artistCreditService);
    }

    @Test
    void unreadableLibraryRootPublishesSpecificFailureWithoutTouchingRepositories() {
        Path normalizedRoot = sourceDir.toAbsolutePath().normalize();
        try (MockedStatic<Files> fileSystem = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            fileSystem.when(() -> Files.isReadable(normalizedRoot)).thenReturn(false);

            LibraryScanService.ScanResult result = scanService.scanAll();

            assertThat(result.scanned()).isZero();
            assertThat(result.fastIndexed()).isZero();
            assertThat(result.probeCalls()).isZero();
            LibraryScanService.ScanProgress progress = scanService.getScanProgress();
            assertThat(progress.running()).isFalse();
            assertThat(progress.state()).isEqualTo(LibraryScanService.ScanState.FAILED);
            assertThat(progress.errorCode()).isEqualTo("ROOT_NOT_READABLE");
            assertThat(progress.failedPaths()).isEqualTo(1);
            verifyNoInteractions(ffprobe, tagReader, songRepository, songFileRepository, artistCreditService);
        }
    }

    @Test
    void traversalFailurePublishesPartialProgressAndKeepsDiscoveredMedia() throws Exception {
        Path mediaFile = Files.write(sourceDir.resolve("周杰伦-晴天-国语-流行.mkv"), new byte[]{1, 2, 3});
        Path unreadableFile = sourceDir.resolve("temporarily-unreadable.mkv");
        BasicFileAttributes rootAttributes = Files.readAttributes(sourceDir, BasicFileAttributes.class);
        BasicFileAttributes mediaAttributes = Files.readAttributes(mediaFile, BasicFileAttributes.class);
        Path normalizedRoot = sourceDir.toAbsolutePath().normalize();

        try (MockedStatic<Files> fileSystem = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            fileSystem.when(() -> Files.walkFileTree(eq(normalizedRoot), any(FileVisitor.class)))
                    .thenAnswer(invocation -> {
                        @SuppressWarnings("unchecked")
                        FileVisitor<Path> visitor = invocation.getArgument(1);
                        visitor.preVisitDirectory(normalizedRoot, rootAttributes);
                        visitor.visitFile(mediaFile, mediaAttributes);
                        visitor.visitFileFailed(unreadableFile, new IOException("synthetic access failure"));
                        visitor.postVisitDirectory(normalizedRoot, null);
                        return normalizedRoot;
                    });

            LibraryScanService.ScanResult result = scanService.scanAll();

            assertThat(result.scanned()).isEqualTo(1);
            assertThat(result.probeCalls()).isEqualTo(1);
            assertThat(filesByPath).containsKey(mediaFile.toString());
            LibraryScanService.ScanProgress progress = scanService.getScanProgress();
            assertThat(progress.running()).isFalse();
            assertThat(progress.state()).isEqualTo(LibraryScanService.ScanState.PARTIAL);
            assertThat(progress.failedPaths()).isEqualTo(1);
            assertThat(progress.errorCode()).isEqualTo("SCAN_PARTIAL_OR_FAILED");
            assertThat(progress.errorMessage()).isEqualTo("synthetic access failure");
        }
    }

    @Test
    void playingBudgetLimitsConcurrentFfprobeCallsInTheRealScanWorkers() throws Exception {
        Files.write(sourceDir.resolve("周杰伦-晴天-国语-流行-1.mkv"), new byte[]{1, 2, 3});
        Files.write(sourceDir.resolve("周杰伦-晴天-国语-流行-2.mkv"), new byte[]{4, 5, 6});
        scanService.shutdown();
        ResourceBudget busyBudget = new ResourceBudget(2, 1, 4, () -> true);
        scanService = new LibraryScanService(props, ffprobe, tagReader, songRepository,
                songFileRepository, new AssetWriter(props), seenPathStore, busyBudget);

        AtomicInteger activeProbes = new AtomicInteger();
        AtomicInteger maximumConcurrentProbes = new AtomicInteger();
        AtomicInteger probeCalls = new AtomicInteger();
        CountDownLatch firstProbeEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstProbe = new CountDownLatch(1);
        when(ffprobe.probe(any(Path.class))).thenAnswer(invocation -> {
            int active = activeProbes.incrementAndGet();
            maximumConcurrentProbes.accumulateAndGet(active, Math::max);
            int call = probeCalls.incrementAndGet();
            try {
                if (call == 1) {
                    firstProbeEntered.countDown();
                    if (!releaseFirstProbe.await(3, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("test did not release the first FFprobe call");
                    }
                }
                return probe();
            } finally {
                activeProbes.decrementAndGet();
            }
        });
        ExecutorService runner = Executors.newSingleThreadExecutor();
        try {
            Future<LibraryScanService.ScanResult> scan = runner.submit(scanService::scanAll);
            assertThat(firstProbeEntered.await(2, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(150);
            assertThat(probeCalls.get()).isEqualTo(1);
            assertThat(busyBudget.activeProbeCount()).isEqualTo(1);

            releaseFirstProbe.countDown();
            scan.get(5, TimeUnit.SECONDS);
            assertThat(probeCalls.get()).isEqualTo(2);
            assertThat(maximumConcurrentProbes.get()).isEqualTo(1);
        } finally {
            releaseFirstProbe.countDown();
            runner.shutdownNow();
        }
    }

    @Test
    void scanUsesBoundedArtistQueryInsteadOfLoadingTheWholeSongEntityTable() throws Exception {
        Files.write(sourceDir.resolve("周杰伦-晴天-国语-流行.mkv"), new byte[]{1, 2, 3});

        scanService.scanAll();

        verify(songRepository).findDistinctArtistByStatus("ok");
        verify(songRepository, org.mockito.Mockito.never()).findAll();
        verify(songFileRepository, org.mockito.Mockito.never())
                .findByFileRoleOrderByImportedAtDesc(anyString());
    }

    @Test
    void firstFastIndexBatchIsVisibleBeforeTheFirstMediaProbe() throws Exception {
        for (int index = 0; index < 500; index++) {
            Files.write(sourceDir.resolve("歌手-歌曲" + index + "-国语-流行.mkv"), new byte[]{1});
        }
        AtomicBoolean firstProbe = new AtomicBoolean(true);
        when(ffprobe.probe(any(Path.class))).thenAnswer(invocation -> {
            if (firstProbe.getAndSet(false)) {
                assertThat(scanService.getScanProgress().completed())
                        .as("first Fast Index batch must be visible before FFprobe")
                        .isGreaterThan(0);
            }
            return probe();
        });

        LibraryScanService.ScanResult result = scanService.scanAll();

        assertThat(result.scanned()).isEqualTo(500);
        assertThat(result.added()).isEqualTo(500);
        assertThat(result.skipped()).isZero();
        assertThat(result.probeCalls()).isEqualTo(500);
        assertThat(result.probeQueued()).isEqualTo(500);
        assertThat(filesByPath).hasSize(500);
        assertThat(filesByPath.values()).allMatch(file -> !file.isProbePending());
    }

    @Test
    void probeWorkersOnlyProbeAndDatabaseMergeRunsOnScanThread() throws Exception {
        for (int index = 0; index < 4; index++) {
            Files.write(sourceDir.resolve("歌手-歌曲" + index + "-国语-流行.mkv"), new byte[]{1});
        }
        String scanThread = Thread.currentThread().getName();

        LibraryScanService.ScanResult result = scanService.scanAll();

        assertThat(result.probeCalls()).isEqualTo(4);
        assertThat(repositorySaveThreads).isNotEmpty()
                .allMatch(threadName -> threadName.equals(scanThread));
    }

    @Test
    void probeConcurrencyIsBoundedToTwoWorkers() throws Exception {
        for (int index = 0; index < 5; index++) {
            Files.write(sourceDir.resolve("歌手-歌曲" + index + "-国语-流行.mkv"), new byte[]{1});
        }
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        CountDownLatch firstTwoStarted = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        when(ffprobe.probe(any(Path.class))).thenAnswer(invocation -> {
            int current = active.incrementAndGet();
            maxActive.accumulateAndGet(current, Math::max);
            firstTwoStarted.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("probe workers were not released");
                }
                return probe();
            } finally {
                active.decrementAndGet();
            }
        });

        ExecutorService runner = Executors.newSingleThreadExecutor();
        try {
            Future<LibraryScanService.ScanResult> result = runner.submit(scanService::scanAll);
            assertThat(firstTwoStarted.await(5, TimeUnit.SECONDS)).isTrue();
            release.countDown();
            assertThat(result.get(20, TimeUnit.SECONDS).probeCalls()).isEqualTo(5);
        } finally {
            release.countDown();
            runner.shutdownNow();
        }

        assertThat(maxActive).hasValue(2);
    }

    @Test
    void scanDrainsAFullPendingProbePageBeforeCompleting() throws Exception {
        List<Path> sourceFiles = new ArrayList<>();
        for (int index = 0; index < LibraryScanService.PROBE_PAGE_SIZE; index++) {
            String filename = "合成曲目-%03d.mkv".formatted(index);
            sourceFiles.add(Files.write(sourceDir.resolve(filename), new byte[]{(byte) index, 2, 3}));
        }

        LibraryScanService.ScanResult result = scanService.scanAll();

        assertThat(result.scanned()).isEqualTo(LibraryScanService.PROBE_PAGE_SIZE);
        assertThat(result.added()).isEqualTo(LibraryScanService.PROBE_PAGE_SIZE);
        assertThat(result.probeCalls()).isEqualTo(LibraryScanService.PROBE_PAGE_SIZE);
        assertThat(filesByPath).hasSize(LibraryScanService.PROBE_PAGE_SIZE);
        assertThat(filesByPath.values()).allSatisfy(file -> {
            assertThat(file.isValid()).isTrue();
            assertThat(file.isProbePending()).isFalse();
        });
        for (int index = 0; index < sourceFiles.size(); index++) {
            assertThat(Files.readAllBytes(sourceFiles.get(index)))
                    .containsExactly((byte) index, (byte) 2, (byte) 3);
        }
        verify(ffprobe, times(LibraryScanService.PROBE_PAGE_SIZE)).probe(any(Path.class));
    }

    @Test
    void startScanReturnsBusyProgressWithoutWaitingForAnInFlightScan() throws Exception {
        Files.write(sourceDir.resolve("周杰伦-晴天-国语-流行.mkv"), new byte[]{1, 2, 3});
        CountDownLatch probeStarted = new CountDownLatch(1);
        CountDownLatch releaseProbe = new CountDownLatch(1);
        when(ffprobe.probe(any(Path.class))).thenAnswer(invocation -> {
            probeStarted.countDown();
            assertThat(releaseProbe.await(5, TimeUnit.SECONDS)).isTrue();
            return probe();
        });

        ExecutorService runner = Executors.newFixedThreadPool(2);
        Future<LibraryScanService.ScanResult> running = runner.submit(scanService::scanAll);
        Future<LibraryScanService.ScanProgress> started = null;
        try {
            assertThat(probeStarted.await(5, TimeUnit.SECONDS)).isTrue();
            started = runner.submit(scanService::startScan);
            assertThat(started.get(500, TimeUnit.MILLISECONDS).running()).isTrue();
        } finally {
            releaseProbe.countDown();
            assertThat(running.get(20, TimeUnit.SECONDS).scanned()).isEqualTo(1);
            if (started != null && !started.isDone()) {
                started.get(5, TimeUnit.SECONDS);
            }
            runner.shutdownNow();
        }
    }

    @Test
    void startScanSubmitsAndPublishesCompletionWithoutBlockingTheCaller() throws Exception {
        Path file = Files.write(sourceDir.resolve("周杰伦-异步扫描-国语-流行.mkv"), new byte[]{1, 2, 3});
        CountDownLatch probeStarted = new CountDownLatch(1);
        CountDownLatch releaseProbe = new CountDownLatch(1);
        when(ffprobe.probe(file)).thenAnswer(invocation -> {
            probeStarted.countDown();
            if (!releaseProbe.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test did not release the asynchronous probe");
            }
            return probe();
        });

        LibraryScanService.ScanProgress accepted = scanService.startScan();
        assertThat(accepted.running()).isTrue();
        assertThat(accepted.state()).isEqualTo(LibraryScanService.ScanState.RUNNING);
        try {
            assertThat(probeStarted.await(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            releaseProbe.countDown();
        }

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (scanService.getScanProgress().running() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        LibraryScanService.ScanProgress completed = scanService.getScanProgress();

        assertThat(completed.running()).isFalse();
        assertThat(completed.state()).isEqualTo(LibraryScanService.ScanState.COMPLETED);
        assertThat(completed.total()).isEqualTo(1);
        assertThat(completed.completed()).isEqualTo(1);
        verify(ffprobe, times(1)).probe(file);
    }

    @Test
    void startScanClearsAdmissionGuardWhenExecutorRejectsSubmission() {
        scanService.shutdown();

        assertThatThrownBy(scanService::startScan)
                .isInstanceOf(RejectedExecutionException.class);
        assertThatThrownBy(scanService::startScan)
                .isInstanceOf(RejectedExecutionException.class);
    }

    @Test
    void scanAllWithLeaseRejectsConcurrentScanAndReleasesItsGuard() throws Exception {
        Path file = Files.write(sourceDir.resolve("周杰伦-并发扫描-国语-流行.mkv"), new byte[]{1, 2, 3});
        CountDownLatch probeStarted = new CountDownLatch(1);
        CountDownLatch releaseProbe = new CountDownLatch(1);
        when(ffprobe.probe(file)).thenAnswer(invocation -> {
            probeStarted.countDown();
            if (!releaseProbe.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("test did not release the active FFprobe call");
            }
            return probe();
        });

        ExecutorService runner = Executors.newSingleThreadExecutor();
        try {
            Future<LibraryScanService.ScanResult> activeScan = runner.submit(scanService::scanAll);
            assertThat(probeStarted.await(5, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> scanService.scanAllWithLease(null))
                    .isInstanceOfSatisfying(ApiException.class,
                            error -> assertThat(error.getCode()).isEqualTo("SCAN_ALREADY_RUNNING"));

            releaseProbe.countDown();
            assertThat(activeScan.get(20, TimeUnit.SECONDS).scanned()).isEqualTo(1);
            assertThat(scanService.scanAllWithLease(null)).isNotNull();
        } finally {
            releaseProbe.countDown();
            runner.shutdownNow();
        }
    }

    @Test
    void directIngestWithoutAnExistingSongRemainsSupported() throws Exception {
        Path file = Files.write(sourceDir.resolve("12345678-周杰伦-晴天-合唱-国语-流行.mkv"), new byte[]{1, 2, 3});

        assertThat(scanService.ingest(file)).isEqualTo(LibraryScanService.IngestOutcome.ADDED);

        SongFile indexed = filesByPath.get(file.toString());
        assertThat(indexed).isNotNull();
        Song song = songsById.get(indexed.getSongId());
        assertThat(song.getTitle()).isEqualTo("晴天");
        assertThat(song.getArtist()).isEqualTo("周杰伦");
        assertThat(song.getLanguage()).isEqualTo("国语");
        assertThat(song.getTags()).containsExactly("流行");
        assertThat(song.getVocalForm()).isEqualTo("合唱");
        assertThat(song.getCatalogNumber()).isEqualTo("12345678");
        assertThat(song.isNeedsAiOptimization()).isFalse();
        assertThat(Files.readAllBytes(file)).containsExactly((byte) 1, (byte) 2, (byte) 3);
    }

    @Test
    void directIngestKeepsAnUnrecognizedFilePendingForMetadataReview() throws Exception {
        Path file = Files.write(sourceDir.resolve("名称不规范.mkv"), new byte[]{1, 2, 3});

        assertThat(scanService.ingest(file)).isEqualTo(LibraryScanService.IngestOutcome.UNRECOGNIZED);

        SongFile indexed = filesByPath.get(file.toString());
        assertThat(indexed).isNotNull();
        Song song = songsById.get(indexed.getSongId());
        assertThat(song.getStatus()).isEqualTo("unrecognized");
        assertThat(song.getArtist()).isEqualTo("未知歌手");
        assertThat(song.isNeedsAiOptimization()).isTrue();
        assertThat(indexed.isProbePending()).isFalse();
    }

    @Test
    void directIngestPersistsMediaWhenArtistCreditServiceIsNotConfigured() throws Exception {
        scanService.setArtistCreditService(null);
        Path file = Files.write(sourceDir.resolve("周杰伦-晴天-国语-流行.mkv"), new byte[]{1, 2, 3});

        assertThat(scanService.ingest(file)).isEqualTo(LibraryScanService.IngestOutcome.ADDED);

        SongFile indexed = filesByPath.get(file.toString());
        assertThat(indexed).isNotNull();
        assertThat(songsById.get(indexed.getSongId()).getTitle()).isEqualTo("晴天");
        verifyNoInteractions(artistCreditService);
    }

    @Test
    void directIngestInManagedModeUsesTheProbeAudioLayout() throws Exception {
        props.setLibraryMode(LibraryMode.MANAGED);
        Path managedRoot = Files.createDirectories(tempDir.resolve("managed"));
        Path file = Files.write(managedRoot
                .resolve("周杰伦-晴天-国语-流行.mkv"), new byte[]{1, 2, 3});

        assertThat(scanService.ingest(file)).isEqualTo(LibraryScanService.IngestOutcome.ADDED);

        SongFile indexed = filesByPath.get(file.toString());
        assertThat(indexed).isNotNull();
        assertThat(indexed.getAudioLayout()).isEqualTo(AudioLayout.DUAL_TRACK);
        verify(ffprobe).probe(file);
    }

    @Test
    void directIngestSkipsUnchangedMediaAndRefreshesChangedLyricsWithoutReprobing() throws Exception {
        useTransactionManager();
        Path file = Files.write(sourceDir.resolve("周杰伦-晴天-国语-流行.mkv"), new byte[]{1, 2, 3});
        Path lrc = sourceDir.resolve("周杰伦-晴天-国语-流行.lrc");
        Files.writeString(lrc, "[00:01.00]旧歌词\n");

        assertThat(scanService.ingest(file)).isEqualTo(LibraryScanService.IngestOutcome.ADDED);
        assertThat(scanService.ingest(file)).isEqualTo(LibraryScanService.IngestOutcome.SKIPPED);

        Files.writeString(lrc, "[00:01.00]新歌词\n");
        Files.setLastModifiedTime(lrc, FileTime.fromMillis(
                Files.getLastModifiedTime(lrc).toMillis() + 2_000));

        assertThat(scanService.ingest(file)).isEqualTo(LibraryScanService.IngestOutcome.UPDATED);

        Song song = songsById.values().stream()
                .filter(candidate -> "晴天".equals(candidate.getTitle()))
                .findFirst()
                .orElseThrow();
        assertThat(Files.readString(tempDir.resolve("data").resolve(song.getLyricPath())))
                .contains("新歌词");
        assertThat(Files.readAllBytes(file)).containsExactly((byte) 1, (byte) 2, (byte) 3);
        verify(ffprobe, times(1)).probe(file);
    }

    @Test
    void directIngestSkipsChangedSidecarWhenItsSongBindingIsUnavailable() throws Exception {
        Path file = Files.write(sourceDir.resolve("周杰伦-晴天-国语-流行.mkv"), new byte[]{1, 2, 3});
        Path lrc = sourceDir.resolve("周杰伦-晴天-国语-流行.lrc");
        Files.writeString(lrc, "[00:01.00]旧歌词\n");
        assertThat(scanService.ingest(file)).isEqualTo(LibraryScanService.IngestOutcome.ADDED);
        filesByPath.get(file.toString()).setSongId(null);

        Files.writeString(lrc, "[00:02.00]新歌词\n");
        Files.setLastModifiedTime(lrc, FileTime.fromMillis(
                Files.getLastModifiedTime(lrc).toMillis() + 2_000));

        assertThat(scanService.ingest(file)).isEqualTo(LibraryScanService.IngestOutcome.SKIPPED);

        assertThat(Files.readAllBytes(file)).containsExactly((byte) 1, (byte) 2, (byte) 3);
        assertThat(filesByPath.get(file.toString()).getSongId()).isNull();
        verify(ffprobe, times(1)).probe(file);
    }

    @Test
    void directIngestReprobesMediaWhenChangedSidecarContainsIdentityTags() throws Exception {
        Path file = Files.write(sourceDir.resolve("周杰伦-晴天-国语-流行.mkv"), new byte[]{1, 2, 3});
        Path lrc = sourceDir.resolve("周杰伦-晴天-国语-流行.lrc");
        Files.writeString(lrc, "[ti:旧标题]\n[ar:旧歌手]\n[00:01.00]旧歌词\n");
        assertThat(scanService.ingest(file)).isEqualTo(LibraryScanService.IngestOutcome.ADDED);

        Files.writeString(lrc, "[ti:新标题]\n[ar:新歌手]\n[00:02.00]新歌词\n");
        Files.setLastModifiedTime(lrc, FileTime.fromMillis(
                Files.getLastModifiedTime(lrc).toMillis() + 2_000));

        assertThat(scanService.ingest(file)).isEqualTo(LibraryScanService.IngestOutcome.UPDATED);

        assertThat(filesByPath.get(file.toString()).isValid()).isTrue();
        verify(ffprobe, times(2)).probe(file);
    }

    @Test
    void publicScanReprobesWhenChangedSidecarContainsOnlyArtistIdentityTag() throws Exception {
        byte[] sourceBytes = {2, 4, 6};
        Path file = Files.write(sourceDir.resolve("周杰伦-歌词歌手更新-国语-流行.mkv"), sourceBytes);
        Path lrc = sourceDir.resolve("周杰伦-歌词歌手更新-国语-流行.lrc");
        props.setStructuredFilenameMetadataPreferred(false);
        when(ffprobe.probe(file)).thenReturn(new MediaProbe(180_000, 2, 0, true,
                "1920x1080", List.of(), "h264", "aac", "容器标题", null, null));
        byte[] initialLyrics = "[00:01.00]旧歌词\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(lrc, initialLyrics);

        LibraryScanService.ScanResult first = scanService.scanAll();
        SongFile indexed = filesByPath.get(file.toString());
        Song song = songsById.get(indexed.getSongId());
        assertThat(first.added()).isEqualTo(1);
        assertThat(first.probeCalls()).isEqualTo(1);
        assertThat(song.getArtist()).isEqualTo("周杰伦");
        assertThat(song.getTitle()).isEqualTo("容器标题");

        byte[] changedLyrics = "[ar:林忆莲]\n[00:02.00]新歌词\n"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(lrc, changedLyrics);
        Files.setLastModifiedTime(lrc, FileTime.fromMillis(
                Files.getLastModifiedTime(lrc).toMillis() + 2_000));

        LibraryScanService.ScanResult second = scanService.scanAll();

        assertThat(second.probeCalls()).isEqualTo(1);
        assertThat(second.updated()).isEqualTo(1);
        assertThat(indexed.isValid()).isTrue();
        assertThat(indexed.isProbePending()).isFalse();
        Song updatedSong = songsById.get(indexed.getSongId());
        assertThat(updatedSong).isNotNull();
        assertThat(updatedSong.getTitle()).isEqualTo("容器标题");
        assertThat(updatedSong.getArtist()).isEqualTo("林忆莲");
        assertThat(updatedSong.getLyricSource()).isEqualTo(Song.LYRIC_SOURCE_SIDECAR);
        assertThat(Files.readString(tempDir.resolve("data").resolve(updatedSong.getLyricPath())))
                .contains("新歌词");
        assertThat(Files.readAllBytes(file)).containsExactly(sourceBytes);
        assertThat(Files.readAllBytes(lrc)).containsExactly(changedLyrics);
        verify(ffprobe, times(2)).probe(file);
    }

    @ParameterizedTest
    @MethodSource("sidecarRefreshFieldStates")
    void publicScanRefreshesSidecarCacheAcrossStaleAndCurrentFields(String staleField) throws Exception {
        byte[] mediaBytes = {3, 1, 4};
        Path file = Files.write(sourceDir.resolve("周杰伦-歌词缓存刷新-国语-流行.mkv"), mediaBytes);
        Path sidecar = sourceDir.resolve("周杰伦-歌词缓存刷新-国语-流行.lrc");
        Files.writeString(sidecar, "[00:01.00]旧歌词\n");

        LibraryScanService.ScanResult first = scanService.scanAll();
        SongFile indexed = filesByPath.get(file.toString());
        Song song = songsById.get(indexed.getSongId());
        assertThat(first.added()).isEqualTo(1);
        assertThat(song.getLyricSource()).isEqualTo(Song.LYRIC_SOURCE_SIDECAR);
        switch (staleField) {
            case "fingerprint" -> song.setFingerprint("");
            case "path" -> song.setLyricPath("lyrics/stale-cache.lrc");
            case "type" -> song.setLyricType(LyricType.NONE);
            case "source" -> song.setLyricSource(Song.LYRIC_SOURCE_MANUAL);
            case "unchanged" -> { }
            default -> throw new IllegalArgumentException("Unexpected stale lyric field: " + staleField);
        }

        byte[] changedLyrics = "[00:02.00]新歌词\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(sidecar, changedLyrics);
        Files.setLastModifiedTime(sidecar, FileTime.fromMillis(
                Files.getLastModifiedTime(sidecar).toMillis() + 2_000));

        LibraryScanService.ScanResult refreshed = scanService.scanAll();

        assertThat(refreshed.updated()).isEqualTo(1);
        assertThat(refreshed.probeCalls()).isZero();
        assertThat(song.getLyricPath()).isNotBlank().doesNotContain("stale-cache");
        assertThat(song.getLyricType()).isEqualTo(LyricType.LINE);
        assertThat(song.getLyricSource()).isEqualTo(Song.LYRIC_SOURCE_SIDECAR);
        assertThat(Files.readString(tempDir.resolve("data").resolve(song.getLyricPath())))
                .contains("新歌词");
        assertThat(indexed.getLyricSize()).isEqualTo((long) changedLyrics.length);
        assertThat(indexed.getLyricSnapshotVersion()).isEqualTo(1);
        assertThat(Files.readAllBytes(file)).containsExactly(mediaBytes);
        assertThat(Files.readAllBytes(sidecar)).containsExactly(changedLyrics);
        verify(ffprobe, times(1)).probe(file);
    }

    private static Stream<Arguments> sidecarRefreshFieldStates() {
        return Stream.of("fingerprint", "path", "type", "source", "unchanged").map(Arguments::of);
    }

    @Test
    void publicScanRetainsCachedLyricsAndSnapshotWhenSidecarMetadataBecomesUnreadable() throws Exception {
        byte[] mediaBytes = {4, 2, 4};
        Path file = Files.write(sourceDir.resolve("周杰伦-歌词权限重试-国语-流行.mkv"), mediaBytes);
        Path sidecar = sourceDir.resolve("周杰伦-歌词权限重试-国语-流行.lrc");
        byte[] originalLyrics = "[00:01.00]已缓存歌词\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(sidecar, originalLyrics);

        LibraryScanService.ScanResult first = scanService.scanAll();
        SongFile indexed = filesByPath.get(file.toString());
        Song song = songsById.get(indexed.getSongId());
        String originalLyricPath = song.getLyricPath();
        byte[] cachedLyrics = Files.readAllBytes(tempDir.resolve("data").resolve(originalLyricPath));
        Long originalLyricSize = indexed.getLyricSize();
        OffsetDateTime originalLyricMtime = indexed.getLyricMtime();
        assertThat(first.probeCalls()).isEqualTo(1);

        byte[] changedLyrics = "[00:02.00]新歌词等待权限恢复\n"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(sidecar, changedLyrics);
        Files.setLastModifiedTime(sidecar, FileTime.fromMillis(
                Files.getLastModifiedTime(sidecar).toMillis() + 2_000));
        LibraryScanService.ScanResult second;
        try (MockedStatic<Files> fileSystem = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            fileSystem.when(() -> Files.readAttributes(sidecar, BasicFileAttributes.class,
                    java.nio.file.LinkOption.NOFOLLOW_LINKS))
                    .thenThrow(new java.nio.file.AccessDeniedException(sidecar.toString()));
            second = scanService.scanAll();
        }

        assertThat(second.probeCalls()).isZero();
        assertThat(second.updated()).isZero();
        assertThat(song.getLyricPath()).isEqualTo(originalLyricPath);
        assertThat(song.getLyricSource()).isEqualTo(Song.LYRIC_SOURCE_SIDECAR);
        assertThat(indexed.getLyricSize()).isEqualTo(originalLyricSize);
        assertThat(indexed.getLyricMtime()).isEqualTo(originalLyricMtime);
        assertThat(Files.readAllBytes(tempDir.resolve("data").resolve(originalLyricPath)))
                .containsExactly(cachedLyrics);
        assertThat(Files.readAllBytes(file)).containsExactly(mediaBytes);
        assertThat(Files.readAllBytes(sidecar)).containsExactly(changedLyrics);
        verify(ffprobe, times(1)).probe(file);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("directIngestProbeFailureCases")
    void directIngestSkipsWhenProbeDoesNotProduceMediaWithoutCreatingIndexRows(
            String scenario, boolean interruptAdmission, boolean returnNullProbe) throws Exception {
        Path file = Files.write(sourceDir.resolve("周杰伦-晴天-国语-流行.mkv"), new byte[]{1, 2, 3});
        if (interruptAdmission) {
            Thread.currentThread().interrupt();
        } else if (returnNullProbe) {
            when(ffprobe.probe(file)).thenReturn(null);
        } else {
            when(ffprobe.probe(file)).thenThrow(new MediaProbeException("synthetic probe failure"));
        }

        LibraryScanService.IngestOutcome outcome;
        try {
            outcome = scanService.ingest(file);
        } finally {
            assertThat(Thread.interrupted()).isEqualTo(interruptAdmission);
        }

        assertThat(outcome).isEqualTo(LibraryScanService.IngestOutcome.SKIPPED);
        assertThat(filesByPath).doesNotContainKey(file.toString());
        assertThat(songsByFingerprint).isEmpty();
        assertThat(Files.readAllBytes(file)).containsExactly((byte) 1, (byte) 2, (byte) 3);
        if (interruptAdmission) {
            verify(ffprobe, never()).probe(file);
        } else {
            verify(ffprobe).probe(file);
        }
    }

    private static Stream<Arguments> directIngestProbeFailureCases() {
        return Stream.of(
                Arguments.of("ffprobe failure", false, false),
                Arguments.of("null probe result", false, true),
                Arguments.of("probe admission interrupted", true, false));
    }

    @Test
    void existingArtistLookupFailureDoesNotBlockSingleFileIngest() throws Exception {
        Path file = Files.write(sourceDir.resolve("周杰伦-晴天-国语-流行.mkv"), new byte[]{1, 2, 3});
        when(songRepository.findDistinctArtistByStatus("ok"))
                .thenThrow(new IllegalStateException("synthetic artist repository outage"));

        assertThat(scanService.ingest(file)).isEqualTo(LibraryScanService.IngestOutcome.ADDED);

        assertThat(filesByPath.get(file.toString())).isNotNull();
        verify(ffprobe).probe(file);
    }

    @Test
    void nullExistingArtistLookupResultFallsBackToAnEmptyArtistIndex() throws Exception {
        Path file = Files.write(sourceDir.resolve("周杰伦-晴天-国语-流行.mkv"), new byte[]{1, 2, 3});
        when(songRepository.findDistinctArtistByStatus("ok")).thenReturn(null);

        assertThat(scanService.ingest(file)).isEqualTo(LibraryScanService.IngestOutcome.ADDED);

        assertThat(filesByPath.get(file.toString())).isNotNull();
        verify(ffprobe).probe(file);
    }

    @Test
    void existingArtistLookupFiltersInvalidRowsAndTrimsDisplayNames() {
        when(songRepository.findDistinctArtistByStatus("ok")).thenReturn(
                java.util.Arrays.asList(null, "", "   ", "未知歌手", " A-Lin "));

        ParsedMeta parsed = scanService.parseFilename("A-Lin-给我一个理由忘记-国语-流行.mkv");

        assertThat(parsed.recognized()).isTrue();
        assertThat(parsed.artist()).isEqualTo("A-Lin");
        assertThat(parsed.title()).isEqualTo("给我一个理由忘记");
    }

    @Test
    void externalReadOnlyIngestRejectsPathsOutsideTheSourceBeforeReadingThem() {
        Path outside = tempDir.resolve("outside-library.mkv");

        assertThatThrownBy(() -> scanService.ingest(outside))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.getCode()).isEqualTo("EXTERNAL_READ_ONLY"));

        assertThat(outside).doesNotExist();
        org.mockito.Mockito.verifyNoInteractions(ffprobe, tagReader, songRepository, songFileRepository);
    }

    @Test
    void managedImportEntryRejectsExternalReadOnlyModeBeforeReadingFiles() {
        Path file = sourceDir.resolve("不可导入.mkv");

        assertThatThrownBy(() -> scanService.ingestLibraryFile(file, null, null, null, false))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.getCode()).isEqualTo("EXTERNAL_READ_ONLY"));

        assertThat(file).doesNotExist();
        org.mockito.Mockito.verifyNoInteractions(ffprobe, tagReader, songRepository, songFileRepository);
    }

    @Test
    void directIngestPersistsFilenameVocalForm() throws Exception {
        Path file = Files.write(sourceDir.resolve(
                "张庭 钟丽缇 王祖蓝-爱上幼儿园-合唱-国语-流行.mkv"), new byte[]{1, 2, 3});

        assertThat(scanService.ingest(file)).isEqualTo(LibraryScanService.IngestOutcome.ADDED);

        assertThat(songsById.values())
                .filteredOn(song -> "爱上幼儿园".equals(song.getTitle()))
                .singleElement()
                .satisfies(song -> {
                    assertThat(song.getArtist()).isEqualTo("张庭 钟丽缇 王祖蓝");
                    assertThat(song.getVocalForm()).isEqualTo("合唱");
                });
    }

    @Test
    void singleFileIngestRunsMediaProbeOutsideDatabaseTransaction() throws Exception {
        Path file = Files.write(sourceDir.resolve("周杰伦-事务边界-国语-流行.mkv"), new byte[]{1, 2, 3});
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:single-ingest-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        DataSourceTransactionManager transactions = new DataSourceTransactionManager(dataSource);
        MediaProbeStage stage = new MediaProbeStage(ffprobe,
                new ResourceBudget(2, 1, 4, () -> false));
        LibraryScanService target = new LibraryScanService(props, ffprobe, tagReader,
                songRepository, songFileRepository, new AssetWriter(props), null, transactions,
                seenPathStore, new SongProjectionService(songRepository, songFileRepository), stage);
        target.setArtistCreditService(artistCreditService);
        ProxyFactory proxyFactory = new ProxyFactory(target);
        proxyFactory.addAdvice(new TransactionInterceptor(
                transactions, new AnnotationTransactionAttributeSource()));
        LibraryScanService proxied = (LibraryScanService) proxyFactory.getProxy();

        when(ffprobe.probe(file)).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                    .as("FFprobe admission must happen before the write transaction begins")
                    .isFalse();
            return probe();
        });

        assertThat(proxied.ingest(file)).isEqualTo(LibraryScanService.IngestOutcome.ADDED);
    }

    @Test
    void managedFileIngestRunsMediaProbeOutsideDatabaseTransaction() throws Exception {
        Path managedRoot = Files.createDirectory(tempDir.resolve("managed-library"));
        Path file = Files.write(managedRoot.resolve("周杰伦-托管入库事务边界-国语-流行.mkv"), new byte[]{1, 2, 3});
        props.setLibraryMode(LibraryMode.MANAGED);
        props.setKtvLibraryPath(managedRoot.toString());
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:managed-ingest-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        DataSourceTransactionManager transactions = new DataSourceTransactionManager(dataSource);
        MediaProbeStage stage = new MediaProbeStage(ffprobe,
                new ResourceBudget(2, 1, 4, () -> false));
        LibraryScanService target = new LibraryScanService(props, ffprobe, tagReader,
                songRepository, songFileRepository, new AssetWriter(props), null, transactions,
                seenPathStore, new SongProjectionService(songRepository, songFileRepository), stage);
        target.setArtistCreditService(artistCreditService);
        ProxyFactory proxyFactory = new ProxyFactory(target);
        proxyFactory.addAdvice(new TransactionInterceptor(
                transactions, new AnnotationTransactionAttributeSource()));
        LibraryScanService proxied = (LibraryScanService) proxyFactory.getProxy();

        when(ffprobe.probe(file)).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                    .as("managed import FFprobe admission must happen before the write transaction")
                    .isFalse();
            return probe();
        });

        LibraryScanService.IngestResult result = proxied.ingestLibraryFile(file, null, "source-md5", "output-md5", false);

        assertThat(result.imported()).isTrue();
        assertThat(result.songId()).isNotNull();
        assertThat(result.songFileId()).isNotNull();
    }

    @Test
    void managedIngestReusesTheProvidedProbeResult() throws Exception {
        Path managedRoot = Files.createDirectory(tempDir.resolve("managed-preprobed"));
        Path file = Files.write(managedRoot.resolve("周杰伦-预探测复用-国语-流行.mkv"), new byte[]{1, 2, 3});
        byte[] sourceBytes = {7, 5, 3, 1};
        Path importedSource = Files.write(tempDir.resolve("import-source.mkv"), sourceBytes);
        props.setLibraryMode(LibraryMode.MANAGED);
        props.setKtvLibraryPath(managedRoot.toString());

        LibraryScanService.IngestResult result = scanService.ingestLibraryFile(
                file, importedSource, "source-md5", "output-md5", true, probe());

        assertThat(result.imported()).isTrue();
        assertThat(result.songId()).isNotNull();
        assertThat(result.songFileId()).isNotNull();
        verify(ffprobe, never()).probe(any(Path.class));
        assertThat(filesByPath).containsKey(file.toString());
        SongFile imported = filesByPath.get(file.toString());
        assertThat(imported.getFileRole()).isEqualTo("LIBRARY");
        assertThat(imported.getSourcePath()).isEqualTo(importedSource.toString());
        assertThat(imported.getSourceMd5()).isEqualTo("source-md5");
        assertThat(imported.getOutputMd5()).isEqualTo("output-md5");
        assertThat(imported.isTranscodeRequired()).isTrue();
        assertThat(imported.getImportedAt()).isNotNull();
        assertThat(Files.readAllBytes(importedSource)).containsExactly(sourceBytes);
        assertThat(Files.readAllBytes(file)).containsExactly((byte) 1, (byte) 2, (byte) 3);
    }

    @Test
    void managedIngestReportsUnrecognizedSongAsImportedWhenRowsArePersisted() throws Exception {
        Path managedRoot = Files.createDirectory(tempDir.resolve("managed-unrecognized"));
        byte[] outputBytes = {2, 4, 6};
        byte[] sourceBytes = {1, 3, 5};
        Path output = Files.write(managedRoot.resolve("名称不规范.mkv"), outputBytes);
        Path source = Files.write(tempDir.resolve("managed-unrecognized-source.mkv"), sourceBytes);
        props.setLibraryMode(LibraryMode.MANAGED);
        props.setKtvLibraryPath(managedRoot.toString());

        LibraryScanService.IngestResult result = scanService.ingestLibraryFile(
                output, source, "source-md5", "output-md5", false, probe());

        assertThat(result.imported()).isTrue();
        assertThat(result.songId()).isNotNull();
        assertThat(result.songFileId()).isNotNull();
        SongFile indexed = filesByPath.get(output.toString());
        assertThat(indexed).isNotNull();
        assertThat(indexed.getFileRole()).isEqualTo("LIBRARY");
        assertThat(indexed.getRelativePath()).isEqualTo("名称不规范.mkv");
        assertThat(indexed.isProbePending()).isFalse();
        Song song = songsById.get(result.songId());
        assertThat(song).isNotNull();
        assertThat(song.getStatus()).isEqualTo("unrecognized");
        assertThat(song.isNeedsAiOptimization()).isTrue();
        assertThat(Files.readAllBytes(output)).containsExactly(outputBytes);
        assertThat(Files.readAllBytes(source)).containsExactly(sourceBytes);
    }

    @Test
    void managedIngestOfAnUnchangedFileReturnsItsExistingIdsWithoutReprobing() throws Exception {
        Path managedRoot = Files.createDirectory(tempDir.resolve("managed-unchanged"));
        Path file = Files.write(managedRoot.resolve("周杰伦-幂等入库-国语-流行.mkv"), new byte[]{1, 2, 3});
        props.setLibraryMode(LibraryMode.MANAGED);
        props.setKtvLibraryPath(managedRoot.toString());
        scanService.scanAll();
        SongFile indexed = filesByPath.get(file.toString());
        assertThat(indexed).isNotNull();

        LibraryScanService.IngestResult result = scanService.ingestLibraryFile(
                file, null, "source-md5", "output-md5", false);

        assertThat(result.imported()).isFalse();
        assertThat(result.songId()).isEqualTo(indexed.getSongId());
        assertThat(result.songFileId()).isEqualTo(indexed.getId());
        verify(ffprobe, times(1)).probe(file);
        assertThat(indexed.isValid()).isTrue();
    }

    @Test
    void managedIngestDoesNotPersistRowsWhenMediaProbeFails() throws Exception {
        Path managedRoot = Files.createDirectory(tempDir.resolve("managed-probe-failure"));
        Path file = Files.write(managedRoot.resolve("周杰伦-探测失败-国语-流行.mkv"), new byte[]{1, 2, 3});
        props.setLibraryMode(LibraryMode.MANAGED);
        props.setKtvLibraryPath(managedRoot.toString());
        when(ffprobe.probe(file)).thenThrow(new MediaProbeException("synthetic probe failure"));

        LibraryScanService.IngestResult result = scanService.ingestLibraryFile(
                file, null, "source-md5", "output-md5", false);

        assertThat(result.imported()).isFalse();
        assertThat(result.songId()).isNull();
        assertThat(result.songFileId()).isNull();
        assertThat(filesByPath).doesNotContainKey(file.toString());
        assertThat(songsById).isEmpty();
        verify(ffprobe, times(1)).probe(file);
    }

    @Test
    void managedIngestProbeFailureReturnsExistingIdsAndKeepsIndexedRow() throws Exception {
        Path managedRoot = Files.createDirectory(tempDir.resolve("managed-existing-probe-failure"));
        Path file = Files.write(managedRoot.resolve("周杰伦-重探测失败-国语-流行.mkv"), new byte[]{1, 2, 3});
        props.setLibraryMode(LibraryMode.MANAGED);
        props.setKtvLibraryPath(managedRoot.toString());
        scanService.scanAll();
        SongFile indexed = filesByPath.get(file.toString());
        Long originalSongId = indexed.getSongId();
        Long originalFileId = indexed.getId();
        java.time.OffsetDateTime originalMtime = indexed.getFileMtime();
        Files.write(file, new byte[]{4, 5, 6});
        Files.setLastModifiedTime(file, FileTime.fromMillis(originalMtime.toInstant().toEpochMilli() + 2_000));
        when(ffprobe.probe(file)).thenThrow(new MediaProbeException("synthetic retry failure"));

        LibraryScanService.IngestResult result = scanService.ingestLibraryFile(
                file, null, "source-md5", "output-md5", false);

        assertThat(result.imported()).isFalse();
        assertThat(result.songId()).isEqualTo(originalSongId);
        assertThat(result.songFileId()).isEqualTo(originalFileId);
        assertThat(indexed.getSongId()).isEqualTo(originalSongId);
        assertThat(indexed.getFileMtime()).isEqualTo(originalMtime);
        assertThat(indexed.isValid()).isTrue();
        assertThat(songsById).containsKey(originalSongId);
        verify(ffprobe, times(2)).probe(file);
    }

    @Test
    void managedIngestRefreshesLyricsOnlyWithoutReprobingMedia() throws Exception {
        Path managedRoot = Files.createDirectory(tempDir.resolve("managed-lyric-only"));
        Path file = Files.write(managedRoot.resolve("周杰伦-歌词刷新-国语-流行.mkv"), new byte[]{1, 2, 3});
        Path sidecar = managedRoot.resolve("周杰伦-歌词刷新-国语-流行.lrc");
        Files.writeString(sidecar, "[00:01.00]旧歌词\n");
        props.setLibraryMode(LibraryMode.MANAGED);
        props.setKtvLibraryPath(managedRoot.toString());
        scanService.scanAll();
        SongFile indexed = filesByPath.get(file.toString());
        Song song = songsById.get(indexed.getSongId());
        java.time.OffsetDateTime originalMtime = indexed.getLyricMtime();
        Files.writeString(sidecar, "[00:02.00]新歌词\n");
        Files.setLastModifiedTime(sidecar, FileTime.fromMillis(originalMtime.toInstant().toEpochMilli() + 2_000));

        LibraryScanService.IngestResult result = scanService.ingestLibraryFile(
                file, null, "source-md5", "output-md5", false);

        assertThat(result.imported()).isTrue();
        assertThat(result.songId()).isEqualTo(song.getId());
        assertThat(result.songFileId()).isEqualTo(indexed.getId());
        assertThat(Files.readString(tempDir.resolve("data").resolve(song.getLyricPath())))
                .contains("新歌词");
        verify(ffprobe, times(1)).probe(file);
    }

    @Test
    void managedIngestReprobesWhenSidecarIdentityTagsChange() throws Exception {
        Path managedRoot = Files.createDirectory(tempDir.resolve("managed-lyric-identity"));
        Path file = Files.write(managedRoot.resolve("周杰伦-歌词身份变化-国语-流行.mkv"), new byte[]{1, 2, 3});
        Path sidecar = managedRoot.resolve("周杰伦-歌词身份变化-国语-流行.lrc");
        Files.writeString(sidecar, "[00:01.00]旧歌词\n");
        props.setLibraryMode(LibraryMode.MANAGED);
        props.setKtvLibraryPath(managedRoot.toString());
        scanService.scanAll();
        SongFile indexed = filesByPath.get(file.toString());
        Long originalSongId = indexed.getSongId();
        java.time.OffsetDateTime originalMtime = indexed.getLyricMtime();
        Files.writeString(sidecar, "[ti:变更身份]\n[ar:新歌手]\n[00:02.00]新歌词\n");
        Files.setLastModifiedTime(sidecar, FileTime.fromMillis(originalMtime.toInstant().toEpochMilli() + 2_000));

        LibraryScanService.IngestResult result = scanService.ingestLibraryFile(
                file, null, "source-md5", "output-md5", false);

        assertThat(result.imported()).isTrue();
        assertThat(result.songId()).isNotEqualTo(originalSongId);
        assertThat(result.songFileId()).isEqualTo(indexed.getId());
        assertThat(indexed.getSongId()).isEqualTo(result.songId());
        assertThat(songsById.get(result.songId()).getTitle()).isEqualTo("变更身份");
        assertThat(songsById.get(result.songId()).getArtist()).isEqualTo("新歌手");
        assertThat(indexed.isProbePending()).isFalse();
        verify(ffprobe, times(2)).probe(file);
    }

    @Test
    void managedIngestPreservesCachedLyricsWhenChangedSidecarExceedsReadLimit() throws Exception {
        Path managedRoot = Files.createDirectory(tempDir.resolve("managed-oversized-lyric"));
        Path file = Files.write(managedRoot.resolve("周杰伦-超大歌词-国语-流行.mkv"), new byte[]{1, 2, 3});
        Path sidecar = managedRoot.resolve("周杰伦-超大歌词-国语-流行.lrc");
        Files.writeString(sidecar, "[00:01.00]旧歌词\n");
        props.setLibraryMode(LibraryMode.MANAGED);
        props.setKtvLibraryPath(managedRoot.toString());
        scanService.scanAll();
        SongFile indexed = filesByPath.get(file.toString());
        Song song = songsById.get(indexed.getSongId());
        String cachedPath = song.getLyricPath();
        java.time.OffsetDateTime originalMtime = indexed.getLyricMtime();
        Files.write(sidecar, new byte[AssetWriter.MAX_LYRIC_BYTES + 1]);
        Files.setLastModifiedTime(sidecar, FileTime.fromMillis(originalMtime.toInstant().toEpochMilli() + 2_000));

        LibraryScanService.IngestResult result = scanService.ingestLibraryFile(
                file, null, "source-md5", "output-md5", false);

        assertThat(result.imported()).isFalse();
        assertThat(result.songId()).isEqualTo(song.getId());
        assertThat(result.songFileId()).isEqualTo(indexed.getId());
        assertThat(song.getLyricPath()).isEqualTo(cachedPath);
        assertThat(Files.readString(tempDir.resolve("data").resolve(cachedPath))).contains("旧歌词");
        assertThat(indexed.getLyricSize()).isEqualTo(
                (long) "[00:01.00]旧歌词\n".getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        verify(ffprobe, times(1)).probe(file);
    }

    @Test
    void persistsFastIndexBeforeProbeReadsMedia() throws Exception {
        Path file = Files.write(sourceDir.resolve("周杰伦-晴天-国语-流行.mkv"), new byte[]{1, 2, 3});
        when(ffprobe.probe(any(Path.class))).thenAnswer(invocation -> {
            SongFile indexed = filesByPath.get(file.toString());
            assertThat(indexed).as("Fast Index must be persisted before FFprobe").isNotNull();
            assertThat(indexed.getRelativePath()).isEqualTo("周杰伦-晴天-国语-流行.mkv");
            assertThat(indexed.isProbePending()).isTrue();
            Song provisional = songsById.get(indexed.getSongId());
            assertThat(provisional).isNotNull();
            assertThat(provisional.getTitle()).isEqualTo("晴天");
            assertThat(provisional.getStatus()).isEqualTo("ok");
            return probe();
        });

        scanService.scanAll();
    }

    @Test
    void unchangedSecondScanDoesNotProbeHashOrUpdateDatabase() throws Exception {
        Path file = Files.write(sourceDir.resolve("周杰伦-晴天-国语-流行.mkv"), new byte[]{1, 2, 3});
        scanService.scanAll();

        LibraryScanService.ScanResult second = scanService.scanAll();

        assertThat(second.scanned()).isEqualTo(1);
        assertThat(second.skipped()).isEqualTo(1);
        assertThat(second.probeQueued()).isZero();
        assertThat(second.probeCalls()).isZero();
        assertThat(second.hashCalls()).isZero();
        assertThat(second.dbUpdates()).isZero();
        assertThat(Files.exists(file)).isTrue();
        verify(ffprobe, times(1)).probe(any(Path.class));
        verify(songRepository, times(2)).save(any(Song.class));
        verify(songFileRepository, times(2)).save(any(SongFile.class));
    }

    @Test
    void unchangedFileRestoresMissingRelativePathWithoutReprobing() throws Exception {
        Path file = Files.write(sourceDir.resolve("周杰伦-晴天-国语-流行.mkv"), new byte[]{1, 2, 3});
        scanService.scanAll();
        SongFile indexed = filesByPath.get(file.toString());
        assertThat(indexed).isNotNull();

        indexed.setRelativePath(null);
        LibraryScanService.ScanResult second = scanService.scanAll();

        assertThat(indexed.getRelativePath()).isEqualTo(file.getFileName().toString());
        assertThat(second.probeCalls()).isZero();
        assertThat(second.hashCalls()).isZero();
        assertThat(second.dbUpdates()).isEqualTo(1);
        verify(ffprobe, times(1)).probe(file);
        verify(songFileRepository, times(3)).save(any(SongFile.class));
    }

    @Test
    void unchangedUnrecognizedRowRepairsFilenameMetadataWithoutReprobing() throws Exception {
        Path file = Files.write(sourceDir.resolve("童唱--爸爸妈妈听我说-国语-儿歌.mkv"),
                new byte[]{1, 2, 3});
        BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
        java.time.OffsetDateTime mediaMtime = attributes.lastModifiedTime().toInstant()
                .atOffset(java.time.ZoneOffset.UTC);
        String mediaIdentity = attributes.fileKey() == null ? null : String.valueOf(attributes.fileKey());

        Song song = new Song();
        song.setId(700L);
        song.setTitle(file.getFileName().toString().replaceFirst("\\.mkv$", ""));
        song.setArtist("未知歌手");
        song.setLanguage("未知");
        song.setMediaType(MediaClassifier.KTV_VIDEO);
        song.setDurationMs(180_000);
        song.setFingerprint("legacy-unrecognized-700");
        song.setStatus("unrecognized");
        song.setNeedsAiOptimization(true);
        songsById.put(song.getId(), song);
        songsByFingerprint.put(song.getFingerprint(), song);

        SongFile indexed = new SongFile();
        indexed.setId(701L);
        indexed.setSongId(song.getId());
        indexed.setFilePath(file.toString());
        indexed.setRelativePath(file.getFileName().toString());
        indexed.setFormat("mkv");
        indexed.setFileSize(attributes.size());
        indexed.setFileMtime(mediaMtime);
        indexed.setFileIdentity(mediaIdentity == null ? null : mediaIdentity + "|null");
        indexed.setMediaMtime(mediaMtime);
        indexed.setMediaFileIdentity(mediaIdentity);
        indexed.setLyricSize(null);
        indexed.setLyricMtime(null);
        indexed.setLyricFileIdentity(null);
        indexed.setLyricSnapshotVersion(1);
        indexed.setFileRole(LibraryModePolicy.EXTERNAL_FILE_ROLE);
        indexed.setValid(true);
        indexed.setProbePending(false);
        filesByPath.put(file.toString(), indexed);

        LibraryScanService.ScanResult result = scanService.scanAll();

        assertThat(result.probeCalls()).isZero();
        assertThat(result.hashCalls()).isZero();
        assertThat(result.updated()).isEqualTo(1);
        assertThat(result.dbUpdates()).isEqualTo(1);
        assertThat(song.getTitle()).isEqualTo("爸爸妈妈听我说");
        assertThat(song.getArtist()).isEqualTo("童唱");
        assertThat(song.getLanguage()).isEqualTo("国语");
        assertThat(song.getStatus()).isEqualTo("ok");
        verify(ffprobe, times(0)).probe(any(Path.class));
    }

    @Test
    void unchangedFilenameRepairKeepsAiReviewForUnknownArtist() throws Exception {
        byte[] sourceBytes = {6, 2, 6};
        Path file = Files.write(sourceDir.resolve("未知歌手-歌曲待补充-国语-流行.mkv"), sourceBytes);
        scanService.setArtistCreditService(null);
        Song song = new Song();
        song.setId(715L);
        song.setTitle("旧标题");
        song.setArtist("旧歌手");
        song.setLanguage("国语");
        song.setMediaType(MediaClassifier.KTV_VIDEO);
        song.setDurationMs(180_000);
        song.setFingerprint("legacy-unrecognized-715");
        song.setStatus("unrecognized");
        song.setNeedsAiOptimization(true);
        songsById.put(song.getId(), song);
        songsByFingerprint.put(song.getFingerprint(), song);
        registerUnchangedFile(file, song);

        LibraryScanService.ScanResult result = scanService.scanAll();

        assertThat(result.updated()).isEqualTo(1);
        assertThat(result.probeCalls()).isZero();
        assertThat(song.getTitle()).isEqualTo("歌曲待补充");
        assertThat(song.getArtist()).isEqualTo("未知歌手");
        assertThat(song.getStatus()).isEqualTo("ok");
        assertThat(song.isNeedsAiOptimization()).isTrue();
        assertThat(Files.readAllBytes(file)).containsExactly(sourceBytes);
        verify(ffprobe, never()).probe(any(Path.class));
        verifyNoInteractions(artistCreditService);
    }

    @Test
    void unchangedFilenameRepairKeepsTheRowUnrecognizedWhenTheNewFingerprintConflicts() throws Exception {
        Path file = Files.write(sourceDir.resolve("童唱--爸爸妈妈听我说-国语-儿歌.mkv"),
                new byte[]{1, 2, 3});
        Song song = new Song();
        song.setId(702L);
        song.setTitle("童唱--爸爸妈妈听我说-国语-儿歌");
        song.setArtist("未知歌手");
        song.setLanguage("未知");
        song.setMediaType(MediaClassifier.KTV_VIDEO);
        song.setDurationMs(180_000);
        song.setFingerprint("legacy-unrecognized-702");
        song.setStatus("unrecognized");
        song.setNeedsAiOptimization(true);
        songsById.put(song.getId(), song);
        songsByFingerprint.put(song.getFingerprint(), song);
        registerUnchangedFile(file, song);

        Song conflict = new Song();
        conflict.setId(703L);
        conflict.setFingerprint(MediaClassifier.fingerprint("童唱", "爸爸妈妈听我说", 180_000));
        songsById.put(conflict.getId(), conflict);
        songsByFingerprint.put(conflict.getFingerprint(), conflict);

        LibraryScanService.ScanResult result = scanService.scanAll();

        assertThat(result.updated()).isZero();
        assertThat(result.skipped()).isEqualTo(1);
        assertThat(result.dbUpdates()).isZero();
        assertThat(song.getTitle()).isEqualTo("童唱--爸爸妈妈听我说-国语-儿歌");
        assertThat(song.getArtist()).isEqualTo("未知歌手");
        assertThat(song.getStatus()).isEqualTo("unrecognized");
        verify(ffprobe, times(0)).probe(any(Path.class));
    }

    @Test
    void unchangedFilenameRepairDoesNotOverwriteManuallyLockedIdentity() throws Exception {
        Path file = Files.write(sourceDir.resolve("童唱--爸爸妈妈听我说-国语-儿歌.mkv"),
                new byte[]{1, 2, 3});
        Song song = new Song();
        song.setId(704L);
        song.setTitle("人工歌名");
        song.setArtist("人工歌手");
        song.setLanguage("粤语");
        song.setMediaType(MediaClassifier.KTV_VIDEO);
        song.setDurationMs(180_000);
        song.setFingerprint("legacy-unrecognized-704");
        song.setStatus("unrecognized");
        song.setNeedsAiOptimization(true);
        song.lockMetadata("title");
        song.lockMetadata("artist");
        songsById.put(song.getId(), song);
        songsByFingerprint.put(song.getFingerprint(), song);
        registerUnchangedFile(file, song);

        LibraryScanService.ScanResult result = scanService.scanAll();

        assertThat(result.updated()).isZero();
        assertThat(result.skipped()).isEqualTo(1);
        assertThat(result.dbUpdates()).isZero();
        assertThat(song.getTitle()).isEqualTo("人工歌名");
        assertThat(song.getArtist()).isEqualTo("人工歌手");
        assertThat(song.getLanguage()).isEqualTo("粤语");
        assertThat(song.getStatus()).isEqualTo("unrecognized");
        verify(ffprobe, times(0)).probe(any(Path.class));
    }

    @Test
    void unchangedFilenameRepairKeepsAiReviewWhenLanguageIsLockedAsUnknown() throws Exception {
        Path file = Files.write(sourceDir.resolve("童唱--爸爸妈妈听我说-国语-儿歌.mkv"),
                new byte[]{1, 2, 3});
        Song song = new Song();
        song.setId(705L);
        song.setTitle("旧歌名");
        song.setArtist("旧歌手");
        song.setLanguage("未知");
        song.setMediaType(MediaClassifier.KTV_VIDEO);
        song.setDurationMs(180_000);
        song.setFingerprint("legacy-unrecognized-705");
        song.setStatus("unrecognized");
        song.setNeedsAiOptimization(true);
        song.lockMetadata("language");
        songsById.put(song.getId(), song);
        songsByFingerprint.put(song.getFingerprint(), song);
        registerUnchangedFile(file, song);

        LibraryScanService.ScanResult result = scanService.scanAll();

        assertThat(result.updated()).isEqualTo(1);
        assertThat(result.dbUpdates()).isEqualTo(1);
        assertThat(song.getTitle()).isEqualTo("爸爸妈妈听我说");
        assertThat(song.getArtist()).isEqualTo("童唱");
        assertThat(song.getLanguage()).isEqualTo("未知");
        assertThat(song.getStatus()).isEqualTo("ok");
        assertThat(song.isNeedsAiOptimization()).isTrue();
        verify(ffprobe, times(0)).probe(any(Path.class));
    }

    @ParameterizedTest
    @MethodSource("filenameRepairLockedOptionalMetadataCases")
    void unchangedFilenameRepairRespectsOptionalMetadataLocks(
            String lockedField, String expectedLanguage, String expectedCategory,
            String expectedVocalForm, boolean expectedAiReview) throws Exception {
        byte[] sourceBytes = {7, 1, 1};
        Path file = Files.write(sourceDir.resolve(
                "00123456-张庭 钟丽缇 王祖蓝-爱上幼儿园-合唱-国语-流行.mkv"), sourceBytes);
        Song song = new Song();
        song.setId(711L);
        song.setTitle("旧歌名");
        song.setArtist("旧歌手");
        song.setLanguage("未知".equals(expectedLanguage) ? "未知" : "粤语");
        song.setTags(new String[]{"旧分类"});
        song.setVocalForm("独唱");
        song.setMediaType(MediaClassifier.KTV_VIDEO);
        song.setDurationMs(180_000);
        song.setFingerprint("legacy-unrecognized-711");
        song.setStatus("unrecognized");
        song.setNeedsAiOptimization(true);
        song.lockMetadata(lockedField);
        songsById.put(song.getId(), song);
        songsByFingerprint.put(song.getFingerprint(), song);
        registerUnchangedFile(file, song);

        LibraryScanService.ScanResult result = scanService.scanAll();

        assertThat(result.scanned()).isEqualTo(1);
        assertThat(result.updated()).isEqualTo(1);
        assertThat(result.dbUpdates()).isEqualTo(1);
        assertThat(song.getTitle()).isEqualTo("爱上幼儿园");
        assertThat(song.getArtist()).isEqualTo("张庭 钟丽缇 王祖蓝");
        assertThat(song.getLanguage()).isEqualTo(expectedLanguage);
        assertThat(song.getTags()).containsExactly(expectedCategory);
        assertThat(song.getVocalForm()).isEqualTo(expectedVocalForm);
        assertThat(song.getCatalogNumber()).isEqualTo("00123456");
        assertThat(song.getStatus()).isEqualTo("ok");
        assertThat(song.isNeedsAiOptimization()).isEqualTo(expectedAiReview);
        assertThat(Files.readAllBytes(file)).containsExactly(sourceBytes);
        verify(ffprobe, never()).probe(any(Path.class));
    }

    private static Stream<Arguments> filenameRepairLockedOptionalMetadataCases() {
        return Stream.of(
                Arguments.of("tags", "国语", "旧分类", "合唱", false),
                Arguments.of("vocalForm", "国语", "流行", "独唱", false),
                Arguments.of("language", "未知", "流行", "合唱", true));
    }

    @Test
    void unrecognizedFilenameForSameSongDoesNotBlockRecognizedSiblingRepair() throws Exception {
        byte[] sourceBytes = {2, 7, 1};
        Path unrecognizedFile = Files.write(sourceDir.resolve("名称不规范.mkv"), sourceBytes);
        Path recognizedFile = Files.write(sourceDir.resolve("周杰伦-晴天-国语-流行.mkv"), sourceBytes);
        assertThat(scanService.parseFilename(unrecognizedFile.getFileName().toString()).recognized()).isFalse();

        Song song = new Song();
        song.setId(712L);
        song.setTitle("旧待审核名称");
        song.setArtist("旧待审核歌手");
        song.setLanguage("未知");
        song.setMediaType(MediaClassifier.KTV_VIDEO);
        song.setDurationMs(180_000);
        song.setFingerprint("legacy-unrecognized-712");
        song.setStatus("unrecognized");
        song.setNeedsAiOptimization(true);
        songsById.put(song.getId(), song);
        songsByFingerprint.put(song.getFingerprint(), song);
        Song knownArtist = new Song();
        knownArtist.setId(713L);
        knownArtist.setTitle("已知歌手目录项");
        knownArtist.setArtist("陈奕迅");
        knownArtist.setFingerprint("known-artist-dictionary-713");
        knownArtist.setStatus("ok");
        songsById.put(knownArtist.getId(), knownArtist);
        songsByFingerprint.put(knownArtist.getFingerprint(), knownArtist);
        registerUnchangedFile(unrecognizedFile, song);
        registerUnchangedFile(recognizedFile, song);

        LibraryScanService.ScanResult result = scanService.scanAll();

        assertThat(result.scanned()).isEqualTo(2);
        assertThat(result.updated()).isEqualTo(1);
        assertThat(result.skipped()).isEqualTo(1);
        assertThat(result.probeCalls()).isZero();
        assertThat(song.getTitle()).isEqualTo("晴天");
        assertThat(song.getArtist()).isEqualTo("周杰伦");
        assertThat(song.getLanguage()).isEqualTo("国语");
        assertThat(song.getStatus()).isEqualTo("ok");
        assertThat(Files.readAllBytes(unrecognizedFile)).containsExactly(sourceBytes);
        assertThat(Files.readAllBytes(recognizedFile)).containsExactly(sourceBytes);
        verify(ffprobe, never()).probe(any(Path.class));
        verify(artistCreditService).replace(eq(song.getId()), eq("周杰伦"),
                org.mockito.ArgumentMatchers.argThat(artists -> artists.contains("陈奕迅")));
    }

    @Test
    void unchangedFilenameRepairRestoresAllUnlockedStructuredMetadata() throws Exception {
        Path file = Files.write(sourceDir.resolve(
                "00123456-张庭 钟丽缇 王祖蓝-爱上幼儿园-合唱-国语-流行.mkv"), new byte[]{1, 2, 3});
        Song song = new Song();
        song.setId(706L);
        song.setTitle("旧歌名");
        song.setArtist("旧歌手");
        song.setLanguage("未知");
        song.setTags(new String[]{"旧分类"});
        song.setVocalForm("独唱");
        song.setMediaType(MediaClassifier.KTV_VIDEO);
        song.setDurationMs(180_000);
        song.setFingerprint("legacy-unrecognized-706");
        song.setStatus("unrecognized");
        song.setNeedsAiOptimization(true);
        songsById.put(song.getId(), song);
        songsByFingerprint.put(song.getFingerprint(), song);
        ParsedMeta parsed = scanService.parseFilename(file.getFileName().toString());
        songsByFingerprint.put(
                MediaClassifier.fingerprint(parsed.artist(), parsed.title(), song.getDurationMs()), song);
        registerUnchangedFile(file, song);

        LibraryScanService.ScanResult result = scanService.scanAll();

        assertThat(result.updated()).isEqualTo(1);
        assertThat(result.dbUpdates()).isEqualTo(1);
        assertThat(song.getTitle()).isEqualTo("爱上幼儿园");
        assertThat(song.getArtist()).isEqualTo("张庭 钟丽缇 王祖蓝");
        assertThat(song.getLanguage()).isEqualTo("国语");
        assertThat(song.getTags()).containsExactly("流行");
        assertThat(song.getVocalForm()).isEqualTo("合唱");
        assertThat(song.getCatalogNumber()).isEqualTo("00123456");
        assertThat(song.getFingerprint())
                .isEqualTo(MediaClassifier.fingerprint(song.getArtist(), song.getTitle(), song.getDurationMs()));
        assertThat(song.getStatus()).isEqualTo("ok");
        assertThat(song.isNeedsAiOptimization()).isFalse();
        assertThat(Files.readAllBytes(file)).containsExactly((byte) 1, (byte) 2, (byte) 3);
        verify(ffprobe, never()).probe(any(Path.class));
    }

    @Test
    void unchangedLegacyFilenameRepairNormalizesMissingLanguageToUnknown() throws Exception {
        byte[] sourceBytes = {4, 2, 1};
        Path file = Files.write(sourceDir.resolve("周杰伦-晴天.mkv"), sourceBytes);
        ParsedMeta parsed = scanService.parseFilename(file.getFileName().toString());
        assertThat(parsed.recognized()).isTrue();
        assertThat(parsed.language()).isEmpty();

        Song song = new Song();
        song.setId(721L);
        song.setTitle("旧标题");
        song.setArtist("旧歌手");
        song.setLanguage("粤语");
        song.setMediaType(MediaClassifier.KTV_VIDEO);
        song.setDurationMs(180_000);
        song.setFingerprint("legacy-unrecognized-721");
        song.setStatus("unrecognized");
        song.setNeedsAiOptimization(true);
        songsById.put(song.getId(), song);
        songsByFingerprint.put(song.getFingerprint(), song);
        songsByFingerprint.put(
                MediaClassifier.fingerprint(parsed.artist(), parsed.title(), song.getDurationMs()), song);
        registerUnchangedFile(file, song);

        LibraryScanService.ScanResult result = scanService.scanAll();

        assertThat(result.scanned()).isEqualTo(1);
        assertThat(result.updated()).isEqualTo(1);
        assertThat(result.dbUpdates()).isEqualTo(1);
        assertThat(song.getTitle()).isEqualTo("晴天");
        assertThat(song.getArtist()).isEqualTo("周杰伦");
        assertThat(song.getLanguage()).isEqualTo("未知");
        assertThat(song.getStatus()).isEqualTo("ok");
        assertThat(song.isNeedsAiOptimization()).isTrue();
        assertThat(Files.readAllBytes(file)).containsExactly(sourceBytes);
        verify(ffprobe, never()).probe(any(Path.class));
    }

    @Test
    void unchangedFilenameRepairSkipsFingerprintLookupWhenFingerprintAlreadyMatches() throws Exception {
        Path file = Files.write(sourceDir.resolve(
                "00123456-张庭 钟丽缇 王祖蓝-爱上幼儿园-合唱-国语-流行.mkv"), new byte[]{1, 2, 3});
        ParsedMeta parsed = scanService.parseFilename(file.getFileName().toString());
        Song song = new Song();
        song.setId(707L);
        song.setTitle(parsed.title());
        song.setArtist(parsed.artist());
        song.setLanguage(parsed.language());
        song.setTags(new String[]{parsed.category()});
        song.setVocalForm(parsed.vocalForm());
        song.setCatalogNumber(parsed.catalogNumber());
        song.setMediaType(MediaClassifier.KTV_VIDEO);
        song.setDurationMs(180_000);
        String fingerprint = MediaClassifier.fingerprint(parsed.artist(), parsed.title(), song.getDurationMs());
        song.setFingerprint(fingerprint);
        song.setStatus("unrecognized");
        song.setNeedsAiOptimization(false);
        songsById.put(song.getId(), song);
        songsByFingerprint.put(fingerprint, song);
        registerUnchangedFile(file, song);

        LibraryScanService.ScanResult result = scanService.scanAll();

        assertThat(result.updated()).isEqualTo(1);
        assertThat(song.getTitle()).isEqualTo(parsed.title());
        assertThat(song.getArtist()).isEqualTo(parsed.artist());
        assertThat(song.getLanguage()).isEqualTo(parsed.language());
        assertThat(song.getTags()).containsExactly(parsed.category());
        assertThat(song.getVocalForm()).isEqualTo(parsed.vocalForm());
        assertThat(song.getCatalogNumber()).isEqualTo(parsed.catalogNumber());
        assertThat(song.getStatus()).isEqualTo("ok");
        assertThat(song.isNeedsAiOptimization()).isFalse();
        verify(songRepository, never()).findByFingerprint(fingerprint);
        verify(ffprobe, never()).probe(any(Path.class));
    }

    @ParameterizedTest
    @MethodSource("filenameRepairAlreadyMatchingIdentityFieldCases")
    void unchangedFilenameRepairDoesNotRewriteAnIdentityFieldThatAlreadyMatches(String matchingField)
            throws Exception {
        byte[] sourceBytes = {2, 4, 6};
        Path file = Files.write(sourceDir.resolve("周杰伦-晴天-国语-流行.mkv"), sourceBytes);
        ParsedMeta candidate = scanService.parseFilename(file.getFileName().toString());
        assertThat(candidate.recognized()).isTrue();

        Song song = new Song();
        song.setId(720L);
        song.setTitle("title".equals(matchingField) ? candidate.title() : "旧歌名");
        song.setArtist("artist".equals(matchingField) ? candidate.artist() : "未知歌手");
        song.setLanguage("未知");
        song.setMediaType(MediaClassifier.KTV_VIDEO);
        song.setDurationMs(180_000);
        song.setFingerprint("legacy-unrecognized-720-" + matchingField);
        song.setStatus("unrecognized");
        song.setNeedsAiOptimization(true);
        songsById.put(song.getId(), song);
        songsByFingerprint.put(song.getFingerprint(), song);
        registerUnchangedFile(file, song);

        LibraryScanService.ScanResult result = scanService.scanAll();

        assertThat(result.scanned()).isEqualTo(1);
        assertThat(result.updated()).isEqualTo(1);
        assertThat(result.probeCalls()).isZero();
        assertThat(song.getTitle()).isEqualTo(candidate.title());
        assertThat(song.getArtist()).isEqualTo(candidate.artist());
        assertThat(song.getStatus()).isEqualTo("ok");
        assertThat(song.isNeedsAiOptimization()).isFalse();
        assertThat(Files.readAllBytes(file)).containsExactly(sourceBytes);
        verify(ffprobe, never()).probe(any(Path.class));
    }

    private static Stream<Arguments> filenameRepairAlreadyMatchingIdentityFieldCases() {
        return Stream.of(Arguments.of("title"), Arguments.of("artist"));
    }

    @Test
    void filenameRepairLookupFailureDoesNotFailAnUnchangedScan() throws Exception {
        Path file = Files.write(sourceDir.resolve("童唱--爸爸妈妈听我说-国语-儿歌.mkv"),
                new byte[]{1, 2, 3});
        Song song = new Song();
        song.setId(708L);
        song.setTitle("旧待审核歌名");
        song.setArtist("旧待审核歌手");
        song.setLanguage("未知");
        song.setMediaType(MediaClassifier.KTV_VIDEO);
        song.setDurationMs(180_000);
        song.setFingerprint("legacy-unrecognized-708");
        song.setStatus("unrecognized");
        song.setNeedsAiOptimization(true);
        songsById.put(song.getId(), song);
        songsByFingerprint.put(song.getFingerprint(), song);
        registerUnchangedFile(file, song);
        when(songRepository.findAllById(org.mockito.ArgumentMatchers.any(Iterable.class)))
                .thenThrow(new IllegalStateException("synthetic metadata lookup failure"));

        LibraryScanService.ScanResult result = scanService.scanAll();

        assertThat(result.scanned()).isEqualTo(1);
        assertThat(result.skipped()).isEqualTo(1);
        assertThat(result.updated()).isZero();
        assertThat(song.getTitle()).isEqualTo("旧待审核歌名");
        assertThat(song.getArtist()).isEqualTo("旧待审核歌手");
        assertThat(song.getStatus()).isEqualTo("unrecognized");
        verify(ffprobe, never()).probe(any(Path.class));
    }

    @ParameterizedTest
    @MethodSource("malformedFilenameRepairLookupResults")
    void unchangedFilenameRepairIgnoresUnavailableOrMalformedBatchRows(List<Song> lookupRows)
            throws Exception {
        byte[] sourceBytes = {3, 1, 4};
        Path file = Files.write(sourceDir.resolve("童唱--爸爸妈妈听我说-国语-儿歌.mkv"), sourceBytes);
        Song song = new Song();
        song.setId(710L);
        song.setTitle("旧待审核歌名");
        song.setArtist("旧待审核歌手");
        song.setLanguage("未知");
        song.setMediaType(MediaClassifier.KTV_VIDEO);
        song.setDurationMs(180_000);
        song.setFingerprint("legacy-unrecognized-710");
        song.setStatus("unrecognized");
        song.setNeedsAiOptimization(true);
        songsById.put(song.getId(), song);
        songsByFingerprint.put(song.getFingerprint(), song);
        registerUnchangedFile(file, song);
        when(songRepository.findAllById(org.mockito.ArgumentMatchers.any(Iterable.class)))
                .thenReturn(lookupRows);

        LibraryScanService.ScanResult result = scanService.scanAll();

        assertThat(result.scanned()).isEqualTo(1);
        assertThat(result.skipped()).isEqualTo(1);
        assertThat(result.updated()).isZero();
        assertThat(result.probeCalls()).isZero();
        assertThat(song.getTitle()).isEqualTo("旧待审核歌名");
        assertThat(song.getArtist()).isEqualTo("旧待审核歌手");
        assertThat(song.getLanguage()).isEqualTo("未知");
        assertThat(song.getFingerprint()).isEqualTo("legacy-unrecognized-710");
        assertThat(song.getStatus()).isEqualTo("unrecognized");
        assertThat(song.isNeedsAiOptimization()).isTrue();
        assertThat(Files.readAllBytes(file)).containsExactly(sourceBytes);
        verify(songRepository, never()).save(any(Song.class));
        verify(ffprobe, never()).probe(any(Path.class));
    }

    private static Stream<Arguments> malformedFilenameRepairLookupResults() {
        Song withoutId = new Song();
        Song unrelated = new Song();
        unrelated.setId(9_999L);
        return Stream.of(
                Arguments.of((List<Song>) null),
                Arguments.of(java.util.Arrays.asList(null, withoutId, unrelated)));
    }

    @Test
    void filenameRepairConflictLookupFailureKeepsTheSongPendingForReview() throws Exception {
        Path file = Files.write(sourceDir.resolve("童唱--爸爸妈妈听我说-国语-儿歌.mkv"),
                new byte[]{1, 2, 3});
        Song song = new Song();
        song.setId(709L);
        song.setTitle("旧待审核歌名");
        song.setArtist("旧待审核歌手");
        song.setLanguage("未知");
        song.setMediaType(MediaClassifier.KTV_VIDEO);
        song.setDurationMs(180_000);
        song.setFingerprint("legacy-unrecognized-709");
        song.setStatus("unrecognized");
        song.setNeedsAiOptimization(true);
        songsById.put(song.getId(), song);
        songsByFingerprint.put(song.getFingerprint(), song);
        registerUnchangedFile(file, song);
        when(songRepository.findByFingerprint(anyString()))
                .thenThrow(new IllegalStateException("synthetic fingerprint lookup failure"));

        LibraryScanService.ScanResult result = scanService.scanAll();

        assertThat(result.scanned()).isEqualTo(1);
        assertThat(result.skipped()).isEqualTo(1);
        assertThat(result.updated()).isZero();
        assertThat(song.getTitle()).isEqualTo("旧待审核歌名");
        assertThat(song.getArtist()).isEqualTo("旧待审核歌手");
        assertThat(song.getStatus()).isEqualTo("unrecognized");
        verify(ffprobe, never()).probe(any(Path.class));
    }

    @Test
    void lrcOnlyChangeRefreshesLyricCacheWithoutReprobingMedia() throws Exception {
        Path file = Files.write(sourceDir.resolve("周杰伦-晴天-国语-流行.mkv"), new byte[]{1, 2, 3});
        Path lrc = sourceDir.resolve("周杰伦-晴天-国语-流行.lrc");
        Files.writeString(lrc, "[00:01.00]旧歌词\n");
        scanService.scanAll();

        SongFile indexed = filesByPath.get(file.toString());
        Song song = songsById.get(indexed.getSongId());
        assertThat(song.getLyricPath()).isNotBlank();
        assertThat(indexed.getLyricSnapshotVersion()).isEqualTo(1);

        Files.writeString(lrc, "[00:01.00]新歌词\n");
        Files.setLastModifiedTime(lrc, FileTime.fromMillis(
                Files.getLastModifiedTime(lrc).toMillis() + 2_000));

        LibraryScanService.ScanResult second = scanService.scanAll();

        assertThat(second.updated()).isEqualTo(1);
        assertThat(second.skipped()).isZero();
        assertThat(second.probeQueued()).isZero();
        assertThat(second.probeCalls()).isZero();
        assertThat(second.hashCalls()).isZero();
        assertThat(Files.readString(tempDir.resolve("data").resolve(song.getLyricPath())))
                .contains("新歌词");
        assertThat(Files.readAllBytes(file)).containsExactly((byte) 1, (byte) 2, (byte) 3);
        verify(ffprobe, times(1)).probe(any(Path.class));
        verify(tagReader, times(0)).read(any());
    }

    @Test
    void unchangedSidecarDoesNotProbeOrWriteDatabase() throws Exception {
        Path file = Files.write(sourceDir.resolve("周杰伦-晴天-国语-流行.mkv"), new byte[]{1, 2, 3});
        Files.writeString(sourceDir.resolve("周杰伦-晴天-国语-流行.lrc"), "[00:01.00]歌词\n");
        scanService.scanAll();

        LibraryScanService.ScanResult second = scanService.scanAll();

        assertThat(second.skipped()).isEqualTo(1);
        assertThat(second.updated()).isZero();
        assertThat(second.probeQueued()).isZero();
        assertThat(second.probeCalls()).isZero();
        assertThat(second.hashCalls()).isZero();
        assertThat(second.dbUpdates()).isZero();
        verify(ffprobe, times(1)).probe(any(Path.class));
        verify(songRepository, times(2)).save(any(Song.class));
        verify(songFileRepository, times(2)).save(any(SongFile.class));
    }

    @Test
    void lyricOnlyMtimeChangeWithIdenticalTextUpdatesOnlyTheSnapshot() throws Exception {
        byte[] mediaBytes = {2, 4, 6};
        byte[] lyricBytes = "[00:01.00]歌词\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Path file = Files.write(sourceDir.resolve("周杰伦-晴天-国语-流行.mkv"), mediaBytes);
        Path lyric = Files.write(sourceDir.resolve("周杰伦-晴天-国语-流行.lrc"), lyricBytes);
        scanService.scanAll();

        SongFile indexed = filesByPath.get(file.toString());
        Song song = songsById.get(indexed.getSongId());
        String lyricPath = song.getLyricPath();
        OffsetDateTime oldLyricMtime = indexed.getLyricMtime();
        Files.setLastModifiedTime(lyric, FileTime.fromMillis(
                Files.getLastModifiedTime(lyric).toMillis() + 2_000));

        LibraryScanService.ScanResult refreshed = scanService.scanAll();

        assertThat(refreshed.updated()).isEqualTo(1);
        assertThat(refreshed.probeCalls()).isZero();
        assertThat(refreshed.hashCalls()).isZero();
        assertThat(refreshed.dbUpdates()).isEqualTo(1);
        assertThat(song.getLyricPath()).isEqualTo(lyricPath);
        assertThat(indexed.getLyricMtime()).isNotEqualTo(oldLyricMtime);
        assertThat(Files.readAllBytes(file)).containsExactly(mediaBytes);
        assertThat(Files.readAllBytes(lyric)).containsExactly(lyricBytes);
        assertThat(Files.readString(tempDir.resolve("data").resolve(lyricPath))).contains("歌词");
        verify(ffprobe, times(1)).probe(file);
        verify(songRepository, times(2)).save(any(Song.class));
    }

    @Test
    void lrcIdentityTagChangeUsesMediaProbeToRefreshSongIdentity() throws Exception {
        Path file = Files.write(sourceDir.resolve("周杰伦-晴天-国语-流行.mkv"), new byte[]{1, 2, 3});
        Path lrc = sourceDir.resolve("周杰伦-晴天-国语-流行.lrc");
        Files.writeString(lrc, "[ti:旧歌名]\n[ar:旧歌手]\n[00:01.00]歌词\n");
        scanService.scanAll();

        Files.writeString(lrc, "[ti:新歌名]\n[ar:新歌手]\n[00:01.00]歌词\n");
        Files.setLastModifiedTime(lrc, FileTime.fromMillis(
                Files.getLastModifiedTime(lrc).toMillis() + 2_000));

        LibraryScanService.ScanResult second = scanService.scanAll();

        assertThat(second.probeQueued()).isEqualTo(1);
        assertThat(second.probeCalls()).isEqualTo(1);
        assertThat(second.updated()).isEqualTo(1);
        verify(ffprobe, times(2)).probe(any(Path.class));
    }

    @Test
    void addingLrcRefreshesLyricCacheWithoutReprobingMedia() throws Exception {
        Path file = Files.write(sourceDir.resolve("周杰伦-晴天-国语-流行.mkv"), new byte[]{1, 2, 3});
        scanService.scanAll();

        Path lrc = sourceDir.resolve("周杰伦-晴天-国语-流行.lrc");
        Files.writeString(lrc, "[00:01.00]新增歌词\n");
        Files.setLastModifiedTime(lrc, FileTime.fromMillis(
                Files.getLastModifiedTime(lrc).toMillis() + 2_000));

        LibraryScanService.ScanResult second = scanService.scanAll();

        SongFile indexed = filesByPath.get(file.toString());
        Song song = songsById.get(indexed.getSongId());
        assertThat(second.updated()).isEqualTo(1);
        assertThat(second.probeCalls()).isZero();
        assertThat(song.getLyricPath()).isNotBlank();
        assertThat(song.getLyricSource()).isEqualTo(Song.LYRIC_SOURCE_SIDECAR);
        assertThat(Files.readString(tempDir.resolve("data").resolve(song.getLyricPath())))
                .contains("新增歌词");
        verify(ffprobe, times(1)).probe(any(Path.class));
    }

    @Test
    void lyricChangeForMissingSongKeepsOldSnapshotSoRefreshCanRetry() throws Exception {
        Path file = Files.write(sourceDir.resolve("周杰伦-孤立记录-国语-流行.mkv"), new byte[]{1, 2, 3});
        Path lrc = sourceDir.resolve("周杰伦-孤立记录-国语-流行.lrc");
        Files.writeString(lrc, "[00:01.00]初始歌词\n");
        scanService.scanAll();

        SongFile indexed = filesByPath.get(file.toString());
        Long songId = indexed.getSongId();
        Long originalLyricSize = indexed.getLyricSize();
        OffsetDateTime originalLyricMtime = indexed.getLyricMtime();
        FileTime originalSidecarMtime = Files.getLastModifiedTime(lrc);
        assertThat(songId).isNotNull();
        assertThat(originalLyricSize).isNotNull();
        assertThat(originalLyricMtime).isNotNull();

        songsById.remove(songId);
        songsByFingerprint.entrySet().removeIf(entry -> songId.equals(entry.getValue().getId()));
        Files.writeString(lrc, "[00:01.00]更新后的歌词等待歌曲记录恢复\n");
        Files.setLastModifiedTime(lrc, FileTime.fromMillis(originalSidecarMtime.toMillis() + 2_000));
        org.mockito.Mockito.clearInvocations(songRepository);

        LibraryScanService.ScanResult result = scanService.scanAll();

        assertThat(result.skipped()).isEqualTo(1);
        assertThat(result.updated()).isZero();
        assertThat(result.probeCalls()).isZero();
        assertThat(indexed.getLyricSize()).isEqualTo(originalLyricSize);
        assertThat(indexed.getLyricMtime()).isEqualTo(originalLyricMtime);
        verify(songRepository).findById(songId);
        verify(ffprobe, times(1)).probe(any(Path.class));
    }

    @Test
    void lyricChangeForUnassociatedFileDoesNotProbeOrAdvanceSnapshot() throws Exception {
        Path file = Files.write(sourceDir.resolve("周杰伦-无关联歌曲-国语-流行.mkv"), new byte[]{1, 2, 3});
        Path lrc = sourceDir.resolve("周杰伦-无关联歌曲-国语-流行.lrc");
        Files.writeString(lrc, "[00:01.00]初始歌词\n");
        scanService.scanAll();

        SongFile indexed = filesByPath.get(file.toString());
        Long originalLyricSize = indexed.getLyricSize();
        OffsetDateTime originalLyricMtime = indexed.getLyricMtime();
        FileTime originalSidecarMtime = Files.getLastModifiedTime(lrc);
        indexed.setSongId(null);
        Files.writeString(lrc, "[00:01.00]等待关联歌曲后再刷新的歌词\n");
        Files.setLastModifiedTime(lrc, FileTime.fromMillis(originalSidecarMtime.toMillis() + 2_000));
        org.mockito.Mockito.clearInvocations(songRepository);

        LibraryScanService.ScanResult result = scanService.scanAll();

        assertThat(result.skipped()).isEqualTo(1);
        assertThat(result.updated()).isZero();
        assertThat(result.probeCalls()).isZero();
        assertThat(indexed.getLyricSize()).isEqualTo(originalLyricSize);
        assertThat(indexed.getLyricMtime()).isEqualTo(originalLyricMtime);
        verify(songRepository, never()).findById(anyLong());
        verify(ffprobe, times(1)).probe(any(Path.class));
    }

    @Test
    void deletingSidecarClearsOnlySidecarOwnedLyricWithoutReprobingMedia() throws Exception {
        Path file = Files.write(sourceDir.resolve("周杰伦-晴天-国语-流行.mkv"), new byte[]{1, 2, 3});
        Path lrc = sourceDir.resolve("周杰伦-晴天-国语-流行.lrc");
        Files.writeString(lrc, "[00:01.00]待删除歌词\n");
        scanService.scanAll();
        SongFile indexed = filesByPath.get(file.toString());
        Song song = songsById.get(indexed.getSongId());
        assertThat(song.getLyricSource()).isEqualTo(Song.LYRIC_SOURCE_SIDECAR);

        Files.delete(lrc);

        LibraryScanService.ScanResult second = scanService.scanAll();

        assertThat(second.updated()).isEqualTo(1);
        assertThat(second.probeCalls()).isZero();
        assertThat(song.getLyricPath()).isNull();
        assertThat(song.getLyricType()).isEqualTo(LyricType.NONE);
        assertThat(song.getLyricSource()).isEqualTo(Song.LYRIC_SOURCE_NONE);
        verify(ffprobe, times(1)).probe(any(Path.class));
    }

    @Test
    void mediaChangeAndSidecarDeletionClearOnlyThePreviouslySidecarOwnedLyric() throws Exception {
        byte[] initialBytes = {1, 2, 3};
        byte[] changedBytes = {3, 2, 1};
        Path file = Files.write(sourceDir.resolve("周杰伦-晴天-国语-流行.mkv"), initialBytes);
        Path lrc = sourceDir.resolve("周杰伦-晴天-国语-流行.lrc");
        Files.writeString(lrc, "[00:01.00]旧侧车歌词\n");
        scanService.scanAll();
        SongFile indexed = filesByPath.get(file.toString());
        Song song = songsById.get(indexed.getSongId());
        OffsetDateTime oldMediaMtime = indexed.getMediaMtime();
        assertThat(song.getLyricSource()).isEqualTo(Song.LYRIC_SOURCE_SIDECAR);

        Files.write(file, changedBytes);
        Files.setLastModifiedTime(file, FileTime.fromMillis(oldMediaMtime.toInstant().toEpochMilli() + 2_000));
        Files.delete(lrc);

        LibraryScanService.ScanResult result = scanService.scanAll();

        assertThat(result.updated()).isEqualTo(1);
        assertThat(result.probeCalls()).isEqualTo(1);
        assertThat(indexed.isProbePending()).isFalse();
        assertThat(song.getLyricPath()).isNull();
        assertThat(song.getLyricType()).isEqualTo(LyricType.NONE);
        assertThat(song.getLyricSource()).isEqualTo(Song.LYRIC_SOURCE_NONE);
        assertThat(Files.readAllBytes(file)).containsExactly(changedBytes);
        verify(ffprobe, times(2)).probe(file);
    }

    @Test
    void deletingSidecarDoesNotClearManualLyric() throws Exception {
        Path file = Files.write(sourceDir.resolve("周杰伦-晴天-国语-流行.mkv"), new byte[]{1, 2, 3});
        Path lrc = sourceDir.resolve("周杰伦-晴天-国语-流行.lrc");
        Files.writeString(lrc, "[00:01.00]侧车歌词\n");
        scanService.scanAll();
        SongFile indexed = filesByPath.get(file.toString());
        Song song = songsById.get(indexed.getSongId());
        song.setLyricPath("lyrics/manual.lrc");
        song.setLyricType(LyricType.LINE);
        song.setLyricSource(Song.LYRIC_SOURCE_MANUAL);
        songRepository.save(song);

        Files.delete(lrc);

        LibraryScanService.ScanResult second = scanService.scanAll();

        assertThat(second.probeCalls()).isZero();
        assertThat(song.getLyricPath()).isEqualTo("lyrics/manual.lrc");
        assertThat(song.getLyricType()).isEqualTo(LyricType.LINE);
        assertThat(song.getLyricSource()).isEqualTo(Song.LYRIC_SOURCE_MANUAL);
        verify(ffprobe, times(1)).probe(any(Path.class));
    }

    @Test
    void legacyRowWithoutSidecarBootstrapsSnapshotsWithoutProbingMedia() throws Exception {
        Path file = Files.write(sourceDir.resolve("周杰伦-晴天-国语-流行.mkv"), new byte[]{1, 2, 3});
        scanService.scanAll();

        SongFile indexed = filesByPath.get(file.toString());
        indexed.setMediaMtime(null);
        indexed.setMediaFileIdentity(null);
        indexed.setLyricSize(null);
        indexed.setLyricMtime(null);
        indexed.setLyricFileIdentity(null);
        indexed.setLyricSnapshotVersion(null);

        LibraryScanService.ScanResult second = scanService.scanAll();

        assertThat(second.skipped()).isEqualTo(1);
        assertThat(second.probeQueued()).isZero();
        assertThat(second.probeCalls()).isZero();
        assertThat(second.dbUpdates()).isEqualTo(1);
        assertThat(indexed.getMediaMtime()).isNotNull();
        assertThat(indexed.getLyricSnapshotVersion()).isEqualTo(1);
        verify(ffprobe, times(1)).probe(any(Path.class));
    }

    @Test
    void unchangedRowWithOnlyLegacyLyricVersionBootstrapsSnapshotsWithoutReprobing() throws Exception {
        byte[] sourceBytes = {6, 4, 2};
        Path file = Files.write(sourceDir.resolve("周杰伦-晴天-国语-流行.mkv"), sourceBytes);
        scanService.scanAll();

        SongFile indexed = filesByPath.get(file.toString());
        assertThat(indexed.getMediaMtime()).isNotNull();
        assertThat(indexed.getLyricSnapshotVersion()).isEqualTo(1);
        indexed.setLyricSnapshotVersion(null);

        LibraryScanService.ScanResult refreshed = scanService.scanAll();

        assertThat(refreshed.skipped()).isEqualTo(1);
        assertThat(refreshed.probeCalls()).isZero();
        assertThat(refreshed.dbUpdates()).isEqualTo(1);
        assertThat(indexed.getMediaMtime()).isNotNull();
        assertThat(indexed.getLyricSnapshotVersion()).isEqualTo(1);
        assertThat(Files.readAllBytes(file)).containsExactly(sourceBytes);
        verify(ffprobe, times(1)).probe(file);
    }

    @Test
    void mediaReprobePreservesManualLyricCache() throws Exception {
        Path file = Files.write(sourceDir.resolve("周杰伦-晴天-国语-流行.mkv"), new byte[]{1, 2, 3});
        scanService.scanAll();

        SongFile indexed = filesByPath.get(file.toString());
        Song song = songsById.get(indexed.getSongId());
        song.setLyricPath("lyrics/manual.lrc");
        song.setLyricType(LyricType.LINE);
        song.setLyricSource(Song.LYRIC_SOURCE_MANUAL);
        songRepository.save(song);
        FileTime originalMtime = Files.getLastModifiedTime(file);
        Files.setLastModifiedTime(file, FileTime.fromMillis(originalMtime.toMillis() + 2_000));

        LibraryScanService.ScanResult second = scanService.scanAll();

        assertThat(second.probeCalls()).isEqualTo(1);
        assertThat(song.getLyricPath()).isEqualTo("lyrics/manual.lrc");
        assertThat(song.getLyricType()).isEqualTo(LyricType.LINE);
        assertThat(song.getLyricSource()).isEqualTo(Song.LYRIC_SOURCE_MANUAL);
        verify(ffprobe, times(2)).probe(any(Path.class));
    }

    @Test
    void sameRelativePathSurvivesLibraryRootRemountWithoutReprobe() throws Exception {
        Path file = Files.write(sourceDir.resolve("周杰伦-晴天-国语-流行.mkv"), new byte[]{1, 2, 3});
        scanService.scanAll();
        FileTime originalMtime = Files.getLastModifiedTime(file);

        Path remountedRoot = Files.createDirectory(tempDir.resolve("remounted-library"));
        Path remountedFile = remountedRoot.resolve(file.getFileName());
        Object originalFileKey = Files.readAttributes(file, java.nio.file.attribute.BasicFileAttributes.class)
                .fileKey();
        Files.move(file, remountedFile);
        Files.setLastModifiedTime(remountedFile, originalMtime);
        Object remountedFileKey = Files.readAttributes(remountedFile,
                java.nio.file.attribute.BasicFileAttributes.class).fileKey();
        if (originalFileKey != null && remountedFileKey != null) {
            assertThat(remountedFileKey).isEqualTo(originalFileKey);
        }
        props.setSourceLibraryPath(remountedRoot.toString());

        LibraryScanService.ScanResult second = scanService.scanAll();

        assertThat(second.skipped()).isEqualTo(1);
        assertThat(second.probeQueued()).isZero();
        assertThat(second.probeCalls()).isZero();
        assertThat(second.dbUpdates()).isEqualTo(1);
        assertThat(filesByPath).hasSize(1);
        assertThat(filesByPath.get(remountedFile.toString())).isNotNull();
        assertThat(filesByPath.get(remountedFile.toString()).getRelativePath())
                .isEqualTo("周杰伦-晴天-国语-流行.mkv");
        verify(ffprobe, times(1)).probe(any(Path.class));
    }

    @ParameterizedTest
    @MethodSource("malformedBatchLookupResults")
    void scanDoesNotReuseAnArbitrarySongWhenBatchLookupsAreUnavailableOrMalformed(
            List<SongFile> exactRows, List<SongFile> relativeRows) throws Exception {
        String filename = "周杰伦-同名歌曲-国语-流行.mkv";
        Path oldRoot = Files.createDirectory(tempDir.resolve("old-batch-library"));
        Path oldFile = Files.write(oldRoot.resolve(filename), new byte[]{9, 8, 7});
        Song oldSong = new Song();
        oldSong.setId(801L);
        oldSong.setTitle("不可重绑记录");
        oldSong.setArtist("周杰伦");
        oldSong.setFingerprint("protected-old-fingerprint");
        oldSong.setStatus("ok");
        songsById.put(oldSong.getId(), oldSong);
        songsByFingerprint.put(oldSong.getFingerprint(), oldSong);
        registerUnchangedFile(oldFile, oldSong);

        byte[] currentBytes = {1, 2, 3};
        Path currentFile = Files.write(sourceDir.resolve(filename), currentBytes);
        when(songFileRepository.findByFileRoleAndFilePathIn(
                eq(LibraryModePolicy.EXTERNAL_FILE_ROLE), org.mockito.ArgumentMatchers.anyCollection()))
                .thenReturn(exactRows);
        when(songFileRepository.findByFileRoleAndRelativePathIn(
                eq(LibraryModePolicy.EXTERNAL_FILE_ROLE), org.mockito.ArgumentMatchers.anyCollection()))
                .thenReturn(relativeRows);

        LibraryScanService.ScanResult result = scanService.scanAll();

        SongFile current = filesByPath.get(currentFile.toString());
        assertThat(result.scanned()).isEqualTo(1);
        assertThat(result.probeCalls()).isEqualTo(1);
        assertThat(current).isNotNull();
        assertThat(current.getSongId()).isNotEqualTo(oldSong.getId());
        assertThat(filesByPath.get(oldFile.toString()).getSongId()).isEqualTo(oldSong.getId());
        assertThat(Files.readAllBytes(currentFile)).containsExactly(currentBytes);
        verify(ffprobe, times(1)).probe(currentFile);
    }

    private static Stream<Arguments> malformedBatchLookupResults() {
        return Stream.of(
                Arguments.of(null, null),
                Arguments.of(java.util.Arrays.asList(null, new SongFile()),
                        java.util.Arrays.asList(null, new SongFile())));
    }

    @Test
    void ambiguousRelativePathDoesNotRebindToAnArbitrarySongFile() throws Exception {
        String filename = "周杰伦-同名歌曲-国语-流行.mkv";
        Path firstOldRoot = Files.createDirectory(tempDir.resolve("old-library-a"));
        Path secondOldRoot = Files.createDirectory(tempDir.resolve("old-library-b"));
        Path thirdOldRoot = Files.createDirectory(tempDir.resolve("old-library-c"));
        Path firstOldFile = Files.write(firstOldRoot.resolve(filename), new byte[]{1, 2, 3});
        Path secondOldFile = Files.write(secondOldRoot.resolve(filename), new byte[]{1, 2, 3});
        Path thirdOldFile = Files.write(thirdOldRoot.resolve(filename), new byte[]{1, 2, 3});
        Song firstSong = new Song();
        firstSong.setId(501L);
        firstSong.setTitle("同名歌曲 A");
        firstSong.setArtist("周杰伦");
        firstSong.setStatus("ok");
        Song secondSong = new Song();
        secondSong.setId(502L);
        secondSong.setTitle("同名歌曲 B");
        secondSong.setArtist("周杰伦");
        secondSong.setStatus("ok");
        Song thirdSong = new Song();
        thirdSong.setId(503L);
        thirdSong.setTitle("同名歌曲 C");
        thirdSong.setArtist("周杰伦");
        thirdSong.setStatus("ok");
        songsById.put(firstSong.getId(), firstSong);
        songsById.put(secondSong.getId(), secondSong);
        songsById.put(thirdSong.getId(), thirdSong);
        registerUnchangedFile(firstOldFile, firstSong);
        registerUnchangedFile(secondOldFile, secondSong);
        registerUnchangedFile(thirdOldFile, thirdSong);

        Path currentFile = Files.write(sourceDir.resolve(filename), new byte[]{1, 2, 3});

        LibraryScanService.ScanResult result = scanService.scanAll();

        SongFile current = filesByPath.get(currentFile.toString());
        assertThat(result.scanned()).isEqualTo(1);
        assertThat(current).isNotNull();
        assertThat(current.getSongId()).isNotIn(firstSong.getId(), secondSong.getId(), thirdSong.getId());
        assertThat(filesByPath).containsKeys(firstOldFile.toString(), secondOldFile.toString(),
                thirdOldFile.toString());
        assertThat(current.getRelativePath()).isEqualTo(filename);
        verify(ffprobe, times(1)).probe(currentFile);
    }

    @Test
    void uniqueRelativePathReusesTheExistingSongFileAfterLibraryRootMoves() throws Exception {
        String filename = "周杰伦-曲库根目录迁移-国语-流行.mkv";
        Path oldRoot = Files.createDirectory(tempDir.resolve("previous-library-root"));
        byte[] sourceBytes = {8, 3, 1};
        Path oldFile = Files.write(oldRoot.resolve(filename), sourceBytes);
        Song song = new Song();
        song.setId(504L);
        song.setTitle("曲库根目录迁移");
        song.setArtist("周杰伦");
        song.setDurationMs(180_000);
        song.setFingerprint(MediaClassifier.fingerprint("周杰伦", "曲库根目录迁移", 180_000));
        song.setStatus("ok");
        songsById.put(song.getId(), song);
        songsByFingerprint.put(song.getFingerprint(), song);
        registerUnchangedFile(oldFile, song);
        SongFile oldIndexed = filesByPath.get(oldFile.toString());

        Path currentFile = Files.write(sourceDir.resolve(filename), sourceBytes);

        LibraryScanService.ScanResult result = scanService.scanAll();

        SongFile rebound = filesByPath.get(currentFile.toString());
        assertThat(result.scanned()).isEqualTo(1);
        assertThat(rebound).isNotNull();
        assertThat(rebound.getId()).isEqualTo(oldIndexed.getId());
        assertThat(rebound.getSongId()).isEqualTo(song.getId());
        assertThat(rebound.getFilePath()).isEqualTo(currentFile.toString());
        assertThat(rebound.getRelativePath()).isEqualTo(filename);
        assertThat(rebound.isValid()).isTrue();
        assertThat(filesByPath).doesNotContainKey(oldFile.toString());
        assertThat(Files.readAllBytes(currentFile)).containsExactly(sourceBytes);
    }

    @Test
    void unsafePersistedPendingPathsRemainPendingAndAreNeverProbed() throws Exception {
        Path outsideRoot = Files.createDirectory(tempDir.resolve("outside-library"));
        Path outsideMedia = Files.write(outsideRoot.resolve("周杰伦-范围外.mkv"), new byte[]{1, 2, 3});
        Song outsideSong = new Song();
        outsideSong.setId(601L);
        outsideSong.setTitle("范围外");
        outsideSong.setArtist("周杰伦");
        outsideSong.setStatus("ok");
        songsById.put(outsideSong.getId(), outsideSong);

        SongFile outsidePending = new SongFile();
        outsidePending.setId(701L);
        outsidePending.setSongId(outsideSong.getId());
        outsidePending.setFilePath(outsideMedia.toAbsolutePath().toString());
        outsidePending.setRelativePath(outsideMedia.getFileName().toString());
        outsidePending.setFileRole(LibraryModePolicy.EXTERNAL_FILE_ROLE);
        outsidePending.setValid(true);
        outsidePending.setProbePending(true);
        filesByPath.put(outsidePending.getFilePath(), outsidePending);

        Song invalidPathSong = new Song();
        invalidPathSong.setId(602L);
        invalidPathSong.setTitle("无效路径");
        invalidPathSong.setArtist("周杰伦");
        invalidPathSong.setStatus("ok");
        songsById.put(invalidPathSong.getId(), invalidPathSong);

        SongFile invalidPathPending = new SongFile();
        invalidPathPending.setId(702L);
        invalidPathPending.setSongId(invalidPathSong.getId());
        invalidPathPending.setFilePath("invalid\u0000path.mkv");
        invalidPathPending.setRelativePath("invalid-path.mkv");
        invalidPathPending.setFileRole(LibraryModePolicy.EXTERNAL_FILE_ROLE);
        invalidPathPending.setValid(true);
        invalidPathPending.setProbePending(true);
        filesByPath.put(invalidPathPending.getFilePath(), invalidPathPending);

        scanService.scanAll();

        assertThat(outsidePending.isProbePending()).isTrue();
        assertThat(invalidPathPending.isProbePending()).isTrue();
        assertThat(filesByPath).containsKeys(outsidePending.getFilePath(), invalidPathPending.getFilePath());
        verify(ffprobe, never()).probe(any(Path.class));
    }

    @Test
    void newFileOnlyQueuesTheNewFile() throws Exception {
        Files.write(sourceDir.resolve("周杰伦-晴天-国语-流行.mkv"), new byte[]{1, 2, 3});
        scanService.scanAll();
        Files.write(sourceDir.resolve("Beyond-海阔天空-粤语-摇滚.mkv"), new byte[]{4, 5, 6});

        LibraryScanService.ScanResult second = scanService.scanAll();

        assertThat(second.scanned()).isEqualTo(2);
        assertThat(second.skipped()).isEqualTo(1);
        assertThat(second.added()).isEqualTo(1);
        assertThat(second.probeQueued()).isEqualTo(1);
        assertThat(second.probeCalls()).isEqualTo(1);
        verify(ffprobe, times(2)).probe(any(Path.class));
    }

    @Test
    void sizeChangeWithSameMtimeReprobesTheFile() throws Exception {
        Path file = Files.write(sourceDir.resolve("周杰伦-晴天-国语-流行.mkv"), new byte[]{1, 2, 3});
        scanService.scanAll();
        FileTime originalMtime = Files.getLastModifiedTime(file);
        Files.write(file, new byte[]{1, 2, 3, 4});
        Files.setLastModifiedTime(file, originalMtime);

        LibraryScanService.ScanResult second = scanService.scanAll();

        assertThat(second.updated()).isEqualTo(1);
        assertThat(second.probeQueued()).isEqualTo(1);
        assertThat(second.probeCalls()).isEqualTo(1);
        verify(ffprobe, times(2)).probe(any(Path.class));
    }

    @Test
    void mtimeChangeWithSameSizeReprobesTheFile() throws Exception {
        Path file = Files.write(sourceDir.resolve("周杰伦-晴天-国语-流行.mkv"), new byte[]{1, 2, 3});
        scanService.scanAll();
        FileTime originalMtime = Files.getLastModifiedTime(file);
        Files.setLastModifiedTime(file, FileTime.fromMillis(originalMtime.toMillis() + 2_000));

        LibraryScanService.ScanResult second = scanService.scanAll();

        assertThat(second.updated()).isEqualTo(1);
        assertThat(second.probeQueued()).isEqualTo(1);
        assertThat(second.probeCalls()).isEqualTo(1);
        verify(ffprobe, times(2)).probe(any(Path.class));
    }

    @Test
    void replacementWithSamePathSizeAndMtimeReprobesWhenFileIdentityChanges() throws Exception {
        Path file = Files.write(sourceDir.resolve("周杰伦-晴天-国语-流行.mkv"), new byte[]{1, 2, 3});
        scanService.scanAll();
        SongFile indexed = filesByPath.get(file.toString());
        String originalIdentity = indexed.getFileIdentity();
        boolean nativeIdentityAvailable = indexed.getMediaFileIdentity() != null;
        if (!nativeIdentityAvailable) {
            // Model a persisted native identity when this test filesystem does
            // not expose fileKey(), then verify losing it invalidates the snapshot.
            indexed.setMediaFileIdentity("persisted-native-file-key");
            indexed.setFileIdentity("persisted-native-file-key|null");
            originalIdentity = indexed.getFileIdentity();
        }
        BasicFileAttributes originalAttributes = Files.readAttributes(file, BasicFileAttributes.class);
        Path replacement = file.resolveSibling("same-metadata-replacement.tmp");
        Files.write(replacement, new byte[]{4, 5, 6});
        Files.delete(file);
        Files.move(replacement, file);
        Files.setLastModifiedTime(file, originalAttributes.lastModifiedTime());
        String replacementIdentity = fileIdentity(file);

        assertThat(originalIdentity)
                .as("the scan must compare against an identity stored before replacement")
                .isNotNull();
        if (nativeIdentityAvailable) {
            assertThat(replacementIdentity)
                    .as("replacing the media must result in a different file identity")
                    .isNotNull()
                    .isNotEqualTo(originalIdentity);
        } else {
            assertThat(replacementIdentity)
                    .as("this filesystem must report the unavailable native file identity")
                    .isNull();
        }

        LibraryScanService.ScanResult second = scanService.scanAll();

        assertThat(second.updated()).isEqualTo(1);
        assertThat(second.probeQueued()).isEqualTo(1);
        assertThat(second.probeCalls()).isEqualTo(1);
        verify(ffprobe, times(2)).probe(any(Path.class));
    }

    @ParameterizedTest
    @MethodSource("nativeFileIdentityCases")
    void scanIndexesAndProbesFilesystemsWithNativeFileIdentityVariants(
            String mediaFileKey, String lyricFileKey, String expectedLegacyIdentity) throws Exception {
        byte[] sourceBytes = {5, 4, 3, 2};
        Path file = Files.write(sourceDir.resolve("周杰伦-文件系统无原生身份-国语-流行.mkv"), sourceBytes);
        Path lyric = sourceDir.resolve("周杰伦-文件系统无原生身份-国语-流行.lrc");
        byte[] lyricBytes = "[00:01.00]无原生身份歌词\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(lyric, lyricBytes);
        BasicFileAttributes realAttributes = Files.readAttributes(file, BasicFileAttributes.class);
        BasicFileAttributes mediaAttributes = org.mockito.Mockito.mock(BasicFileAttributes.class);
        when(mediaAttributes.isRegularFile()).thenReturn(true);
        when(mediaAttributes.size()).thenReturn(realAttributes.size());
        when(mediaAttributes.lastModifiedTime()).thenReturn(realAttributes.lastModifiedTime());
        when(mediaAttributes.fileKey()).thenReturn(mediaFileKey);
        BasicFileAttributes lyricAttributes = org.mockito.Mockito.mock(BasicFileAttributes.class);
        when(lyricAttributes.isRegularFile()).thenReturn(true);
        when(lyricAttributes.size()).thenReturn((long) lyricBytes.length);
        when(lyricAttributes.lastModifiedTime()).thenReturn(Files.getLastModifiedTime(lyric));
        when(lyricAttributes.fileKey()).thenReturn(lyricFileKey);
        Path normalizedRoot = sourceDir.toAbsolutePath().normalize();
        BasicFileAttributes rootAttributes = Files.readAttributes(sourceDir, BasicFileAttributes.class);
        when(ffprobe.probe(file)).thenAnswer(invocation -> {
            SongFile fastIndexed = filesByPath.get(file.toString());
            assertThat(fastIndexed).isNotNull();
            assertThat(fastIndexed.getFileIdentity()).isEqualTo(expectedLegacyIdentity);
            assertThat(fastIndexed.getMediaFileIdentity()).isEqualTo(mediaFileKey);
            return probe();
        });

        try (MockedStatic<Files> fileSystem = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            fileSystem.when(() -> Files.walkFileTree(eq(normalizedRoot), any(FileVisitor.class)))
                    .thenAnswer(invocation -> {
                        @SuppressWarnings("unchecked")
                        FileVisitor<Path> visitor = invocation.getArgument(1);
                        visitor.preVisitDirectory(normalizedRoot, rootAttributes);
                        visitor.visitFile(file, mediaAttributes);
                        visitor.postVisitDirectory(normalizedRoot, null);
                        return normalizedRoot;
                    });
            fileSystem.when(() -> Files.readAttributes(lyric, BasicFileAttributes.class,
                    java.nio.file.LinkOption.NOFOLLOW_LINKS)).thenReturn(lyricAttributes);

            LibraryScanService.ScanResult result = scanService.scanAll();

            SongFile indexed = filesByPath.get(file.toString());
            Song song = indexed == null ? null : songsById.get(indexed.getSongId());
            assertThat(result.added()).isEqualTo(1);
            assertThat(result.probeCalls()).isEqualTo(1);
            assertThat(indexed).isNotNull();
            assertThat(indexed.isValid()).isTrue();
            assertThat(indexed.isProbePending()).isFalse();
            String persistedMediaIdentity = realAttributes.fileKey() == null
                    ? null : String.valueOf(realAttributes.fileKey());
            String persistedLegacyIdentity = persistedMediaIdentity == null && lyricFileKey == null
                    ? null : String.valueOf(persistedMediaIdentity) + "|" + String.valueOf(lyricFileKey);
            assertThat(indexed.getFileIdentity()).isEqualTo(persistedLegacyIdentity);
            assertThat(indexed.getMediaFileIdentity()).isEqualTo(persistedMediaIdentity);
            assertThat(indexed.getLyricFileIdentity()).isEqualTo(lyricFileKey);
            assertThat(indexed.getLyricSize()).isEqualTo((long) lyricBytes.length);
            assertThat(song).isNotNull();
            assertThat(song.getLyricSource()).isEqualTo(Song.LYRIC_SOURCE_SIDECAR);
            assertThat(Files.readAllBytes(file)).containsExactly(sourceBytes);
            assertThat(Files.readAllBytes(lyric)).containsExactly(lyricBytes);
            verify(ffprobe).probe(file);
        }
    }

    private static Stream<Arguments> nativeFileIdentityCases() {
        return Stream.of(
                Arguments.of(null, null, null),
                Arguments.of("media-native-key", null, "media-native-key|null"),
                Arguments.of(null, "lyric-native-key", "null|lyric-native-key"),
                Arguments.of("media-native-key", "lyric-native-key", "media-native-key|lyric-native-key"));
    }

    @Test
    void disappearedFileIsMarkedInvalidWithoutProbingOrDeletingDatabaseRecord() throws Exception {
        Path file = Files.write(sourceDir.resolve("周杰伦-晴天-国语-流行.mkv"), new byte[]{1, 2, 3});
        scanService.scanAll();
        SongFile indexed = filesByPath.get(file.toString());
        Files.delete(file);

        LibraryScanService.ScanResult second = scanService.scanAll();

        assertThat(second.scanned()).isZero();
        assertThat(second.missing()).isEqualTo(1);
        assertThat(second.probeCalls()).isZero();
        assertThat(indexed.isValid()).isFalse();
        assertThat(filesByPath).containsKey(file.toString());
        verify(ffprobe, times(1)).probe(any(Path.class));
    }

    @Test
    void scanDoesNotMarkFilesMissingWhenInitialValidFileCountCannotBeRead() throws Exception {
        Path file = Files.write(sourceDir.resolve("周杰伦-晴天-国语-流行.mkv"), new byte[]{1, 2, 3});
        scanService.scanAll();
        SongFile indexed = filesByPath.get(file.toString());
        Files.delete(file);
        when(songFileRepository.countValidByFileRoleAndRoot(anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("database count unavailable"));

        LibraryScanService.ScanResult second = scanService.scanAll();

        assertThat(second.missing()).isZero();
        assertThat(indexed.isValid()).isTrue();
        assertThat(indexed.isProbePending()).isFalse();
        verify(ffprobe, times(1)).probe(any(Path.class));
    }

    @Test
    void scanCountsDirectChildrenWhenTheConfiguredLibraryIsAVolumeRoot() {
        Path volumeRoot = sourceDir.getRoot();
        assertThat(volumeRoot).isNotNull();
        Path normalizedRoot = volumeRoot.toAbsolutePath().normalize();
        props.setSourceLibraryPath(normalizedRoot.toString());

        try (MockedStatic<Files> fileSystem = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            fileSystem.when(() -> Files.walkFileTree(eq(normalizedRoot), any(FileVisitor.class)))
                    .thenReturn(normalizedRoot);

            LibraryScanService.ScanResult result = scanService.scanAll();

            assertThat(result.scanned()).isZero();
            assertThat(result.missing()).isZero();
            verify(songFileRepository).countValidByFileRoleAndRoot(
                    LibraryModePolicy.EXTERNAL_FILE_ROLE,
                    normalizedRoot.toString(), normalizedRoot.toString(), normalizedRoot.toString());
            verify(ffprobe, never()).probe(any(Path.class));
        }
    }

    @Test
    void unreadableDirectoryLyricIndexFallsBackToSidecarLookupAndDoesNotMarkFilesMissing() throws Exception {
        byte[] mediaBytes = {4, 2, 4};
        byte[] lyricBytes = "[00:01.00]目录索引失败后的歌词回退\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Path visibleMedia = Files.write(sourceDir.resolve("周杰伦-歌词回退-国语-流行.mkv"), mediaBytes);
        Path sidecar = Files.write(sourceDir.resolve("周杰伦-歌词回退-国语-流行.lrc"), lyricBytes);
        Path missingMedia = sourceDir.resolve("暂时不可见-歌曲.mkv");
        SongFile indexed = new SongFile();
        indexed.setId(701L);
        indexed.setSongId(601L);
        indexed.setFilePath(missingMedia.toString());
        indexed.setRelativePath(missingMedia.getFileName().toString());
        indexed.setFileRole(LibraryModePolicy.EXTERNAL_FILE_ROLE);
        indexed.setValid(true);
        indexed.setProbePending(false);
        filesByPath.put(indexed.getFilePath(), indexed);
        Path normalizedRoot = sourceDir.toAbsolutePath().normalize();

        try (MockedStatic<Files> fileSystem = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            fileSystem.when(() -> Files.newDirectoryStream(normalizedRoot, "*.lrc"))
                    .thenThrow(new IOException("synthetic lyric directory access failure"));

            LibraryScanService.ScanResult result = scanService.scanAll();

            assertThat(result.scanned()).isEqualTo(1);
            assertThat(result.missing()).isZero();
            assertThat(indexed.isValid()).isTrue();
            assertThat(indexed.isProbePending()).isFalse();
            SongFile visible = filesByPath.get(visibleMedia.toString());
            assertThat(visible).isNotNull();
            assertThat(visible.isProbePending()).isFalse();
            Song song = songsById.get(visible.getSongId());
            assertThat(song).isNotNull();
            assertThat(song.getLyricSource()).isEqualTo(Song.LYRIC_SOURCE_SIDECAR);
            assertThat(Files.readString(tempDir.resolve("data").resolve(song.getLyricPath())))
                    .contains("目录索引失败后的歌词回退");
            assertThat(Files.readAllBytes(visibleMedia)).containsExactly(mediaBytes);
            assertThat(Files.readAllBytes(sidecar)).containsExactly(lyricBytes);
            verify(ffprobe).probe(visibleMedia);
        }
    }

    @Test
    void managedImportDoesNotPersistAnUnreadableSidecarSnapshot() throws Exception {
        byte[] mediaBytes = {6, 2, 6};
        byte[] lyricBytes = "[00:01.00]暂不可读歌词\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Path managedRoot = Files.createDirectory(tempDir.resolve("managed-unreadable-sidecar"));
        Path media = Files.write(managedRoot.resolve("周杰伦-歌词快照权限错误-国语-流行.mkv"), mediaBytes);
        Path sidecar = Files.write(managedRoot.resolve("周杰伦-歌词快照权限错误-国语-流行.lrc"), lyricBytes);
        byte[] sourceBytes = {3, 6, 9};
        Path source = Files.write(tempDir.resolve("unreadable-sidecar-source.mkv"), sourceBytes);
        props.setLibraryMode(LibraryMode.MANAGED);
        props.setKtvLibraryPath(managedRoot.toString());

        try (MockedStatic<Files> fileSystem = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            fileSystem.when(() -> Files.readAttributes(sidecar, BasicFileAttributes.class,
                            LinkOption.NOFOLLOW_LINKS))
                    .thenThrow(new AccessDeniedException(sidecar.toString()));

            LibraryScanService.IngestResult result = scanService.ingestLibraryFile(
                    media, source, "source-md5", "output-md5", false, probe());

            assertThat(result.imported()).isTrue();
            SongFile indexed = filesByPath.get(media.toString());
            assertThat(indexed).isNotNull();
            assertThat(indexed.isProbePending()).isFalse();
            assertThat(indexed.getLyricSnapshotVersion()).isNull();
            assertThat(indexed.getLyricSize()).isNull();
            Song song = songsById.get(indexed.getSongId());
            assertThat(song).isNotNull();
            assertThat(song.getLyricSource()).isEqualTo(Song.LYRIC_SOURCE_UNKNOWN);
            assertThat(song.getLyricPath()).isNull();
            assertThat(Files.readAllBytes(media)).containsExactly(mediaBytes);
            assertThat(Files.readAllBytes(sidecar)).containsExactly(lyricBytes);
            assertThat(Files.readAllBytes(source)).containsExactly(sourceBytes);
            verify(ffprobe, never()).probe(media);
        }
    }

    @Test
    void managedImportReusesExistingSongFileByIdWhenPathLookupMisses() throws Exception {
        Path managedRoot = Files.createDirectory(tempDir.resolve("managed-id-fallback"));
        byte[] mediaBytes = {5, 4, 3, 2};
        Path media = Files.write(managedRoot.resolve("周杰伦-路径查询回退-国语-流行.mkv"), mediaBytes);
        byte[] sourceBytes = {1, 3, 5};
        Path source = Files.write(tempDir.resolve("id-fallback-source.mkv"), sourceBytes);
        props.setLibraryMode(LibraryMode.MANAGED);
        props.setKtvLibraryPath(managedRoot.toString());

        Song song = new Song();
        song.setId(824L);
        song.setTitle("路径查询回退");
        song.setArtist("周杰伦");
        song.setMediaType(MediaClassifier.PENDING_PROBE);
        song.setDurationMs(0);
        song.setFingerprint("fast-index-824");
        song.setStatus("ok");
        songsById.put(song.getId(), song);
        songsByFingerprint.put(song.getFingerprint(), song);

        SongFile existing = new SongFile();
        existing.setId(924L);
        existing.setSongId(song.getId());
        existing.setFilePath(media.toString());
        existing.setRelativePath(media.getFileName().toString());
        existing.setFileRole("LIBRARY");
        existing.setValid(true);
        filesByPath.put(media.toString(), existing);
        when(songFileRepository.findByFileRoleAndFilePath("LIBRARY", media.toString()))
                .thenReturn(Optional.empty());

        LibraryScanService.IngestResult result = scanService.ingestLibraryFile(
                media, source, "source-md5", "output-md5", false, probe());

        assertThat(result.imported()).isTrue();
        assertThat(result.songId()).isEqualTo(song.getId());
        assertThat(result.songFileId()).isEqualTo(existing.getId());
        assertThat(filesByPath).hasSize(1).containsEntry(media.toString(), existing);
        assertThat(existing.getRelativePath()).isEqualTo(media.getFileName().toString());
        assertThat(existing.isProbePending()).isFalse();
        assertThat(Files.readAllBytes(media)).containsExactly(mediaBytes);
        assertThat(Files.readAllBytes(source)).containsExactly(sourceBytes);
        verify(songFileRepository).findById(existing.getId());
        verify(ffprobe, never()).probe(media);
    }

    @Test
    void managedImportRejectsOutputOutsideActiveLibraryBeforeWritingRows() throws Exception {
        props.setLibraryMode(LibraryMode.MANAGED);
        Path managedRoot = Files.createDirectory(tempDir.resolve("managed-import-root"));
        props.setKtvLibraryPath(managedRoot.toString());
        byte[] outsideBytes = {3, 1, 4, 1, 5};
        Path outsideOutput = Files.write(tempDir.resolve("outside-managed-output.mkv"), outsideBytes);

        assertThatThrownBy(() -> scanService.ingestLibraryFile(
                outsideOutput, null, null, null, false, singleTrackProbe()))
                .isInstanceOfSatisfying(ApiException.class, failure ->
                        assertThat(failure.getCode()).isEqualTo("FILE_PATH_OUTSIDE_LIBRARY"));

        verify(songRepository, never()).save(any(Song.class));
        verify(songFileRepository, never()).save(any(SongFile.class));
        verify(ffprobe, never()).probe(any(Path.class));
        assertThat(Files.readAllBytes(outsideOutput)).containsExactly(outsideBytes);
    }

    @ParameterizedTest(name = "managed direct ingest rejects {0}")
    @MethodSource("managedDirectIngestInvalidInputs")
    void managedDirectIngestRejectsInvalidPathsBeforeProbing(
            String description, boolean directory, String expectedCode) throws Exception {
        props.setLibraryMode(LibraryMode.MANAGED);
        Path managedRoot = Files.createDirectory(tempDir.resolve("managed-direct-ingest-root"));
        props.setKtvLibraryPath(managedRoot.toString());
        byte[] outsideBytes = {2, 7, 1, 8};
        Path candidate = directory
                ? Files.createDirectory(managedRoot.resolve("not-a-media-file.mkv"))
                : Files.write(tempDir.resolve("outside-managed-ingest.mkv"), outsideBytes);

        assertThatThrownBy(() -> scanService.ingest(candidate))
                .isInstanceOfSatisfying(ApiException.class, failure ->
                        assertThat(failure.getCode()).isEqualTo(expectedCode));

        verify(songRepository, never()).save(any(Song.class));
        verify(songFileRepository, never()).save(any(SongFile.class));
        verify(ffprobe, never()).probe(any(Path.class));
        assertThat(Files.isDirectory(candidate)).isEqualTo(directory);
        if (!directory) assertThat(Files.readAllBytes(candidate)).containsExactly(outsideBytes);
    }

    private static Stream<Arguments> managedDirectIngestInvalidInputs() {
        return Stream.of(
                Arguments.of("file outside the active root", false, "FILE_PATH_OUTSIDE_LIBRARY"),
                Arguments.of("directory with a media extension", true, "FILE_NOT_FOUND"));
    }

    @Test
    void scanKeepsUnreadableSidecarSnapshotUnchangedAndRetriesAfterItBecomesReadable() throws Exception {
        byte[] mediaBytes = {2, 4, 6};
        byte[] lyricBytes = "[00:01.00]权限恢复后读取歌词\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Path media = Files.write(sourceDir.resolve("周杰伦-歌词权限恢复-国语-流行.mkv"), mediaBytes);
        Path sidecar = Files.write(sourceDir.resolve("周杰伦-歌词权限恢复-国语-流行.lrc"), lyricBytes);
        when(ffprobe.probe(media))
                .thenThrow(new MediaProbeException("synthetic first probe failure"))
                .thenReturn(probe());

        try (MockedStatic<Files> fileSystem = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            fileSystem.when(() -> Files.readAttributes(sidecar, BasicFileAttributes.class,
                            LinkOption.NOFOLLOW_LINKS))
                    .thenThrow(new AccessDeniedException(sidecar.toString()));

            LibraryScanService.ScanResult first = scanService.scanAll();

            assertThat(first.fastIndexed()).isEqualTo(1);
            assertThat(first.probeCalls()).isEqualTo(1);
            SongFile pending = filesByPath.get(media.toString());
            assertThat(pending).isNotNull();
            assertThat(pending.isValid()).isTrue();
            assertThat(pending.isProbePending()).isTrue();
            assertThat(pending.getLyricSnapshotVersion()).isNull();
            assertThat(pending.getLyricSize()).isNull();
            assertThat(Files.readAllBytes(media)).containsExactly(mediaBytes);
            assertThat(Files.readAllBytes(sidecar)).containsExactly(lyricBytes);
        }

        LibraryScanService.ScanResult retried = scanService.scanAll();

        SongFile indexed = filesByPath.get(media.toString());
        Song song = songsById.get(indexed.getSongId());
        assertThat(retried.probeCalls()).isEqualTo(1);
        assertThat(indexed.isProbePending()).isFalse();
        assertThat(indexed.getLyricSnapshotVersion()).isEqualTo(1);
        assertThat(indexed.getLyricSize()).isEqualTo((long) lyricBytes.length);
        assertThat(song.getLyricSource()).isEqualTo(Song.LYRIC_SOURCE_SIDECAR);
        assertThat(Files.readString(tempDir.resolve("data").resolve(song.getLyricPath())))
                .contains("权限恢复后读取歌词");
        assertThat(Files.readAllBytes(media)).containsExactly(mediaBytes);
        assertThat(Files.readAllBytes(sidecar)).containsExactly(lyricBytes);
        verify(ffprobe, times(2)).probe(media);
    }

    @Test
    void symbolicLinkMediaIsSkippedBeforeIndexingOrProbing() throws Exception {
        Path mediaFile = Files.write(sourceDir.resolve("链接媒体.mkv"), new byte[]{1, 2, 3});
        BasicFileAttributes rootAttributes = Files.readAttributes(sourceDir, BasicFileAttributes.class);
        BasicFileAttributes mediaAttributes = Files.readAttributes(mediaFile, BasicFileAttributes.class);
        Path normalizedRoot = sourceDir.toAbsolutePath().normalize();

        try (MockedStatic<Files> fileSystem = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            fileSystem.when(() -> Files.isSymbolicLink(mediaFile)).thenReturn(true);
            fileSystem.when(() -> Files.walkFileTree(eq(normalizedRoot), any(FileVisitor.class)))
                    .thenAnswer(invocation -> {
                        @SuppressWarnings("unchecked")
                        FileVisitor<Path> visitor = invocation.getArgument(1);
                        visitor.preVisitDirectory(normalizedRoot, rootAttributes);
                        visitor.visitFile(mediaFile, mediaAttributes);
                        visitor.postVisitDirectory(normalizedRoot, null);
                        return normalizedRoot;
                    });

            LibraryScanService.ScanResult result = scanService.scanAll();

            assertThat(result.scanned()).isZero();
            assertThat(result.fastIndexed()).isZero();
            assertThat(filesByPath).doesNotContainKey(mediaFile.toString());
            verify(ffprobe, never()).probe(any(Path.class));
        }
    }

    @Test
    void externalScanSkipsSymbolicLinkAndOutOfRootDirectories() throws Exception {
        Path outsideDirectory = Files.createDirectory(tempDir.resolve("outside-source"));
        Path outsideMedia = Files.write(outsideDirectory.resolve("outside.mkv"), new byte[]{1});
        Path linkedDirectory = Files.createDirectory(sourceDir.resolve("linked-subtree"));
        Files.write(linkedDirectory.resolve("linked.mkv"), new byte[]{2});
        BasicFileAttributes rootAttributes = Files.readAttributes(sourceDir, BasicFileAttributes.class);
        BasicFileAttributes outsideAttributes = Files.readAttributes(outsideDirectory, BasicFileAttributes.class);
        BasicFileAttributes linkedAttributes = Files.readAttributes(linkedDirectory, BasicFileAttributes.class);
        Path normalizedRoot = sourceDir.toAbsolutePath().normalize();

        try (MockedStatic<Files> fileSystem = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            fileSystem.when(() -> Files.isSymbolicLink(linkedDirectory)).thenReturn(true);
            fileSystem.when(() -> Files.walkFileTree(eq(normalizedRoot), any(FileVisitor.class)))
                    .thenAnswer(invocation -> {
                        @SuppressWarnings("unchecked")
                        FileVisitor<Path> visitor = invocation.getArgument(1);
                        assertThat(visitor.preVisitDirectory(normalizedRoot, rootAttributes))
                                .isEqualTo(FileVisitResult.CONTINUE);
                        assertThat(visitor.preVisitDirectory(outsideDirectory, outsideAttributes))
                                .isEqualTo(FileVisitResult.SKIP_SUBTREE);
                        assertThat(visitor.preVisitDirectory(linkedDirectory, linkedAttributes))
                                .isEqualTo(FileVisitResult.SKIP_SUBTREE);
                        assertThat(visitor.visitFile(outsideMedia, Files.readAttributes(
                                outsideMedia, BasicFileAttributes.class)))
                                .isEqualTo(FileVisitResult.CONTINUE);
                        visitor.postVisitDirectory(normalizedRoot, null);
                        return normalizedRoot;
                    });

            LibraryScanService.ScanResult result = scanService.scanAll();

            assertThat(result.scanned()).isZero();
            assertThat(result.fastIndexed()).isZero();
            assertThat(filesByPath).doesNotContainKey(outsideMedia.toString());
            verify(ffprobe, never()).probe(any(Path.class));
            assertThat(Files.readAllBytes(outsideMedia)).containsExactly((byte) 1);
        }
    }

    @Test
    void externalScanRejectsDirectoryWhoseRealPathEscapesAfterTheLinkCheck() throws Exception {
        Path outsideDirectory = Files.createDirectory(tempDir.resolve("outside-real-path"));
        Path outsideMedia = Files.write(outsideDirectory.resolve("outside.mkv"), new byte[]{7, 8, 9});
        Path linkedDirectory = sourceDir.resolve("linked-outside");
        Files.createSymbolicLink(linkedDirectory, outsideDirectory);
        BasicFileAttributes rootAttributes = Files.readAttributes(sourceDir, BasicFileAttributes.class);
        BasicFileAttributes linkedAttributes = Files.readAttributes(outsideDirectory, BasicFileAttributes.class);
        Path normalizedRoot = sourceDir.toAbsolutePath().normalize();

        try (MockedStatic<Files> fileSystem = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            // Deterministically model a symlink swap between the link check and real-path check.
            fileSystem.when(() -> Files.isSymbolicLink(linkedDirectory)).thenReturn(false);
            fileSystem.when(() -> Files.walkFileTree(eq(normalizedRoot), any(FileVisitor.class)))
                    .thenAnswer(invocation -> {
                        @SuppressWarnings("unchecked")
                        FileVisitor<Path> visitor = invocation.getArgument(1);
                        assertThat(visitor.preVisitDirectory(normalizedRoot, rootAttributes))
                                .isEqualTo(FileVisitResult.CONTINUE);
                        assertThat(visitor.preVisitDirectory(linkedDirectory, linkedAttributes))
                                .isEqualTo(FileVisitResult.SKIP_SUBTREE);
                        visitor.postVisitDirectory(normalizedRoot, null);
                        return normalizedRoot;
                    });

            LibraryScanService.ScanResult result = scanService.scanAll();

            assertThat(result.scanned()).isZero();
            assertThat(result.fastIndexed()).isZero();
            assertThat(result.missing()).isZero();
            assertThat(filesByPath).doesNotContainKey(outsideMedia.toString());
            assertThat(Files.readAllBytes(outsideMedia)).containsExactly((byte) 7, (byte) 8, (byte) 9);
            verify(ffprobe, never()).probe(any(Path.class));
        }
    }

    @Test
    void externalScanSkipsMediaSymlinkWithoutReadingItsTarget() throws Exception {
        byte[] protectedBytes = {6, 2, 6, 4};
        Path protectedTarget = Files.write(tempDir.resolve("protected-media-target.mkv"), protectedBytes);
        Path mediaLink = Files.createSymbolicLink(sourceDir.resolve("linked-media.mkv"), protectedTarget);

        LibraryScanService.ScanResult result = scanService.scanAll();

        assertThat(result.scanned()).isZero();
        assertThat(result.fastIndexed()).isZero();
        assertThat(result.probeQueued()).isZero();
        assertThat(result.probeCalls()).isZero();
        assertThat(result.missing()).isZero();
        assertThat(filesByPath).doesNotContainKey(mediaLink.toString());
        assertThat(Files.isSymbolicLink(mediaLink)).isTrue();
        assertThat(Files.readAllBytes(protectedTarget)).containsExactly(protectedBytes);
        verify(ffprobe, never()).probe(any(Path.class));
    }

    @Test
    void externalScanRejectsMediaReplacedBySymlinkBetweenVisitChecks() throws Exception {
        byte[] protectedBytes = {8, 6, 7};
        Path mediaFile = Files.write(sourceDir.resolve("周杰伦-扫描时替换链接-国语-流行.mkv"),
                new byte[]{1, 2, 3});
        Path protectedTarget = Files.write(tempDir.resolve("scan-protected-target.mkv"), protectedBytes);
        BasicFileAttributes rootAttributes = Files.readAttributes(sourceDir, BasicFileAttributes.class);
        BasicFileAttributes mediaAttributes = Files.readAttributes(mediaFile, BasicFileAttributes.class);
        Path normalizedRoot = sourceDir.toAbsolutePath().normalize();
        AtomicInteger mediaLinkChecks = new AtomicInteger();

        try (MockedStatic<Files> fileSystem = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            fileSystem.when(() -> Files.isSymbolicLink(any(Path.class))).thenAnswer(invocation -> {
                Path checked = invocation.getArgument(0);
                if (!checked.toAbsolutePath().normalize().equals(normalizedRoot.resolve(mediaFile.getFileName()))) {
                    return invocation.callRealMethod();
                }
                if (mediaLinkChecks.incrementAndGet() == 1) {
                    Files.delete(mediaFile);
                    Files.createSymbolicLink(mediaFile, protectedTarget);
                    return false;
                }
                return true;
            });
            fileSystem.when(() -> Files.walkFileTree(eq(normalizedRoot), any(FileVisitor.class)))
                    .thenAnswer(invocation -> {
                        @SuppressWarnings("unchecked")
                        FileVisitor<Path> visitor = invocation.getArgument(1);
                        visitor.preVisitDirectory(normalizedRoot, rootAttributes);
                        visitor.visitFile(mediaFile, mediaAttributes);
                        visitor.postVisitDirectory(normalizedRoot, null);
                        return normalizedRoot;
                    });

            LibraryScanService.ScanResult result = scanService.scanAll();

            assertThat(mediaLinkChecks).hasValue(2);
            assertThat(result.scanned()).isZero();
            assertThat(result.fastIndexed()).isZero();
            assertThat(result.probeQueued()).isZero();
            assertThat(result.probeCalls()).isZero();
            assertThat(result.missing()).isZero();
            assertThat(filesByPath).doesNotContainKey(mediaFile.toString());
            verify(ffprobe, never()).probe(any(Path.class));
        }

        assertThat(Files.isSymbolicLink(mediaFile)).isTrue();
        assertThat(Files.readAllBytes(protectedTarget)).containsExactly(protectedBytes);
    }

    @Test
    void pendingFileReplacedBySymlinkBeforeWorkerProbeIsRejected() throws Exception {
        Path mediaFile = Files.write(sourceDir.resolve("周杰伦-探测前替换为链接-国语-流行.mkv"),
                new byte[]{1, 2, 3});
        byte[] protectedBytes = {9, 8, 7};
        Path outsideTarget = Files.write(tempDir.resolve("protected-outside-target.mkv"), protectedBytes);
        Song song = new Song();
        song.setId(710L);
        song.setTitle("探测前替换为链接");
        song.setArtist("周杰伦");
        song.setStatus("ok");
        songsById.put(song.getId(), song);
        registerUnchangedFile(mediaFile, song);
        SongFile indexed = filesByPath.get(mediaFile.toString());
        indexed.setProbePending(true);

        AtomicBoolean replacedAfterAdmission = new AtomicBoolean();
        try (MockedStatic<Files> fileSystem = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            // Preserve the result of the no-follow admission check, then replace
            // the entry before the worker's independent real-path validation.
            fileSystem.when(() -> Files.isRegularFile(mediaFile, LinkOption.NOFOLLOW_LINKS))
                    .thenAnswer(invocation -> {
                        boolean wasRegular = Files.readAttributes(mediaFile, BasicFileAttributes.class,
                                LinkOption.NOFOLLOW_LINKS).isRegularFile();
                        if (replacedAfterAdmission.compareAndSet(false, true)) {
                            Files.delete(mediaFile);
                            Files.createSymbolicLink(mediaFile, outsideTarget);
                        }
                        return wasRegular;
                    });

            LibraryScanService.ScanResult result = scanService.scanAll();

            assertThat(replacedAfterAdmission).isTrue();
            assertThat(result.probeCalls()).isZero();
            assertThat(result.missing()).isZero();
            assertThat(indexed.isProbePending()).isTrue();
            assertThat(indexed.isValid()).isTrue();
            assertThat(indexed.getSongId()).isEqualTo(song.getId());
            verify(ffprobe, never()).probe(any(Path.class));
        }
        assertThat(Files.isSymbolicLink(mediaFile)).isTrue();
        assertThat(Files.readAllBytes(outsideTarget)).containsExactly(protectedBytes);
    }

    @Test
    void pendingFileReplacedByDirectoryBeforeWorkerProbeIsRejected() throws Exception {
        Path mediaFile = Files.write(sourceDir.resolve("周杰伦-探测前替换为目录-国语-流行.mkv"),
                new byte[]{2, 3, 4});
        Song song = new Song();
        song.setId(812L);
        song.setTitle("探测前替换为目录");
        song.setArtist("周杰伦");
        song.setStatus("ok");
        songsById.put(song.getId(), song);
        registerUnchangedFile(mediaFile, song);
        SongFile indexed = filesByPath.get(mediaFile.toString());
        indexed.setProbePending(true);

        AtomicBoolean replacedAfterAdmission = new AtomicBoolean();
        try (MockedStatic<Files> fileSystem = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            fileSystem.when(() -> Files.isRegularFile(mediaFile, LinkOption.NOFOLLOW_LINKS))
                    .thenAnswer(invocation -> {
                        boolean wasRegular = Files.readAttributes(mediaFile, BasicFileAttributes.class,
                                LinkOption.NOFOLLOW_LINKS).isRegularFile();
                        if (replacedAfterAdmission.compareAndSet(false, true)) {
                            Files.delete(mediaFile);
                            Files.createDirectory(mediaFile);
                        }
                        return wasRegular;
                    });

            LibraryScanService.ScanResult result = scanService.scanAll();

            assertThat(replacedAfterAdmission).isTrue();
            assertThat(result.probeCalls()).isZero();
            assertThat(result.missing()).isZero();
            assertThat(indexed.isValid()).isTrue();
            assertThat(indexed.isProbePending()).isTrue();
            assertThat(Files.isDirectory(mediaFile)).isTrue();
            verify(ffprobe, never()).probe(any(Path.class));
        }
    }

    @Test
    void pendingRowPointingToADirectoryRemainsPendingWithoutProbing() throws Exception {
        Path mediaFile = Files.write(sourceDir.resolve("草蜢-路径变为目录-国语-流行.mkv"), new byte[]{4, 5, 6});
        Song song = new Song();
        song.setId(713L);
        song.setTitle("路径变为目录");
        song.setArtist("草蜢");
        song.setStatus("ok");
        songsById.put(song.getId(), song);
        registerUnchangedFile(mediaFile, song);
        SongFile indexed = filesByPath.get(mediaFile.toString());
        indexed.setProbePending(true);
        Files.delete(mediaFile);
        Files.createDirectory(mediaFile);

        LibraryScanService.ScanResult result = scanService.scanAll();

        assertThat(result.scanned()).isZero();
        assertThat(result.probeCalls()).isZero();
        assertThat(result.missing()).isZero();
        assertThat(Files.isDirectory(mediaFile)).isTrue();
        assertThat(filesByPath).containsEntry(mediaFile.toString(), indexed);
        assertThat(indexed.isValid()).isFalse();
        assertThat(indexed.isProbePending()).isTrue();
        verify(ffprobe, never()).probe(any(Path.class));
    }

    @Test
    void directoryThatDisappearsDuringTraversalIsReportedAsAccessFailure() throws Exception {
        Path disappearingDirectory = Files.createDirectory(sourceDir.resolve("暂时不可达"));
        BasicFileAttributes rootAttributes = Files.readAttributes(sourceDir, BasicFileAttributes.class);
        BasicFileAttributes directoryAttributes = Files.readAttributes(
                disappearingDirectory, BasicFileAttributes.class);
        Files.delete(disappearingDirectory);
        Path normalizedRoot = sourceDir.toAbsolutePath().normalize();

        SongFile indexed = new SongFile();
        indexed.setId(702L);
        indexed.setSongId(602L);
        indexed.setFilePath(disappearingDirectory.resolve("歌曲.mkv").toString());
        indexed.setRelativePath("歌曲.mkv");
        indexed.setFileRole(LibraryModePolicy.EXTERNAL_FILE_ROLE);
        indexed.setValid(true);
        indexed.setProbePending(false);
        filesByPath.put(indexed.getFilePath(), indexed);

        try (MockedStatic<Files> fileSystem = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            fileSystem.when(() -> Files.walkFileTree(eq(normalizedRoot), any(FileVisitor.class)))
                    .thenAnswer(invocation -> {
                        @SuppressWarnings("unchecked")
                        FileVisitor<Path> visitor = invocation.getArgument(1);
                        visitor.preVisitDirectory(normalizedRoot, rootAttributes);
                        FileVisitResult result = visitor.preVisitDirectory(
                                disappearingDirectory, directoryAttributes);
                        assertThat(result).isEqualTo(FileVisitResult.SKIP_SUBTREE);
                        // SKIP_SUBTREE does not produce a postVisitDirectory callback for this node.
                        visitor.postVisitDirectory(normalizedRoot, null);
                        return normalizedRoot;
                    });

            scanService.scanAll();

            LibraryScanService.ScanProgress progress = scanService.getScanProgress();
            assertThat(progress.running()).isFalse();
            assertThat(progress.state()).isEqualTo(LibraryScanService.ScanState.FAILED);
            assertThat(progress.failedPaths()).isEqualTo(1);
            assertThat(progress.errorMessage()).contains("IO异常");
            assertThat(indexed.isValid()).isTrue();
            verify(ffprobe, never()).probe(any(Path.class));
        }
    }

    @Test
    void postVisitDirectoryFailureDoesNotMarkUnvisitedMediaMissing() throws Exception {
        byte[] visitedBytes = {1, 3, 5};
        byte[] unvisitedBytes = {2, 4, 6};
        Path visitedFile = Files.write(sourceDir.resolve("周杰伦-已访问-国语-流行.mkv"), visitedBytes);
        Path unvisitedFile = Files.write(sourceDir.resolve("周杰伦-未访问-国语-流行.mkv"), unvisitedBytes);
        assertThat(scanService.scanAll().scanned()).isEqualTo(2);
        SongFile previouslyIndexedUnvisited = filesByPath.get(unvisitedFile.toString());
        assertThat(previouslyIndexedUnvisited).isNotNull();
        assertThat(previouslyIndexedUnvisited.isValid()).isTrue();

        Path normalizedRoot = sourceDir.toAbsolutePath().normalize();
        BasicFileAttributes rootAttributes = Files.readAttributes(sourceDir, BasicFileAttributes.class);
        BasicFileAttributes visitedAttributes = Files.readAttributes(visitedFile, BasicFileAttributes.class);
        try (MockedStatic<Files> fileSystem = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            fileSystem.when(() -> Files.walkFileTree(eq(normalizedRoot), any(FileVisitor.class)))
                    .thenAnswer(invocation -> {
                        @SuppressWarnings("unchecked")
                        FileVisitor<Path> visitor = invocation.getArgument(1);
                        assertThat(visitor.preVisitDirectory(normalizedRoot, rootAttributes))
                                .isEqualTo(FileVisitResult.CONTINUE);
                        assertThat(visitor.visitFile(visitedFile, visitedAttributes))
                                .isEqualTo(FileVisitResult.CONTINUE);
                        visitor.postVisitDirectory(normalizedRoot,
                                new IOException("synthetic directory read failure"));
                        return normalizedRoot;
                    });

            LibraryScanService.ScanResult partial = scanService.scanAll();

            assertThat(partial.scanned()).isEqualTo(1);
            assertThat(partial.missing()).isZero();
            assertThat(partial.probeCalls()).isZero();
            assertThat(previouslyIndexedUnvisited.isValid()).isTrue();
            assertThat(scanService.getScanProgress().state()).isEqualTo(LibraryScanService.ScanState.PARTIAL);
            assertThat(Files.readAllBytes(visitedFile)).containsExactly(visitedBytes);
            assertThat(Files.readAllBytes(unvisitedFile)).containsExactly(unvisitedBytes);
            verify(ffprobe, times(2)).probe(any(Path.class));
        }
    }

    @Test
    void unchangedFileReactivatesStaleMissingDatabaseStateWithoutReprobing() throws Exception {
        Path file = Files.write(sourceDir.resolve("周杰伦-晴天-国语-流行.mkv"), new byte[]{1, 2, 3});
        when(ffprobe.probe(file)).thenReturn(probe());

        scanService.scanAll();

        SongFile indexed = filesByPath.get(file.toString());
        Song song = songsById.get(indexed.getSongId());
        indexed.setValid(false);
        song.setStatus("file_missing");

        LibraryScanService.ScanResult recovered = scanService.scanAll();

        assertThat(recovered.missing()).isZero();
        assertThat(indexed.isValid()).isTrue();
        assertThat(indexed.isProbePending()).isFalse();
        assertThat(song.getStatus()).isEqualTo("ok");
        verify(ffprobe, times(1)).probe(file);
    }

    @Test
    void failedPreScanCountPreventsFalseMissingReconciliation() throws Exception {
        Path file = Files.write(sourceDir.resolve("周杰伦-晴天-国语-流行.mkv"), new byte[]{1, 2, 3});
        props.getScan().getMissingGuard().setMinimumPreviousFiles(0);
        props.getScan().getMissingGuard().setMinimumSeenRatio(0.5d);
        scanService.scanAll();
        SongFile indexed = filesByPath.get(file.toString());
        assertThat(indexed).isNotNull();
        assertThat(indexed.isValid()).isTrue();

        Files.delete(file);
        when(songFileRepository.countValidByFileRoleAndRoot(
                anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("temporary count failure"));

        LibraryScanService.ScanResult result = scanService.scanAll();

        assertThat(result.missing()).isZero();
        assertThat(indexed.isValid()).isTrue();
        assertThat(filesByPath).containsKey(file.toString());
        verify(ffprobe, times(1)).probe(any(Path.class));
    }

    @Test
    void audioTagWithTitleButBlankArtistFallsBackToFilenameArtist() throws Exception {
        Path file = Files.write(sourceDir.resolve("草蜢-爱-国语-流行.mp3"), new byte[]{1, 2, 3});
        TagInfo tag = new TagInfo();
        tag.setTitle("嵌入歌名");
        tag.setArtist("  ");
        when(tagReader.read(file.toFile())).thenReturn(tag);

        LibraryScanService.ScanResult result = scanService.scanAll();

        assertThat(result.added()).isEqualTo(1);
        Song song = songsById.values().stream().findFirst().orElseThrow();
        assertThat(song.getTitle()).isEqualTo("嵌入歌名");
        assertThat(song.getArtist()).isEqualTo("草蜢");
        assertThat(song.getStatus()).isEqualTo("ok");
    }

    @Test
    void containerAndSidecarIdentityTagsAreUsedWhenHigherPriorityTagsAreAbsent() throws Exception {
        Path containerFile = Files.write(sourceDir.resolve("容器元数据曲目.mp3"), new byte[]{1, 2, 3});
        Path sidecarFile = Files.write(sourceDir.resolve("歌词元数据曲目.mp3"), new byte[]{4, 5, 6});
        Path sidecar = sourceDir.resolve("歌词元数据曲目.lrc");
        Files.writeString(sidecar, "[ti:歌词歌名]\n[ar:歌词歌手]\n[00:01.00]歌词内容\n");

        when(ffprobe.probe(containerFile)).thenReturn(new MediaProbe(180_000, 2, 0, true,
                "1920x1080", List.of(), "h264", "aac", "容器歌名", "容器歌手", null));

        LibraryScanService.ScanResult result = scanService.scanAll();

        assertThat(result.added()).isEqualTo(2);
        Song containerSong = songsById.values().stream()
                .filter(song -> "容器歌名".equals(song.getTitle()))
                .findFirst().orElseThrow();
        assertThat(containerSong.getArtist()).isEqualTo("容器歌手");
        assertThat(containerSong.getMetadataProvenance()).contains("container_tag");
        assertThat(containerSong.getStatus()).isEqualTo("ok");

        Song sidecarSong = songsById.values().stream()
                .filter(song -> "歌词歌名".equals(song.getTitle()))
                .findFirst().orElseThrow();
        assertThat(sidecarSong.getArtist()).isEqualTo("歌词歌手");
        assertThat(sidecarSong.getMetadataProvenance()).contains("lrc_tag");
        assertThat(sidecarSong.getLyricSource()).isEqualTo(Song.LYRIC_SOURCE_SIDECAR);
        assertThat(sidecarSong.getStatus()).isEqualTo("ok");
    }

    @Test
    void blankContainerAndSidecarIdentityTagsFallBackToStructuredFilename() throws Exception {
        Path file = Files.write(sourceDir.resolve("草蜢-文件名歌名-国语-流行.mp3"), new byte[]{1, 2, 3});
        Files.writeString(sourceDir.resolve("草蜢-文件名歌名-国语-流行.lrc"),
                "[ti: ]\n[ar: ]\n[00:01.00]歌词内容\n");
        when(ffprobe.probe(file)).thenReturn(new MediaProbe(180_000, 2, 0, true,
                "1920x1080", List.of(), "h264", "aac", "  ", "  ", null));

        LibraryScanService.ScanResult result = scanService.scanAll();

        Song song = songsById.values().stream().findFirst().orElseThrow();
        assertThat(result.added()).isEqualTo(1);
        assertThat(song.getTitle()).isEqualTo("文件名歌名");
        assertThat(song.getArtist()).isEqualTo("草蜢");
        assertThat(song.getMetadataProvenance()).contains("filename");
        assertThat(song.getLyricSource()).isEqualTo(Song.LYRIC_SOURCE_SIDECAR);
    }

    @Test
    void recognizedSongWithoutLanguageIsQueuedForAiMetadataOptimization() throws Exception {
        Path file = Files.write(sourceDir.resolve("未标语种音频.mp3"), new byte[]{1, 2, 3});
        TagInfo tag = new TagInfo();
        tag.setTitle("有标题歌曲");
        tag.setArtist("已知歌手");
        when(tagReader.read(file.toFile())).thenReturn(tag);

        LibraryScanService.ScanResult result = scanService.scanAll();

        Song song = songsById.values().stream().findFirst().orElseThrow();
        assertThat(result.added()).isEqualTo(1);
        assertThat(song.getStatus()).isEqualTo("ok");
        assertThat(song.getLanguage()).isEqualTo("未知");
        assertThat(song.isNeedsAiOptimization()).isTrue();
    }

    @Test
    void recognizedSongWithoutArtistIsQueuedForAiMetadataOptimization() throws Exception {
        Path file = Files.write(sourceDir.resolve("不可解析歌手格式.mp3"), new byte[]{1, 2, 3});
        TagInfo tag = new TagInfo();
        tag.setTitle("有标题歌曲");
        tag.setLanguage("国语");
        when(tagReader.read(file.toFile())).thenReturn(tag);

        LibraryScanService.ScanResult result = scanService.scanAll();

        Song song = songsById.values().stream().findFirst().orElseThrow();
        assertThat(result.added()).isEqualTo(1);
        assertThat(song.getStatus()).isEqualTo("ok");
        assertThat(song.getArtist()).isEqualTo("未知歌手");
        assertThat(song.isNeedsAiOptimization()).isTrue();
    }

    @Test
    void managedScanPersistsDetectedAndFallbackDualTrackMappings() throws Exception {
        props.setLibraryMode(LibraryMode.MANAGED);
        Path managedRoot = Files.createDirectories(tempDir.resolve("managed"));
        Path detectedFile = Files.write(managedRoot.resolve("王菲-检测零号伴奏轨-国语-流行.mkv"),
                new byte[]{1, 2, 3});
        Path fallbackFile = Files.write(managedRoot.resolve("草蜢-无轨标签伴奏-粤语-流行.mkv"),
                new byte[]{4, 5, 6});
        byte[] existingBytes = {7, 8, 9};
        Path existingFile = Files.write(managedRoot.resolve("王菲-保留既有轨道映射-国语-流行.mkv"),
                existingBytes);
        when(ffprobe.probe(detectedFile)).thenReturn(new MediaProbe(180_000, 2, 0, true,
                "1920x1080", List.of(
                new AudioStreamInfo(0, "伴奏", null, 2, false, false),
                new AudioStreamInfo(1, "原唱", null, 2, false, true)), "h264", "aac"));
        when(ffprobe.probe(fallbackFile)).thenReturn(probe());
        when(ffprobe.probe(existingFile)).thenReturn(new MediaProbe(180_000, 2, 0, true,
                "1920x1080", List.of(
                new AudioStreamInfo(0, "伴奏", null, 2, false, false),
                new AudioStreamInfo(1, "原唱", null, 2, false, true)), "h264", "aac"));

        Song existingSong = new Song();
        existingSong.setId(843L);
        existingSong.setTitle("保留既有轨道映射");
        existingSong.setArtist("王菲");
        existingSong.setDurationMs(180_000);
        existingSong.setMediaType(MediaClassifier.KTV_VIDEO);
        existingSong.setFingerprint(MediaClassifier.fingerprint("王菲", "保留既有轨道映射", 180_000));
        songsById.put(existingSong.getId(), existingSong);
        songsByFingerprint.put(existingSong.getFingerprint(), existingSong);
        registerUnchangedFile(existingFile, existingSong);
        SongFile existing = filesByPath.get(existingFile.toString());
        existing.setId(50L);
        existing.setFileRole("LIBRARY");
        existing.setAudioLayout(AudioLayout.DUAL_TRACK);
        existing.setAccompanimentTrackIndex(0);
        existing.setOriginalTrackIndex(1);
        existing.setProbePending(true);

        LibraryScanService.ScanResult result = scanService.scanAll();

        assertThat(result.added()).isEqualTo(3);
        assertThat(result.updated()).isZero();
        assertThat(result.probeCalls()).isEqualTo(3);
        SongFile detected = filesByPath.get(detectedFile.toString());
        assertThat(detected.getAudioLayout()).isEqualTo(AudioLayout.DUAL_TRACK);
        assertThat(detected.getAccompanimentTrackIndex()).isZero();
        assertThat(detected.getVocalConfidence()).isEqualTo("HIGH");
        assertThat(detected.getOriginalTrackIndex()).isEqualTo(1);

        SongFile fallback = filesByPath.get(fallbackFile.toString());
        assertThat(fallback.getAudioLayout()).isEqualTo(AudioLayout.DUAL_TRACK);
        assertThat(fallback.getAccompanimentTrackIndex()).isEqualTo(1);
        assertThat(fallback.getVocalConfidence()).isEqualTo("LOW");
        assertThat(fallback.getOriginalTrackIndex()).isZero();
        assertThat(existing.getId()).isEqualTo(50L);
        assertThat(existing.getAccompanimentTrackIndex()).isZero();
        assertThat(existing.getOriginalTrackIndex()).isEqualTo(1);
        assertThat(existing.isProbePending()).isFalse();
        assertThat(Files.readAllBytes(existingFile)).containsExactly(existingBytes);
    }

    @Test
    void externalReadOnlyScanUsesConfiguredDefaultAudioLayout() throws Exception {
        Path file = Files.write(sourceDir.resolve("王菲-默认双声道-国语-流行.mkv"), new byte[]{1, 2, 3});
        when(ffprobe.probe(file)).thenReturn(singleTrackProbe());
        SettingService settings = org.mockito.Mockito.mock(SettingService.class);
        when(settings.externalDefaultAudioLayout()).thenReturn(AudioLayout.DUAL_CHANNEL);
        useSettingService(settings);

        LibraryScanService.ScanResult result = scanService.scanAll();

        assertThat(result.added()).isEqualTo(1);
        SongFile indexed = filesByPath.get(file.toString());
        assertThat(indexed.getAudioLayout()).isEqualTo(AudioLayout.DUAL_CHANNEL);
        assertThat(indexed.getAudioLayoutSource()).isEqualTo(AudioLayoutSource.AUTO_DEFAULT);
        verify(settings).externalDefaultAudioLayout();
    }

    @Test
    void externalReadOnlyScanFallsBackToStereoWhenConfiguredDefaultIsMissing() throws Exception {
        Path file = Files.write(sourceDir.resolve("王菲-默认布局缺失-国语-流行.mkv"), new byte[]{1, 2, 3});
        when(ffprobe.probe(file)).thenReturn(singleTrackProbe());
        SettingService settings = org.mockito.Mockito.mock(SettingService.class);
        when(settings.externalDefaultAudioLayout()).thenReturn(null);
        useSettingService(settings);

        LibraryScanService.ScanResult result = scanService.scanAll();

        assertThat(result.added()).isEqualTo(1);
        SongFile indexed = filesByPath.get(file.toString());
        assertThat(indexed.getAudioLayout()).isEqualTo(AudioLayout.NORMAL_STEREO);
        assertThat(indexed.getAudioLayoutSource()).isEqualTo(AudioLayoutSource.AUTO_DEFAULT);
        verify(settings).externalDefaultAudioLayout();
    }

    @Test
    void reprobePreservesManuallyConfirmedDualTrackMapping() throws Exception {
        props.setLibraryMode(LibraryMode.MANAGED);
        Path managedRoot = Files.createDirectories(tempDir.resolve("managed"));
        Path file = Files.write(managedRoot.resolve("王菲-人工伴奏轨-国语-流行.mkv"), new byte[]{1, 2, 3});
        when(ffprobe.probe(file)).thenReturn(new MediaProbe(180_000, 2, 0, true,
                "1920x1080", List.of(
                new AudioStreamInfo(0, "伴奏", null, 2, false, false),
                new AudioStreamInfo(1, "原唱", null, 2, false, true)), "h264", "aac"));
        scanService.scanAll();

        SongFile indexed = filesByPath.get(file.toString());
        indexed.setAudioLayoutSource(AudioLayoutSource.MANUAL);
        indexed.setAudioLayout(AudioLayout.DUAL_TRACK);
        indexed.setVocalTrackIndex(1);
        indexed.setOriginalTrackIndex(0);
        Files.write(file, new byte[]{4, 5, 6, 7});

        LibraryScanService.ScanResult result = scanService.scanAll();

        assertThat(result.updated()).isEqualTo(1);
        assertThat(indexed.getAudioLayout()).isEqualTo(AudioLayout.DUAL_TRACK);
        assertThat(indexed.getAudioLayoutSource()).isEqualTo(AudioLayoutSource.MANUAL);
        assertThat(indexed.getAccompanimentTrackIndex()).isEqualTo(1);
        assertThat(indexed.getOriginalTrackIndex()).isZero();
        assertThat(indexed.getVocalConfidence()).isEqualTo("HIGH");
    }

    @Test
    void manualIdentityFingerprintConflictFailsWithoutRebindingTheFile() throws Exception {
        Path file = Files.write(sourceDir.resolve("周杰伦-晴天-国语-流行.mkv"), new byte[]{1, 2, 3});
        assertThat(scanService.ingest(file)).isEqualTo(LibraryScanService.IngestOutcome.ADDED);
        SongFile indexed = filesByPath.get(file.toString());
        Song original = songsById.get(indexed.getSongId());
        Long originalSongId = original.getId();
        original.setTitle("人工确认标题");
        original.setArtist("人工确认歌手");
        original.setFingerprint(MediaClassifier.fingerprint("人工确认歌手", "人工确认标题", 180_000));
        original.lockMetadata("title");
        original.lockMetadata("artist");
        songRepository.save(original);

        Song conflicting = new Song();
        conflicting.setId(900L);
        conflicting.setTitle("冲突歌曲");
        conflicting.setArtist("冲突歌手");
        conflicting.setFingerprint(original.getFingerprint());
        songsById.put(conflicting.getId(), conflicting);
        songsByFingerprint.put(conflicting.getFingerprint(), conflicting);
        Files.write(file, new byte[]{4, 5, 6, 7});

        assertThatThrownBy(() -> scanService.ingest(file))
                .isInstanceOf(ApiException.class)
                .satisfies(failure -> assertThat(((ApiException) failure).getCode())
                        .isEqualTo("FINGERPRINT_CONFLICT"));

        assertThat(indexed.getSongId()).isEqualTo(originalSongId);
        assertThat(indexed.isProbePending()).isFalse();
        assertThat(songsById).containsEntry(originalSongId, original);
        assertThat(songsById).containsEntry(conflicting.getId(), conflicting);
        verify(songFileRepository, times(1)).save(any(SongFile.class));
    }

    @Test
    void structuredFilenameMetadataRemainsAuthoritativeAfterMediaProbe() throws Exception {
        props.setStructuredFilenameMetadataPreferred(true);
        Path file = Files.write(sourceDir.resolve("韩红-望-国语-流行.mp3"), new byte[]{1, 2, 3});
        TagInfo tag = new TagInfo();
        tag.setTitle("容器测试歌名");
        tag.setArtist("容器测试歌手");
        tag.setLanguage("英语");
        when(tagReader.read(file.toFile())).thenReturn(tag);

        LibraryScanService.ScanResult result = scanService.scanAll();

        assertThat(result.added()).isEqualTo(1);
        Song song = songsById.values().stream().findFirst().orElseThrow();
        assertThat(song.getTitle()).isEqualTo("望");
        assertThat(song.getArtist()).isEqualTo("韩红");
        assertThat(song.getLanguage()).isEqualTo("国语");
        assertThat(song.getTags()).containsExactly("流行");
        assertThat(song.getTitlePy()).isEqualTo("wang");
        assertThat(song.getArtistPy()).isEqualTo("hanhong");
        assertThat(song.getMetadataProvenance()).contains("filename");
    }

    @Test
    void structuredFilenamePreferenceFallsBackToContainerLanguageForTwoFieldNames() throws Exception {
        props.setStructuredFilenameMetadataPreferred(true);
        Path file = Files.write(sourceDir.resolve("草蜢-两段标题.mp3"), new byte[]{1, 2, 3});
        when(tagReader.read(file.toFile())).thenReturn(new TagInfo());
        when(ffprobe.probe(file)).thenReturn(new MediaProbe(180_000, 2, 0, true, "1920x1080",
                List.of(), "h264", "aac", null, null, "zh-HK"));

        LibraryScanService.ScanResult result = scanService.scanAll();

        Song song = songsById.values().stream().findFirst().orElseThrow();
        assertThat(result.added()).isEqualTo(1);
        assertThat(song.getTitle()).isEqualTo("两段标题");
        assertThat(song.getArtist()).isEqualTo("草蜢");
        assertThat(song.getLanguage()).isEqualTo("粤语");
    }

    @Test
    void reprobeKeepsOnlyLockedTitleAndRefreshesUnlockedArtist() throws Exception {
        Path file = Files.write(sourceDir.resolve("草蜢-旧标题-国语-流行.mp3"), new byte[]{1});
        scanService.scanAll();
        Song song = songsById.values().stream().findFirst().orElseThrow();
        song.setTitle("人工标题");
        song.setTitlePy("manual-title-py");
        song.setTitleInit("M");
        song.setMetadataProvenance("manual-title-provenance");
        song.lockMetadata("title");

        TagInfo updatedTag = new TagInfo();
        updatedTag.setTitle("探测标题");
        updatedTag.setArtist("探测歌手");
        when(tagReader.read(file.toFile())).thenReturn(updatedTag);
        Files.write(file, new byte[]{1, 2});

        LibraryScanService.ScanResult result = scanService.scanAll();

        assertThat(result.updated()).isEqualTo(1);
        assertThat(song.getTitle()).isEqualTo("人工标题");
        assertThat(song.getTitlePy()).isEqualTo("manual-title-py");
        assertThat(song.getTitleInit()).isEqualTo("M");
        assertThat(song.getArtist()).isEqualTo("探测歌手");
        assertThat(song.getMetadataProvenance()).isEqualTo("manual-title-provenance");
    }

    @Test
    void reprobeKeepsOnlyLockedArtistAndRefreshesUnlockedTitle() throws Exception {
        Path file = Files.write(sourceDir.resolve("草蜢-旧标题-国语-流行.mp3"), new byte[]{1});
        scanService.scanAll();
        Song song = songsById.values().stream().findFirst().orElseThrow();
        song.setArtist("人工歌手");
        song.setMetadataProvenance("manual-artist-provenance");
        song.lockMetadata("artist");

        TagInfo updatedTag = new TagInfo();
        updatedTag.setTitle("探测标题");
        updatedTag.setArtist("探测歌手");
        when(tagReader.read(file.toFile())).thenReturn(updatedTag);
        Files.write(file, new byte[]{1, 2});

        LibraryScanService.ScanResult result = scanService.scanAll();

        assertThat(result.updated()).isEqualTo(1);
        assertThat(song.getTitle()).isEqualTo("探测标题");
        assertThat(song.getArtist()).isEqualTo("人工歌手");
        assertThat(song.getMetadataProvenance()).isEqualTo("manual-artist-provenance");
    }

    @Test
    void embeddedLanguageAliasesAreStoredAsCanonicalLibraryLanguages() throws Exception {
        List<Map.Entry<String, String>> languageCases = List.of(
                Map.entry("zh-CN", "国语"), Map.entry("zh_CN", "国语"), Map.entry("zhcn", "国语"),
                Map.entry("中文", "国语"), Map.entry("mandarin", "国语"), Map.entry("国语", "国语"),
                Map.entry("普通话", "国语"), Map.entry("yue", "粤语"), Map.entry("zh-HK", "粤语"),
                Map.entry("zh_hk", "粤语"), Map.entry("zhhk", "粤语"), Map.entry("粤语", "粤语"),
                Map.entry("cantonese", "粤语"), Map.entry("nan", "闽南语"), Map.entry("闽南语", "闽南语"),
                Map.entry("台语", "闽南语"), Map.entry("hokkien", "闽南语"), Map.entry("en", "英语"),
                Map.entry("英语", "英语"), Map.entry("英文", "英语"), Map.entry("ENGLISH", "英语"),
                Map.entry("ja", "日语"), Map.entry("日语", "日语"), Map.entry("日文", "日语"),
                Map.entry("japanese", "日语"), Map.entry("ko", "韩语"), Map.entry("韩语", "韩语"),
                Map.entry("韩文", "韩语"), Map.entry("korean", "韩语"), Map.entry("instrumental", "纯音乐"),
                Map.entry("纯音乐", "纯音乐"), Map.entry("music", "纯音乐"), Map.entry("其他", "其他"),
                Map.entry("未知", "未知"), Map.entry("unmapped-language", "其他"));

        for (int index = 0; index < languageCases.size(); index++) {
            Path file = Files.write(sourceDir.resolve("歌手-标签测试" + index + ".mp3"), new byte[]{(byte) index});
            TagInfo tag = new TagInfo();
            tag.setTitle("标签测试" + index);
            tag.setArtist("歌手");
            tag.setLanguage(languageCases.get(index).getKey());
            when(tagReader.read(file.toFile())).thenReturn(tag);
        }

        LibraryScanService.ScanResult result = scanService.scanAll();

        assertThat(result.added()).isEqualTo(languageCases.size());
        for (int index = 0; index < languageCases.size(); index++) {
            String title = "标签测试" + index;
            Song song = songsById.values().stream()
                    .filter(candidate -> title.equals(candidate.getTitle()))
                    .findFirst()
                    .orElseThrow();
            assertThat(song.getLanguage())
                    .as("canonical language for tag %s", languageCases.get(index).getKey())
                    .isEqualTo(languageCases.get(index).getValue());
        }
    }

    @Test
    void containerLanguageIsUsedWhenEmbeddedLanguageTagIsBlank() throws Exception {
        Path file = Files.write(sourceDir.resolve("草蜢-容器语言-国语-流行.mp3"), new byte[]{1, 2, 3});
        TagInfo tag = new TagInfo();
        tag.setLanguage("  ");
        when(tagReader.read(file.toFile())).thenReturn(tag);
        when(ffprobe.probe(file)).thenReturn(new MediaProbe(180_000, 2, 0, true, "1920x1080",
                List.of(), "h264", "aac", null, null, "zh-HK"));

        LibraryScanService.ScanResult result = scanService.scanAll();

        Song song = songsById.values().stream().findFirst().orElseThrow();
        assertThat(result.added()).isEqualTo(1);
        assertThat(song.getLanguage()).isEqualTo("粤语");
    }

    @ParameterizedTest
    @MethodSource("filenameLanguageFallbackCases")
    void filenameLanguageFallbackUsesTheFilenameOrKeepsUnknownWhenAllSourcesAreBlank(
            String filename, String probeLanguage, String expectedLanguage) throws Exception {
        byte[] sourceBytes = {8, 6, 7};
        Path file = Files.write(sourceDir.resolve(filename), sourceBytes);
        when(tagReader.read(file.toFile())).thenReturn(new TagInfo());
        when(ffprobe.probe(file)).thenReturn(new MediaProbe(180_000, 2, 0, true, "1920x1080",
                List.of(), "h264", "aac", null, null, probeLanguage));

        LibraryScanService.ScanResult result = scanService.scanAll();

        Song song = songsById.values().stream().findFirst().orElseThrow();
        assertThat(result.added()).isEqualTo(1);
        assertThat(song.getTitle()).isNotBlank();
        assertThat(song.getArtist()).isEqualTo("草蜢");
        assertThat(song.getLanguage()).isEqualTo(expectedLanguage);
        assertThat(song.getStatus()).isEqualTo("ok");
        SongFile indexed = filesByPath.get(file.toString());
        assertThat(indexed).isNotNull();
        assertThat(indexed.isProbePending()).isFalse();
        assertThat(Files.readAllBytes(file)).containsExactly(sourceBytes);
        verify(ffprobe).probe(file);
    }

    private static Stream<Arguments> filenameLanguageFallbackCases() {
        return Stream.of(
                Arguments.of("草蜢-文件名语言-国语-流行.mp3", null, "国语"),
                Arguments.of("草蜢-文件名语言-国语-流行.mp3", " \t", "国语"),
                Arguments.of("草蜢-无语种标题.mp3", null, "未知"),
                Arguments.of("草蜢-无语种标题.mp3", " \t", "未知"));
    }

    @Test
    void embeddedCoverIsStoredAsIsWhenNoNormalizerIsConfigured() throws Exception {
        Path file = Files.write(sourceDir.resolve("草蜢-原样封面-国语-流行.mp3"), new byte[]{1, 2, 3});
        byte[] embeddedCover = new byte[]{11, 12, 13};
        TagInfo tag = new TagInfo();
        tag.setCoverImage(embeddedCover);
        when(tagReader.read(file.toFile())).thenReturn(tag);

        LibraryScanService.ScanResult result = scanService.scanAll();

        Song song = songsById.values().stream().findFirst().orElseThrow();
        assertThat(result.added()).isEqualTo(1);
        assertThat(song.getCoverPath()).isNotBlank();
        assertThat(Files.readAllBytes(tempDir.resolve("data").resolve(song.getCoverPath())))
                .containsExactly((byte) 11, (byte) 12, (byte) 13);
        verifyNoInteractions(coverImageNormalizer);
    }

    @Test
    void embeddedCoverIsStoredAfterSuccessfulNormalization() throws Exception {
        Path file = Files.write(sourceDir.resolve("草蜢-规范封面-国语-流行.mp3"), new byte[]{1, 2, 3});
        byte[] embeddedCover = new byte[]{21, 22, 23};
        byte[] normalizedCover = new byte[]{31, 32, 33};
        TagInfo tag = new TagInfo();
        tag.setCoverImage(embeddedCover);
        tag.setEmbeddedLyric("[00:01.00]首次探测读取的内嵌歌词\n");
        when(tagReader.read(file.toFile())).thenReturn(tag);
        when(coverImageNormalizer.normalize(embeddedCover)).thenReturn(normalizedCover);
        scanService.setCoverImageNormalizer(coverImageNormalizer);

        LibraryScanService.ScanResult result = scanService.scanAll();

        Song song = songsById.values().stream().findFirst().orElseThrow();
        assertThat(result.added()).isEqualTo(1);
        assertThat(song.getCoverPath()).isNotBlank();
        assertThat(Files.readAllBytes(tempDir.resolve("data").resolve(song.getCoverPath())))
                .containsExactly((byte) 31, (byte) 32, (byte) 33);
        assertThat(song.getLyricSource()).isEqualTo(Song.LYRIC_SOURCE_EMBEDDED);
        assertThat(Files.readString(tempDir.resolve("data").resolve(song.getLyricPath())))
                .contains("首次探测读取的内嵌歌词");
        verify(coverImageNormalizer).normalize(embeddedCover);
    }

    @Test
    void invalidEmbeddedCoverIsSkippedWithoutFailingTheSongImport() throws Exception {
        Path file = Files.write(sourceDir.resolve("草蜢-坏封面-国语-流行.mp3"), new byte[]{1, 2, 3});
        byte[] embeddedCover = new byte[]{41, 42, 43};
        TagInfo tag = new TagInfo();
        tag.setCoverImage(embeddedCover);
        when(tagReader.read(file.toFile())).thenReturn(tag);
        when(coverImageNormalizer.normalize(embeddedCover))
                .thenThrow(new ApiException("INVALID_IMAGE", "synthetic invalid cover"));
        scanService.setCoverImageNormalizer(coverImageNormalizer);

        LibraryScanService.ScanResult result = scanService.scanAll();

        Song song = songsById.values().stream().findFirst().orElseThrow();
        assertThat(result.added()).isEqualTo(1);
        assertThat(song.getCoverPath()).isNull();
        verify(coverImageNormalizer).normalize(embeddedCover);
    }

    @ParameterizedTest
    @MethodSource("aiOptimizationMetadataCases")
    void importedSongNeedsAiReviewOnlyWhenRecognitionLanguageOrArtistRequiresIt(
            String fileName, String title, String artist, String language, boolean expectedReview)
            throws Exception {
        byte[] sourceBytes = {2, 4, 6};
        Path file = Files.write(sourceDir.resolve(fileName), sourceBytes);
        TagInfo tag = new TagInfo();
        tag.setTitle(title);
        tag.setArtist(artist);
        tag.setLanguage(language);
        when(tagReader.read(file.toFile())).thenReturn(tag);

        LibraryScanService.ScanResult result = scanService.scanAll();

        SongFile indexed = filesByPath.get(file.toString());
        Song song = indexed == null ? null : songsById.get(indexed.getSongId());
        assertThat(result.added()).isEqualTo(1);
        assertThat(indexed).isNotNull();
        assertThat(indexed.isProbePending()).isFalse();
        assertThat(song).isNotNull();
        assertThat(song.getTitle()).isEqualTo(title);
        assertThat(song.getArtist()).isEqualTo(artist);
        assertThat(song.getLanguage()).isEqualTo(language);
        assertThat(song.isNeedsAiOptimization()).isEqualTo(expectedReview);
        assertThat(song.getStatus()).isEqualTo("ok");
        assertThat(Files.readAllBytes(file)).containsExactly(sourceBytes);
    }

    private static Stream<Arguments> aiOptimizationMetadataCases() {
        return Stream.of(
                Arguments.of("tagged-valid.mp3", "标签歌名", "王菲", "国语", false),
                Arguments.of("tagged-unknown-language.mp3", "待复核歌名", "王菲", "未知", true),
                Arguments.of("tagged-unknown-artist.mp3", "待复核歌名", "未知歌手", "国语", true)
        );
    }

    @Test
    void publicScanUsesDefaultArtistProvenanceWhenAllArtistSourcesAreBlank() throws Exception {
        byte[] sourceBytes = {8, 6, 7};
        Path file = Files.write(sourceDir.resolve("名称不规范.mp3"), sourceBytes);
        when(tagReader.read(file.toFile())).thenReturn(new TagInfo());

        LibraryScanService.ScanResult result = scanService.scanAll();

        SongFile indexed = filesByPath.get(file.toString());
        Song song = indexed == null ? null : songsById.get(indexed.getSongId());
        assertThat(result.added()).isEqualTo(1);
        assertThat(indexed).isNotNull();
        assertThat(indexed.isProbePending()).isFalse();
        assertThat(song).isNotNull();
        assertThat(song.getTitle()).isEqualTo("名称不规范");
        assertThat(song.getArtist()).isEqualTo("未知歌手");
        assertThat(song.getStatus()).isEqualTo("unrecognized");
        assertThat(song.getMetadataProvenance()).contains("\"artist\":{\"source\":\"default\"}");
        assertThat(Files.readAllBytes(file)).containsExactly(sourceBytes);
        verify(ffprobe).probe(file);
    }

    @ParameterizedTest
    @MethodSource("blankEmbeddedLyricCases")
    void blankEmbeddedLyricsDoNotCreateAnAssetOrFalseLyricState(String embeddedLyric) throws Exception {
        Path file = Files.write(sourceDir.resolve("草蜢-空白内嵌歌词-国语-流行.mp3"), new byte[]{3, 5, 7});
        TagInfo tag = new TagInfo();
        tag.setTitle("空白内嵌歌词");
        tag.setArtist("草蜢");
        tag.setLanguage("国语");
        tag.setEmbeddedLyric(embeddedLyric);
        when(tagReader.read(file.toFile())).thenReturn(tag);

        LibraryScanService.ScanResult result = scanService.scanAll();

        SongFile indexed = filesByPath.get(file.toString());
        Song song = indexed == null ? null : songsById.get(indexed.getSongId());
        assertThat(result.added()).isEqualTo(1);
        assertThat(indexed).isNotNull();
        assertThat(indexed.isProbePending()).isFalse();
        assertThat(song).isNotNull();
        assertThat(song.getLyricPath()).isNull();
        assertThat(song.getLyricType()).isEqualTo(LyricType.NONE);
        assertThat(song.getLyricSource()).isEqualTo(Song.LYRIC_SOURCE_NONE);
        assertThat(Files.exists(tempDir.resolve("data").resolve("lyrics"))).isFalse();
        assertThat(Files.readAllBytes(file)).containsExactly((byte) 3, (byte) 5, (byte) 7);
    }

    private static Stream<Arguments> blankEmbeddedLyricCases() {
        return Stream.of(Arguments.of(""), Arguments.of(" \n\t "));
    }

    @Test
    void oversizedSidecarRemainsUntouchedAndIsNotCachedDuringPublicScan() throws Exception {
        byte[] sourceBytes = {4, 3, 2};
        Path file = Files.write(sourceDir.resolve("草蜢-超大侧车歌词-国语-流行.mp3"), sourceBytes);
        Path sidecar = sourceDir.resolve("草蜢-超大侧车歌词-国语-流行.lrc");
        byte[] sidecarBytes = new byte[AssetWriter.MAX_LYRIC_BYTES + 1];
        sidecarBytes[0] = '[';
        sidecarBytes[1] = '0';
        Files.write(sidecar, sidecarBytes);
        TagInfo tag = new TagInfo();
        tag.setTitle("超大侧车歌词");
        tag.setArtist("草蜢");
        tag.setLanguage("国语");
        when(tagReader.read(file.toFile())).thenReturn(tag);

        LibraryScanService.ScanResult result = scanService.scanAll();

        SongFile indexed = filesByPath.get(file.toString());
        Song song = indexed == null ? null : songsById.get(indexed.getSongId());
        assertThat(result.added()).isEqualTo(1);
        assertThat(indexed).isNotNull();
        assertThat(indexed.isProbePending()).isFalse();
        assertThat(song).isNotNull();
        assertThat(song.getLyricPath()).isNull();
        assertThat(song.getLyricSource()).isEqualTo(Song.LYRIC_SOURCE_UNKNOWN);
        assertThat(Files.readAllBytes(file)).containsExactly(sourceBytes);
        assertThat(Files.readAllBytes(sidecar)).containsExactly(sidecarBytes);
        assertThat(Files.exists(tempDir.resolve("data").resolve("lyrics"))).isFalse();
    }

    @Test
    void duplicateProvisionalRowDoesNotOverwriteTheCanonicalSongsEmbeddedCover() throws Exception {
        Path file = Files.write(sourceDir.resolve("草蜢-重复歌曲-国语-流行.mp3"), new byte[]{1, 2, 3});
        String fingerprint = MediaClassifier.fingerprint("草蜢", "重复歌曲", 180_000);
        Song canonical = new Song();
        canonical.setId(1L);
        canonical.setTitle("重复歌曲");
        canonical.setArtist("草蜢");
        canonical.setDurationMs(180_000);
        canonical.setFingerprint(fingerprint);
        canonical.setCoverPath("covers/canonical.jpg");
        songsById.put(canonical.getId(), canonical);
        songsByFingerprint.put(fingerprint, canonical);
        byte[] canonicalCover = new byte[]{51, 52, 53};
        Path canonicalCoverPath = tempDir.resolve("data").resolve(canonical.getCoverPath());
        Files.createDirectories(canonicalCoverPath.getParent());
        Files.write(canonicalCoverPath, canonicalCover);

        TagInfo duplicateTag = new TagInfo();
        duplicateTag.setCoverImage(new byte[]{61, 62, 63});
        when(tagReader.read(file.toFile())).thenReturn(duplicateTag);
        scanService.setCoverImageNormalizer(coverImageNormalizer);

        LibraryScanService.ScanResult result = scanService.scanAll();

        SongFile indexed = filesByPath.get(file.toString());
        assertThat(result.added()).isEqualTo(1);
        assertThat(result.probeCalls()).isEqualTo(1);
        assertThat(indexed).isNotNull();
        assertThat(indexed.getSongId()).isEqualTo(canonical.getId());
        assertThat(canonical.getCoverPath()).isEqualTo("covers/canonical.jpg");
        assertThat(Files.readAllBytes(canonicalCoverPath)).containsExactly((byte) 51, (byte) 52, (byte) 53);
        verifyNoInteractions(coverImageNormalizer);
    }

    @Test
    void pendingProvisionalWithFinalFingerprintIsReusedAfterSuccessfulProbe() throws Exception {
        byte[] sourceBytes = {8, 6, 4};
        Path file = Files.write(sourceDir.resolve("周杰伦-暂存指纹恢复-国语-流行.mkv"), sourceBytes);
        String fingerprint = MediaClassifier.fingerprint("周杰伦", "暂存指纹恢复", 180_000);
        Song provisional = new Song();
        provisional.setId(730L);
        provisional.setTitle("暂存指纹恢复");
        provisional.setArtist("周杰伦");
        provisional.setMediaType(MediaClassifier.PENDING_PROBE);
        provisional.setDurationMs(180_000);
        provisional.setFingerprint(fingerprint);
        provisional.setStatus("unrecognized");
        provisional.setNeedsAiOptimization(true);
        songsById.put(provisional.getId(), provisional);
        songsByFingerprint.put(fingerprint, provisional);
        registerUnchangedFile(file, provisional);
        SongFile pending = filesByPath.get(file.toString());
        pending.setProbePending(true);

        LibraryScanService.ScanResult result = scanService.scanAll();

        SongFile indexed = filesByPath.get(file.toString());
        assertThat(result.added()).isEqualTo(1);
        assertThat(result.probeCalls()).isEqualTo(1);
        assertThat(indexed).isNotNull();
        assertThat(indexed.getId()).isEqualTo(pending.getId());
        assertThat(indexed.getSongId()).isEqualTo(provisional.getId());
        assertThat(indexed.isValid()).isTrue();
        assertThat(indexed.isProbePending()).isFalse();
        assertThat(provisional.getStatus()).isEqualTo("ok");
        assertThat(provisional.getFingerprint()).isEqualTo(fingerprint);
        assertThat(songsById).containsOnlyKeys(provisional.getId());
        assertThat(Files.readAllBytes(file)).containsExactly(sourceBytes);
        verify(ffprobe).probe(file);
    }

    @Test
    void duplicateFingerprintWithoutCatalogPrefixClearsCanonicalCatalogNumber() throws Exception {
        Path numbered = Files.write(sourceDir.resolve("12345678-草蜢-目录号合并-国语-流行.mkv"), new byte[]{1, 2, 3});
        Path unnumbered = Files.write(sourceDir.resolve("草蜢-目录号合并-国语-流行.mkv"), new byte[]{4, 5, 6});

        assertThat(scanService.ingest(numbered)).isEqualTo(LibraryScanService.IngestOutcome.ADDED);
        Song canonical = songsById.values().stream().findFirst().orElseThrow();
        assertThat(canonical.getCatalogNumber()).isEqualTo("12345678");

        assertThat(scanService.ingest(unnumbered)).isEqualTo(LibraryScanService.IngestOutcome.ADDED);

        SongFile firstFile = filesByPath.get(numbered.toString());
        SongFile secondFile = filesByPath.get(unnumbered.toString());
        assertThat(secondFile.getSongId()).isEqualTo(canonical.getId());
        assertThat(firstFile.getSongId()).isEqualTo(canonical.getId());
        assertThat(canonical.getCatalogNumber()).isEmpty();
        assertThat(Files.readAllBytes(numbered)).containsExactly((byte) 1, (byte) 2, (byte) 3);
        assertThat(Files.readAllBytes(unnumbered)).containsExactly((byte) 4, (byte) 5, (byte) 6);
    }

    @Test
    void successfullyReprobedFileRestoresSongAfterItWasMarkedMissing() throws Exception {
        Path file = Files.write(sourceDir.resolve("周杰伦-晴天-国语-流行.mkv"), new byte[]{1, 2, 3});
        scanService.scanAll();
        SongFile indexed = filesByPath.get(file.toString());
        Song song = songsById.get(indexed.getSongId());

        Files.delete(file);
        LibraryScanService.ScanResult missing = scanService.scanAll();
        assertThat(missing.missing()).isEqualTo(1);
        assertThat(song.getStatus()).isEqualTo("file_missing");

        Files.write(file, new byte[]{4, 5, 6});
        LibraryScanService.ScanResult recovered = scanService.scanAll();

        assertThat(recovered.probeCalls()).isEqualTo(1);
        assertThat(indexed.isValid()).isTrue();
        assertThat(indexed.isProbePending()).isFalse();
        assertThat(song.getStatus()).isEqualTo("ok");
        verify(ffprobe, times(2)).probe(file);
    }

    @ParameterizedTest
    @MethodSource("reactivationStatusCases")
    void unchangedInvalidFileReactivatesWithoutReprobing(
            String startingSongStatus, String expectedSongStatus) throws Exception {
        byte[] sourceBytes = {7, 8, 9};
        Path file = Files.write(sourceDir.resolve("周杰伦-晴天-国语-流行.mkv"), sourceBytes);
        Song song = new Song();
        song.setId(91L);
        song.setTitle("晴天");
        song.setArtist("周杰伦");
        song.setFingerprint("stable-fingerprint");
        song.setStatus(startingSongStatus);
        songsById.put(song.getId(), song);
        songsByFingerprint.put(song.getFingerprint(), song);
        registerUnchangedFile(file, song);
        SongFile indexed = filesByPath.get(file.toString());
        indexed.setValid(false);

        LibraryScanService.ScanResult result = scanService.scanAll();

        assertThat(result.skipped()).isEqualTo(1);
        assertThat(result.probeCalls()).isZero();
        assertThat(indexed.isValid()).isTrue();
        assertThat(indexed.isProbePending()).isFalse();
        assertThat(song.getStatus()).isEqualTo(expectedSongStatus);
        assertThat(Files.readAllBytes(file)).containsExactly(sourceBytes);
        verify(ffprobe, never()).probe(file);
    }

    private static Stream<Arguments> reactivationStatusCases() {
        return Stream.of(
                Arguments.of("file_missing", "ok"),
                Arguments.of("ok", "ok"));
    }

    @Test
    void scanSynchronizesEveryIngestedSongWithItsIndependentArtistCredits() throws Exception {
        Files.write(sourceDir.resolve("单依纯_王子异-合唱歌曲-国语-合唱.mkv"), new byte[]{1, 2, 3});

        scanService.scanAll();

        verify(artistCreditService, atLeastOnce())
                .createInitial(anyLong(), eq("单依纯_王子异"), any());
        verify(artistCreditService, never())
                .replace(anyLong(), anyString());
        verify(artistCreditService, never())
                .replace(anyLong(), anyString(), any());
    }

    @Test
    void externalReadOnlyScanKeepsIndexingWhenOptionalArtistCreditServiceIsUnavailable() throws Exception {
        byte[] sourceBytes = {7, 8, 9};
        Path file = Files.write(sourceDir.resolve("草蜢-只读索引-国语-流行.mkv"), sourceBytes);
        FileTime sourceMtime = Files.getLastModifiedTime(file);
        scanService.setArtistCreditService(null);

        LibraryScanService.ScanResult result = scanService.scanAll();

        SongFile indexed = filesByPath.get(file.toString());
        Song song = indexed == null ? null : songsById.get(indexed.getSongId());
        assertThat(result.added()).isEqualTo(1);
        assertThat(indexed).isNotNull();
        assertThat(indexed.isProbePending()).isFalse();
        assertThat(song).isNotNull();
        assertThat(song.getTitle()).isEqualTo("只读索引");
        assertThat(Files.readAllBytes(file)).containsExactly(sourceBytes);
        assertThat(Files.getLastModifiedTime(file)).isEqualTo(sourceMtime);
        verifyNoInteractions(artistCreditService);
    }

    @Test
    void scanReplacesInitialArtistCreditsWhenMediaTagsCorrectTheArtist() throws Exception {
        Path file = Files.write(sourceDir.resolve("草蜢-爱-国语-流行.mp3"), new byte[]{1, 2, 3});
        TagInfo tag = new TagInfo();
        tag.setArtist("王菲");
        when(tagReader.read(file.toFile())).thenReturn(tag);

        scanService.scanAll();

        verify(artistCreditService).createInitial(anyLong(), eq("草蜢"), any());
        verify(artistCreditService).replace(anyLong(), eq("王菲"));
    }

    @Test
    void laterSongSynchronizesCreditsWithTheExistingArtistIndex() throws Exception {
        Path first = Files.write(sourceDir.resolve("草蜢-第一首-国语-流行.mkv"), new byte[]{1});
        scanService.scanAll();
        Path second = Files.write(sourceDir.resolve("王菲-第二首-国语-流行.mp3"), new byte[]{2});
        TagInfo correctedTag = new TagInfo();
        correctedTag.setArtist("陈奕迅");
        when(tagReader.read(second.toFile())).thenReturn(correctedTag);

        LibraryScanService.ScanResult result = scanService.scanAll();

        SongFile secondFile = filesByPath.get(second.toString());
        assertThat(result.added()).isEqualTo(1);
        assertThat(secondFile.getSongId()).isNotNull();
        assertThat(secondFile.getSongId()).isNotEqualTo(filesByPath.get(first.toString()).getSongId());
        assertThat(songsById.get(secondFile.getSongId()).getArtist()).isEqualTo("陈奕迅");
        verify(artistCreditService).replace(eq(secondFile.getSongId()), eq("陈奕迅"),
                org.mockito.ArgumentMatchers.argThat(artists -> artists.contains("草蜢")));
    }

    @Test
    void scanStillSynchronizesProvisionalCreditsForRowsPendingBeforeThisScan() throws Exception {
        Path file = Files.write(sourceDir.resolve("草蜢-爱-国语-流行.mkv"), new byte[]{1, 2, 3});
        Song provisional = new Song();
        provisional.setId(800L);
        provisional.setTitle("爱");
        provisional.setArtist("草蜢");
        provisional.setMediaType(MediaClassifier.PENDING_PROBE);
        provisional.setFingerprint("fast-index-800");
        provisional.setStatus("unrecognized");
        provisional.setNeedsAiOptimization(true);
        songsById.put(provisional.getId(), provisional);
        songsByFingerprint.put(provisional.getFingerprint(), provisional);
        registerUnchangedFile(file, provisional);
        filesByPath.get(file.toString()).setProbePending(true);

        scanService.scanAll();

        verify(artistCreditService).replace(anyLong(), eq("草蜢"));
    }

    @Test
    void missingReconciliationDoesNotInvalidateRowsOutsideTheActiveRoot() throws Exception {
        Path outside = tempDir.resolve("other-library").resolve("other.mkv").toAbsolutePath();
        SongFile outsideRow = new SongFile();
        outsideRow.setId(9_999L);
        outsideRow.setSongId(9_998L);
        outsideRow.setFilePath(outside.toString());
        outsideRow.setRelativePath("other.mkv");
        outsideRow.setFileRole("EXTERNAL_READ_ONLY");
        outsideRow.setValid(true);
        filesByPath.put(outside.toString(), outsideRow);

        Files.write(sourceDir.resolve("周杰伦-晴天-国语-流行.mkv"), new byte[]{1, 2, 3});
        scanService.scanAll();

        assertThat(outsideRow.isValid()).isTrue();
    }

    @Test
    void suspiciousLargeDisappearanceKeepsExistingRowsValid() throws Exception {
        for (int index = 0; index < 1_000; index++) {
            SongFile existing = new SongFile();
            existing.setId((long) index + 1);
            existing.setSongId(10_000L + index);
            existing.setFilePath(sourceDir.resolve("existing-" + index + ".mkv").toString());
            existing.setFileRole(LibraryModePolicy.EXTERNAL_FILE_ROLE);
            existing.setFormat("mkv");
            existing.setFileMtime(java.time.OffsetDateTime.now());
            existing.setValid(true);
            filesByPath.put(existing.getFilePath(), existing);
        }

        LibraryScanService.ScanResult result = scanService.scanAll();

        assertThat(result.scanned()).isZero();
        assertThat(result.missing()).isZero();
        assertThat(filesByPath.values()).allMatch(SongFile::isValid);
    }

    @Test
    void seenPathStoreFailureStillFlushesEachFastIndexBatch() throws Exception {
        seenPathStore.failRecordBatches();
        for (int index = 0; index < 501; index++) {
            Files.write(sourceDir.resolve("歌手-歌曲" + index + "-国语-流行.mkv"), new byte[]{1});
        }

        LibraryScanService.ScanResult result = scanService.scanAll();

        assertThat(result.fastIndexed()).isEqualTo(501);
        assertThat(seenPathStore.recordBatchCalls()).isEqualTo(2);
        assertThat(filesByPath).hasSize(501);
    }

    @Test
    void scanAlwaysCleansTransientSeenPathsWhenQueuePreparationFails() throws Exception {
        Files.write(sourceDir.resolve("周杰伦-晴天-国语-流行.mkv"), new byte[]{1, 2, 3});
        when(songFileRepository.countByFileRoleAndProbePendingTrue(anyString()))
                .thenThrow(new IllegalStateException("test queue count failure"));

        assertThatThrownBy(() -> scanService.scanAll())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("test queue count failure");
        assertThat(seenPathStore.activeScanCount()).isZero();
    }

    @Test
    void indeterminateFileExistenceDoesNotMarkAnOtherwisePresentFileMissing() throws Exception {
        Path file = Files.write(sourceDir.resolve("周杰伦-晴天-国语-流行.mkv"), new byte[]{1, 2, 3});
        scanService.scanAll();
        SongFile indexed = filesByPath.get(file.toString());

        LibraryScanService.ScanResult second;
        try (MockedStatic<Files> files = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            files.when(() -> Files.exists(file)).thenReturn(false);
            second = scanService.scanAll();
        }

        assertThat(second.missing()).isZero();
        assertThat(indexed.isValid()).isTrue();
    }

    @Test
    void failedProbeIsRetriedOnTheNextScan() throws Exception {
        Path file = Files.write(sourceDir.resolve("周杰伦-晴天-国语-流行.mkv"), new byte[]{1, 2, 3});
        when(ffprobe.probe(any(Path.class)))
                .thenThrow(new MediaProbeException("temporary probe failure"))
                .thenReturn(probe());

        LibraryScanService.ScanResult failed = scanService.scanAll();
        SongFile pending = filesByPath.get(file.toString());
        assertThat(pending).isNotNull();
        assertThat(pending.isProbePending()).isTrue();
        assertThat(songsById.get(pending.getSongId()).getStatus()).isEqualTo("ok");

        LibraryScanService.ScanResult recovered = scanService.scanAll();

        assertThat(failed.probeCalls()).isEqualTo(1);
        assertThat(failed.skipped()).isEqualTo(1);
        assertThat(recovered.probeCalls()).isEqualTo(1);
        assertThat(recovered.added()).isEqualTo(1);
        assertThat(filesByPath).containsKey(file.toString());
        assertThat(filesByPath.get(file.toString()).isProbePending()).isFalse();
        verify(ffprobe, times(2)).probe(any(Path.class));
    }

    @Test
    void failedProbeKeepsAnUnrecognizedFilenamePendingForReview() throws Exception {
        Path file = Files.write(sourceDir.resolve("名称不规范.mkv"), new byte[]{1, 2, 3});
        when(ffprobe.probe(any(Path.class)))
                .thenThrow(new MediaProbeException("temporary probe failure"));

        scanService.scanAll();

        SongFile pending = filesByPath.get(file.toString());
        assertThat(pending).isNotNull();
        assertThat(pending.isProbePending()).isTrue();
        assertThat(songsById.get(pending.getSongId()).getStatus()).isEqualTo("unrecognized");
    }

    @Test
    void manualIdentityAndClassificationLocksSurviveProbeRetry() throws Exception {
        Path file = Files.write(sourceDir.resolve("周杰伦-晴天-合唱-国语-流行.mkv"), new byte[]{1, 2, 3});
        when(ffprobe.probe(any(Path.class)))
                .thenThrow(new MediaProbeException("temporary probe failure"))
                .thenReturn(probe());

        scanService.scanAll();

        SongFile pending = filesByPath.get(file.toString());
        Song song = songsById.get(pending.getSongId());
        song.setTitle("人工歌名");
        song.setArtist("人工歌手");
        song.setTitlePy("manual-title-py");
        song.setTitleInit("M");
        song.setArtistPy("manual-artist-py");
        song.setArtistInit("A");
        song.setLanguage("粤语");
        song.setTags(new String[]{"人工分类"});
        song.setVocalForm("独唱");
        song.setMetadataProvenance("{\"title\":{\"source\":\"manual\"},"
                + "\"artist\":{\"source\":\"manual\"}}");
        song.setStatus("ok");
        song.setNeedsAiOptimization(false);
        song.lockMetadata("title");
        song.lockMetadata("artist");
        song.lockMetadata("language");
        song.lockMetadata("tags");
        song.lockMetadata("vocalForm");

        LibraryScanService.ScanResult recovered = scanService.scanAll();

        assertThat(recovered.probeCalls()).isEqualTo(1);
        assertThat(pending.isProbePending()).isFalse();
        assertThat(song.getTitle()).isEqualTo("人工歌名");
        assertThat(song.getArtist()).isEqualTo("人工歌手");
        assertThat(song.getTitlePy()).isEqualTo("manual-title-py");
        assertThat(song.getTitleInit()).isEqualTo("M");
        assertThat(song.getArtistPy()).isEqualTo("manual-artist-py");
        assertThat(song.getArtistInit()).isEqualTo("A");
        assertThat(song.getLanguage()).isEqualTo("粤语");
        assertThat(song.getTags()).containsExactly("人工分类");
        assertThat(song.getVocalForm()).isEqualTo("独唱");
        assertThat(song.getMetadataProvenance()).contains("manual");
        assertThat(song.getStatus()).isEqualTo("ok");
        assertThat(song.getDurationMs()).isEqualTo(180_000);
        assertThat(song.isMetadataLocked("title")).isTrue();
        assertThat(song.isMetadataLocked("artist")).isTrue();
        assertThat(song.isMetadataLocked("language")).isTrue();
        assertThat(song.isMetadataLocked("tags")).isTrue();
        assertThat(song.isMetadataLocked("vocalForm")).isTrue();
    }

    @Test
    void reprobeKeepsManuallyLockedSongIdentityAndFileBinding() throws Exception {
        Path file = Files.write(sourceDir.resolve("周杰伦-晴天-国语-流行.mkv"), new byte[]{1, 2, 3});
        scanService.scanAll();

        SongFile indexed = filesByPath.get(file.toString());
        Song song = songsById.get(indexed.getSongId());
        Long originalSongId = song.getId();
        song.setTitle("爱");
        song.setArtist("草蜢");
        song.setFingerprint(MediaClassifier.fingerprint("草蜢", "爱", song.getDurationMs()));
        song.lockMetadata("title");
        song.lockMetadata("artist");
        songRepository.save(song);

        FileTime originalMtime = Files.getLastModifiedTime(file);
        Files.setLastModifiedTime(file, FileTime.fromMillis(originalMtime.toMillis() + 2_000));

        scanService.scanAll();

        assertThat(indexed.getSongId()).isEqualTo(originalSongId);
        assertThat(song.getTitle()).isEqualTo("爱");
        assertThat(song.getArtist()).isEqualTo("草蜢");
        assertThat(song.getFingerprint())
                .isEqualTo(MediaClassifier.fingerprint("草蜢", "爱", song.getDurationMs()));
    }

    @Test
    void reprobePreservesManuallyLockedTags() throws Exception {
        Path file = Files.write(sourceDir.resolve("周杰伦-晴天-国语-流行.mkv"), new byte[]{1, 2, 3});
        scanService.scanAll();

        SongFile indexed = filesByPath.get(file.toString());
        Song song = songsById.get(indexed.getSongId());
        song.setTags(new String[]{"经典粤语"});
        song.lockMetadata("tags");
        songRepository.save(song);

        FileTime originalMtime = Files.getLastModifiedTime(file);
        Files.setLastModifiedTime(file, FileTime.fromMillis(originalMtime.toMillis() + 2_000));

        scanService.scanAll();

        assertThat(song.getTags()).containsExactly("经典粤语");
    }

    @Test
    void reconciliationDoesNotDemoteManuallyConfirmedIdentityWhenProbeStillFails() throws Exception {
        Path file = Files.write(sourceDir.resolve("名称不规范.mkv"), new byte[]{1, 2, 3});
        when(ffprobe.probe(any(Path.class)))
                .thenThrow(new MediaProbeException("temporary probe failure"));

        scanService.scanAll();

        SongFile pending = filesByPath.get(file.toString());
        Song song = songsById.get(pending.getSongId());
        song.setTitle("人工歌名");
        song.setArtist("人工歌手");
        song.setStatus("ok");
        song.setNeedsAiOptimization(false);
        song.lockMetadata("title");
        song.lockMetadata("artist");

        scanService.scanAll();

        assertThat(song.getStatus()).isEqualTo("ok");
        assertThat(song.isNeedsAiOptimization()).isFalse();
        assertThat(song.getTitle()).isEqualTo("人工歌名");
        assertThat(song.getArtist()).isEqualTo("人工歌手");
    }

    @Test
    void successfulProbeKeepsAnUnrecognizedFilenameForReview() throws Exception {
        Path file = Files.write(sourceDir.resolve("名称不规范.mkv"), new byte[]{1, 2, 3});

        scanService.scanAll();

        SongFile indexed = filesByPath.get(file.toString());
        assertThat(indexed).isNotNull();
        assertThat(songsById.get(indexed.getSongId()).getStatus()).isEqualTo("unrecognized");
        assertThat(songsById.get(indexed.getSongId()).isNeedsAiOptimization()).isTrue();
    }

    @Test
    void unavailableExternalRootDoesNotMarkTrackedFilesMissing() throws Exception {
        Path file = Files.write(sourceDir.resolve("周杰伦-晴天-国语-流行.mkv"), new byte[]{1, 2, 3});
        scanService.scanAll();
        SongFile indexed = filesByPath.get(file.toString());
        assertThat(indexed.isValid()).isTrue();

        props.setSourceLibraryPath(tempDir.resolve("temporarily-unavailable").toString());
        LibraryScanService.ScanResult result = scanService.scanAll();

        assertThat(result.missing()).isZero();
        assertThat(indexed.isValid()).isTrue();
        verify(songFileRepository, times(2)).save(any(SongFile.class));
    }

    @Test
    void retryReconcilesAnOldFastIndexRowThatWasPersistedAsOk() throws Exception {
        Path file = Files.write(sourceDir.resolve("名称不规范.mkv"), new byte[]{1, 2, 3});
        when(ffprobe.probe(any(Path.class)))
                .thenThrow(new MediaProbeException("temporary probe failure"));

        scanService.scanAll();

        SongFile pending = filesByPath.get(file.toString());
        Song provisional = songsById.get(pending.getSongId());
        provisional.setStatus("ok");
        provisional.setNeedsAiOptimization(false);

        scanService.scanAll();

        assertThat(provisional.getStatus()).isEqualTo("unrecognized");
        assertThat(provisional.isNeedsAiOptimization()).isTrue();
    }

    @Test
    void fastIndex_forVideoFiles_skipsTagReader() throws Exception {
        Path file = Files.write(sourceDir.resolve("周杰伦-晴天-国语-流行.mkv"), new byte[]{1, 2, 3});
        when(ffprobe.probe(any(Path.class))).thenReturn(probe());

        scanService.scanAll();

        SongFile indexed = filesByPath.get(file.toString());
        assertThat(indexed).isNotNull();
        assertThat(indexed.isProbePending()).isFalse();
        // For video containers (.mkv, .mp4, etc.), TagReader (jaudiotagger) should never be called
        verify(tagReader, times(0)).read(any(java.io.File.class));
    }

    @Test
    void pendingProbeQueueReadFailureRetainsIndexedFileForRetry() throws Exception {
        byte[] sourceBytes = {1, 2, 3};
        Path file = Files.write(sourceDir.resolve("周杰伦-晴天-国语-流行.mkv"), sourceBytes);
        when(songFileRepository.findPendingForScan(anyString(), anyLong(), any(UUID.class), anyString(),
                any(Pageable.class))).thenThrow(new IllegalStateException("synthetic repository outage"));

        LibraryScanService.ScanResult result = scanService.scanAll();

        SongFile pending = filesByPath.get(file.toString());
        assertThat(result.fastIndexed()).isEqualTo(1);
        assertThat(result.probeQueued()).isEqualTo(1);
        assertThat(result.probeCalls()).isZero();
        assertThat(pending).isNotNull();
        assertThat(pending.isValid()).isTrue();
        assertThat(pending.isProbePending()).isTrue();
        assertThat(Files.readAllBytes(file)).containsExactly(sourceBytes);
        verify(ffprobe, never()).probe(any(Path.class));
    }

    @ParameterizedTest(name = "pending-at-start lookup failure={0}")
    @MethodSource("blankPendingPathLookupCases")
    void blankPendingPathIsRetainedWithoutAttemptingProbe(boolean failPendingAtStartLookup) throws Exception {
        Song song = new Song();
        song.setId(810L);
        song.setTitle("遗留待探测记录");
        song.setArtist("草蜢");
        song.setStatus("ok");
        songsById.put(song.getId(), song);

        SongFile pending = new SongFile();
        pending.setId(811L);
        pending.setSongId(song.getId());
        pending.setFilePath(" ");
        pending.setFileRole(LibraryModePolicy.EXTERNAL_FILE_ROLE);
        pending.setValid(true);
        pending.setProbePending(true);
        filesByPath.put(pending.getFilePath(), pending);
        if (failPendingAtStartLookup) seenPathStore.failPendingAtStartLookups();

        LibraryScanService.ScanResult result = scanService.scanAll();

        assertThat(result.probeCalls()).isZero();
        assertThat(filesByPath).containsEntry(" ", pending);
        assertThat(pending.isValid()).isTrue();
        assertThat(pending.isProbePending()).isTrue();
        verify(ffprobe, never()).probe(any(Path.class));
    }

    private static Stream<Arguments> blankPendingPathLookupCases() {
        return Stream.of(Arguments.of(false), Arguments.of(true));
    }

    @Test
    void pendingNonMediaPathIsRetainedWithoutAttemptingProbe() throws Exception {
        byte[] sourceBytes = {3, 1, 4};
        Path file = Files.write(sourceDir.resolve("legacy-pending-index.txt"), sourceBytes);
        Song song = new Song();
        song.setId(815L);
        song.setTitle("遗留待探测记录");
        song.setArtist("草蜢");
        song.setStatus("ok");
        song.setFingerprint("legacy-pending-nonmedia-815");
        songsById.put(song.getId(), song);

        SongFile pending = new SongFile();
        pending.setId(816L);
        pending.setSongId(song.getId());
        pending.setFilePath(file.toString());
        pending.setFileRole(LibraryModePolicy.EXTERNAL_FILE_ROLE);
        pending.setValid(true);
        pending.setProbePending(true);
        filesByPath.put(pending.getFilePath(), pending);

        LibraryScanService.ScanResult result = scanService.scanAll();

        assertThat(result.scanned()).isZero();
        assertThat(result.probeCalls()).isZero();
        assertThat(result.missing()).isEqualTo(1);
        assertThat(filesByPath).containsEntry(file.toString(), pending);
        assertThat(pending.isValid()).isFalse();
        assertThat(pending.isProbePending()).isTrue();
        assertThat(song.getStatus()).isEqualTo("file_missing");
        assertThat(Files.readAllBytes(file)).containsExactly(sourceBytes);
        verify(ffprobe, never()).probe(any(Path.class));
    }

    @ParameterizedTest
    @MethodSource("pendingFilenameStatusCases")
    void changedPendingSnapshotRepairsLegacyFilenameStatusBeforeProbeRetry(
            String startingStatus, boolean startingNeedsAi, String startingMediaType,
            String startingFingerprint, String expectedStatus, boolean expectedNeedsAi) throws Exception {
        byte[] originalBytes = {1, 3, 5};
        Path file = Files.write(sourceDir.resolve("名称不规范.mkv"), originalBytes);
        Song song = new Song();
        song.setId(819L);
        song.setTitle("名称不规范");
        song.setArtist("未知歌手");
        song.setMediaType(startingMediaType);
        song.setDurationMs(0);
        song.setFingerprint(startingFingerprint);
        song.setStatus(startingStatus);
        song.setNeedsAiOptimization(startingNeedsAi);
        songsById.put(song.getId(), song);
        songsByFingerprint.put(song.getFingerprint(), song);
        registerUnchangedFile(file, song);
        SongFile pending = filesByPath.get(file.toString());
        pending.setProbePending(true);

        byte[] changedBytes = {1, 3, 5, 7};
        Files.write(file, changedBytes);
        Files.setLastModifiedTime(file, FileTime.fromMillis(
                Files.getLastModifiedTime(file).toMillis() + 2_000));
        when(ffprobe.probe(file)).thenThrow(new MediaProbeException("synthetic probe retry failure"));

        LibraryScanService.ScanResult result = scanService.scanAll();

        assertThat(result.scanned()).isEqualTo(1);
        assertThat(result.probeCalls()).isEqualTo(1);
        assertThat(pending.isValid()).isTrue();
        assertThat(pending.isProbePending()).isTrue();
        assertThat(song.getStatus()).isEqualTo(expectedStatus);
        assertThat(song.isNeedsAiOptimization()).isEqualTo(expectedNeedsAi);
        assertThat(Files.readAllBytes(file)).containsExactly(changedBytes);
        verify(ffprobe).probe(file);
    }

    @Test
    void changedPendingMediaRepairsMissingRelativePathBeforeProbeRetry() throws Exception {
        byte[] originalBytes = {2, 4, 6};
        byte[] changedBytes = {2, 4, 6, 8};
        Path file = Files.write(sourceDir.resolve("周杰伦-修复相对路径-国语-流行.mkv"), originalBytes);
        Song song = new Song();
        song.setId(823L);
        song.setTitle("修复相对路径");
        song.setArtist("周杰伦");
        song.setStatus("ok");
        song.setFingerprint("completed-relative-path-823");
        songsById.put(song.getId(), song);
        songsByFingerprint.put(song.getFingerprint(), song);
        registerUnchangedFile(file, song);
        SongFile pending = filesByPath.get(file.toString());
        pending.setProbePending(true);
        pending.setRelativePath(null);
        Files.write(file, changedBytes);
        Files.setLastModifiedTime(file, FileTime.fromMillis(
                Files.getLastModifiedTime(file).toMillis() + 2_000));
        when(ffprobe.probe(file)).thenThrow(new MediaProbeException("synthetic retry failure"));

        LibraryScanService.ScanResult result = scanService.scanAll();

        SongFile indexed = filesByPath.get(file.toString());
        assertThat(result.scanned()).isEqualTo(1);
        assertThat(result.probeCalls()).isEqualTo(1);
        assertThat(indexed.getId()).isEqualTo(pending.getId());
        assertThat(indexed.getRelativePath()).isEqualTo(file.getFileName().toString());
        assertThat(indexed.isValid()).isTrue();
        assertThat(indexed.isProbePending()).isTrue();
        assertThat(song.getStatus()).isEqualTo("ok");
        assertThat(Files.readAllBytes(file)).containsExactly(changedBytes);
        verify(ffprobe).probe(file);
    }

    private static Stream<Arguments> pendingFilenameStatusCases() {
        return Stream.of(
                Arguments.of("ok", false, MediaClassifier.PENDING_PROBE, "fast-index-819", "unrecognized", true),
                Arguments.of("unrecognized", false, MediaClassifier.PENDING_PROBE, "fast-index-819", "unrecognized", true),
                Arguments.of("unrecognized", true, MediaClassifier.PENDING_PROBE, "fast-index-819", "unrecognized", true),
                Arguments.of("ok", false, MediaClassifier.KTV_VIDEO, "fast-index-819", "unrecognized", true));
    }

    @Test
    void failedProbeDoesNotDemoteACompletedSongFromAnUnrecognizedFilename() throws Exception {
        byte[] originalBytes = {1, 3, 5};
        byte[] changedBytes = {1, 3, 5, 7};
        Path file = Files.write(sourceDir.resolve("名称不规范.mkv"), originalBytes);
        Song completed = new Song();
        completed.setId(820L);
        completed.setTitle("人工确认歌名");
        completed.setArtist("人工确认歌手");
        completed.setMediaType(MediaClassifier.KTV_VIDEO);
        completed.setDurationMs(180_000);
        completed.setFingerprint("confirmed-video-fingerprint");
        completed.setStatus("ok");
        completed.setNeedsAiOptimization(false);
        songsById.put(completed.getId(), completed);
        songsByFingerprint.put(completed.getFingerprint(), completed);
        registerUnchangedFile(file, completed);
        SongFile pending = filesByPath.get(file.toString());
        pending.setProbePending(true);
        Files.write(file, changedBytes);
        Files.setLastModifiedTime(file, FileTime.fromMillis(
                Files.getLastModifiedTime(file).toMillis() + 2_000));
        when(ffprobe.probe(file)).thenThrow(new MediaProbeException("synthetic retry failure"));

        LibraryScanService.ScanResult result = scanService.scanAll();

        assertThat(result.probeCalls()).isEqualTo(1);
        assertThat(result.skipped()).isEqualTo(1);
        assertThat(pending.isValid()).isTrue();
        assertThat(pending.isProbePending()).isTrue();
        assertThat(completed.getStatus()).isEqualTo("ok");
        assertThat(completed.isNeedsAiOptimization()).isFalse();
        assertThat(completed.getTitle()).isEqualTo("人工确认歌名");
        assertThat(completed.getArtist()).isEqualTo("人工确认歌手");
        assertThat(Files.readAllBytes(file)).containsExactly(changedBytes);
        verify(ffprobe).probe(file);
    }

    @Test
    void failedProbeDoesNotTreatCompletedSongWithoutFingerprintAsProvisional() throws Exception {
        byte[] originalBytes = {1, 5, 9};
        byte[] changedBytes = {1, 5, 9, 2};
        Path file = Files.write(sourceDir.resolve("名称不规范且无指纹.mkv"), originalBytes);
        Song completed = new Song();
        completed.setId(825L);
        completed.setTitle("人工确认歌名");
        completed.setArtist("人工确认歌手");
        completed.setMediaType(MediaClassifier.KTV_VIDEO);
        completed.setDurationMs(180_000);
        completed.setFingerprint(null);
        completed.setStatus("ok");
        completed.setNeedsAiOptimization(false);
        songsById.put(completed.getId(), completed);
        registerUnchangedFile(file, completed);
        SongFile pending = filesByPath.get(file.toString());
        pending.setProbePending(true);
        Files.write(file, changedBytes);
        Files.setLastModifiedTime(file, FileTime.fromMillis(
                Files.getLastModifiedTime(file).toMillis() + 2_000));
        when(ffprobe.probe(file)).thenThrow(new MediaProbeException("synthetic retry failure"));

        LibraryScanService.ScanResult result = scanService.scanAll();

        assertThat(result.probeCalls()).isEqualTo(1);
        assertThat(pending.isValid()).isTrue();
        assertThat(pending.isProbePending()).isTrue();
        assertThat(completed.getStatus()).isEqualTo("ok");
        assertThat(completed.isNeedsAiOptimization()).isFalse();
        assertThat(completed.getTitle()).isEqualTo("人工确认歌名");
        assertThat(completed.getArtist()).isEqualTo("人工确认歌手");
        assertThat(Files.readAllBytes(file)).containsExactly(changedBytes);
        verify(ffprobe).probe(file);
    }

    @Test
    void probeAdmissionInterruptionLeavesPendingSongAvailableForRetry() throws Exception {
        byte[] sourceBytes = {9, 4, 1};
        Path file = Files.write(sourceDir.resolve("周杰伦-等待探测配额-国语-流行.mkv"), sourceBytes);
        when(ffprobe.probe(file)).thenThrow(new MediaProbeStage.ProbeAdmissionInterruptedException(
                new InterruptedException("synthetic permit wait interruption")));

        LibraryScanService.ScanResult result = scanService.scanAll();

        SongFile pending = filesByPath.get(file.toString());
        Song song = pending == null ? null : songsById.get(pending.getSongId());
        assertThat(result.probeCalls()).isZero();
        assertThat(pending).isNotNull();
        assertThat(pending.isValid()).isTrue();
        assertThat(pending.isProbePending()).isTrue();
        assertThat(song).isNotNull();
        assertThat(Files.readAllBytes(file)).containsExactly(sourceBytes);
        verify(ffprobe).probe(file);
    }

    @Test
    void pendingAtStartLookupFailurePreservesExistingPendingImportSemantics() throws Exception {
        Path file = Files.write(sourceDir.resolve("草蜢-暂存恢复-国语-流行.mkv"), new byte[]{1, 2, 3});
        Song provisional = new Song();
        provisional.setId(800L);
        provisional.setTitle("暂存恢复");
        provisional.setArtist("草蜢");
        provisional.setMediaType(MediaClassifier.PENDING_PROBE);
        provisional.setFingerprint("fast-index-800");
        provisional.setStatus("unrecognized");
        provisional.setNeedsAiOptimization(true);
        songsById.put(provisional.getId(), provisional);
        songsByFingerprint.put(provisional.getFingerprint(), provisional);
        registerUnchangedFile(file, provisional);
        SongFile pending = filesByPath.get(file.toString());
        pending.setProbePending(true);
        seenPathStore.failPendingAtStartLookups();

        LibraryScanService.ScanResult result = scanService.scanAll();

        assertThat(result.added()).isEqualTo(1);
        assertThat(result.updated()).isZero();
        assertThat(result.probeCalls()).isEqualTo(1);
        assertThat(filesByPath).hasSize(1);
        assertThat(filesByPath.get(file.toString()).getId()).isEqualTo(pending.getId());
        assertThat(filesByPath.get(file.toString()).isProbePending()).isFalse();
        assertThat(songsById.values()).anyMatch(song -> "ok".equals(song.getStatus()));
        verify(ffprobe, times(1)).probe(file);
    }

    @Test
    void pendingAtStartLookupFailureDoesNotReclassifyRowsCreatedDuringThisScan() throws Exception {
        byte[] sourceBytes = {5, 7, 9};
        Path file = Files.write(sourceDir.resolve("周杰伦-本轮新建记录-国语-流行.mkv"), sourceBytes);
        seenPathStore.failPendingAtStartLookups();

        LibraryScanService.ScanResult result = scanService.scanAll();

        SongFile indexed = filesByPath.get(file.toString());
        Song song = indexed == null ? null : songsById.get(indexed.getSongId());
        assertThat(result.added()).isEqualTo(1);
        assertThat(result.updated()).isZero();
        assertThat(result.probeCalls()).isEqualTo(1);
        assertThat(indexed).isNotNull();
        assertThat(indexed.getId()).isGreaterThan(0L);
        assertThat(indexed.isValid()).isTrue();
        assertThat(indexed.isProbePending()).isFalse();
        assertThat(song).isNotNull();
        assertThat(song.getStatus()).isEqualTo("ok");
        assertThat(Files.readAllBytes(file)).containsExactly(sourceBytes);
        verify(ffprobe).probe(file);
    }

    @Test
    void pendingLegacyRowReadsItsSidecarWhenSnapshotVersionIsMissing() throws Exception {
        byte[] sourceBytes = {7, 4, 2};
        Path file = Files.write(sourceDir.resolve("周杰伦-旧版快索引歌词-国语-流行.mkv"), sourceBytes);
        byte[] lyricBytes = "[00:01.00]旧版快索引歌词\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Path sidecar = Files.write(sourceDir.resolve("周杰伦-旧版快索引歌词-国语-流行.lrc"), lyricBytes);
        Song provisional = new Song();
        provisional.setId(814L);
        provisional.setTitle("旧版快索引歌词");
        provisional.setArtist("周杰伦");
        provisional.setMediaType(MediaClassifier.PENDING_PROBE);
        provisional.setDurationMs(180_000);
        provisional.setFingerprint("fast-index-814");
        provisional.setStatus("ok");
        songsById.put(provisional.getId(), provisional);
        songsByFingerprint.put(provisional.getFingerprint(), provisional);
        registerUnchangedFile(file, provisional);
        SongFile pending = filesByPath.get(file.toString());
        pending.setProbePending(true);
        pending.setLyricSnapshotVersion(null);
        pending.setLyricSize(null);
        pending.setLyricMtime(null);
        pending.setLyricFileIdentity(null);

        LibraryScanService.ScanResult result = scanService.scanAll();

        SongFile indexed = filesByPath.get(file.toString());
        assertThat(result.probeCalls()).isEqualTo(1);
        assertThat(indexed.getId()).isEqualTo(pending.getId());
        assertThat(indexed.isProbePending()).isFalse();
        assertThat(indexed.getLyricSnapshotVersion()).isEqualTo(1);
        assertThat(indexed.getLyricSize()).isEqualTo((long) lyricBytes.length);
        assertThat(provisional.getLyricSource()).isEqualTo(Song.LYRIC_SOURCE_SIDECAR);
        assertThat(Files.readString(tempDir.resolve("data").resolve(provisional.getLyricPath())))
                .contains("旧版快索引歌词");
        assertThat(Files.readAllBytes(file)).containsExactly(sourceBytes);
        assertThat(Files.readAllBytes(sidecar)).containsExactly(lyricBytes);
        verify(ffprobe).probe(file);
    }

    @Test
    void unexpectedProbeWorkerErrorRetainsIndexedFileForRetry() throws Exception {
        byte[] sourceBytes = {4, 5, 6};
        Path file = Files.write(sourceDir.resolve("周杰伦-晴天-国语-流行.mkv"), sourceBytes);
        when(ffprobe.probe(file)).thenThrow(new AssertionError("synthetic worker error"));

        LibraryScanService.ScanResult result = scanService.scanAll();

        SongFile pending = filesByPath.get(file.toString());
        assertThat(result.fastIndexed()).isEqualTo(1);
        assertThat(result.probeQueued()).isEqualTo(1);
        assertThat(result.probeCalls()).isZero();
        assertThat(pending).isNotNull();
        assertThat(pending.isValid()).isTrue();
        assertThat(pending.isProbePending()).isTrue();
        assertThat(Files.readAllBytes(file)).containsExactly(sourceBytes);
        verify(ffprobe, times(1)).probe(file);
    }

    private static String fileIdentity(Path file) throws Exception {
        Object key = Files.readAttributes(file, BasicFileAttributes.class).fileKey();
        return key == null ? null : key + "|null";
    }

    private static MediaProbe probe() {
        return new MediaProbe(180_000, 2, 0, true, "1920x1080", List.of(), "h264", "aac");
    }

    private static MediaProbe singleTrackProbe() {
        return new MediaProbe(180_000, 1, 0, true, "1920x1080",
                List.of(new AudioStreamInfo(0, "立体声", null, 2, false, true)), "h264", "aac");
    }

    private void useSettingService(SettingService settings) {
        scanService.shutdown();
        scanService = new LibraryScanService(props, ffprobe, tagReader, songRepository,
                songFileRepository, new AssetWriter(props), settings, seenPathStore);
        scanService.setArtistCreditService(artistCreditService);
    }

    private void useTransactionManager() {
        scanService.shutdown();
        JdbcDataSource transactionDataSource = new JdbcDataSource();
        transactionDataSource.setURL("jdbc:h2:mem:scan-transaction-" + UUID.randomUUID());
        scanService = new LibraryScanService(props, ffprobe, tagReader, songRepository,
                songFileRepository, new AssetWriter(props), null,
                new DataSourceTransactionManager(transactionDataSource), seenPathStore);
        scanService.setArtistCreditService(artistCreditService);
    }

    private void registerUnchangedFile(Path file, Song song) throws Exception {
        BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
        java.time.OffsetDateTime mediaMtime = attributes.lastModifiedTime().toInstant()
                .atOffset(java.time.ZoneOffset.UTC);
        String mediaIdentity = attributes.fileKey() == null ? null : String.valueOf(attributes.fileKey());

        SongFile indexed = new SongFile();
        indexed.setId(song.getId() + 100L);
        indexed.setSongId(song.getId());
        indexed.setFilePath(file.toString());
        indexed.setRelativePath(file.getFileName().toString());
        indexed.setFormat("mkv");
        indexed.setFileSize(attributes.size());
        indexed.setFileMtime(mediaMtime);
        indexed.setFileIdentity(mediaIdentity == null ? null : mediaIdentity + "|null");
        indexed.setMediaMtime(mediaMtime);
        indexed.setMediaFileIdentity(mediaIdentity);
        indexed.setLyricSnapshotVersion(1);
        indexed.setFileRole(LibraryModePolicy.EXTERNAL_FILE_ROLE);
        indexed.setValid(true);
        indexed.setProbePending(false);
        filesByPath.put(file.toString(), indexed);
    }
}
