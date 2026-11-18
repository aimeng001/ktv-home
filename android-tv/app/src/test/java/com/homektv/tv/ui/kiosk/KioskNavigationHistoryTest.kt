package com.homektv.tv.ui.kiosk

import com.homektv.tv.ui.KioskCommercialRoute
import com.homektv.tv.ui.KioskTab
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KioskNavigationHistoryTest {

    @Test
    fun `artist search GFT to artist detail and pop restores GFT filter page and focus`() {
        val history = KioskNavigationHistory()
        val songNameEntry = KioskNavigationEntry(
            tab = KioskTab.PINYIN,
            commercialRoute = KioskCommercialRoute.SONG_NAME,
            keyword = "ZW",
            pageIndex = 1,
            focusedItemKey = "song:101",
        )
        history.push(songNameEntry)

        val singersGftEntry = KioskNavigationEntry(
            tab = KioskTab.SINGERS,
            commercialRoute = KioskCommercialRoute.SINGERS,
            keyword = "GFT",
            artistGender = "男歌手",
            artistInitial = "G",
            artistRegion = "内地",
            pageIndex = 2,
            focusedItemKey = "artist:gft_key",
        )
        history.push(singersGftEntry)

        assertEquals(2, history.depth())
        val poppedSingers = history.pop()
        assertNotNull(poppedSingers)
        assertEquals(KioskTab.SINGERS, poppedSingers?.tab)
        assertNull(poppedSingers?.selectedArtist)
        assertEquals("GFT", poppedSingers?.keyword)
        assertEquals("男歌手", poppedSingers?.artistGender)
        assertEquals("G", poppedSingers?.artistInitial)
        assertEquals("内地", poppedSingers?.artistRegion)
        assertEquals(2, poppedSingers?.pageIndex)
        assertEquals("artist:gft_key", poppedSingers?.focusedItemKey)

        val poppedSongName = history.pop()
        assertEquals(KioskTab.PINYIN, poppedSongName?.tab)
        assertEquals("ZW", poppedSongName?.keyword)
        assertEquals(1, poppedSongName?.pageIndex)
        assertFalse(history.canPop())
    }

    @Test
    fun `sameScreen replaces top snapshot with updated page and focus without duplicating depth`() {
        val history = KioskNavigationHistory()
        history.push(
            KioskNavigationEntry(
                tab = KioskTab.SINGERS,
                commercialRoute = KioskCommercialRoute.SINGERS,
                keyword = "GFT",
                pageIndex = 0,
                focusedItemKey = "artist:a",
            ),
        )
        history.push(
            KioskNavigationEntry(
                tab = KioskTab.SINGERS,
                commercialRoute = KioskCommercialRoute.SINGERS,
                keyword = "GFT",
                pageIndex = 3,
                focusedItemKey = "artist:b",
            ),
        )

        assertEquals(1, history.depth())
        assertEquals(3, history.peek()?.pageIndex)
        assertEquals("artist:b", history.peek()?.focusedItemKey)
    }

    @Test
    fun `remembers module states across tab switches and clears on clearAll`() {
        val history = KioskNavigationHistory()
        val singersEntry = KioskNavigationEntry(
            tab = KioskTab.SINGERS,
            commercialRoute = KioskCommercialRoute.SINGERS,
            keyword = "ZJL",
            artistGender = "男歌手",
            pageIndex = 1,
        )
        history.rememberModule(singersEntry)
        assertEquals(singersEntry, history.lastModuleState(KioskCommercialRoute.SINGERS))

        history.clearAll()
        assertFalse(history.canPop())
        assertNull(history.lastModuleState(KioskCommercialRoute.SINGERS))
    }

    @Test
    fun `bounded stack drops oldest entries when exceeding maxDepth`() {
        val history = KioskNavigationHistory(maxDepth = 3)
        repeat(5) { idx ->
            history.push(
                KioskNavigationEntry(
                    tab = KioskTab.PINYIN,
                    commercialRoute = KioskCommercialRoute.SONG_NAME,
                    keyword = "K$idx",
                ),
            )
        }
        assertEquals(3, history.depth())
        assertEquals("K4", history.pop()?.keyword)
        assertEquals("K3", history.pop()?.keyword)
        assertEquals("K2", history.pop()?.keyword)
        assertNull(history.pop())
    }

    @Test
    fun `fullscreen resume and kiosk hiding preserve navigation context while home resets`() {
        val mainSource = locate("src/main/java/com/homektv/tv/ui/MainActivity.kt").readText()
        val openKioskFromFullscreen = mainSource
            .substringAfter("private fun openKioskFromFullscreen()")
            .substringBefore("private fun showHomePage()")
        assertTrue(openKioskFromFullscreen.contains("resumeKiosk()"))
        assertFalse(openKioskFromFullscreen.contains("selectTab(KioskTab.PINYIN)"))

        val showHomePage = mainSource
            .substringAfter("private fun showHomePage()")
            .substringBefore("private fun openHistoryTab()")
        assertTrue(showHomePage.contains("openDashboardHome()"))

        val controllerSource = locate("src/main/java/com/homektv/tv/ui/KtvKioskOverlayController.kt").readText()
        val inactiveBranch = controllerSource
            .substringAfter("private fun applyKioskActive(active: Boolean)")
            .substringAfter("} else {")
            .substringBefore("private fun renderPipState()")
        assertFalse("hiding kiosk must not wipe selectedArtist", inactiveBranch.contains("clearArtistSelection()"))
        assertFalse("hiding kiosk must not wipe keyboard search text", inactiveBranch.contains("kioskKeyboard.clear()"))
        assertFalse("hiding kiosk must not wipe inner back state", inactiveBranch.contains("hasInnerDetailBack = false"))
    }

    private fun locate(relativePath: String): File {
        var current = File(System.getProperty("user.dir").orEmpty())
        repeat(6) {
            val candidate = File(current, "android-tv/app/$relativePath")
            if (candidate.isFile) return candidate
            val localCandidate = File(current, relativePath)
            if (localCandidate.isFile) return localCandidate
            current = current.parentFile ?: return@repeat
        }
        error("Could not locate $relativePath from ${System.getProperty("user.dir")}")
    }
}
