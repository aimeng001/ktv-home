package com.homektv.library;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class LibraryScanFileTypeTest {
    private static final Set<String> MEDIA_EXTENSIONS = Set.of(
            "mkv", "mp4", "m4v", "avi", "mov", "ts", "m2ts", "mts", "mpg", "mpeg",
            "vob", "webm", "wmv", "asf", "flv", "f4v", "3gp", "3g2", "rm", "rmvb",
            "mp3", "mp2", "aac", "flac", "wav", "m4a", "ape", "ogg", "oga", "opus",
            "ac3", "eac3", "dts", "mka", "wma", "aiff", "aif", "alac");
    private static final Set<String> AUDIO_EXTENSIONS = Set.of(
            "mp3", "mp2", "aac", "flac", "wav", "m4a", "ape", "ogg", "oga", "opus",
            "ac3", "eac3", "dts", "mka", "wma", "aiff", "aif", "alac");

    @Test
    void recognizesEverySupportedMediaExtensionWithoutCaseSensitivity() {
        for (String extension : MEDIA_EXTENSIONS) {
            assertThat(LibraryScanService.isMediaFile(Path.of("song." + extension.toUpperCase(Locale.ROOT))))
                    .as("media extension %s", extension)
                    .isTrue();
        }
    }

    @Test
    void recognizesOnlyAudioExtensionsAsAudio() {
        for (String extension : AUDIO_EXTENSIONS) {
            assertThat(LibraryScanService.isAudioFile(Path.of("song." + extension)))
                    .as("audio extension %s", extension)
                    .isTrue();
        }
        assertThat(LibraryScanService.isAudioFile(Path.of("video.mp4"))).isFalse();
        assertThat(LibraryScanService.isAudioFile(Path.of("song.lrc"))).isFalse();
    }

    @Test
    void rejectsNullAndPathsWithoutARegularExtension() {
        assertThat(LibraryScanService.isMediaFile(null)).isFalse();
        assertThat(LibraryScanService.isAudioFile(null)).isFalse();
        assertThat(LibraryScanService.isMediaFile(Path.of("README"))).isFalse();
        assertThat(LibraryScanService.isMediaFile(Path.of(".mp4"))).isFalse();
        assertThat(LibraryScanService.isAudioFile(Path.of(".mp3"))).isFalse();
    }
}
