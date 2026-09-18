package com.homektv.tv.ui

import com.homektv.tv.net.SongDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KtvAutoBackgroundMusicPolicyTest {

    private fun dummySong(id: Long, title: String) = SongDto(
        id = id,
        title = title,
        artist = "歌手$id",
    )

    @Test
    fun picksSongFromHistoryWhenAvailable() {
        val history = listOf(
            dummySong(1L, "晴天"),
            dummySong(2L, "海阔天空"),
        )
        val recommendations = listOf(
            dummySong(3L, "后来"),
        )

        val candidate = KtvAutoBackgroundMusicPolicy.pickNextCandidate(
            history = history,
            recommendations = recommendations,
            lastPlayedId = null,
        )

        assertEquals("优先选取曾经点唱过的历史歌曲", 1L, candidate?.id)
        assertEquals("晴天", candidate?.title)
    }

    @Test
    fun advancesToNextHistorySongAvoidingImmediateRepeat() {
        val history = listOf(
            dummySong(1L, "晴天"),
            dummySong(2L, "海阔天空"),
        )

        val candidate = KtvAutoBackgroundMusicPolicy.pickNextCandidate(
            history = history,
            recommendations = emptyList(),
            lastPlayedId = 1L,
        )

        assertEquals("不应连续播放同一首历史歌曲", 2L, candidate?.id)
        assertEquals("海阔天空", candidate?.title)
    }

    @Test
    fun fallsBackToRecommendationsWhenHistoryIsEmpty() {
        val recommendations = listOf(
            dummySong(10L, "热歌推荐1"),
            dummySong(11L, "热歌推荐2"),
        )

        val candidate = KtvAutoBackgroundMusicPolicy.pickNextCandidate(
            history = emptyList(),
            recommendations = recommendations,
            lastPlayedId = null,
        )

        assertEquals("历史记录为空时降级到推荐歌曲", 10L, candidate?.id)
    }

    @Test
    fun shouldPlayAmbientBgmOnlyWhenQueueIsEmptyAndIdle() {
        // 空闲无歌时应启用垫乐
        assertTrue(
            KtvAutoBackgroundMusicPolicy.shouldPlayAmbient(
                hasPlaying = false,
                waitingCount = 0,
                isKioskActive = true,
            )
        )

        // 用户有歌曲正在排队，绝不启用垫乐
        assertFalse(
            KtvAutoBackgroundMusicPolicy.shouldPlayAmbient(
                hasPlaying = false,
                waitingCount = 2,
                isKioskActive = true,
            )
        )

        // 用户有歌曲正在播放，绝不启用垫乐
        assertFalse(
            KtvAutoBackgroundMusicPolicy.shouldPlayAmbient(
                hasPlaying = true,
                waitingCount = 0,
                isKioskActive = false,
            )
        )
    }
}
