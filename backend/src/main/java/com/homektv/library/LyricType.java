package com.homektv.library;

import java.util.regex.Pattern;

/**
 * 歌词类型判定（P1.4，详设§10 lyric_type 枚举：word/line/sub/none）。
 */
public final class LyricType {

    public static final String WORD = "word";   // 逐字（KSC / 增强 LRC 带内联时间标签）
    public static final String LINE = "line";   // 逐行 LRC
    public static final String NONE = "none";

    // 行首时间标签 [mm:ss.xx]
    private static final Pattern LINE_TAG = Pattern.compile("\\[\\d{1,2}:\\d{2}(\\.\\d{1,3})?]");
    // 逐字内联时间标签 <mm:ss.xx> （增强 LRC）
    private static final Pattern WORD_TAG = Pattern.compile("<\\d{1,2}:\\d{2}(\\.\\d{1,3})?>");

    private LyricType() {}

    /**
     * 根据歌词文本内容判定类型。
     * - 含内联 &lt;时间&gt; 标签 → word（逐字）
     * - 仅含行首 [时间] → line（逐行）
     * - 否则 → none
     */
    public static String detect(String lyricText) {
        if (lyricText == null || lyricText.isBlank()) return NONE;
        if (WORD_TAG.matcher(lyricText).find()) return WORD;
        if (LINE_TAG.matcher(lyricText).find()) return LINE;
        return NONE;
    }
}
