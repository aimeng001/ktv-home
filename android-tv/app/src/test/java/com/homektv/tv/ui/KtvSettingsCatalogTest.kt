package com.homektv.tv.ui

import com.homektv.tv.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KtvSettingsCatalogTest {
    @Test
    fun presentsTheTwelveReferenceRowsWithoutRemovingHomeSettingsElsewhere() {
        val entries = KtvSettingsCatalog.entries(
            autoFullscreenEnabled = false,
            globalSearchEnabled = true,
            exitOnBackEnabled = true,
            canOpenKiosk = true,
            startupSyncEnabled = true,
        )

        assertEquals(12, entries.size)
        assertEquals(6, entries.count { it.column == KtvSettingsColumn.LEFT })
        assertEquals(6, entries.count { it.column == KtvSettingsColumn.RIGHT })
        assertEquals(
            listOf(
                "download_path", "sync_songs", "manual_media", "auto_fullscreen", "auto_delete", "device_sync",
                "download_server", "media_feedback", "global_search", "download_window", "startup_sync", "back_exit",
            ),
            entries.map { it.key },
        )
        val byKey = entries.associateBy { it.key }
        assertEquals("自动删歌", byKey.getValue("auto_delete").title)
        assertEquals("设备同步", byKey.getValue("device_sync").title)
        assertEquals(KtvSettingsIndicator.SWITCH, byKey.getValue("auto_fullscreen").indicator)
        assertEquals(KtvSettingsIndicator.SWITCH, byKey.getValue("global_search").indicator)
        assertEquals(R.drawable.ktv_ref_v50_settings_search, byKey.getValue("global_search").iconRes)
        assertEquals(KtvSettingsIndicator.SWITCH, byKey.getValue("back_exit").indicator)
        assertEquals(KtvSettingsIndicator.DISABLED_SWITCH, byKey.getValue("auto_delete").indicator)
        assertEquals(KtvSettingsIndicator.DISABLED_SWITCH, byKey.getValue("device_sync").indicator)
        assertEquals(KtvSettingsIndicator.DISABLED_SWITCH, byKey.getValue("download_window").indicator)
        assertEquals(KtvSettingsIndicator.SWITCH, byKey.getValue("startup_sync").indicator)
        assertFalse(byKey.getValue("auto_delete").checked)
        assertFalse(byKey.getValue("device_sync").checked)
        assertTrue(byKey.getValue("global_search").checked)
        assertTrue(byKey.getValue("back_exit").checked)
        assertTrue(byKey.getValue("startup_sync").checked)
    }

    @Test
    fun connectsOnlySettingsWithExistingHomeKtvFlows() {
        val entries = KtvSettingsCatalog.entries(
            autoFullscreenEnabled = false,
            globalSearchEnabled = true,
            exitOnBackEnabled = true,
            canOpenKiosk = true,
        ).associateBy { it.key }

        assertEquals(KtvSettingsAction.OPEN_LIBRARY_SCAN, entries.getValue("sync_songs").action)
        assertEquals(KtvSettingsAction.OPEN_MEDIA_FEEDBACK, entries.getValue("media_feedback").action)
        assertEquals(KtvSettingsAction.TOGGLE_LOCAL_PREFERENCE, entries.getValue("auto_fullscreen").action)
        assertEquals(KtvSettingsAction.TOGGLE_LOCAL_PREFERENCE, entries.getValue("global_search").action)
        assertEquals(KtvSettingsAction.TOGGLE_LOCAL_PREFERENCE, entries.getValue("back_exit").action)
        assertEquals(KtvSettingsAction.TOGGLE_LOCAL_PREFERENCE, entries.getValue("startup_sync").action)

        listOf("download_path", "manual_media", "download_server", "auto_delete", "device_sync", "download_window")
            .forEach { key ->
                assertEquals("$key must not claim a Home KTV side effect", KtvSettingsAction.EXPLAIN, entries.getValue(key).action)
        }
    }

    @Test
    fun settingsValuesReflectPreferencesAndDeviceCapabilities() {
        val available = KtvSettingsCatalog.entries(true, true, false, canOpenKiosk = true).associateBy { it.key }
        val unavailable = KtvSettingsCatalog.entries(false, true, true, canOpenKiosk = false).associateBy { it.key }

        assertTrue(available.getValue("auto_fullscreen").checked)
        assertTrue(available.getValue("global_search").checked)
        assertFalse(available.getValue("back_exit").checked)
        assertTrue(available.getValue("global_search").enabled)
        assertFalse(unavailable.getValue("global_search").enabled)
        assertFalse(unavailable.getValue("startup_sync").enabled)
        assertEquals("Home KTV 服务连接在系统信息中设置", available.getValue("download_server").description)
    }
}
