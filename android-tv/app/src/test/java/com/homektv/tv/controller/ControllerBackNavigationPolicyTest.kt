package com.homektv.tv.controller

import org.junit.Assert.assertEquals
import org.junit.Test

class ControllerBackNavigationPolicyTest {
    @Test
    fun playlistDetailTakesPriorityOverCatalogDetail() {
        assertEquals(
            ControllerBackTarget.CLOSE_PLAYLIST_DETAIL,
            ControllerBackNavigationPolicy.resolve(
                playlistDetail = true,
                catalogDetail = true,
            ),
        )
    }

    @Test
    fun catalogDetailReturnsToCatalogBeforeExiting() {
        assertEquals(
            ControllerBackTarget.CLOSE_CATALOG_DETAIL,
            ControllerBackNavigationPolicy.resolve(
                playlistDetail = false,
                catalogDetail = true,
            ),
        )
    }

    @Test
    fun ordinaryBackExitsController() {
        assertEquals(
            ControllerBackTarget.EXIT,
            ControllerBackNavigationPolicy.resolve(
                playlistDetail = false,
                catalogDetail = false,
            ),
        )
    }
}
