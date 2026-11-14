package com.homektv.tv.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KtvBottomDockControllerTest {
    @Test
    fun dockReflectsVocalModeAndQueueCount() {
        val accompaniment = BottomDockUiModel(
            queueCount = 5,
            isPlaying = true,
            vocalMode = "accompaniment",
        )

        assertEquals("伴唱", accompaniment.vocalLabel)
        assertEquals("已点 5 首", accompaniment.queueBadgeText)
        assertTrue(accompaniment.vocalGlowActive)

        val original = accompaniment.copy(vocalMode = "original", queueCount = 2)
        assertEquals("原唱", original.vocalLabel)
        assertEquals("已点 2 首", original.queueBadgeText)
        assertFalse(original.vocalGlowActive)
        assertFalse(accompaniment.copy(vocalSwitchEnabled = false).vocalGlowActive)
    }

    @Test
    fun queueBadgeDistinguishesUnknownFromEmptyAndMarksStaleCounts() {
        val unknown = BottomDockUiModel(
            queueCount = 0,
            isPlaying = false,
            vocalMode = "accompaniment",
            queueCountKnown = false,
            queueSnapshotFresh = false,
        )
        assertEquals("0", unknown.queueCountText)
        assertEquals("已点队列状态尚未同步", unknown.queueBadgeText)

        val stale = BottomDockUiModel(
            queueCount = 3,
            isPlaying = false,
            vocalMode = "accompaniment",
            queueCountKnown = true,
            queueSnapshotFresh = false,
        )
        assertEquals("3", stale.queueCountText)
        assertEquals("已点 3 首（上次同步）", stale.queueBadgeText)
    }

    @Test
    fun fullscreenGatedByVideoVisibilityAndExternalDisplay() {
        // 当视频不可见时，禁止进入全屏
        assertFalse(DockFullscreenPolicy.canEnterFullscreen(videoVisible = false, externalDisplayActive = false, isFullscreen = false))

        // 当外部 HDMI 异显激活时，本地点歌屏必须禁止切换全屏，保护 HDMI 视频独立播放
        assertFalse(DockFullscreenPolicy.canEnterFullscreen(videoVisible = true, externalDisplayActive = true, isFullscreen = false))

        // 已经全屏时，不能重复进入
        assertFalse(DockFullscreenPolicy.canEnterFullscreen(videoVisible = true, externalDisplayActive = false, isFullscreen = true))

        // 仅在视频可见、无外接异显、当前非全屏时，才允许进入全屏
        assertTrue(DockFullscreenPolicy.canEnterFullscreen(videoVisible = true, externalDisplayActive = false, isFullscreen = false))
    }

    @Test
    fun externalDisplayActivationForcesExitFullscreen() {
        // 如果处于全屏态下外部屏突然上线，必须触发退出全屏回退至底栏
        assertTrue(DockFullscreenPolicy.shouldExitOnExternalDisplay(externalDisplayActive = true, isFullscreen = true))
        assertFalse(DockFullscreenPolicy.shouldExitOnExternalDisplay(externalDisplayActive = false, isFullscreen = true))
        assertFalse(DockFullscreenPolicy.shouldExitOnExternalDisplay(externalDisplayActive = true, isFullscreen = false))
    }

    @Test
    fun windowBoundsTransitionBetweenPipAndFullscreen() {
        val pipBounds = DockFullscreenPolicy.boundsFor(isFullscreen = false)
        assertFalse(pipBounds.isMatchParent)
        assertEquals(104, pipBounds.heightDp)
        assertEquals(185, pipBounds.widthDp)
        assertEquals(0, pipBounds.bottomMarginDp)

        val fullscreenBounds = DockFullscreenPolicy.boundsFor(isFullscreen = true)
        assertTrue(fullscreenBounds.isMatchParent)
        assertEquals(0, fullscreenBounds.bottomMarginDp)
    }

    @Test
    fun dockContainerKeepsVisibleForQrWhenVideoIsIdle() {
        val source = java.io.File("src/main/java/com/homektv/tv/ui/KtvBottomDockController.kt").let { file ->
            if (file.exists()) file.readText() else java.io.File("../$file").readText()
        }
        assertTrue(
            "当视频空闲但有二维码时，playerContainer 必须保持可见，不能被全盘隐藏",
            source.contains("playerView.visibility = if (visible) View.VISIBLE else View.INVISIBLE") ||
                source.contains("playerView.visibility = if (effectiveVideoVisible)"),
        )
    }

    @Test
    fun bottomDockUsesHomeIconsAndKeepsPipEdgeShading() {
        val layout = java.io.File("src/main/res/layout/view_ktv_bottom_dock.xml").let { file ->
            if (file.exists()) file.readText() else java.io.File("../$file").readText()
        }
        assertTrue("小窗左侧必须使用 Home KTV 渐隐遮罩", layout.contains("@drawable/bg_ktv_dock_shadow_left"))
        assertTrue("小窗右侧必须使用 Home KTV 渐隐遮罩", layout.contains("@drawable/bg_ktv_dock_shadow_right"))
        assertTrue("已点按钮必须配置原版已点图标", layout.contains("@drawable/ic_dock_order_list"))
        assertTrue("播放按钮必须配置 Home KTV 播放图标", layout.contains("@drawable/ic_player_play"))
        assertTrue("重唱按钮必须配置 Home KTV 重唱图标", layout.contains("@drawable/ic_player_replay"))
        assertTrue("切歌按钮必须配置 Home KTV 切歌图标", layout.contains("@drawable/ic_player_next"))
        assertTrue("降调按钮必须使用已核验的 IKTV 原画", layout.contains("@drawable/ktv_ref_v50_dock_tone_down"))
        assertTrue("升调按钮必须使用已核验的 IKTV 原画", layout.contains("@drawable/ktv_ref_v50_dock_tone_up"))
        assertTrue("下载按钮必须使用已核验的 IKTV 原画", layout.contains("@drawable/ktv_ref_v50_dock_download"))
        assertTrue("已唱按钮应使用历史记录图标", layout.contains("@drawable/ic_history"))
        assertTrue("伴唱按钮必须使用对标 IKTV 的伴唱切图", layout.contains("@drawable/ic_dock_accompany"))

        val controller = java.io.File("src/main/java/com/homektv/tv/ui/KtvBottomDockController.kt").let { file ->
            if (file.exists()) file.readText() else java.io.File("../$file").readText()
        }
        val render = controller.substringAfter("fun render(model: BottomDockUiModel)")
            .substringBefore("fun setQrBitmap")
        assertTrue("伴唱状态与原唱状态必须动态切换图标", render.contains("R.drawable.ic_dock_accompany") && render.contains("R.drawable.ic_dock_original"))
        assertTrue("播放状态切换后必须重新统一图标尺寸", render.indexOf("setCompoundDrawablesRelativeWithIntrinsicBounds") < render.indexOf("normalizeDockButtonIcons()"))
    }
}
