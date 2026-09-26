package com.homektv.tv.ui

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class AndroidLintWarningRemediationContractTest {
    @Test
    fun mainActivityDoesNotPinOrientationInTheManifest() {
        val manifest = parse("src/main/AndroidManifest.xml")
        val mainActivity = elements(manifest).single {
            it.getAttribute("android:name") == ".ui.MainActivity"
        }

        assertFalse(mainActivity.hasAttribute("android:screenOrientation"))
        assertTrue(read("src/main/java/com/homektv/tv/ui/SetupActivity.kt").contains("applyOrientationForMode(mode)"))
        val mainActivitySource = read("src/main/java/com/homektv/tv/ui/MainActivity.kt")
        assertTrue(mainActivitySource.contains("applyOrientationForMode(config.effectiveMode())"))
        assertTrue(mainActivitySource.contains("requestedOrientation = when"))
    }

    @Test
    fun remoteMenuIsADeferredSeparateLayoutWithAllExistingActions() {
        val mainLayout = parse("src/main/res/layout/activity_main.xml")
        val stub = elements(mainLayout).singleOrNull {
            it.getAttribute("android:id") == "@+id/remoteMenuStub"
        }
        assertEquals("ViewStub", stub?.tagName)
        assertEquals("@layout/view_remote_menu", stub?.getAttribute("android:layout"))

        val menuLayout = parse("src/main/res/layout/view_remote_menu.xml")
        val menuIds = elements(menuLayout).map { it.getAttribute("android:id") }.toSet()
        val requiredIds = setOf(
            "@+id/remoteMenu", "@+id/remotePlay", "@+id/remoteNext", "@+id/remoteRestart",
            "@+id/remoteVocal", "@+id/remoteVolUp", "@+id/remoteVolDown", "@+id/remoteMute",
            "@+id/remoteQueue", "@+id/remoteOrder", "@+id/remoteMicrophone", "@+id/remoteSettings",
        )
        assertTrue(menuIds.containsAll(requiredIds))

        val mainActivity = read("src/main/java/com/homektv/tv/ui/MainActivity.kt")
        val onCreate = mainActivity.substringAfter("override fun onCreate(savedInstanceState: Bundle?)")
            .substringBefore("private fun renderAudioPreview()")
        assertFalse(onCreate.contains("setupRemoteMenu("))
        assertTrue(mainActivity.contains("binding.remoteMenuStub.inflate()"))
    }

    @Test
    fun mainActivityThemeKeepsVideoWindowClearAndKioskUsesReferenceBackground() {
        val manifest = parse("src/main/AndroidManifest.xml")
        val mainActivity = elements(manifest).single {
            it.getAttribute("android:name") == ".ui.MainActivity"
        }
        assertEquals("@style/Theme.HomeKtvTv.MainActivity", mainActivity.getAttribute("android:theme"))

        val themes = parse("src/main/res/values/themes.xml")
        val mainTheme = elements(themes).singleOrNull {
            it.tagName == "style" && it.getAttribute("name") == "Theme.HomeKtvTv.MainActivity"
        }
        assertEquals("@style/Theme.HomeKtvTv", mainTheme?.getAttribute("parent"))
        val windowBackground = mainTheme?.let { theme ->
            elements(themes).singleOrNull {
                it.tagName == "item" && it.parentNode === theme && it.getAttribute("name") == "android:windowBackground"
            }
        }
        assertEquals("@null", windowBackground?.textContent?.trim())

        val overlay = elements(parse("src/main/res/layout/view_ktv_kiosk_overlay.xml")).first()
        assertEquals("@drawable/ktv_dashboard_background", overlay.getAttribute("android:background"))
    }

    @Test
    fun rarelyUsedAudioPlaybackOverlayIsDeferredUntilNeeded() {
        val mainLayout = parse("src/main/res/layout/activity_main.xml")
        val stub = elements(mainLayout).singleOrNull {
            it.getAttribute("android:id") == "@+id/audioOverlayStub"
        }
        assertEquals("ViewStub", stub?.tagName)
        assertEquals("@layout/view_audio_overlay", stub?.getAttribute("android:layout"))

        val audioIds = elements(parse("src/main/res/layout/view_audio_overlay.xml"))
            .map { it.getAttribute("android:id") }
            .toSet()
        assertTrue(
            audioIds.containsAll(
                setOf(
                    "@+id/audioOverlay", "@+id/imgAudioCover", "@+id/txtAudioTitle",
                    "@+id/txtAudioArtist", "@+id/txtAudioLyricCurrent", "@+id/txtAudioLyricNext",
                    "@+id/audioProgress", "@+id/txtAudioElapsed", "@+id/txtAudioDuration",
                    "@+id/audioSpectrum", "@+id/imgAudioMiniQr", "@+id/txtAudioMiniQrHint",
                ),
            ),
        )
        assertTrue(
            read("src/main/java/com/homektv/tv/ui/MainActivity.kt")
                .contains("binding.audioOverlayStub.inflate()"),
        )
    }

    @Test
    fun kioskOverlayUsesItsActualHostActivityThemeForLintAnalysis() {
        val overlay = parse("src/main/res/layout/view_ktv_kiosk_overlay.xml").documentElement
        assertEquals("@style/Theme.HomeKtvTv.MainActivity", overlay.getAttribute("tools:theme"))
        assertEquals(".ui.MainActivity", overlay.getAttribute("tools:context"))
        val activityLayout = parse("src/main/res/layout/activity_main.xml").documentElement
        assertEquals(".ui.MainActivity", activityLayout.getAttribute("tools:context"))
    }

    private fun elements(document: org.w3c.dom.Document): List<Element> {
        val nodes = document.getElementsByTagName("*")
        return (0 until nodes.length).map { nodes.item(it) }.filterIsInstance<Element>()
    }

    private fun parse(relativePath: String) =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(locate(relativePath))

    private fun read(relativePath: String) = locate(relativePath).readText()

    private fun locate(relativePath: String): File {
        val workingDirectory = File(System.getProperty("user.dir").orEmpty())
        val candidates = sequenceOf(
            workingDirectory.resolve(relativePath),
            workingDirectory.resolve("android-tv/app/$relativePath"),
            workingDirectory.resolve("../app/$relativePath"),
        )
        return candidates.firstOrNull(File::isFile)
            ?: error("Cannot locate $relativePath")
    }
}
