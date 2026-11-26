package com.homektv.musicsource;

import com.homektv.common.ApiException;

import java.util.Locale;

public enum MusicProvider {
    NETEASE("网易云音乐", true),
    QQ("QQ 音乐", true),
    KUGOU("酷狗音乐", true),
    KUWO("酷我音乐", false),
    ITUNES("iTunes / Apple Music", false);

    private final String displayName;
    private final boolean supportsSongMetadata;

    MusicProvider(String displayName, boolean supportsSongMetadata) {
        this.displayName = displayName;
        this.supportsSongMetadata = supportsSongMetadata;
    }

    public String displayName() { return displayName; }

    public boolean supportsSongMetadata() { return supportsSongMetadata; }

    public boolean supportsArtistAvatar() { return true; }

    public static MusicProvider parse(String value) {
        try {
            return valueOf(value == null ? "" : value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new ApiException("MUSIC_PROVIDER_INVALID", "不支持的音乐平台：" + value);
        }
    }
}
