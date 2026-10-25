package com.homektv.library;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProcessOutputTailTest {

    @Test
    void keepsOnlyTheTailWhileDrainingTheWholeProcessOutput() throws Exception {
        String output = "start-" + "x".repeat(20_000) + "-final-error";

        String tail = ProcessOutputTail.read(
                new ByteArrayInputStream(output.getBytes(StandardCharsets.UTF_8)),
                StandardCharsets.UTF_8, 128);

        assertThat(tail).hasSize(128);
        assertThat(tail).endsWith("-final-error");
        assertThat(tail).doesNotContain("start-");
    }

    @Test
    void rejectsNonPositiveTailLimits() {
        assertThatThrownBy(() -> ProcessOutputTail.read(
                new ByteArrayInputStream(new byte[0]), StandardCharsets.UTF_8, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("maxBytes must be positive");
        assertThatThrownBy(() -> ProcessOutputTail.read(
                new ByteArrayInputStream(new byte[0]), StandardCharsets.UTF_8, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("maxBytes must be positive");
    }
}
