package com.homektv.tv.ui.controller

import com.homektv.tv.controller.ControllerConnection

internal enum class ControllerPhonePanel {
    CATALOG,
    SEARCH,
    QUEUE,
    REMOTE,
    PERSONAL,
}

internal data class ControllerPhoneTab(
    val panel: ControllerPhonePanel,
    val title: String,
)

internal object ControllerPhoneNavigationPolicy {
    val tabs = listOf(
        ControllerPhoneTab(ControllerPhonePanel.CATALOG, "目录"),
        ControllerPhoneTab(ControllerPhonePanel.SEARCH, "搜索"),
        ControllerPhoneTab(ControllerPhonePanel.QUEUE, "队列"),
        ControllerPhoneTab(ControllerPhonePanel.REMOTE, "遥控"),
        ControllerPhoneTab(ControllerPhonePanel.PERSONAL, "我的"),
    )
}

internal object ControllerSavedStatePolicy {
    fun catalogMode(savedMode: String?): String = when (savedMode) {
        "LANGUAGES", "TAGS" -> savedMode
        else -> "ARTISTS"
    }

    fun personalMode(savedMode: String?, isPhone: Boolean): String = when (savedMode) {
        "HISTORY" -> "HISTORY"
        "PLAYLISTS" -> if (isPhone) "FAVORITES" else "PLAYLISTS"
        else -> "FAVORITES"
    }

    fun phonePanel(savedPanel: String?): String = when (savedPanel) {
        "CATALOG", "SEARCH", "QUEUE", "REMOTE", "PERSONAL" -> savedPanel
        else -> "CATALOG"
    }
}

internal object ControllerConnectionSummaryPolicy {
    fun label(connection: ControllerConnection, tvOnline: Boolean): String = when (connection) {
        ControllerConnection.CONNECTING -> "连接中…"
        ControllerConnection.ONLINE -> if (tvOnline) "电视在线" else "服务在线 · 电视未连接"
        ControllerConnection.OFFLINE -> "服务离线"
    }
}

internal object ControllerRemoteStatusPolicy {
    fun label(
        connection: ControllerConnection,
        tvOnline: Boolean,
        hasSong: Boolean,
        playbackState: String,
    ): String = when {
        connection == ControllerConnection.CONNECTING -> "连接中…"
        connection == ControllerConnection.OFFLINE -> "服务离线"
        !tvOnline -> "电视未连接"
        !hasSong -> "暂无歌曲"
        playbackState == "playing" -> "正在播放"
        playbackState == "paused" -> "已暂停"
        playbackState == "buffering" -> "缓冲中"
        playbackState == "idle" || playbackState == "stopped" -> "等待播放"
        else -> "播放状态未知"
    }
}
