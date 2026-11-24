package com.homektv.musicsource;

import com.homektv.common.ApiException;

import java.util.Locale;

public enum MusicProvider {
    NETEASE("网易云音乐"), QQ("QQ 音乐"), KUGOU("酷狗音乐");

    private final String displayName;

    MusicProvider(String displayName) { this.displayName = displayName; }

    public String displayName() { return displayName; }

    public static MusicProvider parse(String value) {
        try {
            return valueOf(value == null ? "" : value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new ApiException("MUSIC_PROVIDER_INVALID", "不支持的音乐平台：" + value);
        }
    }
}
