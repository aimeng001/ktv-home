package com.homektv.library;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class LibraryScanSidecarTest {

    @TempDir
    Path tempDir;

    @Test
    void absentDirectoryAndUnavailableSidecarsAreNotTreatedAsLyrics() {
        var missing = LibraryScanService.readSidecarLyric(tempDir.resolve("missing.lrc"));
        var directory = LibraryScanService.readSidecarLyric(tempDir);
        var unreadableSnapshot = LibraryScanService.readSidecarLyric(
                tempDir.resolve("unavailable.lrc"), snapshot(false, null));

        assertThat(missing.present()).isFalse();
        assertThat(directory.present()).isFalse();
        assertThat(unreadableSnapshot.present()).isTrue();
        assertThat(unreadableSnapshot.readable()).isFalse();
        assertThat(unreadableSnapshot.text()).isNull();
    }

    @Test
    void rejectsOversizedSnapshotBeforeOpeningTheSidecar() {
        var result = LibraryScanService.readSidecarLyric(
                tempDir.resolve("not-opened.lrc"), snapshot(true, AssetWriter.MAX_LYRIC_BYTES + 1L));

        assertThat(result.present()).isTrue();
        assertThat(result.readable()).isFalse();
        assertThat(result.text()).isNull();
    }

    @Test
    void capsReadWhenSidecarGrowsAfterItsSnapshotWasTaken() throws Exception {
        Path lyric = tempDir.resolve("growing.lrc");
        Files.write(lyric, new byte[AssetWriter.MAX_LYRIC_BYTES + 1]);

        var result = LibraryScanService.readSidecarLyric(lyric, snapshot(true, 1L));

        assertThat(result.present()).isTrue();
        assertThat(result.readable()).isFalse();
        assertThat(result.text()).isNull();
    }

    @Test
    void returnsOnlyRecognizedLrcTextAndKeepsReadFailuresUnreadable() throws Exception {
        Path valid = Files.writeString(tempDir.resolve("valid.lrc"), "[00:01.00]副歌\n");
        Path plainText = Files.writeString(tempDir.resolve("plain.lrc"), "不是歌词时间轴");

        var validResult = LibraryScanService.readSidecarLyric(valid);
        var plainResult = LibraryScanService.readSidecarLyric(plainText);
        var unknownSizeResult = LibraryScanService.readSidecarLyric(valid, snapshot(true, null));
        var racedDeleteResult = LibraryScanService.readSidecarLyric(
                tempDir.resolve("deleted-after-snapshot.lrc"), snapshot(true, 12L));

        assertThat(validResult.readable()).isTrue();
        assertThat(validResult.text()).isEqualTo("[00:01.00]副歌\n");
        assertThat(plainResult.readable()).isTrue();
        assertThat(plainResult.text()).isNull();
        assertThat(unknownSizeResult.readable()).isTrue();
        assertThat(unknownSizeResult.text()).isEqualTo("[00:01.00]副歌\n");
        assertThat(racedDeleteResult.present()).isTrue();
        assertThat(racedDeleteResult.readable()).isFalse();
        assertThat(racedDeleteResult.text()).isNull();
    }

    private static LibraryScanService.LyricSnapshot snapshot(boolean readable, Long size) {
        return new LibraryScanService.LyricSnapshot(true, readable, size,
                OffsetDateTime.parse("2026-10-19T10:00:00Z"), "fixture-key");
    }
}
