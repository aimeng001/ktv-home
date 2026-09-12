package com.homektv.tv.net

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** User registration and server-scoped nickname operations. */
class UserApi(
    private val transport: KtvTransport,
    private val userToken: String,
) {
    suspend fun register(nickname: String): KtvApiResult<UserProfile> =
        decode(
            transport.post(
                "/user",
                buildJsonObject {
                    put("client_token", userToken)
                    put("nickname", nickname.trim().take(32))
                },
            ),
            UserProfile.serializer(),
        )
}

class FavoriteApi(
    private val transport: KtvTransport,
    private val userToken: String,
) {
    suspend fun list(): KtvApiResult<List<SongDto>> =
        decode(transport.get("/favorites?clientToken=${encodeQueryComponent(userToken)}"), ListSerializer(SongDto.serializer()))

    suspend fun ids(): KtvApiResult<List<Long>> =
        decode(transport.get("/favorites/ids?clientToken=${encodeQueryComponent(userToken)}"), ListSerializer(Long.serializer()))

    suspend fun add(songId: Long): KtvApiResult<JsonObject> =
        decode(
            transport.post(
                "/favorites/${songId.coerceAtLeast(1)}",
                buildJsonObject { put("clientToken", userToken) },
            ),
            JsonObject.serializer(),
        )

    suspend fun remove(songId: Long): KtvApiResult<JsonObject> =
        decode(
            transport.delete(
                "/favorites/${songId.coerceAtLeast(1)}?clientToken=${encodeQueryComponent(userToken)}",
            ),
            JsonObject.serializer(),
        )
}

class RecentHistoryApi(
    private val transport: KtvTransport,
    private val userToken: String,
) {
    suspend fun list(mine: Boolean = false): KtvApiResult<List<RecentHistoryItem>> =
        decode(
            transport.get(
                "/history/recent?clientToken=${encodeQueryComponent(userToken)}&mine=$mine",
            ),
            ListSerializer(RecentHistoryItem.serializer()),
        )

    suspend fun repeat(historyId: Long, force: Boolean = false): KtvApiResult<JsonObject> =
        decode(
            transport.post(
                "/history/${historyId.coerceAtLeast(1)}/repeat",
                buildJsonObject {
                    put("clientToken", userToken)
                    put("force", force)
                },
            ),
            JsonObject.serializer(),
        )
}

class PlaylistApi(
    private val transport: KtvTransport,
    private val userToken: String,
) {
    suspend fun list(): KtvApiResult<List<PlaylistSummary>> =
        decode(transport.get("/playlists"), ListSerializer(PlaylistSummary.serializer()))

    suspend fun detail(id: Long): KtvApiResult<PlaylistDetail> =
        decode(transport.get("/playlists/${id.coerceAtLeast(1)}"), PlaylistDetail.serializer())

    suspend fun cover(id: Long): KtvApiResult<ByteArray> =
        transport.getBytes("/playlists/${id.coerceAtLeast(1)}/cover")

    suspend fun orderAll(id: Long): KtvApiResult<JsonObject> =
        decode(
            transport.post(
                "/playlists/${id.coerceAtLeast(1)}/order",
                buildJsonObject { put("clientToken", userToken) },
            ),
            JsonObject.serializer(),
        )
}

class RoomHostApi(
    private val transport: KtvTransport,
    private val userToken: String,
) {
    suspend fun status(): KtvApiResult<RoomHostStatus> =
        decode(
            transport.get("/room/host?clientToken=${encodeQueryComponent(userToken)}"),
            RoomHostStatus.serializer(),
        )

    suspend fun claim(): KtvApiResult<RoomHostStatus> =
        mutate("/room/host/claim")

    suspend fun release(): KtvApiResult<RoomHostStatus> =
        mutate("/room/host/release")

    private suspend fun mutate(path: String): KtvApiResult<RoomHostStatus> =
        decode(
            transport.post(path, buildJsonObject { put("clientToken", userToken) }),
            RoomHostStatus.serializer(),
        )
}

private val personalJson = Json { ignoreUnknownKeys = true; isLenient = true }

private fun <T> decode(response: KtvApiResult<String>, serializer: KSerializer<T>): KtvApiResult<T> =
    when (response) {
        is KtvApiResult.Failure -> response
        is KtvApiResult.Success -> runCatching {
            personalJson.decodeFromString(serializer, response.value)
        }.fold(
            onSuccess = { KtvApiResult.Success(it) },
            onFailure = {
                KtvApiResult.Failure(
                    KtvApiError(KtvApiErrorKind.DECODE, "INVALID_RESPONSE", "服务端数据格式异常"),
                )
            },
        )
    }
