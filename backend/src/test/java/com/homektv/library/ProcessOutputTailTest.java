package com.homektv.library;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

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
}
