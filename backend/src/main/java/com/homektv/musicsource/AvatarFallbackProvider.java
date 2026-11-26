package com.homektv.musicsource;

import com.homektv.common.ApiException;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/** Explicit web and image-search fallback providers for artist avatar scraping. */
public enum AvatarFallbackProvider {
    BAIDU_BAIKE("百度百科", false),
    BING_IMAGES("Bing 图片", false),
    BAIDU_IMAGES("百度图片", false),
    DEEZER("Deezer", true),
    WIKIPEDIA_ZH("中文维基百科", true),
    WIKIPEDIA_EN("英文维基百科", true);

    private final String displayName;
    private final boolean overseas;

    AvatarFallbackProvider(String displayName, boolean overseas) {
        this.displayName = displayName;
        this.overseas = overseas;
    }

    public String displayName() {
        return displayName;
    }

    public boolean overseas() {
        return overseas;
    }

    public static Set<AvatarFallbackProvider> defaultEnabled() {
        return EnumSet.of(BAIDU_BAIKE, BING_IMAGES, BAIDU_IMAGES);
    }

    public static AvatarFallbackProvider parse(String value) {
        try {
            return valueOf(value == null ? "" : value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new ApiException("FALLBACK_PROVIDER_INVALID", "不支持的兜底平台：" + value);
        }
    }

    public static AvatarFallbackProvider parseOptional(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
