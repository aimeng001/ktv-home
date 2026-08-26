package com.homektv.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class AppPropertiesTest {
    @Test
    void defaultReleaseAnnouncementDoesNotInstructAutomaticSourceCleanup() {
        String message = new AppProperties().getRelease().getAnnouncement().getMessage();

        assertThat(message).doesNotContain("自动清理");
        assertThat(message).doesNotContain("先执行");
    }

    @Test
    void packagedReleaseAnnouncementDoesNotInstructAutomaticSourceCleanup() throws Exception {
        ClassPathResource applicationYaml = new ClassPathResource("application.yml");
        String contents;
        try (var input = applicationYaml.getInputStream()) {
            contents = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(contents).doesNotContain("自动清理");
        assertThat(contents).doesNotContain("先执行");
    }
}
