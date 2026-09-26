package com.homektv.tv.ui.controller

import org.junit.Assert.assertEquals
import org.junit.Test

class ControllerPhoneNavigationPolicyTest {
    @Test
    fun phoneNavigationHasExactlyTheFiveProductAreasInOrder() {
        assertEquals(
            listOf("目录", "搜索", "队列", "遥控", "我的"),
            ControllerPhoneNavigationPolicy.tabs.map { it.title },
        )
        assertEquals(
            listOf(
                ControllerPhonePanel.CATALOG,
                ControllerPhonePanel.SEARCH,
                ControllerPhonePanel.QUEUE,
                ControllerPhonePanel.REMOTE,
                ControllerPhonePanel.PERSONAL,
            ),
            ControllerPhoneNavigationPolicy.tabs.map { it.panel },
        )
    }

    @Test
    fun removedCatalogAndPlaylistModesRestoreToSupportedDestinations() {
        assertEquals("ARTISTS", ControllerSavedStatePolicy.catalogMode("RANKING"))
        assertEquals("ARTISTS", ControllerSavedStatePolicy.catalogMode("NEW"))
        assertEquals("TAGS", ControllerSavedStatePolicy.catalogMode("TAGS"))
        assertEquals("FAVORITES", ControllerSavedStatePolicy.personalMode("PLAYLISTS", isPhone = true))
        assertEquals("PLAYLISTS", ControllerSavedStatePolicy.personalMode("PLAYLISTS", isPhone = false))
        assertEquals("HISTORY", ControllerSavedStatePolicy.personalMode("HISTORY", isPhone = true))
        assertEquals("CATALOG", ControllerSavedStatePolicy.phonePanel("not-a-panel"))
    }
}
