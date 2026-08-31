package com.homektv.library;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ArtistCreditParserTest {

    private static final List<String> KNOWN_ARTISTS =
            List.of("张庭", "钟丽缇", "王祖蓝", "D.N.A 张艺兴");

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

    @Test
    void splitsSpaceSeparatedArtistsOnlyWhenKnownArtistsCoverTheWholeCredit() {
        assertThat(ArtistCreditParser.parse("张庭 钟丽缇 王祖蓝", KNOWN_ARTISTS))
                .containsExactly("张庭", "钟丽缇", "王祖蓝");
        assertThat(ArtistCreditParser.parse("未知甲 未知乙", KNOWN_ARTISTS))
                .containsExactly("未知甲 未知乙");
    }

    @Test
    void keepsTheLongestKnownSpaceSeparatedArtistCredit() {
        assertThat(ArtistCreditParser.parse("D.N.A 张艺兴", KNOWN_ARTISTS))
                .containsExactly("D.N.A 张艺兴");
    }

    @Test
    void splitsCommonSlashCommaAndSemicolonCollaborations() {
        assertThat(ArtistCreditParser.parse("周杰伦/林俊杰"))
                .containsExactly("周杰伦", "林俊杰");
        assertThat(ArtistCreditParser.parse("周杰伦，林俊杰;蔡依林"))
                .containsExactly("周杰伦", "林俊杰", "蔡依林");
    }

    @Test
    void preservesKnownArtistNamesThatContainPlusSigns() {
        assertThat(ArtistCreditParser.parse("C++", List.of("C++")))
                .containsExactly("C++");
    }

    @Test
    void splitsAStandalonePlusButKeepsProgrammingLanguageNames() {
        assertThat(ArtistCreditParser.parse("周杰伦+林俊杰"))
                .containsExactly("周杰伦", "林俊杰");
        assertThat(ArtistCreditParser.parse("C++"))
                .containsExactly("C++");
    }
}
