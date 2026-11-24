package com.homektv.contract;

/**
 * 伴奏轨低置信度复核项（后台）：一条待人工确认原伴唱的文件源 + 所属歌曲信息。
 *
 * A low-confidence vocal-track review item (backend): a file source pending manual
 * confirmation of original/accompaniment, together with its song metadata.
 *
 * @param fileId          song_files.id（swap/确认时用）
 *                        song_files.id (used for swap/confirmation)
 * @param songId          所属歌曲 id
 *                        owning song ID
 * @param title           歌名
 *                        song title
 * @param artist          歌手
 *                        artist name
 * @param format          容器格式
 *                        container format
 * @param audioTracks     音轨数
 *                        number of audio tracks
 * @param vocalTrackIndex 当前判定的伴奏轨 index（0-based，回落时通常为 1）
 *                        currently identified accompaniment track index (0-based, usually 1 on fallback)
 * @param vocalConfidence 置信度（LOW）
 *                        confidence level (LOW)
 */
public record VocalReviewDto(
        Long fileId,
        Long songId,
        String title,
        String artist,
        String format,
        int audioTracks,
        Integer vocalTrackIndex,
        String vocalConfidence
) {}
