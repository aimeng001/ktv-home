package com.homektv.tv.ui

import com.homektv.tv.net.SongDto

/**
 * 电视端空闲自动垫乐/背景轮播策略。
 *
 * 优先从曾经点唱过的历史歌曲列表中提取候选曲目；
 * 若历史歌曲为空，则降级为精选推荐曲目；
 * 仅在队列完全处于空闲且无歌曲排队时允许垫乐，一旦有正式点歌立即让位。
 */
object KtvAutoBackgroundMusicPolicy {

    fun pickNextCandidate(
        history: List<SongDto>,
        recommendations: List<SongDto>,
        lastPlayedId: Long?,
    ): SongDto? {
        val pool = if (history.isNotEmpty()) history else recommendations
        if (pool.isEmpty()) return null

        if (pool.size == 1) return pool[0]

        val candidates = if (lastPlayedId != null) {
            val filtered = pool.filter { it.id != lastPlayedId }
            if (filtered.isNotEmpty()) filtered else pool
        } else {
            pool
        }

        return candidates.firstOrNull()
    }

    fun shouldPlayAmbient(
        hasPlaying: Boolean,
        waitingCount: Int,
        isKioskActive: Boolean,
    ): Boolean {
        return !hasPlaying && waitingCount == 0 && isKioskActive
    }
}
