package com.homektv.library;

import com.homektv.config.AppProperties;
import com.homektv.ws.WsBroadcaster;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LibraryWatchServiceTest {

    private final AppProperties props = new AppProperties();
    private final SettingService settingService = mock(SettingService.class);
    private final LibraryScanService scanService = mock(LibraryScanService.class);
    private final MediaImportService importService = mock(MediaImportService.class);
    private final WsBroadcaster broadcaster = mock(WsBroadcaster.class);
    private LibraryWatchService watchService;

    @AfterEach
    void stopWatching() {
        if (watchService != null) watchService.stop();
    }

    @Test
    void filesCreatedInANewDirectoryRemainWatched() throws Exception {
        Path root = Files.createTempDirectory("ktv-watch-");
        props.setSourceLibraryPath(root.toString());
        props.setLibraryMode(LibraryMode.EXTERNAL_READ_ONLY);
        when(settingService.isLibraryWatchEnabled()).thenReturn(true);
        when(scanService.scanAll()).thenReturn(new LibraryScanService.ScanResult(1, 0, 0, 0, 0));

        watchService = new LibraryWatchService(props, settingService, scanService, importService, broadcaster,
                Duration.ofMillis(25));
        watchService.start();

        Path artistDirectory = Files.createDirectory(root.resolve("artist"));
        verify(scanService, timeout(5_000).times(1)).scanAll();

        reset(scanService);
        when(scanService.scanAll()).thenReturn(new LibraryScanService.ScanResult(1, 0, 0, 0, 0));
        Files.writeString(artistDirectory.resolve("song.mkv"), "media");

        verify(scanService, timeout(5_000).times(1)).scanAll();
    }

    @Test
    void disabledWatchingDoesNotStartAnExternalScan() throws Exception {
        Path root = Files.createTempDirectory("ktv-watch-disabled-");
        props.setSourceLibraryPath(root.toString());
        props.setLibraryMode(LibraryMode.EXTERNAL_READ_ONLY);
        when(settingService.isLibraryWatchEnabled()).thenReturn(false);

        watchService = new LibraryWatchService(props, settingService, scanService, importService, broadcaster);
        watchService.start();
        Files.writeString(root.resolve("song.mkv"), "media");

        verify(scanService, never()).scanAll();
    }
}
