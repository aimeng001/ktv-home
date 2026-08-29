package com.homektv.library;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class LibraryScanMemoryLifecycleTest {

    @Test
    void scannerDoesNotRetainPerScanArtistIndexesInItsSingletonState() {
        Set<String> fields = Arrays.stream(LibraryScanService.class.getDeclaredFields())
                .map(Field::getName)
                .collect(Collectors.toSet());

        assertThat(fields)
                .doesNotContain("cachedArtistNames", "cachedArtistIndex");
    }
}
