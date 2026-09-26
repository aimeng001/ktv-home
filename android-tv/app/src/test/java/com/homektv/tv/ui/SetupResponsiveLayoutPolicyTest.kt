package com.homektv.tv.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupResponsiveLayoutPolicyTest {
    @Test
    fun controllerPhoneGetsCompactSetupWhileTvAndTabletKeepWideLayout() {
        assertTrue(SetupResponsiveLayoutPolicy.useCompactLayout(isTelevision = false, smallestWidthDp = 360))
        assertFalse(SetupResponsiveLayoutPolicy.useCompactLayout(isTelevision = true, smallestWidthDp = 360))
        assertFalse(SetupResponsiveLayoutPolicy.useCompactLayout(isTelevision = false, smallestWidthDp = 600))
    }
}
