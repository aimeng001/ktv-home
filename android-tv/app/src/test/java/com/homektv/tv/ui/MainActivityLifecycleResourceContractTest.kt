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
    fun burnInTickerPausesWhenStandbyIsHiddenAndSettingsTickerDefersInitialPoll() {
        val source = locateMainActivity().readText()
        val burnInTicker = source.substringAfter("private val burnInTicker")
            .substringBefore("private val standbySettingsTicker")

        assertTrue(
            "burnInTicker must stop mutating translation when standbyPanel is not VISIBLE",
            burnInTicker.contains("standbyPanel.visibility != View.VISIBLE"),
        )
        assertTrue(
            "onCreate must defer first standbySettingsTicker poll instead of duplicating loadStandbyContent",
            source.contains("binding.standbyPanel.postDelayed(standbySettingsTicker, 60_000L)") &&
                !source.contains("binding.standbyPanel.post(standbySettingsTicker)"),
        )
        assertTrue(
            "onDestroy must remove standbySettingsTicker and burnInTicker callbacks",
            source.contains("removeCallbacks(standbySettingsTicker)") &&
                source.contains("removeCallbacks(burnInTicker)"),
        )
    }

    @Test
    fun modalDialogsAreTrackedDismissedOnDestroyAndDeduplicated() {
        val source = locateMainActivity().readText()
        val trackMethod = source.substringAfter("private fun trackModalDialog")
            .substringBefore("private fun untrackModalDialog")
        val destroyMethod = source.substringAfter("override fun onDestroy()")
            .substringBefore("/** 顶层 BACK")
        val migrationMethod = source.substringAfter("private fun checkModeMigration()")
            .substringBefore("override fun onDestroy()")
        val confirmMethod = source.substringAfter("private fun confirmControl(")
            .substringBefore("private fun sendControl(")
        val trackedAlertHelper = source.substringAfter("private fun showTrackedAlertDialog(")
            .substringBefore("private fun dismissActiveModalDialogs()")

        assertTrue(
            "trackModalDialog must notify kioskCoordinator that a modal is active",
            trackMethod.contains("kioskCoordinator.setModalActive(true)"),
        )
        assertTrue(
            "showTrackedAlertDialog must register and unregister modal tracking",
            trackedAlertHelper.contains("trackModalDialog(") &&
                trackedAlertHelper.contains("untrackModalDialog("),
        )
        assertTrue(
            "onDestroy must dismiss all active modal dialogs to prevent WindowLeaked",
            destroyMethod.contains("dismissActiveModalDialogs()"),
        )
        assertTrue(
            "checkModeMigration must guard against duplicate showing dialogs and track the dialog",
            migrationMethod.contains("modeMigrationDialog?.isShowing != true") &&
                (migrationMethod.contains("trackModalDialog(") || migrationMethod.contains("showTrackedAlertDialog(")),
        )
        assertTrue(
            "confirmControl must track its AlertDialog for BACK/idle/destroy handling",
            (confirmMethod.contains("trackModalDialog(") && confirmMethod.contains("untrackModalDialog(")) ||
                confirmMethod.contains("showTrackedAlertDialog("),
        )
    }

    @Test
    fun onStopPausesStandbyTickersInBackground() {
        val source = locateMainActivity().readText()
        val stopMethod = source.substringAfter("override fun onStop()")
            .substringBefore("private fun checkSessionRestart()")

        assertTrue(
            "onStop must remove standby ticker callbacks while MainActivity is backgrounded",
            stopMethod.contains("removeStandbyTickers()") ||
                (stopMethod.contains("removeCallbacks(standbyTicker)") &&
                    stopMethod.contains("removeCallbacks(burnInTicker)") &&
                    stopMethod.contains("removeCallbacks(standbySettingsTicker)")),
        )
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
