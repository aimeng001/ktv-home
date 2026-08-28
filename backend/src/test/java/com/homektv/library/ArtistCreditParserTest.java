package com.homektv.library;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ArtistCreditParserTest {

    @Test
    void splitsCollaborativeUnderscoreCreditsWithoutChangingArtistNames() {
        assertThat(ArtistCreditParser.parse("单依纯_王子异"))
                .containsExactly("单依纯", "王子异");
    }

    @Test
    void preservesARegularArtistAndRemovesDuplicateOrBlankCredits() {
        assertThat(ArtistCreditParser.parse("A-Lin"))
                .containsExactly("A-Lin");
        assertThat(ArtistCreditParser.parse("单依纯__ 王子异 _单依纯"))
                .containsExactly("单依纯", "王子异");
    }
}
