package com.homektv.tv.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotEventPolicyTest {

    @Test
    fun completeSnapshotTypes_mustIncludeAllFullPayloadEvents() {
        val expected = listOf(
            "sync_full",
            "queue_updated",
            "now_playing",
            "player_state",
            "playback_restarted",
            "playback_seeked",
            "volume_changed",
            "vocal_changed",
        )
        expected.forEach { type ->
            assertTrue("Event $type 必须被判定为完整快照", SnapshotEventPolicy.isCompleteSnapshot(type))
        }
    }

    @Test
    fun controlAndPartialEvents_mustNotBeCompleteSnapshots() {
        val nonSnapshots = listOf(
            "toast",
            "player_role",
            "pong",
            "playback_report_ack",
            "snapshot_chunk",
            "unknown",
        )
        nonSnapshots.forEach { type ->
            assertFalse("Event $type 不能被判定为完整快照", SnapshotEventPolicy.isCompleteSnapshot(type))
        }
    }
}
