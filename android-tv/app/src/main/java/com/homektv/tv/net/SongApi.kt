package com.homektv.tv.net

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** Read-only public catalogue API used by Home/Search/Browse screens. */
class SongApi(
    private val transport: KtvTransport,
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
) {
    suspend fun search(keyword: String, type: String = "", page: Int = 0): KtvApiResult<List<SongDto>> =
        getList(
            "/songs?keyword=${encodeQueryComponent(keyword.trim())}" +
                "&type=${encodeQueryComponent(type.trim())}&page=${page.coerceIn(0, MAX_PAGE)}",
        )

    suspend fun songDetail(songId: Long): KtvApiResult<SongDetail> =
        getDecoded("/songs/${songId.coerceAtLeast(1)}", SongDetail.serializer())

    suspend fun ranking(days: Int = 30): KtvApiResult<List<SongDto>> =
        getList("/ranking?days=${days.coerceIn(1, 3650)}")

    suspend fun newSongs(): KtvApiResult<List<SongDto>> = getList("/songs/new")

    suspend fun browseArtistsPage(
        page: Int = 0,
        size: Int = 30,
        gender: String = "",
        initial: String = "",
    ): KtvApiResult<ArtistPage> = getDecoded(
        "/browse/artists/page?gender=${encodeQueryComponent(gender.trim())}" +
            "&initial=${encodeQueryComponent(initial.trim())}" +
            "&page=${page.coerceIn(0, MAX_PAGE)}&size=${size.coerceIn(1, 100)}",
        ArtistPage.serializer(),
    )

    suspend fun artistInitials(gender: String = ""): KtvApiResult<List<String>> =
        getDecoded(
            "/browse/artists/initials?gender=${encodeQueryComponent(gender.trim())}",
            ListSerializer(String.serializer()),
        )

    suspend fun languages(): KtvApiResult<List<NamedCount>> =
        getDecoded("/browse/languages", ListSerializer(NamedCount.serializer()))

    suspend fun tags(): KtvApiResult<List<NamedCount>> =
        getDecoded("/browse/tags", ListSerializer(NamedCount.serializer()))

    suspend fun browseSongPage(
        page: Int = 0,
        size: Int = 50,
        artist: String = "",
        artistKey: String = "",
        artistGender: String = "",
        language: String = "",
        tag: String = "",
        sort: String = "hot",
    ): KtvApiResult<SongPage> {
        val query = listOf(
            "artist" to artist,
            "artistKey" to artistKey,
            "artistGender" to artistGender,
            "language" to language,
            "tag" to tag,
            "sort" to sort,
            "page" to page.coerceIn(0, MAX_PAGE).toString(),
            "size" to size.coerceIn(1, 100).toString(),
        ).joinToString("&") { (key, value) -> "$key=${encodeQueryComponent(value.trim())}" }
        return getDecoded("/browse/songs/page?$query", SongPage.serializer())
    }

    private suspend fun getList(path: String): KtvApiResult<List<SongDto>> =
        getDecoded(path, ListSerializer(SongDto.serializer()))

    private suspend fun <T> getDecoded(path: String, serializer: KSerializer<T>): KtvApiResult<T> =
        when (val response = transport.get(path)) {
            is KtvApiResult.Failure -> response
            is KtvApiResult.Success -> runCatching { json.decodeFromString(serializer, response.value) }
                .fold(
                    onSuccess = { KtvApiResult.Success(it) },
                    onFailure = {
                        KtvApiResult.Failure(
                            KtvApiError(KtvApiErrorKind.DECODE, "INVALID_RESPONSE", "服务端数据格式异常"),
                        )
                    },
                )
        }

    companion object {
        private const val MAX_PAGE = 4000
    }
}
