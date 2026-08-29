package com.homektv.library;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

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
}
