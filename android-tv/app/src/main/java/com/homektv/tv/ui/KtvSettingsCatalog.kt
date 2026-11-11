package com.homektv.tv.ui

import com.homektv.tv.R

enum class KtvSettingsColumn { LEFT, RIGHT }

enum class KtvSettingsAction {
    OPEN_LIBRARY_SCAN,
    OPEN_MEDIA_FEEDBACK,
    TOGGLE_LOCAL_PREFERENCE,
    EXPLAIN,
}

enum class KtvSettingsIndicator { ARROW, SWITCH, DISABLED_SWITCH }

data class KtvSettingsEntry(
    val key: String,
    val title: String,
    val value: String,
    val description: String,
    val iconRes: Int,
    val column: KtvSettingsColumn,
    val action: KtvSettingsAction,
    val indicator: KtvSettingsIndicator,
    val enabled: Boolean = true,
    val checked: Boolean = false,
)

/** The twelve reference rows, with Home KTV actions and capability state kept truthful. */
object KtvSettingsCatalog {
    fun entries(
        autoFullscreenEnabled: Boolean,
        globalSearchEnabled: Boolean,
        exitOnBackEnabled: Boolean,
        canOpenKiosk: Boolean,
        startupSyncEnabled: Boolean = false,
    ): List<KtvSettingsEntry> = listOf(
        KtvSettingsEntry(
            key = "download_path",
            title = "下载路径",
            value = "未设置下载目录",
            description = "Home KTV 当前没有歌曲下载目录。",
            iconRes = R.drawable.ktv_ref_v50_settings_device,
            column = KtvSettingsColumn.LEFT,
            action = KtvSettingsAction.EXPLAIN,
            indicator = KtvSettingsIndicator.ARROW,
        ),
        KtvSettingsEntry(
            key = "sync_songs",
            title = "同步歌曲",
            value = "扫描 Home KTV 曲库索引",
            description = "请求本地服务扫描曲库索引。",
            iconRes = R.drawable.ktv_ref_v50_settings_sync,
            column = KtvSettingsColumn.LEFT,
            action = KtvSettingsAction.OPEN_LIBRARY_SCAN,
            indicator = KtvSettingsIndicator.ARROW,
        ),
        KtvSettingsEntry(
            key = "manual_media",
            title = "手动添加媒体",
            value = "当前没有可导入的本地媒体",
            description = "Home KTV 当前没有安全的本地媒体导入流程。",
            iconRes = R.drawable.ktv_ref_v50_settings_usb,
            column = KtvSettingsColumn.LEFT,
            action = KtvSettingsAction.EXPLAIN,
            indicator = KtvSettingsIndicator.ARROW,
        ),
        KtvSettingsEntry(
            key = "auto_fullscreen",
            title = "自动全屏",
            value = "无操作时放大全屏播放",
            description = "一定时间没有交互后，视频窗口自动全屏。",
            iconRes = R.drawable.ktv_ref_v50_settings_fullscreen,
            column = KtvSettingsColumn.LEFT,
            action = KtvSettingsAction.TOGGLE_LOCAL_PREFERENCE,
            indicator = KtvSettingsIndicator.SWITCH,
            checked = autoFullscreenEnabled,
        ),
        KtvSettingsEntry(
            key = "auto_delete",
            title = "自动删歌",
            value = "关闭",
            description = "此设置保持关闭，不会删除曲库媒体。",
            iconRes = R.drawable.ktv_ref_v50_settings_delete,
            column = KtvSettingsColumn.LEFT,
            action = KtvSettingsAction.EXPLAIN,
            indicator = KtvSettingsIndicator.DISABLED_SWITCH,
        ),
        KtvSettingsEntry(
            key = "device_sync",
            title = "设备同步",
            value = "关闭",
            description = "Home KTV 不会因 USB 或存储设备变化自动复制媒体。",
            iconRes = R.drawable.ktv_ref_v50_settings_sync,
            column = KtvSettingsColumn.LEFT,
            action = KtvSettingsAction.EXPLAIN,
            indicator = KtvSettingsIndicator.DISABLED_SWITCH,
        ),
        KtvSettingsEntry(
            key = "download_server",
            title = "下载服务器",
            value = "无歌曲下载节点",
            description = "Home KTV 服务连接在系统信息中设置",
            iconRes = R.drawable.ktv_ref_v50_settings_cloud,
            column = KtvSettingsColumn.RIGHT,
            action = KtvSettingsAction.EXPLAIN,
            indicator = KtvSettingsIndicator.ARROW,
        ),
        KtvSettingsEntry(
            key = "media_feedback",
            title = "媒体反馈",
            value = "提交本地曲库心愿",
            description = "请将您需要的媒体反馈给我们。",
            iconRes = R.drawable.ktv_ref_v50_settings_feedback,
            column = KtvSettingsColumn.RIGHT,
            action = KtvSettingsAction.OPEN_MEDIA_FEEDBACK,
            indicator = KtvSettingsIndicator.ARROW,
        ),
        KtvSettingsEntry(
            key = "global_search",
            title = "全局搜索",
            value = if (canOpenKiosk) "在点歌页面检索曲库" else "当前设备模式没有电视点歌台",
            description = if (canOpenKiosk) {
                "控制点歌页面是否启用全局曲库检索。顶部搜索和歌名点歌始终可用。"
            } else {
                "当前设备工作模式没有电视点歌台。"
            },
            iconRes = R.drawable.ktv_ref_v50_settings_search,
            column = KtvSettingsColumn.RIGHT,
            action = KtvSettingsAction.TOGGLE_LOCAL_PREFERENCE,
            indicator = KtvSettingsIndicator.SWITCH,
            enabled = canOpenKiosk,
            checked = globalSearchEnabled,
        ),
        KtvSettingsEntry(
            key = "download_window",
            title = "下载悬浮窗",
            value = "关闭",
            description = "当前没有歌曲下载任务源。",
            iconRes = R.drawable.ktv_ref_v50_settings_download,
            column = KtvSettingsColumn.RIGHT,
            action = KtvSettingsAction.EXPLAIN,
            indicator = KtvSettingsIndicator.DISABLED_SWITCH,
        ),
        KtvSettingsEntry(
            key = "startup_sync",
            title = "启动同步",
            value = if (startupSyncEnabled) "电视冷启动时扫描一次" else "关闭",
            description = "仅在电视业务模式、服务已连接且服务确认外部曲库可扫描时，每次冷启动请求一次；不导入媒体。",
            iconRes = R.drawable.ktv_ref_v50_settings_sync,
            column = KtvSettingsColumn.RIGHT,
            action = KtvSettingsAction.TOGGLE_LOCAL_PREFERENCE,
            indicator = KtvSettingsIndicator.SWITCH,
            enabled = canOpenKiosk,
            checked = startupSyncEnabled,
        ),
        KtvSettingsEntry(
            key = "back_exit",
            title = "返回键退出应用",
            value = if (exitOnBackEnabled) "首页连续按两次返回键退出应用" else "首页返回键不会退出应用",
            description = "控制 Home KTV 首页的双返回退出行为。",
            iconRes = R.drawable.ktv_ref_v50_settings_back,
            column = KtvSettingsColumn.RIGHT,
            action = KtvSettingsAction.TOGGLE_LOCAL_PREFERENCE,
            indicator = KtvSettingsIndicator.SWITCH,
            checked = exitOnBackEnabled,
        ),
    )
}
