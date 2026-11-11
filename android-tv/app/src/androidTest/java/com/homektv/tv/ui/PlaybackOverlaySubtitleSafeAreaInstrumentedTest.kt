package com.homektv.tv.ui

import android.graphics.Rect
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.homektv.tv.R
import com.homektv.tv.net.AppConfig
import com.homektv.tv.net.NowPlaying
import com.homektv.tv.net.QueueSnapshot
import com.homektv.tv.net.SongDto
import com.homektv.tv.session.DeviceMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Verifies playback overlays leave the burned-in karaoke subtitle area readable. */
@RunWith(AndroidJUnit4::class)
class PlaybackOverlaySubtitleSafeAreaInstrumentedTest {
    @Test
    fun videoOverlaysStayOutOfSubtitleAreaAndDoNotInventLyricText() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val isTelevision = context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK) ||
            (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK) ==
            android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
        assumeTrue("playback overlay subtitle layout is TV-only", isTelevision)

        val config = AppConfig(context)
        val previousHost = config.serverHost
        val previousMode = config.modeFor() ?: config.effectiveMode()
        val localOfflineHost = "127.0.0.1:9"
        config.serverHost = localOfflineHost
        config.saveMode(DeviceMode.COMBINED, localOfflineHost)

        var scenario: ActivityScenario<MainActivity>? = null
        try {
            scenario = ActivityScenario.launch(android.content.Intent(context, MainActivity::class.java))
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val song = SongDto(
                    id = 90001L,
                    title = "字幕安全区测试",
                    artist = "本地仪器夹具",
                    mediaType = "KTV_VIDEO",
                    lyricType = "none",
                )
                MainActivity::class.java
                    .getDeclaredMethod("updatePlayerInfo", QueueSnapshot::class.java)
                    .apply { isAccessible = true }
                    .invoke(activity, QueueSnapshot(playing = NowPlaying(queueId = 90001L, song = song), state = "playing"))
                activity.findViewById<View>(R.id.playerView).visibility = View.VISIBLE
                MainActivity::class.java
                    .getDeclaredMethod("showPlaybackProgress")
                    .apply { isAccessible = true }
                    .invoke(activity)

                activity.findViewById<View>(R.id.ktvOverlay).visibility = View.VISIBLE
                activity.findViewById<View>(R.id.imgMiniQr).visibility = View.VISIBLE
                activity.findViewById<View>(R.id.txtMiniQrHint).visibility = View.VISIBLE
                activity.findViewById<View>(R.id.kioskRootOverlay).visibility = View.VISIBLE
                activity.findViewById<View>(R.id.pipVideoFrame).visibility = View.VISIBLE
                activity.findViewById<TextView>(R.id.txtPipLabel).text = "正在播放  字幕安全区测试 · 本地仪器夹具"
            }
            instrumentation.waitForIdleSync()

            scenario.onActivity { activity ->
                val lyric = activity.findViewById<TextView>(R.id.txtLyricCurrent)
                assertEquals("missing external LRC must not place the song title over the MV's own subtitles", "", lyric.text.toString())
                assertEquals("without LRC, no app lyric overlay should cover the video subtitle area", View.GONE, lyric.visibility)

                fun visibleRect(viewId: Int): Rect = Rect().also {
                    val view = activity.findViewById<View>(viewId)
                    assertTrue("${activity.resources.getResourceEntryName(viewId)} must be laid out", view.getGlobalVisibleRect(it))
                }

                val playerRect = visibleRect(R.id.ktvOverlay)
                val qrRect = visibleRect(R.id.imgMiniQr)
                val qrHintRect = visibleRect(R.id.txtMiniQrHint)
                val subtitleSafeTop = playerRect.top + (playerRect.height() * 0.65f).toInt()
                assertTrue("the QR image must stay above the lower subtitle band: qr=$qrRect safeTop=$subtitleSafeTop", qrRect.bottom <= subtitleSafeTop)
                assertTrue("the QR label must stay above the lower subtitle band: label=$qrHintRect safeTop=$subtitleSafeTop", qrHintRect.bottom <= subtitleSafeTop)

                val pipRect = visibleRect(R.id.pipVideoFrame)
                val pipLabelRect = visibleRect(R.id.txtPipLabel)
                val embeddedSubtitleBand = Rect(
                    pipRect.left,
                    pipRect.top + pipRect.height() * 2 / 3,
                    pipRect.right,
                    pipRect.bottom,
                )
                assertFalse(
                    "the PiP caption must stay above burned-in subtitles: label=$pipLabelRect subtitleBand=$embeddedSubtitleBand",
                    Rect.intersects(pipLabelRect, embeddedSubtitleBand),
                )
            }
        } finally {
            scenario?.close()
            config.serverHost = previousHost
            config.saveMode(previousMode, previousHost)
        }
    }
}
