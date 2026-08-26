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
}
