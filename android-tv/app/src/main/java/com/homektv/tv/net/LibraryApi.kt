package com.homektv.tv.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Reads the public catalogue status through the shared transport/session. */
class LibraryApi(private val transport: KtvTransport) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun status(): KtvApiResult<LibraryStatus> = when (val response = transport.get("/library/status")) {
        is KtvApiResult.Failure -> response
        is KtvApiResult.Success -> runCatching {
            json.decodeFromString(LibraryStatus.serializer(), response.value)
        }.fold(
            onSuccess = { KtvApiResult.Success(it) },
            onFailure = { KtvApiResult.Failure(KtvApiError(
                KtvApiErrorKind.DECODE, "INVALID_RESPONSE", "服务端数据格式异常")) },
        )
    }

    suspend fun requestScan(): KtvApiResult<LibraryScanSubmission> = when (
        val response = transport.post("/library/scan", JsonObject(emptyMap()))
    ) {
        is KtvApiResult.Failure -> response
        is KtvApiResult.Success -> runCatching {
            json.decodeFromString(LibraryScanSubmission.serializer(), response.value)
        }.fold(
            onSuccess = { KtvApiResult.Success(it) },
            onFailure = {
                KtvApiResult.Failure(KtvApiError(
                    KtvApiErrorKind.DECODE, "INVALID_RESPONSE", "服务端未返回扫描受理状态"))
            },
        )
    }
}
