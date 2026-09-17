package com.homektv.tv.net

/**
 * 统一快照事件类型策略。
 * 明确哪些事件携带全量 QueueSnapshot payload，可作为入站合并与同步就绪屏障的事实依据。
 */
internal object SnapshotEventPolicy {
    val COMPLETE_SNAPSHOT_TYPES: Set<String> = setOf(
        "sync_full",
        "queue_updated",
        "now_playing",
        "player_state",
        "playback_restarted",
        "playback_seeked",
        "volume_changed",
        "vocal_changed",
    )

    fun isCompleteSnapshot(type: String): Boolean = type in COMPLETE_SNAPSHOT_TYPES
}
