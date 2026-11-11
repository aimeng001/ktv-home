package com.homektv.tv.ui

import com.homektv.tv.net.LibraryStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupScanPolicyTest {
    @Test
    fun requiresExplicitOptInTvConnectionAndSupportedReadableExternalLibrary() {
        val eligible = LibraryStatus(
            libraryMode = "EXTERNAL_READ_ONLY",
            rootState = "READABLE",
            rootIdentityState = "MATCH",
            scanState = "COMPLETED",
            scanRequestSupported = true,
        )

        assertTrue(StartupScanPolicy.shouldRequest(true, true, true, eligible))
        assertFalse(StartupScanPolicy.shouldRequest(false, true, true, eligible))
        assertFalse(StartupScanPolicy.shouldRequest(true, false, true, eligible))
        assertFalse(StartupScanPolicy.shouldRequest(true, true, false, eligible))
        assertFalse(StartupScanPolicy.shouldRequest(true, true, true, null))
    }

    @Test
    fun rejectsManagedUnreadableMismatchedRunningAndUnconfirmedServices() {
        val eligible = LibraryStatus(
            libraryMode = "EXTERNAL_READ_ONLY",
            rootState = "READABLE",
            rootIdentityState = "MATCH",
            scanState = "IDLE",
            scanRequestSupported = true,
        )

        assertFalse(StartupScanPolicy.shouldRequest(true, true, true, eligible.copy(libraryMode = "MANAGED")))
        assertFalse(StartupScanPolicy.shouldRequest(true, true, true, eligible.copy(rootState = "NOT_FOUND")))
        assertFalse(StartupScanPolicy.shouldRequest(true, true, true, eligible.copy(rootIdentityState = "MISMATCH")))
        assertFalse(StartupScanPolicy.shouldRequest(true, true, true, eligible.copy(scanState = "RUNNING")))
        assertFalse(StartupScanPolicy.shouldRequest(true, true, true, eligible.copy(scanRequestSupported = false)))
    }
}
