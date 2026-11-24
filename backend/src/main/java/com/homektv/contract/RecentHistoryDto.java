package com.homektv.contract;

import java.time.OffsetDateTime;

/**
 * 最近点歌历史记录 DTO。
 *
 * DTO for recent song request history.
 */
public record RecentHistoryDto(
        /** 历史记录ID。 History record ID. */
        Long historyId,
        /** 歌曲信息。 Song information. */
        SongDto song,
        /** 点歌人ID。 ID of the user who requested the song. */
        Long playedBy,
        /** 点歌人昵称。 Nickname of the user who requested the song. */
        String playedByNick,
        /** 是否为自己所点。 Whether the song was requested by the current user. */
        boolean mine,
        /** 点歌时间。 Time when the song was requested. */
        OffsetDateTime playedAt
) {}
