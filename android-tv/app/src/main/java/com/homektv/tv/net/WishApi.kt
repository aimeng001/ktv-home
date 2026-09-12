package com.homektv.tv.net

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** Missing-song feedback. It never performs a library or NAS write. */
class WishApi(
    private val transport: KtvTransport,
    private val userToken: String,
) {
    suspend fun add(keyword: String): KtvApiResult<JsonObject> = decodeWish(
        transport.post(
            "/wishes",
            buildJsonObject {
                put("keyword", keyword.trim().take(MAX_KEYWORD_LENGTH))
                put("client_token", userToken)
            },
        ),
    )

    companion object {
        private const val MAX_KEYWORD_LENGTH = 100
    }
}

private val wishJson = kotlinx.serialization.json.Json {
    ignoreUnknownKeys = true
    isLenient = true
}

private fun decodeWish(response: KtvApiResult<String>): KtvApiResult<JsonObject> = when (response) {
    is KtvApiResult.Failure -> response
    is KtvApiResult.Success -> runCatching { wishJson.parseToJsonElement(response.value).jsonObject }
        .fold(
            onSuccess = { KtvApiResult.Success(it) },
            onFailure = {
                KtvApiResult.Failure(
                    KtvApiError(KtvApiErrorKind.DECODE, "INVALID_RESPONSE", "服务端数据格式异常"),
                )
            },
        )
}
