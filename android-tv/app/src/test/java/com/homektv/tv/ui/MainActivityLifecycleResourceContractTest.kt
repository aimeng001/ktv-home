package com.homektv.tv.ui

import org.junit.Assert.assertFalse
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
    fun displaySettingsTickerDefersInitialPollAndCleansUpInStopAndDestroy() {
        val source = locateMainActivity().readText()

        assertTrue(
            "onCreate must defer first displaySettingsTicker poll instead of duplicating loadDisplayContent",
            source.contains("binding.root.postDelayed(displaySettingsTicker, 60_000L)") &&
                !source.contains("binding.root.post(displaySettingsTicker)"),
        )
        assertTrue(
            "onDestroy must remove displaySettingsTicker callbacks",
            source.contains("removeCallbacks(displaySettingsTicker)"),
        )
    }

    @Test
    fun modalDialogsAreTrackedDismissedOnDestroyAndDeduplicated() {
        val source = locateMainActivity().readText()
        val trackMethod = source.substringAfter("private fun trackModalDialog")
            .substringBefore("private fun untrackModalDialog")
        val destroyMethod = source.substringAfter("override fun onDestroy()")
            .substringBefore("/** 顶层 BACK")
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
    }

    @Test
    fun onStopPausesDisplaySettingsTickerInBackground() {
        val source = locateMainActivity().readText()
        val stopMethod = source.substringAfter("override fun onStop()")
            .substringBefore("private fun checkSessionRestart()")

        assertTrue(
            "onStop must remove displaySettingsTicker callbacks while MainActivity is backgrounded",
            stopMethod.contains("removeDisplaySettingsTicker()") ||
                stopMethod.contains("removeCallbacks(displaySettingsTicker)"),
        )
        assertFalse(source.contains("standbyPanel"))
    }

    @Test
    fun legacyStandbyPanelViewsAreRemovedFromMainActivity() {
        val source = locateMainActivity().readText()
        val layout = locateLayout().readText()

        assertTrue(!layout.contains("@+id/txtWaitingStat"))
        assertTrue(!layout.contains("@+id/standbyPanel"))
        assertTrue(!source.contains("txtWaitingStat"))
        assertTrue(!source.contains("txtPlayedStat"))
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
