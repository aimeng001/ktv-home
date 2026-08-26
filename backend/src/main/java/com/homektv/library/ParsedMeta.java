package com.homektv.library;

/**
 * 从标签或文件名解析出的元数据（P1.1/P1.2）。
 *
 * @param title    歌名（可能为空 → 待审核）
 * @param artist   歌手（可能为空）
 * @param language 语种（文件名未提供时为空）
 * @param category 分类（文件名未提供时为空）
 * @param status    {@link #RECOGNIZED} 或 {@link #NEEDS_REVIEW}
 */
public record ParsedMeta(String title, String artist, String language, String category, String status) {

    public static final String RECOGNIZED = "RECOGNIZED";
    public static final String NEEDS_REVIEW = "NEEDS_REVIEW";

    public ParsedMeta {
        title = title == null ? "" : title.trim();
        artist = artist == null ? "" : artist.trim();
        language = language == null ? "" : language.trim();
        category = category == null ? "" : category.trim();
        status = NEEDS_REVIEW.equals(status) ? NEEDS_REVIEW : RECOGNIZED;
    }

    /** 保持既有调用方使用 boolean recognized() 的兼容 API。 */
    public ParsedMeta(String title, String artist, boolean recognized) {
        this(title, artist, "", "", recognized ? RECOGNIZED : NEEDS_REVIEW);
    }

    public boolean recognized() {
        return RECOGNIZED.equals(status);
    }

    public boolean needsReview() {
        return NEEDS_REVIEW.equals(status);
    }

    public static ParsedMeta unrecognized(String fallbackTitle) {
        return new ParsedMeta(fallbackTitle, "", "", "", NEEDS_REVIEW);
    }

    public static ParsedMeta of(String title, String artist) {
        boolean ok = title != null && !title.isBlank();
        return new ParsedMeta(
                ok ? title : "",
                artist,
                "",
                "",
                ok ? RECOGNIZED : NEEDS_REVIEW
        );
    }

    public static ParsedMeta of(String title, String artist, String language, String category) {
        boolean ok = title != null && !title.isBlank()
                && artist != null && !artist.isBlank()
                && language != null && !language.isBlank()
                && category != null && !category.isBlank();
        return new ParsedMeta(title, artist, language, category, ok ? RECOGNIZED : NEEDS_REVIEW);
    }
}
