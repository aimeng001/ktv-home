package com.homektv.tv.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

/** A server discovered on the local network. / 局域网中发现的服务端。 */
data class DiscoveredServer(
    val hostPort: String,
    val name: String,
    val instanceId: String? = null,
)

/** Wire constants and response parser for LAN discovery. / 局域网发现协议常量与响应解析器。 */
object DiscoveryProtocol {
    const val SERVICE_TYPE = "_home-ktv._tcp."
    const val REQUEST = "HOME_KTV_DISCOVER_V1"
    const val UDP_PORT = 18_888

    private val json = Json { ignoreUnknownKeys = true }

    internal fun normalizeInstanceId(raw: String?): String? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching { UUID.fromString(value) }.getOrNull()?.toString()
    }

    /**
     * 解析并校验服务端发现响应，非法响应返回 null。
     *
     * Parses and validates a discovery response, returning null for invalid payloads.
     */
    fun parseResponse(payload: String, sourceHost: String): DiscoveredServer? = runCatching {
        val root = json.parseToJsonElement(payload).jsonObject
        if (root["service"]?.jsonPrimitive?.content != "home-ktv") return null
        if (root["protocolVersion"]?.jsonPrimitive?.content?.toIntOrNull() != 1) return null
        val port = root["port"]?.jsonPrimitive?.content?.toIntOrNull()
            ?.takeIf { it in 1..65_535 } ?: return null
        val name = root["name"]?.jsonPrimitive?.content?.trim()
            ?.takeIf { it.isNotEmpty() } ?: sourceHost
        val hasInstanceId = root.containsKey("instanceId")
        val instanceId = root["instanceId"]?.let { element ->
            normalizeInstanceId(element.jsonPrimitive.content) ?: return null
        }
        if (hasInstanceId && instanceId == null) return null
        val formattedHost = if (':' in sourceHost && !sourceHost.startsWith("[")) "[$sourceHost]" else sourceHost
        DiscoveredServer("$formattedHost:$port", name, instanceId)
    }.getOrNull()
}
