package com.homektv.library;

import java.util.Locale;

/**
 * Normalized search input and its SQL LIKE-safe representation.
 *
 * <p>The raw value is kept for exact/canonical semantics, while the lower
 * value is used by case-insensitive pinyin/language/tag comparisons. The
 * LIKE pattern escapes the three PostgreSQL LIKE metacharacters so a user
 * supplied query cannot widen a search accidentally.</p>
 */
public record SearchKeyword(String raw, String lower, String likePattern) {

    public static final int DEFAULT_MAX_LENGTH = 50;

    public static SearchKeyword of(String input, int maxLength) {
        if (maxLength <= 0) {
            throw new IllegalArgumentException("maxLength must be positive");
        }
        String raw = input == null ? "" : input.trim();
        if (raw.length() > maxLength) {
            raw = raw.substring(0, maxLength);
        }
        String lower = raw.toLowerCase(Locale.ROOT);
        return new SearchKeyword(raw, lower, escapeLikePattern(lower));
    }

    private static String escapeLikePattern(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }
}
