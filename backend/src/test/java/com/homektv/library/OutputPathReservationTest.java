package com.homektv.library;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutputPathReservationTest {

    @TempDir
    Path tempDir;

    @Test
    void concurrentReservationsNeverReturnTheSameExistingTarget() throws Exception {
        Path desired = tempDir.resolve("song.mkv");

        Path first = OutputPathReservation.reserve(desired);
        Path second = OutputPathReservation.reserve(desired);

        assertThat(first).isEqualTo(desired);
        assertThat(Files.exists(first)).isTrue();
        assertThat(second).isNotEqualTo(first);
        assertThat(Files.exists(second)).isTrue();
    }

    @Test
    void skipsConflictingNamesForAFileWithoutAnExtension() throws Exception {
        Path desired = tempDir.resolve("song");
        Files.createFile(desired);
        Files.createFile(tempDir.resolve("song-2"));

        Path reserved = OutputPathReservation.reserve(desired);

        assertThat(reserved).isEqualTo(tempDir.resolve("song-3"));
        assertThat(Files.exists(reserved)).isTrue();
    }

    @Test
    void rejectsAPathThatHasNoParentDirectory() {
        Path filesystemRoot = tempDir.getRoot();

        assertThatThrownBy(() -> OutputPathReservation.reserve(filesystemRoot))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("输出路径没有父目录");
    }

    @Test
    void failsAfterEveryCandidateInTheConfiguredBoundIsAlreadyTaken() throws Exception {
        Path desired = tempDir.resolve("occupied.mkv");
        Files.createFile(desired);
        Files.createFile(tempDir.resolve("occupied-2.mkv"));

        assertThatThrownBy(() -> OutputPathReservation.reserve(desired, 2))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("无法为输出文件分配唯一文件名");
        assertThat(Files.exists(desired)).isTrue();
        assertThat(Files.exists(tempDir.resolve("occupied-2.mkv"))).isTrue();
    }
}
