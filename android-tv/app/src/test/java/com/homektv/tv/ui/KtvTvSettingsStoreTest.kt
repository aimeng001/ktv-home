package com.homektv.tv.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KtvTvSettingsStoreTest {
    @Test
    fun settingsStartAtExplicitTvSafeDefaults() {
        val store = KtvTvSettingsStore(InMemoryBooleanPreferences())

        assertEquals(
            KtvTvSettingsSnapshot(
                autoFullscreenEnabled = false,
                globalSearchEnabled = true,
                exitOnBackEnabled = true,
                startupSyncEnabled = false,
            ),
            store.snapshot(),
        )
    }

    @Test
    fun supportedPreferencesSurviveStoreRecreation() {
        val preferences = InMemoryBooleanPreferences()
        val firstLaunch = KtvTvSettingsStore(preferences)
        assertTrue(firstLaunch.set(KtvTvSettingsStore.KEY_AUTO_FULLSCREEN, true))
        assertTrue(firstLaunch.set(KtvTvSettingsStore.KEY_GLOBAL_SEARCH, false))
        assertTrue(firstLaunch.set(KtvTvSettingsStore.KEY_EXIT_ON_BACK, false))
        assertTrue(firstLaunch.set(KtvTvSettingsStore.KEY_STARTUP_SYNC, true))

        val nextLaunch = KtvTvSettingsStore(preferences)
        assertEquals(KtvTvSettingsSnapshot(true, false, false, true), nextLaunch.snapshot())
    }

    @Test
    fun unsupportedMediaMutationsCannotBecomePersistedSwitches() {
        val preferences = InMemoryBooleanPreferences()
        val store = KtvTvSettingsStore(preferences)

        assertFalse(store.set("auto_delete", true))
        assertFalse(store.set("device_sync", true))
        assertTrue(preferences.values.isEmpty())
    }

    private class InMemoryBooleanPreferences : KtvTvBooleanPreferences {
        val values = mutableMapOf<String, Boolean>()

        override fun getBoolean(key: String, defaultValue: Boolean): Boolean = values[key] ?: defaultValue

        override fun putBoolean(key: String, value: Boolean): Boolean {
            values[key] = value
            return true
        }
    }
}
