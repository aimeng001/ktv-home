package com.homektv.library;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LibraryScanLanguageNormalizationTest {

    @Test
    void parsedMetadataNormalizesNullLanguageBeforeScanNormalization() {
        ParsedMeta parsed = new ParsedMeta("歌名", "歌手", null, "流行", ParsedMeta.RECOGNIZED);

        assertThat(parsed.language()).isEmpty();
        assertThat(LibraryScanService.normalizeLanguage(parsed.language())).isEqualTo("未知");
    }

    @Test
    void blankLanguageIsUnknown() {
        assertThat(LibraryScanService.normalizeLanguage(" \t ")).isEqualTo("未知");
    }

    @Test
    void supportedLanguageAliasesNormalizeToStableCatalogValues() {
        assertThat(LibraryScanService.normalizeLanguage("ZH_cn")).isEqualTo("国语");
        assertThat(LibraryScanService.normalizeLanguage("普通话")).isEqualTo("国语");
        assertThat(LibraryScanService.normalizeLanguage("cantonese")).isEqualTo("粤语");
        assertThat(LibraryScanService.normalizeLanguage("zh-HK")).isEqualTo("粤语");
        assertThat(LibraryScanService.normalizeLanguage("hokkien")).isEqualTo("闽南语");
        assertThat(LibraryScanService.normalizeLanguage("台语")).isEqualTo("闽南语");
        assertThat(LibraryScanService.normalizeLanguage("English")).isEqualTo("英语");
        assertThat(LibraryScanService.normalizeLanguage("日文")).isEqualTo("日语");
        assertThat(LibraryScanService.normalizeLanguage("Korean")).isEqualTo("韩语");
        assertThat(LibraryScanService.normalizeLanguage("instrumental")).isEqualTo("纯音乐");
        assertThat(LibraryScanService.normalizeLanguage("其他")).isEqualTo("其他");
        assertThat(LibraryScanService.normalizeLanguage("未知")).isEqualTo("未知");
        assertThat(LibraryScanService.normalizeLanguage("unlisted-language")).isEqualTo("其他");
    }
}
