package com.homektv.library;

import com.homektv.config.AppProperties;
import com.homektv.domain.Song;
import com.homektv.domain.SongFile;
import com.homektv.media.FFprobeService;
import com.homektv.media.MediaProbe;
import com.homektv.repo.SongFileRepository;
import com.homektv.repo.SongRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExternalReadOnlyLibraryTest {

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

    private LibraryScanService scanService;
    private Path sourceDir;

    @BeforeEach
    void setUp() throws Exception {
        sourceDir = Files.createDirectory(tempDir.resolve("nas-copy"));
        AppProperties props = new AppProperties();
        props.setSourceLibraryPath(sourceDir.toString());
        props.setKtvLibraryPath(tempDir.resolve("managed").toString());
        props.setDataPath(tempDir.resolve("data").toString());
        props.setLibraryMode(LibraryMode.EXTERNAL_READ_ONLY);

        when(songFileRepository.findByFilePath(anyString())).thenReturn(Optional.empty());
        when(songRepository.findByFingerprint(anyString())).thenReturn(Optional.empty());
        when(songRepository.save(any(Song.class))).thenAnswer(invocation -> {
            Song song = invocation.getArgument(0);
            song.setId(101L);
            return song;
        });
        when(songFileRepository.save(any(SongFile.class))).thenAnswer(invocation -> {
            SongFile songFile = invocation.getArgument(0);
            songFile.setId(202L);
            return songFile;
        });

        scanService = new LibraryScanService(props, ffprobe, tagReader, songRepository,
                songFileRepository, new AssetWriter(props));
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
        verify(songFileRepository).save(fileCaptor.capture());
        assertThat(fileCaptor.getValue().getFilePath()).isEqualTo(source.toString());
        assertThat(fileCaptor.getValue().getFileRole()).isEqualTo("EXTERNAL_READ_ONLY");
    }

}
