package com.homektv.tv.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupDraftTest {

    @Test
    fun freshInstallWithNoSavedServersMustAutoScan() {
        assertTrue(SetupAutoScanPolicy.shouldAutoScan(isFreshInstall = true))
        assertFalse(SetupAutoScanPolicy.shouldAutoScan(isFreshInstall = false))
    }
}
