package com.homektv.library;

import com.homektv.config.AppProperties;
import com.homektv.domain.Song;
import com.homektv.domain.SongFile;
import com.homektv.media.FFprobeService;
import com.homektv.media.MediaProbe;
import com.homektv.media.MediaProbeException;
import com.homektv.repo.SongFileRepository;
import com.homektv.repo.SongRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

    private final Map<String, Song> songsByFingerprint = new HashMap<>();
    private final Map<Long, Song> songsById = new HashMap<>();
    private final Map<String, SongFile> filesByPath = new HashMap<>();
    private final AtomicLong ids = new AtomicLong(100);

    private LibraryScanService scanService;
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
        lenient().when(songRepository.findByFingerprint(anyString())).thenAnswer(invocation ->
                Optional.ofNullable(songsByFingerprint.get(invocation.getArgument(0))));
        lenient().when(songRepository.findById(anyLong())).thenAnswer(invocation ->
                Optional.ofNullable(songsById.get(invocation.getArgument(0))));
        when(songRepository.save(any(Song.class))).thenAnswer(invocation -> {
            Song song = invocation.getArgument(0);
            if (song.getId() == null) song.setId(ids.getAndIncrement());
            songsById.put(song.getId(), song);
            songsByFingerprint.put(song.getFingerprint(), song);
            return song;
        });

        lenient().when(songFileRepository.findByFilePath(anyString())).thenAnswer(invocation ->
                Optional.ofNullable(filesByPath.get(invocation.getArgument(0))));
        lenient().when(songFileRepository.findByFileRoleOrderByImportedAtDesc(anyString())).thenAnswer(invocation ->
                filesByPath.values().stream().filter(file -> invocation.getArgument(0).equals(file.getFileRole())).toList());
        lenient().when(songFileRepository.findBySongIdAndValidTrueOrderByPriorityDesc(anyLong())).thenAnswer(invocation ->
                filesByPath.values().stream()
                        .filter(file -> invocation.getArgument(0).equals(file.getSongId()) && file.isValid())
                        .toList());
        when(songFileRepository.save(any(SongFile.class))).thenAnswer(invocation -> {
            SongFile file = invocation.getArgument(0);
            if (file.getId() == null) file.setId(ids.getAndIncrement());
            filesByPath.entrySet().removeIf(entry -> entry.getValue() == file);
            filesByPath.put(file.getFilePath(), file);
            return file;
        });

        lenient().when(tagReader.read(any())).thenReturn(new TagInfo());
        lenient().when(ffprobe.probe(any(Path.class))).thenReturn(probe());

        scanService = new LibraryScanService(props, ffprobe, tagReader, songRepository,
                songFileRepository, new AssetWriter(props));
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

    private static String fileIdentity(Path file) throws Exception {
        Object key = Files.readAttributes(file, BasicFileAttributes.class).fileKey();
        return key == null ? null : key + "|null";
    }

    private static MediaProbe probe() {
        return new MediaProbe(180_000, 2, 0, true, "1920x1080", List.of(), "h264", "aac");
    }
}
