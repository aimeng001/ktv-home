package com.homektv.tv.ui.kiosk

import org.junit.Assert.assertEquals
import org.junit.Test

class SongDisplayFormatterTest {

    @Test
    fun formatTitleCleansCaretAndTechnicalChannelTags() {
        assertEquals(
            "再见 来自天堂的魔鬼 光年之外",
            SongDisplayFormatter.formatTitle("再见^来自天堂的魔鬼^光年之外"),
        )
        assertEquals(
            "爱在同一个星球现场版",
            SongDisplayFormatter.formatTitle("爱在同一个星球现场版[声道..."),
        )
        assertEquals(
            "十年",
            SongDisplayFormatter.formatTitle("十年[1080P][原版]"),
        )
    }

    @Test
    fun formatTitlePreservesNormalTitlesAndFallsBackWhenOnlyTags() {
        assertEquals(
            "给我一首歌的时间",
            SongDisplayFormatter.formatTitle("给我一首歌的时间"),
        )
        assertEquals(
            "[1080P]",
            SongDisplayFormatter.formatTitle("[1080P]"),
        )
    }

    @Test
    fun formatArtistNormalizesUnderscoresAndFormatsSubtitle() {
        assertEquals("邓紫棋/许佳麟", SongDisplayFormatter.formatArtist("邓紫棋_许佳麟"))
        assertEquals("刘德华/周杰伦", SongDisplayFormatter.formatArtist("刘德华_周杰伦"))
        assertEquals(
            "周杰伦 蔡依林 | KTV",
            SongDisplayFormatter.formatArtistSubtitle("周杰伦 蔡依林", "KTV_VIDEO"),
        )
        assertEquals(
            "邓紫棋/许佳麟 | MV",
            SongDisplayFormatter.formatArtistSubtitle("邓紫棋_许佳麟", "MV"),
        )
        assertEquals(
            "刘德华/周杰伦",
            SongDisplayFormatter.formatArtistSubtitle("刘德华_周杰伦", "AUDIO"),
        )
    }
}
