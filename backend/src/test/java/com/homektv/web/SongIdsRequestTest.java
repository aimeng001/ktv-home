package com.homektv.web;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SongIdsRequestTest {

    @Test
    void normalizesMissingIdsToAnEmptyListAndPreservesProvidedIds() {
        assertThat(new AdminScanController.SongIdsRequest(null).ids()).isEmpty();

        List<Long> ids = List.of(3L, 8L);
        assertThat(new AdminScanController.SongIdsRequest(ids).ids()).containsExactly(3L, 8L);
    }
}
