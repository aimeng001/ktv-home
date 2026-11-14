package com.homektv.tv.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KtvKioskSongAdapterTest {

    @Test
    fun buttonPolicy_onSubmit_disablesButtonAndShowsSubmittingText() {
        val orderState = SongOrderButtonPolicy.onSubmit(isTop = false)
        assertEquals("点歌中...", orderState.text)
        assertFalse(orderState.isEnabled)

        val topState = SongOrderButtonPolicy.onSubmit(isTop = true)
        assertEquals("插播中...", topState.text)
        assertFalse(topState.isEnabled)
    }

    @Test
    fun buttonPolicy_onComplete_whenSuccess_showsFinishedTextAndKeepsDisabled() {
        val orderSuccess = SongOrderButtonPolicy.onComplete(isTop = false, success = true)
        assertEquals("已点", orderSuccess.text)
        assertFalse(orderSuccess.isEnabled)

        val topSuccess = SongOrderButtonPolicy.onComplete(isTop = true, success = true)
        assertEquals("已置顶", topSuccess.text)
        assertFalse(topSuccess.isEnabled)
    }

    @Test
    fun buttonPolicy_onComplete_whenFailed_restoresInitialTextAndReEnablesButton() {
        // F-07: Rollback button state on network failure or business rejection
        val orderFailed = SongOrderButtonPolicy.onComplete(isTop = false, success = false)
        assertEquals("点歌", orderFailed.text)
        assertTrue(orderFailed.isEnabled)

        val topFailed = SongOrderButtonPolicy.onComplete(isTop = true, success = false)
        assertEquals("优先", topFailed.text)
        assertTrue(topFailed.isEnabled)
    }

    @Test
    fun buttonPolicy_shouldApplyResult_returnsTrueOnlyWhenTagMatchesCurrentSong() {
        val currentSongId = 88L
        assertTrue(SongOrderButtonPolicy.shouldApplyResult(currentSongId, 88L))
        assertFalse(SongOrderButtonPolicy.shouldApplyResult(currentSongId, 99L))
        assertFalse(SongOrderButtonPolicy.shouldApplyResult(null, 88L))
    }

    @Test
    fun buttonPolicy_resolveStateOnBind_distinguishesInFlightAndIdle() {
        val submittingState = SongOrderButtonPolicy.resolveStateOnBind(
            isTop = false,
            isSubmitting = true,
        )
        assertEquals("点歌中...", submittingState.text)
        assertFalse(submittingState.isEnabled)

        val idleState = SongOrderButtonPolicy.resolveStateOnBind(
            isTop = false,
            isSubmitting = false,
        )
        assertEquals("点歌", idleState.text)
        assertTrue(idleState.isEnabled)
    }

    @Test
    fun buttonPolicy_disablesSongsThatAreIndexedButNotPlayable() {
        val pending = SongOrderButtonPolicy.resolveStateOnBind(
            isTop = false,
            isSubmitting = false,
            queueState = null,
            playable = false,
        )
        assertEquals("准备中", pending.text)
        assertFalse(pending.isEnabled)
    }

    @Test
    fun buttonPolicy_resolveStateOnBind_retainsPlayingAndWaitingStatesAcrossRecycle() {
        // Playing song
        val orderPlaying = SongOrderButtonPolicy.resolveStateOnBind(
            isTop = false,
            isSubmitting = false,
            queueState = SongQueueState.Playing,
        )
        assertEquals("演唱中", orderPlaying.text)
        assertFalse(orderPlaying.isEnabled)

        val topPlaying = SongOrderButtonPolicy.resolveStateOnBind(
            isTop = true,
            isSubmitting = false,
            queueState = SongQueueState.Playing,
        )
        assertEquals("优先", topPlaying.text)
        assertFalse(topPlaying.isEnabled)

        // Waiting song
        val orderWaiting = SongOrderButtonPolicy.resolveStateOnBind(
            isTop = false,
            isSubmitting = false,
            queueState = SongQueueState.Waiting(3),
        )
        assertEquals("已点", orderWaiting.text)
        assertFalse(orderWaiting.isEnabled)

        val topWaiting = SongOrderButtonPolicy.resolveStateOnBind(
            isTop = true,
            isSubmitting = false,
            queueState = SongQueueState.Waiting(3),
        )
        assertEquals("优先", topWaiting.text)
        assertTrue(topWaiting.isEnabled)
    }

    @Test
    fun songListItemUsesHomeQueueIconAndHasNoPhysicalDeleteButton() {
        val workingDirectory = java.io.File(System.getProperty("user.dir").orEmpty())
        val layoutFile = sequenceOf(
            workingDirectory.resolve("src/main/res/layout/item_ktv_browser_song.xml"),
            workingDirectory.resolve("app/src/main/res/layout/item_ktv_browser_song.xml"),
            workingDirectory.resolve("android-tv/app/src/main/res/layout/item_ktv_browser_song.xml"),
        ).firstOrNull(java.io.File::isFile) ?: error("Cannot locate item_ktv_browser_song.xml")

        val content = layoutFile.readText()
        assertFalse("点歌台普通歌曲列表项严禁包含物理删除按钮以捍卫 NAS 只读红线", content.contains("btnBrowserSongDelete") || content.contains("ic_delete"))
        assertTrue("点歌台歌曲列表项必须使用 Home KTV 队列图标", content.contains("@drawable/ic_queue"))
        assertFalse("歌曲操作图标不得直接引用 IKTV 切图", content.contains("ktv_ref_"))
    }

    @Test
    fun compactSongCardKeepsLongTextBoundedAndActionsAccessible() {
        val workingDirectory = java.io.File(System.getProperty("user.dir").orEmpty())
        val layoutFile = sequenceOf(
            workingDirectory.resolve("src/main/res/layout/item_ktv_browser_song.xml"),
            workingDirectory.resolve("app/src/main/res/layout/item_ktv_browser_song.xml"),
            workingDirectory.resolve("android-tv/app/src/main/res/layout/item_ktv_browser_song.xml"),
        ).firstOrNull(java.io.File::isFile) ?: error("Cannot locate item_ktv_browser_song.xml")
        val layout = layoutFile.readText()
        val adapter = sequenceOf(
            workingDirectory.resolve("src/main/java/com/homektv/tv/ui/KtvKioskBrowserSongAdapter.kt"),
            workingDirectory.resolve("app/src/main/java/com/homektv/tv/ui/KtvKioskBrowserSongAdapter.kt"),
            workingDirectory.resolve("android-tv/app/src/main/java/com/homektv/tv/ui/KtvKioskBrowserSongAdapter.kt"),
        ).firstOrNull(java.io.File::isFile)?.readText() ?: error("Cannot locate KtvKioskBrowserSongAdapter.kt")

        assertTrue(layout.contains("android:id=\"@+id/txtBrowserSongTitle\""))
        assertTrue(layout.contains("android:id=\"@+id/txtBrowserSongMatch\""))
        assertTrue(layout.contains("android:ellipsize=\"end\""))
        assertTrue(layout.contains("android:maxLines=\"1\""))
        assertTrue(adapter.contains("binding.root.contentDescription = \"${'$'}displayTitle，${'$'}displayArtist\""))
        assertTrue(adapter.contains("SongDisplayFormatter.formatTitle"))
        assertTrue(adapter.contains("binding.btnBrowserSongOrder.contentDescription ="))
        assertTrue(adapter.contains("binding.btnBrowserSongOrderTop.contentDescription ="))
        assertTrue(adapter.contains("binding.btnBrowserSongFavorite.contentDescription ="))
    }

    @Test
    fun browserSongCardAndKeyboardMatchIktvReferenceStandards() {
        val workingDirectory = java.io.File(System.getProperty("user.dir").orEmpty())
        val layoutFile = sequenceOf(
            workingDirectory.resolve("src/main/res/layout/item_ktv_browser_song.xml"),
            workingDirectory.resolve("app/src/main/res/layout/item_ktv_browser_song.xml"),
            workingDirectory.resolve("android-tv/app/src/main/res/layout/item_ktv_browser_song.xml"),
        ).firstOrNull(java.io.File::isFile) ?: error("Cannot locate item_ktv_browser_song.xml")
        val layout = layoutFile.readText()

        val keyboardFile = sequenceOf(
            workingDirectory.resolve("src/main/res/layout/view_ktv_keyboard.xml"),
            workingDirectory.resolve("app/src/main/res/layout/view_ktv_keyboard.xml"),
            workingDirectory.resolve("android-tv/app/src/main/res/layout/view_ktv_keyboard.xml"),
        ).firstOrNull(java.io.File::isFile) ?: error("Cannot locate view_ktv_keyboard.xml")
        val keyboard = keyboardFile.readText()

        val adapterFile = sequenceOf(
            workingDirectory.resolve("src/main/java/com/homektv/tv/ui/KtvKioskBrowserSongAdapter.kt"),
            workingDirectory.resolve("app/src/main/java/com/homektv/tv/ui/KtvKioskBrowserSongAdapter.kt"),
            workingDirectory.resolve("android-tv/app/src/main/java/com/homektv/tv/ui/KtvKioskBrowserSongAdapter.kt"),
        ).firstOrNull(java.io.File::isFile) ?: error("Cannot locate KtvKioskBrowserSongAdapter.kt")
        val adapter = adapterFile.readText()

        // 验证歌曲卡片高度设为 46dp（配合 2dp 上下间距 = 50dp 步长）以保证 3 列 x 5 行精确满幅不露第 6 行
        assertTrue("歌曲卡片高度应为 46dp", layout.contains("android:layout_height=\"46dp\""))
        // 验证头像与紫红姓氏徽章组件
        assertTrue("卡片包含头像控件", layout.contains("android:id=\"@+id/imgBrowserSongAvatar\""))
        assertTrue("卡片包含姓氏/首字控件", layout.contains("android:id=\"@+id/txtBrowserSongIndex\""))
        assertTrue("卡片徽章背景使用原版紫红", layout.contains("@drawable/bg_ktv_browser_song_avatar_initial"))
        // 验证搜索框图标使用原版 ic_input_search
        assertTrue("键盘搜索框使用原版细线镂空图标", keyboard.contains("@drawable/ic_input_search"))
        // 验证适配器调用了 ArtistInitialResolver 提取姓氏
        assertTrue("适配器使用 ArtistInitialResolver 提取歌手姓氏", adapter.contains("ArtistInitialResolver.resolve"))
    }

    @Test
    fun browserAndSingerAdaptersSupportPayloadPartialBindingWithFreshListeners() {
        val workingDirectory = java.io.File(System.getProperty("user.dir").orEmpty())
        val songAdapter = sequenceOf(
            workingDirectory.resolve("src/main/java/com/homektv/tv/ui/KtvKioskBrowserSongAdapter.kt"),
            workingDirectory.resolve("app/src/main/java/com/homektv/tv/ui/KtvKioskBrowserSongAdapter.kt"),
            workingDirectory.resolve("android-tv/app/src/main/java/com/homektv/tv/ui/KtvKioskBrowserSongAdapter.kt"),
        ).firstOrNull(java.io.File::isFile)?.readText() ?: error("Cannot locate KtvKioskBrowserSongAdapter.kt")

        assertTrue(songAdapter.contains("PAYLOAD_STATE_CHANGED"))
        assertTrue(songAdapter.contains("notifyItemRangeChanged(0, itemCount, PAYLOAD_STATE_CHANGED)"))
        assertTrue(songAdapter.contains("override fun onBindViewHolder(") && songAdapter.contains("payloads: MutableList<Any>"))
        assertTrue(songAdapter.contains("private var boundSong: SongDto? = null"))

        val singerAdapter = sequenceOf(
            workingDirectory.resolve("src/main/java/com/homektv/tv/ui/SingerCardAdapter.kt"),
            workingDirectory.resolve("app/src/main/java/com/homektv/tv/ui/SingerCardAdapter.kt"),
            workingDirectory.resolve("android-tv/app/src/main/java/com/homektv/tv/ui/SingerCardAdapter.kt"),
        ).firstOrNull(java.io.File::isFile)?.readText() ?: error("Cannot locate SingerCardAdapter.kt")

        assertTrue(singerAdapter.contains("PAYLOAD_ARTIST_COUNTS"))
        assertTrue(singerAdapter.contains("getChangePayload("))
        assertTrue(singerAdapter.contains("private var boundArtist: ArtistItem? = null"))
    }
}
