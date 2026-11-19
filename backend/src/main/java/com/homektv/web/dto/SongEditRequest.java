package com.homektv.web.dto;

/**
 * 曲库编辑请求（P2.3，详设§8 ADM-02）。仅传需修改字段。
 *
 * Song edit request (P2.3, DD §8 ADM-02). Only fields to be modified are passed.
 */
public record SongEditRequest(
        String title,
        String artist,
        String language,
        String[] tags,
        String lyricText,   // 可选：粘贴歌词 (Optional: paste lyrics)
        String vocalForm,
        String artistGender,
        Boolean clearLyric
    ) {
        public SongEditRequest(String title, String artist, String language, String[] tags, String lyricText) {
        this(title, artist, language, tags, lyricText, null, null, null);
    }

    public SongEditRequest(String title, String artist, String language, String[] tags,
                           String lyricText, String vocalForm, String artistGender) {
        this(title, artist, language, tags, lyricText, vocalForm, artistGender, null);
    }
}
