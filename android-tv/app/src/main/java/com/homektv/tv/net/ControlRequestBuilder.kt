package com.homektv.tv.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

private val controlJson = Json { ignoreUnknownKeys = true; isLenient = true }

internal fun buildControlRequest(action: String, params: String, clientToken: String): JsonObject? {
    val parsedParams = runCatching { controlJson.parseToJsonElement(params).jsonObject }.getOrNull() ?: return null
    return buildJsonObject {
        put("action", action.trim())
        put("params", parsedParams)
        put("client_token", clientToken)
    }
}
