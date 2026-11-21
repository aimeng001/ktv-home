package com.homektv.library;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AutoInferenceLibraryScanTest {

    @Test
    @DisplayName("字符集推导：日文假名自动推导为日语，无人工介入")
    void inferLanguageJapanese() {
        assertThat(FilenameParser.inferLanguage("残酷な天使のテーゼ", "高桥洋子")).isEqualTo("日语");
        assertThat(FilenameParser.inferLanguage("Lemon", "米津玄師")).isEqualTo("国语"); // 含有汉字且无假名
        assertThat(FilenameParser.inferLanguage("さくら", "森山直太朗")).isEqualTo("日语");
    }

    @Test
    @DisplayName("字符集推导：韩文字符自动推导为韩语")
    void inferLanguageKorean() {
        assertThat(FilenameParser.inferLanguage("강남스타일", "싸이")).isEqualTo("韩语");
        assertThat(FilenameParser.inferLanguage("Gee", "소녀시대")).isEqualTo("韩语");
    }

    @Test
    @DisplayName("字符集推导：纯英文拉丁目动推导为英语")
    void inferLanguageEnglish() {
        assertThat(FilenameParser.inferLanguage("Hotel California", "Eagles")).isEqualTo("英语");
        assertThat(FilenameParser.inferLanguage("Shape of You", "Ed Sheeran")).isEqualTo("英语");
    }

    @Test
    @DisplayName("字符集推导：普通中文歌曲默认推导为国语")
    void inferLanguageChinese() {
        assertThat(FilenameParser.inferLanguage("晴天", "周杰伦")).isEqualTo("国语");
        assertThat(FilenameParser.inferLanguage("十年", "陈奕迅")).isEqualTo("国语");
    }

    @Test
    @DisplayName("主题分类推导：儿歌、戏曲、红歌、抖音关键词自动打上对应分类")
    void inferCategoryKeywords() {
        assertThat(FilenameParser.inferCategory("小燕子 (经典儿歌)", "少儿")).isEqualTo("儿歌");
        assertThat(FilenameParser.inferCategory("女驸马 谁料皇榜中状元", "黄梅戏")).isEqualTo("戏曲");
        assertThat(FilenameParser.inferCategory("我的祖国", "郭兰英 革命红歌")).isEqualTo("红歌");
        assertThat(FilenameParser.inferCategory("学猫叫", "小潘潘 抖音热歌")).isEqualTo("抖音");
    }
}
