package com.homektv.tv.ui

/**
 * 扫码点歌与网络访问地址格式化策略。
 */
object KtvQrPolicy {

    /**
     * 格式化局域网点歌访问 URL，若无 http 前缀自动补全。
     */
    fun formatPortalUrl(serverHost: String?): String {
        if (serverHost.isNullOrBlank()) return ""
        val host = serverHost.trim()
        return if (host.startsWith("http://", ignoreCase = true) || host.startsWith("https://", ignoreCase = true)) {
            host
        } else {
            "http://$host"
        }
    }

    /**
     * 构建 /api/qr 请求地址。
     */
    fun buildQrUrl(apiBase: String?, sizePx: Int = 540): String {
        if (apiBase.isNullOrBlank()) return ""
        val base = apiBase.trim().removeSuffix("/")
        return "$base/qr?size=$sizePx"
    }
}
