package com.homektv.library;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FilenameParserTest {

    private static final List<String> EXISTING_ARTISTS = List.of(
            "A", "A-Lin", "Beyond", "草蜢"
    );

    @Test
    void parsesStandardFilenameFromTheRight() {
        assertStandard("草蜢-爱-国语-流行.mkv", "草蜢", "爱", "国语", "流行");
        assertStandard("Beyond-海阔天空-粤语-摇滚.mkv", "Beyond", "海阔天空", "粤语", "摇滚");
        assertStandard("Beyond-海阔天空-Live版-粤语-摇滚.mkv",
                "Beyond", "海阔天空-Live版", "粤语", "摇滚");
        assertStandard("A-Lin-给我一个理由忘记-国语-流行.mkv",
                "A-Lin", "给我一个理由忘记", "国语", "流行");
    }

    @Test
    void acceptsRedundantSeparatorsOnlyWhenTheRemainingFieldsAreUnambiguous() {
        assertStandard("童唱--爸爸妈妈听我说-国语-儿歌.mkv",
                "童唱", "爸爸妈妈听我说", "国语", "儿歌");
        assertStandard("赵雷-成都GS-国语--流行.mkv",
                "赵雷", "成都GS", "国语", "流行");
        assertStandard("谢霆锋&感应Telepathy-感应-国语--流行.mkv",
                "谢霆锋&感应Telepathy", "感应", "国语", "流行");
    }

    @Test
    void keepsEmDashAsTitlePunctuation() {
        assertStandard("郑秀文-加而各答的天使——德蕾修女-粤语-流行.mkv",
                "郑秀文", "加而各答的天使——德蕾修女", "粤语", "流行");
    }

    @Test
    void removesALeadingLanguageOnlyWhenTheRemainingShapeIsValid() {
        assertStandard("国语-苏晨-坚强-国语-流行.mkv",
                "苏晨", "坚强", "国语", "流行");
        assertStandard("粤语-苏永康-红颜知己-粤语-流行.mkv",
                "苏永康", "红颜知己", "粤语", "流行");
    }

    @Test
    void keepsMissingTitleForReviewInsteadOfGuessing() {
        assertNeedsReview("陈奕迅--国语-流行.mkv");
        assertNeedsReview("李宇春--国语-流行.mkv");
        assertNeedsReview("李荣浩--国语-流行.mkv");
    }

    @Test
    void extractsARecognizedVocalFormWithoutAppendingItToTheTitle() {
        ParsedMeta parsed = FilenameParser.parse(
                "张庭 钟丽缇 王祖蓝-爱上幼儿园-合唱-国语-流行.mkv");

        assertThat(parsed.artist()).isEqualTo("张庭 钟丽缇 王祖蓝");
        assertThat(parsed.title()).isEqualTo("爱上幼儿园");
        assertThat(parsed.vocalForm()).isEqualTo("合唱");
        assertThat(parsed.language()).isEqualTo("国语");
        assertThat(parsed.category()).isEqualTo("流行");
        assertThat(parsed.recognized()).isTrue();
    }

    @Test
    void parsesCollaborativeArtistBlockWithoutSplittingUnderscores() {
        assertStandard("D.N.A 张艺兴_GALI_单依纯_王子异-D.N.A Cypher I-国语-合唱.mkv",
                "D.N.A 张艺兴_GALI_单依纯_王子异", "D.N.A Cypher I", "国语", "合唱");
    }

    @Test
    void parsesOtherObservedCollaborativeArtistBlocks() {
        assertStandard("黄绮珊_希林娜依高-是真的吗妈妈是女儿(2023央视春晚)-国语-合唱.mkv",
                "黄绮珊_希林娜依高", "是真的吗妈妈是女儿(2023央视春晚)", "国语", "合唱");
        assertStandard("雷亿_二哥莫姓-首山湖的眷恋-国语-合唱.mkv",
                "雷亿_二哥莫姓", "首山湖的眷恋", "国语", "合唱");
        assertStandard("Kkecho_Ty._Redboi-超-国语-合唱.mkv",
                "Kkecho_Ty._Redboi", "超", "国语", "合唱");
    }

    @Test
    void recognizesOtherAndUnknownAsStandardLanguages() {
        assertStandard("草蜢-爱-其他-流行.mkv", "草蜢", "爱", "其他", "流行");
        assertStandard("草蜢-爱-未知-流行.mkv", "草蜢", "爱", "未知", "流行");
        assertStandard("一绫-爱情烧抹退-闽南-流行.mkv", "一绫", "爱情烧抹退", "闽南语", "流行");
    }

    @Test
    void usesTheLongestExistingArtistMatch() {
        ParsedMeta parsed = FilenameParser.parse(
                "A-Lin-给我一个理由忘记-国语-流行.mkv", EXISTING_ARTISTS);

        assertThat(parsed.artist()).isEqualTo("A-Lin");
        assertThat(parsed.title()).isEqualTo("给我一个理由忘记");
        assertThat(parsed.recognized()).isTrue();
    }

    @Test
    void preparedArtistIndexKeepsLongestMatchBehavior() {
        FilenameParser.ArtistIndex index = FilenameParser.prepareKnownArtists(EXISTING_ARTISTS);

        ParsedMeta parsed = FilenameParser.parse(
                "A-Lin-给我一个理由忘记-国语-流行.mkv", index);

        assertThat(parsed.artist()).isEqualTo("A-Lin");
        assertThat(parsed.title()).isEqualTo("给我一个理由忘记");
    }

    @Test
    void marksUnreliableFilenameForReviewWithoutChangingItsText() {
        String filename = "名称不规范.mkv";

        ParsedMeta parsed = FilenameParser.parse(filename, EXISTING_ARTISTS);

        assertThat(parsed.status()).isEqualTo(ParsedMeta.NEEDS_REVIEW);
        assertThat(parsed.needsReview()).isTrue();
        assertThat(parsed.recognized()).isFalse();
        assertThat(parsed.title()).isEqualTo("名称不规范");
        assertThat(parsed.artist()).isBlank();
        assertThat(parsed.language()).isBlank();
        assertThat(parsed.category()).isBlank();
        assertThat(filename).isEqualTo("名称不规范.mkv");
    }

    private void assertStandard(String filename, String artist, String title,
                                String language, String category) {
        ParsedMeta parsed = FilenameParser.parse(filename, EXISTING_ARTISTS);

        assertThat(parsed.artist()).isEqualTo(artist);
        assertThat(parsed.title()).isEqualTo(title);
        assertThat(parsed.language()).isEqualTo(language);
        assertThat(parsed.category()).isEqualTo(category);
        assertThat(parsed.status()).isEqualTo(ParsedMeta.RECOGNIZED);
        assertThat(parsed.recognized()).isTrue();
    }

    private void assertNeedsReview(String filename) {
        ParsedMeta parsed = FilenameParser.parse(filename, EXISTING_ARTISTS);

        assertThat(parsed.status()).isEqualTo(ParsedMeta.NEEDS_REVIEW);
        assertThat(parsed.needsReview()).isTrue();
    }

    @Test
    void preservesBracketedArtistNamesWhileStrippingQualityAndCategoryTags() {
        assertStandard("[Alexandros]-Wataridori-日语-流行.mkv", "[Alexandros]", "Wataridori", "日语", "流行");
        assertStandard("[ALEX]-Love Song-英语-流行.mkv", "[ALEX]", "Love Song", "英语", "流行");
        assertStandard("【经典红歌】草蜢-爱-国语-流行.mkv", "草蜢", "爱", "国语", "流行");
        assertStandard("[4K超清]Beyond-海阔天空-粤语-摇滚.mkv", "Beyond", "海阔天空", "粤语", "摇滚");
        assertStandard("Beyond-海阔天空 [4K修复]-粤语-摇滚.mkv", "Beyond", "海阔天空", "粤语", "摇滚");
    }

    @Test
    void parsesNumericArtistNamesCorrectlyWithoutMistakingThemAsTrackNumbers() {
        assertStandard("1983-风打雨下-国语-流行.mkv", "1983", "风打雨下", "国语", "流行");
        assertStandard("1976-前王子-国语-流行.mkv", "1976", "前王子", "国语", "流行");
        assertStandard("51-自己-国语-流行.mkv", "51", "自己", "国语", "流行");
        assertStandard("51-喇勹一赛-国语-流行.mkv", "51", "喇勹一赛", "国语", "流行");
        assertStandard("1314-我从陕北来-国语-流行.mkv", "1314", "我从陕北来", "国语", "流行");
        assertStandard("1976-世界尽头-国语-流行.mkv", "1976", "世界尽头", "国语", "流行");
        assertStandard("51-标准-国语-流行.mkv", "51", "标准", "国语", "流行");
    }

    @Test
    void stripsLeadingTrackNumbersWhenFollowedByArtistAndTitle() {
        assertStandard("01-周杰伦-晴天-国语-流行.mkv", "周杰伦", "晴天", "国语", "流行");
        assertStandard("002.周杰伦-晴天-国语-流行.mkv", "周杰伦", "晴天", "国语", "流行");
        assertStandard("【03】周杰伦-晴天-国语-流行.mkv", "周杰伦", "晴天", "国语", "流行");
        assertStandard("(04) 周杰伦-晴天-国语-流行.mkv", "周杰伦", "晴天", "国语", "流行");

        ParsedMeta legacyWithTrack = FilenameParser.parse("01-周杰伦-晴天.mkv");
        assertThat(legacyWithTrack.artist()).isEqualTo("周杰伦");
        assertThat(legacyWithTrack.title()).isEqualTo("晴天");
    }

    @Test
    void preservesEightDigitCatalogNumberSeparatelyFromTrackNumbers() {
        ParsedMeta parsed = FilenameParser.parse("00123456-周杰伦-晴天-国语-流行.mkv", EXISTING_ARTISTS);

        assertThat(parsed.catalogNumber()).isEqualTo("00123456");
        assertThat(parsed.artist()).isEqualTo("周杰伦");
        assertThat(parsed.title()).isEqualTo("晴天");
        assertThat(parsed.recognized()).isTrue();
    }
}
