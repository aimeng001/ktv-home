package com.homektv.tv.net

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Queue and control API. Write calls deliberately have no automatic retry. */
class QueueApi(
    private val transport: KtvTransport,
    private val userToken: String,
) {
    suspend fun snapshot(): KtvApiResult<QueueSnapshot> = postOrGetSnapshot(transport.get("/queue"))

    suspend fun control(action: String, params: Map<String, Any?> = emptyMap()): KtvApiResult<QueueSnapshot> {
        val body = buildJsonObject {
            put("action", action.trim())
            put("params", params.toJsonObject())
            put("client_token", userToken)
        }
        return postOrGetSnapshot(transport.post("/control", body))
    }

    private fun postOrGetSnapshot(response: KtvApiResult<String>): KtvApiResult<QueueSnapshot> = when (response) {
        is KtvApiResult.Failure -> response
        is KtvApiResult.Success -> runCatching {
            KtvJson.decodeFromString(QueueSnapshot.serializer(), response.value)
        }.fold(
            onSuccess = { KtvApiResult.Success(it) },
            onFailure = {
                KtvApiResult.Failure(
                    KtvApiError(KtvApiErrorKind.DECODE, "INVALID_QUEUE", "队列数据格式异常"),
                )
            },
        )
    }

    private fun Map<String, Any?>.toJsonObject(): JsonObject = buildJsonObject {
        for ((key, value) in this@toJsonObject) {
            when (value) {
                null -> put(key, kotlinx.serialization.json.JsonNull)
                is Boolean -> put(key, value)
                is Byte, is Short, is Int, is Long -> put(key, (value as Number).toLong())
                is Float, is Double -> put(key, (value as Number).toDouble())
                is String -> put(key, value)
                else -> error("Unsupported control parameter: $key")
            }
        }
    }

    companion object {
        private val KtvJson = kotlinx.serialization.json.Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
    }
}
