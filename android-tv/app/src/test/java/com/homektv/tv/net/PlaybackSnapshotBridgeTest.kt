package com.homektv.tv.net

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackSnapshotBridgeTest {
    @After
    fun tearDown() = PlaybackSnapshotBridge.resetForTests()

    @Test
    fun stateFlowDeliversOnlyTheLatestSnapshotForTheCurrentServerSession() {
        val epoch = PlaybackSnapshotBridge.beginSession("nas-a:8080")
        assertFalse(PlaybackSnapshotBridge.events.value?.connected ?: true)

        PlaybackSnapshotBridge.publish("nas-a:8080", epoch, "queue_updated", QueueSnapshot(positionMs = 123L))
        assertEquals(123L, PlaybackSnapshotBridge.events.value?.snapshot?.positionMs)

        PlaybackSnapshotBridge.publish("nas-a:8080", epoch - 1, "stale", QueueSnapshot(positionMs = 1L))
        assertEquals(123L, PlaybackSnapshotBridge.events.value?.snapshot?.positionMs)
    }

    @Test
    fun serverSwitchAndDisconnectCannotBeOverwrittenByOldPlayerEvents() {
        val oldEpoch = PlaybackSnapshotBridge.beginSession("nas-a:8080")
        PlaybackSnapshotBridge.publish("nas-a:8080", oldEpoch, "queue_updated", QueueSnapshot(positionMs = 123L))

        val newEpoch = PlaybackSnapshotBridge.beginSession("nas-b:8080")
        assertEquals("nas-b:8080", PlaybackSnapshotBridge.events.value?.serverHost)
        assertFalse(PlaybackSnapshotBridge.events.value?.connected ?: true)
        PlaybackSnapshotBridge.publishConnection("nas-b:8080", newEpoch, connected = true)
        PlaybackSnapshotBridge.publish("nas-a:8080", oldEpoch, "late", QueueSnapshot(positionMs = 999L))

        assertEquals("nas-b:8080", PlaybackSnapshotBridge.events.value?.serverHost)
        assertEquals(newEpoch, PlaybackSnapshotBridge.events.value?.epoch)
        assertTrue(PlaybackSnapshotBridge.events.value?.connected ?: false)
        assertNull(PlaybackSnapshotBridge.events.value?.snapshot)
    }

    @Test
    fun retainsOnlyBoundedServerSessionMetadata() {
        repeat(20) { index -> PlaybackSnapshotBridge.beginSession("server-$index") }

        val field = PlaybackSnapshotBridge::class.java.getDeclaredField("activeEpochByServer")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val sessions = field.get(PlaybackSnapshotBridge) as Map<String, Long>

        org.junit.Assert.assertEquals(8, sessions.size)
        org.junit.Assert.assertFalse(sessions.containsKey("server-0"))
        org.junit.Assert.assertTrue(sessions.containsKey("server-19"))
    }

    @Test
    fun publishRoomHostDeliversStatusToCurrentServerSession() {
        val epoch = PlaybackSnapshotBridge.beginSession("nas-a:8080")
        val status = RoomHostStatus(claimed = true, hostUserId = 42L, hostNickname = "Alice", revision = 5L)
        PlaybackSnapshotBridge.publishRoomHost("nas-a:8080", epoch, status)

        val current = PlaybackSnapshotBridge.events.value
        assertEquals("nas-a:8080", current?.serverHost)
        assertTrue(current?.connected ?: false)
        assertEquals("room_host_changed", current?.event)
        assertEquals(status, current?.roomHost)
    }

    @Test
    fun publishingSnapshotMustNotEraseRoomHostStatus() {
        val epoch = PlaybackSnapshotBridge.beginSession("nas-a:8080")
        val hostStatus = RoomHostStatus(claimed = true, hostUserId = 12L, revision = 3L)
        PlaybackSnapshotBridge.publishRoomHost("nas-a:8080", epoch, hostStatus)

        val snapshot = QueueSnapshot(positionMs = 5000L)
        PlaybackSnapshotBridge.publish("nas-a:8080", epoch, "queue_updated", snapshot)

        val state = PlaybackSnapshotBridge.events.value
        org.junit.Assert.assertNotNull(state?.snapshot)
        org.junit.Assert.assertNotNull(state?.roomHost)
        assertEquals(12L, state?.roomHost?.hostUserId)
        assertEquals(5000L, state?.snapshot?.positionMs)
    }

    @Test
    fun publishingRoomHostMustNotEraseExistingSnapshot() {
        val epoch = PlaybackSnapshotBridge.beginSession("nas-a:8080")
        val snapshot = QueueSnapshot(positionMs = 5000L)
        PlaybackSnapshotBridge.publish("nas-a:8080", epoch, "queue_updated", snapshot)

        val hostStatus = RoomHostStatus(claimed = true, hostUserId = 12L, revision = 3L)
        PlaybackSnapshotBridge.publishRoomHost("nas-a:8080", epoch, hostStatus)

        val state = PlaybackSnapshotBridge.events.value
        org.junit.Assert.assertNotNull(state?.snapshot)
        org.junit.Assert.assertNotNull(state?.roomHost)
        assertEquals(5000L, state?.snapshot?.positionMs)
        assertEquals(12L, state?.roomHost?.hostUserId)
    }
}
