package com.homektv.tv.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyQueuePolicyTest {
    @Test
    fun playerModeIsAlwaysReadOnly() {
        assertFalse(LegacyQueuePolicy.shouldAllowMutations(canOpenKiosk = false))
    }

    @Test
    fun combinedModeDelegatesToKioskDrawerInsteadOfLegacyMutations() {
        assertFalse(LegacyQueuePolicy.shouldAllowMutations(canOpenKiosk = true))
    }

    @Test
    fun combinedModePrefersKioskDrawerForQueueAccess() {
        assertTrue(LegacyQueuePolicy.shouldOpenKioskDrawer(canOpenKiosk = true))
        assertFalse(LegacyQueuePolicy.shouldOpenKioskDrawer(canOpenKiosk = false))
    }
}
