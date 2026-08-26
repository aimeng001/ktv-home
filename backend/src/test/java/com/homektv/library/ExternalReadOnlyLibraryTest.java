package com.homektv.library;

import com.homektv.config.AppProperties;
import com.homektv.domain.Song;
import com.homektv.domain.SongFile;
import com.homektv.media.FFprobeService;
import com.homektv.media.MediaProbe;
import com.homektv.media.MediaProbeException;
import com.homektv.repo.SongFileRepository;
import com.homektv.repo.SongRepository;
import com.homektv.domain.AudioChannel;
import com.homektv.domain.AudioLayout;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExternalReadOnlyLibraryTest {

    private final Map<Long, Song> songsById = new HashMap<>();
    private final Map<String, SongFile> filesByPath = new HashMap<>();

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
    private SettingService settingService;

    private LibraryScanService scanService;
    private Path sourceDir;
    private AppProperties props;

    @BeforeEach
    void setUp() throws Exception {
        sourceDir = Files.createDirectory(tempDir.resolve("nas-copy"));
        props = new AppProperties();
        props.setSourceLibraryPath(sourceDir.toString());
        props.setKtvLibraryPath(tempDir.resolve("managed").toString());
        props.setDataPath(tempDir.resolve("data").toString());
        props.setLibraryMode(LibraryMode.EXTERNAL_READ_ONLY);

        lenient().when(songFileRepository.findByFilePath(anyString())).thenAnswer(invocation ->
                Optional.ofNullable(filesByPath.get(invocation.getArgument(0))));
        lenient().when(songFileRepository.findByFileRoleOrderByImportedAtDesc(anyString()))
                .thenAnswer(invocation -> new ArrayList<>(filesByPath.values()));
        lenient().when(songRepository.findByFingerprint(anyString())).thenReturn(Optional.empty());
        lenient().when(songRepository.save(any(Song.class))).thenAnswer(invocation -> {
            Song song = invocation.getArgument(0);
            song.setId(101L);
            songsById.put(song.getId(), song);
            return song;
        });
        lenient().when(songRepository.findById(anyLong())).thenAnswer(invocation ->
                Optional.ofNullable(songsById.get(invocation.getArgument(0))));
        lenient().when(songFileRepository.save(any(SongFile.class))).thenAnswer(invocation -> {
            SongFile songFile = invocation.getArgument(0);
            songFile.setId(202L);
            filesByPath.put(songFile.getFilePath(), songFile);
            return songFile;
        });

        lenient().when(settingService.externalDefaultAudioLayout()).thenReturn(AudioLayout.NORMAL_STEREO);
        scanService = new LibraryScanService(props, ffprobe, tagReader, songRepository,
                songFileRepository, new AssetWriter(props), settingService);
    }

    @AfterEach
    void tearDown() {
        scanService.shutdown();
    }

    @Test
    void externalLibraryIsEnumeratedAndIndexedWithoutChangingTheSourceFile() throws Exception {
        Path source = sourceDir.resolve("周杰伦-晴天-国语-流行.mkv");
        byte[] originalBytes = new byte[]{0x01, 0x23, 0x45, 0x67};
        Files.write(source, originalBytes);
        FileTime originalMtime = Files.getLastModifiedTime(source);
        when(tagReader.read(any())).thenReturn(new TagInfo());
        when(ffprobe.probe(source)).thenReturn(new MediaProbe(180_000, 2, 0, true,
                "1920x1080", List.of(), "h264", "aac"));

        LibraryScanService.ScanResult result = scanService.scanAll();

        assertThat(result.scanned()).isEqualTo(1);
        assertThat(result.added()).isEqualTo(1);
        assertThat(Files.readAllBytes(source)).isEqualTo(originalBytes);
        assertThat(Files.size(source)).isEqualTo(originalBytes.length);
        assertThat(Files.getLastModifiedTime(source)).isEqualTo(originalMtime);
        assertThat(tempDir.resolve("managed").resolve(source.getFileName())).doesNotExist();
        verify(ffprobe).probe(source);

        var fileCaptor = org.mockito.ArgumentCaptor.forClass(SongFile.class);
        verify(songFileRepository, times(2)).save(fileCaptor.capture());
        assertThat(fileCaptor.getAllValues().get(1).getFilePath()).isEqualTo(source.toString());
        assertThat(fileCaptor.getAllValues().get(1).getFileRole()).isEqualTo("EXTERNAL_READ_ONLY");

        var songCaptor = org.mockito.ArgumentCaptor.forClass(Song.class);
        verify(songRepository, times(2)).save(songCaptor.capture());
        assertThat(songCaptor.getAllValues().get(1).getTitle()).isEqualTo("晴天");
        assertThat(songCaptor.getAllValues().get(1).getArtist()).isEqualTo("周杰伦");
        assertThat(songCaptor.getAllValues().get(1).getLanguage()).isEqualTo("国语");
        assertThat(songCaptor.getAllValues().get(1).getTags()).containsExactly("流行");
    }

    @Test
    void configuredExternalDefaultAppliesToNewFilesWithSafeChannelDefaults() throws Exception {
        when(settingService.externalDefaultAudioLayout()).thenReturn(AudioLayout.DUAL_CHANNEL);
        Path source = sourceDir.resolve("周杰伦-晴天-国语-流行.mkv");
        Files.write(source, new byte[]{0x01, 0x23});
        when(tagReader.read(any())).thenReturn(new TagInfo());
        when(ffprobe.probe(source)).thenReturn(new MediaProbe(180_000, 1, 0, true,
                "1920x1080", List.of(), "h264", "aac"));

        scanService.scanAll();

        var fileCaptor = org.mockito.ArgumentCaptor.forClass(SongFile.class);
        verify(songFileRepository, times(2)).save(fileCaptor.capture());
        SongFile indexed = fileCaptor.getAllValues().get(1);
        assertThat(indexed.getAudioLayout()).isEqualTo(AudioLayout.DUAL_CHANNEL);
        assertThat(indexed.getOriginalChannel()).isEqualTo(AudioChannel.LEFT);
        assertThat(indexed.getAccompanimentChannel()).isEqualTo(AudioChannel.RIGHT);
        assertThat(indexed.getOriginalTrackIndex()).isNull();
        assertThat(indexed.getAccompanimentTrackIndex()).isNull();
    }

    @Test
    void aPerFileOverrideSurvivesALaterExternalReprobe() throws Exception {
        when(settingService.externalDefaultAudioLayout()).thenReturn(AudioLayout.DUAL_CHANNEL);
        Path source = sourceDir.resolve("周杰伦-晴天-国语-流行.mkv");
        Files.write(source, new byte[]{0x01, 0x23});
        when(tagReader.read(any())).thenReturn(new TagInfo());
        when(ffprobe.probe(source)).thenReturn(new MediaProbe(180_000, 1, 0, true,
                "1920x1080", List.of(), "h264", "aac"));

        scanService.scanAll();
        SongFile indexed = filesByPath.get(source.toString());
        indexed.setAudioLayout(AudioLayout.NORMAL_STEREO);
        when(settingService.externalDefaultAudioLayout()).thenReturn(AudioLayout.DUAL_TRACK);
        Files.write(source, new byte[]{0x45}, StandardOpenOption.APPEND);

        scanService.scanAll();

        assertThat(filesByPath.get(source.toString()).getAudioLayout()).isEqualTo(AudioLayout.NORMAL_STEREO);
    }

    @Test
    void oneTrackFileDoesNotKeepAnInvalidExternalDualTrackDefault() throws Exception {
        when(settingService.externalDefaultAudioLayout()).thenReturn(AudioLayout.DUAL_TRACK);
        Path source = sourceDir.resolve("周杰伦-晴天-国语-流行.mkv");
        Files.write(source, new byte[]{0x01, 0x23});
        when(tagReader.read(any())).thenReturn(new TagInfo());
        when(ffprobe.probe(source)).thenReturn(new MediaProbe(180_000, 1, 0, true,
                "1920x1080", List.of(), "h264", "aac"));

        scanService.scanAll();

        assertThat(filesByPath.get(source.toString()).getAudioLayout())
                .isEqualTo(AudioLayout.NORMAL_STEREO);
        assertThat(filesByPath.get(source.toString()).getOriginalTrackIndex()).isNull();
        assertThat(filesByPath.get(source.toString()).getAccompanimentTrackIndex()).isNull();
    }

    @Test
    void failedProbeRetryPreservesPerFileOverrideOnThePendingPlaceholder() throws Exception {
        when(settingService.externalDefaultAudioLayout()).thenReturn(AudioLayout.DUAL_TRACK);
        Path source = sourceDir.resolve("周杰伦-晴天-国语-流行.mkv");
        Files.write(source, new byte[]{0x01, 0x23});
        when(tagReader.read(any())).thenReturn(new TagInfo());
        when(ffprobe.probe(source))
                .thenThrow(new MediaProbeException("temporary probe failure"))
                .thenReturn(new MediaProbe(180_000, 1, 0, true,
                        "1920x1080", List.of(), "h264", "aac"));

        scanService.scanAll();
        SongFile pending = filesByPath.get(source.toString());
        assertThat(pending.isProbePending()).isTrue();
        pending.setAudioLayout(AudioLayout.DUAL_CHANNEL);

        scanService.scanAll();

        SongFile retried = filesByPath.get(source.toString());
        assertThat(retried.getAudioLayout()).isEqualTo(AudioLayout.DUAL_CHANNEL);
        assertThat(retried.getOriginalChannel()).isEqualTo(AudioChannel.LEFT);
        assertThat(retried.getAccompanimentChannel()).isEqualTo(AudioChannel.RIGHT);
        verify(ffprobe, times(2)).probe(source);
    }

    @Test
    void scanUsesTheLongestReliableArtistFromTheExistingLibrary() throws Exception {
        Song shortArtist = new Song();
        shortArtist.setArtist("A");
        shortArtist.setStatus("ok");
        Song longArtist = new Song();
        longArtist.setArtist("A-Lin");
        longArtist.setStatus("ok");
        when(songRepository.findAll()).thenReturn(List.of(shortArtist, longArtist));

        Path source = sourceDir.resolve("A-Lin-给我一个理由忘记-国语-流行.mkv");
        Files.write(source, new byte[]{0x11, 0x22});
        when(tagReader.read(any())).thenReturn(new TagInfo());
        when(ffprobe.probe(source)).thenReturn(new MediaProbe(180_000, 2, 0, true,
                "1920x1080", List.of(), "h264", "aac"));

        scanService.scanAll();

        var songCaptor = org.mockito.ArgumentCaptor.forClass(Song.class);
        verify(songRepository, times(2)).save(songCaptor.capture());
        assertThat(songCaptor.getAllValues().get(1).getArtist()).isEqualTo("A-Lin");
        assertThat(songCaptor.getAllValues().get(1).getTitle()).isEqualTo("给我一个理由忘记");
    }

    @Test
    void externalScanIgnoresMediaSymlinkOutsideTheSourceRoot() throws Exception {
        Path outsideDir = Files.createDirectory(tempDir.resolve("outside"));
        Files.write(outsideDir.resolve("outside.mkv"), new byte[]{0x01, 0x02});
        Path linkDir = sourceDir.resolve("linked-directory");
        createDirectoryLinkOrSkip(linkDir, outsideDir);

        LibraryScanService.ScanResult result = scanService.scanAll();

        assertThat(result.scanned()).isZero();
        org.mockito.Mockito.verifyNoInteractions(ffprobe, tagReader);
    }

    private static void createDirectoryLinkOrSkip(Path link, Path target) throws Exception {
        try {
            Files.createSymbolicLink(link, target);
            return;
        } catch (UnsupportedOperationException | SecurityException e) {
        } catch (java.io.IOException e) {
        }
        if (!System.getProperty("os.name").toLowerCase().contains("win")) {
            Assumptions.assumeTrue(false, "当前文件系统不支持目录链接");
        }
        String command = "mklink /J \"" + link + "\" \"" + target + "\"";
        Process process = new ProcessBuilder("cmd.exe", "/c", command)
                .redirectErrorStream(true)
                .start();
        process.getInputStream().readAllBytes();
        int exitCode = process.waitFor();
        Assumptions.assumeTrue(exitCode == 0 && Files.isDirectory(link),
                "当前运行权限不允许创建目录 Junction");
    }

}
