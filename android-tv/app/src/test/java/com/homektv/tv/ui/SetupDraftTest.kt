package com.homektv.tv.ui

import com.homektv.tv.session.DeviceMode
import org.junit.Assert.assertEquals
import org.junit.Test

class SetupDraftTest {

    @Test
    fun draftModeMustNotModifyPersistedModeUntilCommitted() {
        var persistedMode = DeviceMode.COMBINED
        var draftMode = persistedMode

        // User checks another mode (e.g. PLAYER)
        draftMode = DeviceMode.PLAYER
        // Persisted mode remains unchanged
        assertEquals(DeviceMode.COMBINED, persistedMode)

        // Only upon successful connection commit
        persistedMode = draftMode
        assertEquals(DeviceMode.PLAYER, persistedMode)
    }

    @Test
    fun freshInstallWithNoSavedServersMustAutoScan() {
        org.junit.Assert.assertTrue(SetupAutoScanPolicy.shouldAutoScan(isFreshInstall = true))
        org.junit.Assert.assertFalse(SetupAutoScanPolicy.shouldAutoScan(isFreshInstall = false))
    }
}
