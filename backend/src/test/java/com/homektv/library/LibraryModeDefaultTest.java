package com.homektv.library;

import com.homektv.config.AppProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LibraryModeDefaultTest {

    @Test
    void managedModeRemainsTheDefault() {
        assertThat(new AppProperties().getLibraryMode()).isEqualTo(LibraryMode.MANAGED);
    }
}
