package com.homektv.library;

import com.homektv.config.AppProperties;
import com.homektv.domain.Song;
import com.homektv.domain.SongFile;
import com.homektv.media.FFprobeService;
import com.homektv.media.MediaProbe;
import com.homektv.media.MediaProbeException;
import com.homektv.repo.SongFileRepository;
import com.homektv.repo.SongFileScanSnapshot;
import com.homektv.repo.SongRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
        ReflectionTestUtils.setField(scanService, "artistCreditService", artistCreditService);
    }

    @AfterEach
    void tearDown() {
        scanService.shutdown();
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
    void directIngestWithoutAnExistingSongRemainsSupported() throws Exception {
        Path file = Files.write(sourceDir.resolve("周杰伦-晴天-国语-流行.mkv"), new byte[]{1, 2, 3});

        assertThat(scanService.ingest(file)).isEqualTo(LibraryScanService.IngestOutcome.ADDED);
        assertThat(filesByPath.get(file.toString())).isNotNull();
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
        Files.move(file, remountedFile);
        Files.setLastModifiedTime(remountedFile, originalMtime);
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
        BasicFileAttributes originalAttributes = Files.readAttributes(file, BasicFileAttributes.class);
        String replacementIdentity;

        Files.delete(file);
        Files.write(file, new byte[]{4, 5, 6});
        Files.setLastModifiedTime(file, originalAttributes.lastModifiedTime());
        replacementIdentity = fileIdentity(file);

        org.junit.jupiter.api.Assumptions.assumeTrue(originalIdentity != null
                        && replacementIdentity != null
                        && !originalIdentity.equals(replacementIdentity),
                "filesystem does not expose a changed identity for replacement");

        LibraryScanService.ScanResult second = scanService.scanAll();

        assertThat(second.updated()).isEqualTo(1);
        assertThat(second.probeQueued()).isEqualTo(1);
        assertThat(second.probeCalls()).isEqualTo(1);
        verify(ffprobe, times(2)).probe(any(Path.class));
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

    @Test
    void scanSynchronizesEveryIngestedSongWithItsIndependentArtistCredits() throws Exception {
        Files.write(sourceDir.resolve("单依纯_王子异-合唱歌曲-国语-合唱.mkv"), new byte[]{1, 2, 3});

        scanService.scanAll();

        verify(artistCreditService, atLeastOnce()).replace(anyLong(), eq("单依纯_王子异"));
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
    void manualIdentityLocksSurviveProbeRetryWhileUnlockedMediaFieldsRefresh() throws Exception {
        Path file = Files.write(sourceDir.resolve("名称不规范.mkv"), new byte[]{1, 2, 3});
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
        song.setMetadataProvenance("{\"title\":{\"source\":\"manual\"},"
                + "\"artist\":{\"source\":\"manual\"}}");
        song.setStatus("ok");
        song.setNeedsAiOptimization(false);
        song.lockMetadata("title");
        song.lockMetadata("artist");
        song.lockMetadata("language");

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
        assertThat(song.getMetadataProvenance()).contains("manual");
        assertThat(song.getStatus()).isEqualTo("ok");
        assertThat(song.getDurationMs()).isEqualTo(180_000);
        assertThat(song.isMetadataLocked("title")).isTrue();
        assertThat(song.isMetadataLocked("artist")).isTrue();
        assertThat(song.isMetadataLocked("language")).isTrue();
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

    private static String fileIdentity(Path file) throws Exception {
        Object key = Files.readAttributes(file, BasicFileAttributes.class).fileKey();
        return key == null ? null : key + "|null";
    }

    private static MediaProbe probe() {
        return new MediaProbe(180_000, 2, 0, true, "1920x1080", List.of(), "h264", "aac");
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
