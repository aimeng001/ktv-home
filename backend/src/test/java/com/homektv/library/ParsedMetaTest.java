package com.homektv.library;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ParsedMetaTest {

    @Test
    void canonicalConstructorNormalizesNullAndWhitespaceAndRestrictsStatusValues() {
        ParsedMeta normalized = new ParsedMeta(null, " 歌手 ", " 粤语 ", null,
                " 合唱 ", "unknown-status", " 00123456 ");
        ParsedMeta missing = new ParsedMeta("  ", null, "\t", " 流行 ", null,
                ParsedMeta.NEEDS_REVIEW, null);

        assertThat(normalized).isEqualTo(new ParsedMeta("", "歌手", "粤语", "", "合唱",
                ParsedMeta.RECOGNIZED, "00123456"));
        assertThat(normalized.recognized()).isTrue();
        assertThat(normalized.needsReview()).isFalse();
        assertThat(missing.title()).isEmpty();
        assertThat(missing.artist()).isEmpty();
        assertThat(missing.language()).isEmpty();
        assertThat(missing.category()).isEqualTo("流行");
        assertThat(missing.vocalForm()).isEmpty();
        assertThat(missing.catalogNumber()).isEmpty();
        assertThat(missing.recognized()).isFalse();
        assertThat(missing.needsReview()).isTrue();
    }

    @Test
    void compatibilityConstructorsRetainLegacyArgumentSemantics() {
        ParsedMeta fiveArguments = new ParsedMeta("歌名", "歌手", "国语", "流行", ParsedMeta.RECOGNIZED);
        ParsedMeta sixArguments = new ParsedMeta("歌名", "歌手", "国语", "流行", "对唱",
                ParsedMeta.NEEDS_REVIEW);
        ParsedMeta recognized = new ParsedMeta("歌名", "歌手", true);
        ParsedMeta needsReview = new ParsedMeta("歌名", "歌手", false);

        assertThat(fiveArguments.vocalForm()).isEmpty();
        assertThat(fiveArguments.catalogNumber()).isEmpty();
        assertThat(fiveArguments.recognized()).isTrue();
        assertThat(sixArguments.vocalForm()).isEqualTo("对唱");
        assertThat(sixArguments.catalogNumber()).isEmpty();
        assertThat(sixArguments.needsReview()).isTrue();
        assertThat(recognized.status()).isEqualTo(ParsedMeta.RECOGNIZED);
        assertThat(needsReview.status()).isEqualTo(ParsedMeta.NEEDS_REVIEW);
    }

    @Test
    void catalogNumberCopyNormalizesItsValueWithoutChangingOtherFields() {
        ParsedMeta original = new ParsedMeta(" 歌名 ", " 歌手 ", "国语", "流行", "独唱",
                ParsedMeta.RECOGNIZED);

        ParsedMeta numbered = original.withCatalogNumber(" 00123456 ");
        ParsedMeta withoutNumber = numbered.withCatalogNumber(null);

        assertThat(numbered).isEqualTo(new ParsedMeta("歌名", "歌手", "国语", "流行", "独唱",
                ParsedMeta.RECOGNIZED, "00123456"));
        assertThat(withoutNumber.catalogNumber()).isEmpty();
        assertThat(withoutNumber.title()).isEqualTo("歌名");
        assertThat(withoutNumber.status()).isEqualTo(ParsedMeta.RECOGNIZED);
    }

    @Test
    void unrecognizedFactoryNormalizesNullAndPreservesFallbackTitle() {
        ParsedMeta nullFallback = ParsedMeta.unrecognized(null);
        ParsedMeta namedFallback = ParsedMeta.unrecognized("  歌名 ");

        assertThat(nullFallback.title()).isEmpty();
        assertThat(nullFallback.artist()).isEmpty();
        assertThat(nullFallback.needsReview()).isTrue();
        assertThat(namedFallback.title()).isEqualTo("歌名");
        assertThat(namedFallback.needsReview()).isTrue();
    }

    @Test
    void twoFieldFactoryUsesTitlePresenceAsItsRecognitionRule() {
        assertThat(ParsedMeta.of(null, "歌手").needsReview()).isTrue();
        assertThat(ParsedMeta.of(" \t ", "歌手").needsReview()).isTrue();

        ParsedMeta titleOnly = ParsedMeta.of("  歌名 ", null);

        assertThat(titleOnly.title()).isEqualTo("歌名");
        assertThat(titleOnly.artist()).isEmpty();
        assertThat(titleOnly.recognized()).isTrue();
    }

    @Test
    void fourAndFiveFieldFactoriesRequireEveryCoreMetadataValueButNotVocalForm() {
        ParsedMeta complete = ParsedMeta.of(" 歌名 ", " 歌手 ", "国语", "流行");
        ParsedMeta withVocalForm = ParsedMeta.of("歌名", "歌手", "国语", "流行", " 合唱 ");

        assertThat(complete.recognized()).isTrue();
        assertThat(complete.vocalForm()).isEmpty();
        assertThat(withVocalForm.recognized()).isTrue();
        assertThat(withVocalForm.vocalForm()).isEqualTo("合唱");

        assertThat(ParsedMeta.of(null, "歌手", "国语", "流行", "独唱").needsReview()).isTrue();
        assertThat(ParsedMeta.of(" \t ", "歌手", "国语", "流行", "独唱").needsReview()).isTrue();
        assertThat(ParsedMeta.of("歌名", null, "国语", "流行", "独唱").needsReview()).isTrue();
        assertThat(ParsedMeta.of("歌名", " ", "国语", "流行", "独唱").needsReview()).isTrue();
        assertThat(ParsedMeta.of("歌名", "歌手", null, "流行", "独唱").needsReview()).isTrue();
        assertThat(ParsedMeta.of("歌名", "歌手", " ", "流行", "独唱").needsReview()).isTrue();
        assertThat(ParsedMeta.of("歌名", "歌手", "国语", null, "独唱").needsReview()).isTrue();
        assertThat(ParsedMeta.of("歌名", "歌手", "国语", "\t", "独唱").needsReview()).isTrue();
        assertThat(ParsedMeta.of("歌名", "歌手", "国语", "流行", null).recognized()).isTrue();
    }

    @Test
    void recordEqualityDistinguishesMetadataAndSupportsMatchingValues() {
        ParsedMeta value = new ParsedMeta("歌名", "歌手", "国语", "流行", "独唱",
                ParsedMeta.RECOGNIZED, "00123456");
        ParsedMeta equalValue = new ParsedMeta("歌名", "歌手", "国语", "流行", "独唱",
                ParsedMeta.RECOGNIZED, "00123456");
        ParsedMeta other = value.withCatalogNumber("87654321");

        assertThat(value).isEqualTo(equalValue);
        assertThat(value.hashCode()).isEqualTo(equalValue.hashCode());
        assertThat(value).isNotEqualTo(other);
        assertThat(value.toString()).contains("歌名", "00123456");
    }
}
