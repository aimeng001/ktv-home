package com.homektv.tv.ui.kiosk

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KioskCategoryLoadPolicyTest {

    @Test
    fun eachCategoryModeMustLoadOnceIndependently() {
        val policy = KioskCategoryLoadPolicy()

        assertTrue(policy.shouldLoad(KioskCategoryMode.NEW))
        policy.markLoaded(KioskCategoryMode.NEW)
        assertFalse(policy.shouldLoad(KioskCategoryMode.NEW))

        // Loading the latest-song mode must not suppress the language request.
        assertTrue(policy.shouldLoad(KioskCategoryMode.LANGUAGES))
        policy.markLoaded(KioskCategoryMode.LANGUAGES)
        assertFalse(policy.shouldLoad(KioskCategoryMode.LANGUAGES))

        // Switching back to a loaded mode must still use its own cache entry.
        assertFalse(policy.shouldLoad(KioskCategoryMode.NEW))
    }

    @Test
    fun failedModeCanBeRetriedUntilMarkedLoaded() {
        val policy = KioskCategoryLoadPolicy()

        assertTrue(policy.shouldLoad(KioskCategoryMode.TAGS))
        // A failed request does not call markLoaded, so retry remains possible.
        assertTrue(policy.shouldLoad(KioskCategoryMode.TAGS))
    }
}
