package com.homektv.tv.net

import kotlinx.serialization.Serializable

/** A remembered server connection. / 已保存的服务端连接。 */
@Serializable
data class SavedServer(
    val hostPort: String,
    val name: String,
    val instanceId: String? = null,
)
