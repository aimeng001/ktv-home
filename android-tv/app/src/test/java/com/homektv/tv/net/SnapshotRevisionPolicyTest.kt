package com.homektv.tv.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotRevisionPolicyTest {
    @Test
    fun onlyCurrentOrNewerRevisionCanReplaceRevisionedState() {
        assertFalse(SnapshotRevisionPolicy.accepts(12L, 11L))
        assertFalse(SnapshotRevisionPolicy.accepts(12L, 0L))
        assertTrue(SnapshotRevisionPolicy.accepts(12L, 12L))
        assertTrue(SnapshotRevisionPolicy.accepts(12L, 13L))
    }

    @Test
    fun legacyStateAcceptsLegacyOrRevisionedFirstSnapshot() {
        assertTrue(SnapshotRevisionPolicy.accepts(0L, 0L))
        assertTrue(SnapshotRevisionPolicy.accepts(0L, 1L))
    }
}
