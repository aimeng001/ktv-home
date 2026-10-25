package com.homektv.library;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
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

    @Test
    void splitsPlusOnlyWhenEveryCollaboratorIsKnown() {
        assertThat(ArtistCreditParser.parse("周杰伦+++林俊杰", List.of("周杰伦", "林俊杰")))
                .containsExactly("周杰伦", "林俊杰");
        assertThat(ArtistCreditParser.parse("周杰伦+++未知歌手", List.of("周杰伦")))
                .containsExactly("周杰伦+++未知歌手");
        assertThat(ArtistCreditParser.parse("周杰伦+++林俊杰", null))
                .containsExactly("周杰伦+++林俊杰");
        assertThat(ArtistCreditParser.parse("A+++B", List.of()))
                .containsExactly("A+++B");
        assertThat(ArtistCreditParser.parse("+++", List.of("A")))
                .containsExactly("+++");
    }

    @Test
    void usesKnownMultiwordArtistsToCoverTheWholeCredit() {
        assertThat(ArtistCreditParser.parse("D.N.A 张艺兴 王祖蓝",
                List.of("D.N.A 张艺兴", "王祖蓝")))
                .containsExactly("D.N.A 张艺兴", "王祖蓝");
        assertThat(ArtistCreditParser.parse("张三 李四",
                List.of("张三", "张三 李四")))
                .containsExactly("张三 李四");
        assertThat(ArtistCreditParser.parse("张三 未知歌手",
                List.of("张三", "李四")))
                .containsExactly("张三 未知歌手");
        assertThat(ArtistCreditParser.parse("A B C D", List.of("A B", "A", "B", "C")))
                .containsExactly("A B C D");
    }

    @Test
    void onlySplitsWhitespaceCreditsWhenKnownNamesCoverEveryToken() {
        assertThat(ArtistCreditParser.parse("张三 李四", null))
                .containsExactly("张三 李四");
        assertThat(ArtistCreditParser.parse("张三 李四", List.of()))
                .containsExactly("张三 李四");
        assertThat(ArtistCreditParser.parse("张三 李四", Arrays.asList(null, " ")))
                .containsExactly("张三 李四");
        assertThat(ArtistCreditParser.parse("张三 李四", Arrays.asList(null, " ", "张三", " 张三 ", "李四")))
                .containsExactly("张三", "李四");
    }

    @Test
    void treatsNullAndBlankCreditsAsEmptyAndDeduplicatesNormalizedNames() {
        assertThat(ArtistCreditParser.parse(null)).isEmpty();
        assertThat(ArtistCreditParser.parse(" \t\n", KNOWN_ARTISTS)).isEmpty();
        assertThat(ArtistCreditParser.normalize(null)).isEmpty();
        assertThat(ArtistCreditParser.normalize(List.of())).isEmpty();
        assertThat(ArtistCreditParser.normalize(Arrays.asList(null, " ", " Beyoncé ", "beyoncé")))
                .containsExactly("Beyoncé");
        assertThat(ArtistCreditParser.key(null)).isEmpty();
    }
}
