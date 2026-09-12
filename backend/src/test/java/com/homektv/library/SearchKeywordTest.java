package com.homektv.library;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SearchKeywordTest {

    @Test
    void escapesLikeWildcardsAndBackslashesWithoutChangingRawValue() {
        SearchKeyword keyword = SearchKeyword.of("  A%_\\B  ", 50);

        assertThat(keyword.raw()).isEqualTo("A%_\\B");
        assertThat(keyword.lower()).isEqualTo("a%_\\b");
        assertThat(keyword.likePattern()).isEqualTo("a\\%\\_\\\\b");
    }
}
