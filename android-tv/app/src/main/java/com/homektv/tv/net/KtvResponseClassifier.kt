package com.homektv.tv.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Maps both HTTP failures and the server's 2xx business-error envelope. */
internal fun classifyKtvResponse(
    status: Int,
    body: String,
    json: Json = defaultKtvJson,
): KtvApiResult<String> {
    val envelope = runCatching { json.parseToJsonElement(body) }
        .getOrNull()
        ?.let { it as? JsonObject }
    val code = envelope?.get("code")?.let { element ->
        runCatching { element.jsonPrimitive.contentOrNull?.trim().orEmpty() }.getOrDefault("")
    }.orEmpty()
    val message = envelope?.get("message")?.let { element ->
        runCatching { element.jsonPrimitive.contentOrNull?.trim() }.getOrNull()
    }?.takeIf { it.isNotEmpty() }
    if (code.isNotEmpty()) {
        return KtvApiResult.Failure(
            KtvApiError(KtvApiErrorKind.BUSINESS, code, message ?: "请求未完成", status),
        )
    }
    if (status !in 200..299) {
        return KtvApiResult.Failure(
            KtvApiError(KtvApiErrorKind.HTTP, "HTTP_$status", "服务端请求失败（$status）", status),
        )
    }
    return KtvApiResult.Success(body)
}

internal fun isSuccessfulKtvControlResponse(status: Int, body: String?): Boolean =
    body != null && classifyKtvResponse(status, body) is KtvApiResult.Success<*>

private val defaultKtvJson = Json { ignoreUnknownKeys = true; isLenient = true }
