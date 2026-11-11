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

    suspend fun searchAcronym(acronym: String, type: String = "", page: Int = 0): KtvApiResult<List<SongDto>> =
        getList(
            "/songs?acronym=${encodeQueryComponent(acronym.trim().uppercase())}" +
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
        region: String = "",
    ): KtvApiResult<ArtistPage> {
        val query = buildList {
            add("gender" to gender.trim())
            add("initial" to initial.trim())
            if (region.isNotBlank()) add("region" to region.trim())
            add("page" to page.coerceIn(0, MAX_PAGE).toString())
            add("size" to size.coerceIn(1, 100).toString())
        }.joinToString("&") { (key, value) -> "$key=${encodeQueryComponent(value)}" }
        return getDecoded("/browse/artists/page?$query", ArtistPage.serializer())
    }

    suspend fun artistInitials(gender: String = "", region: String = ""): KtvApiResult<List<String>> {
        val query = buildList {
            add("gender" to gender.trim())
            if (region.isNotBlank()) add("region" to region.trim())
        }.joinToString("&") { (key, value) -> "$key=${encodeQueryComponent(value)}" }
        return getDecoded("/browse/artists/initials?$query", ListSerializer(String.serializer()))
    }

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
