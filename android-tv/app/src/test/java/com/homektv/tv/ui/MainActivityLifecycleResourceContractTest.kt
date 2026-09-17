package com.homektv.tv.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MainActivityLifecycleResourceContractTest {

    @Test
    fun sessionFingerprintIncludesConfiguredServerInstanceId() {
        val source = locateMainActivity().readText()

        assertTrue(source.contains("instanceId = config.serverForHost(config.serverHost)?.instanceId"))
        assertTrue(source.contains("instanceId = currentConfig.serverForHost(currentConfig.serverHost)?.instanceId"))
    }

    @Test
    fun destroyGuardsBindingAndClosesMediaApi() {
        val source = locateMainActivity().readText()
        val destroy = source.substringAfter("override fun onDestroy()")
            .substringBefore("/** 顶层 BACK")

        assertTrue(destroy.contains("if (::binding.isInitialized)"))
        assertTrue(destroy.contains("mediaApi.close()"))
    }

    @Test
    fun recommendationBitmapCacheHasFiniteByteWeight() {
        val source = locateMainActivity().readText()

        assertTrue(source.contains("maxBytes ="))
        assertTrue(source.contains("allocationByteCount.toLong()"))
    }

    @Test
    fun carouselRunsOnlyWhenStandbyPanelIsVisible() {
        val source = locateMainActivity().readText()
        val ticker = source.substringAfter("private val standbyTicker")
            .substringBefore("private val burnInTicker")

        assertTrue(ticker.contains("standbyPanel.visibility"))
        assertTrue(source.contains("removeCallbacks(standbyTicker)"))
    }

    @Test
    fun logoRequestUsesCancellationAndGenerationGuard() {
        val source = locateMainActivity().readText()

        assertTrue(source.contains("standbyLogoJob?.cancel()"))
        assertTrue(source.contains("standbyLogoRequestId"))
        assertTrue(source.contains("logoRequestId != standbyLogoRequestId"))
    }

    @Test
    fun standbyStatsDoNotRenderSyntheticZeroValues() {
        val source = locateMainActivity().readText()
        val layout = locateLayout().readText()

        assertTrue(layout.contains("@+id/txtWaitingStat"))
        assertTrue(source.contains("txtWaitingStat.visibility"))
        assertTrue(source.contains("txtPlayedStat.visibility"))
        assertTrue(!source.contains("libraryCount ?: recommendations.size"))
    }

    private fun locateMainActivity(): File {
        val candidates = sequenceOf(
            File("src/main/java/com/homektv/tv/ui/MainActivity.kt"),
            File("android-tv/app/src/main/java/com/homektv/tv/ui/MainActivity.kt"),
            File("../app/src/main/java/com/homektv/tv/ui/MainActivity.kt"),
        )
        return candidates.firstOrNull(File::isFile)
            ?: error("Cannot locate MainActivity.kt from ${File(".").absolutePath}")
    }

    private fun locateLayout(): File {
        val candidates = sequenceOf(
            File("src/main/res/layout/activity_main.xml"),
            File("android-tv/app/src/main/res/layout/activity_main.xml"),
            File("../app/src/main/res/layout/activity_main.xml"),
        )
        return candidates.firstOrNull(File::isFile)
            ?: error("Cannot locate activity_main.xml from ${File(".").absolutePath}")
    }
}
