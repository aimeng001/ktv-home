package com.homektv.tv.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class KtvKioskStartModeTest {

    @Test
    fun kioskControllerInitialState_mustBeDashboard() {
        val state = KioskPresentationState(initialTab = KioskTab.DASHBOARD)
        assertEquals(KioskTab.DASHBOARD, state.currentTab.value)
    }
}
