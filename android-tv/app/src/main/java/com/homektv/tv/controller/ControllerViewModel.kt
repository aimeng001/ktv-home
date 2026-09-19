package com.homektv.tv.controller

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.homektv.tv.net.AppConfig
import com.homektv.tv.net.KtvApiResult
import com.homektv.tv.net.KtvHttpTransport
import com.homektv.tv.net.KtvSocket
import com.homektv.tv.net.KtvSocketRole
import com.homektv.tv.net.LibraryApi
import com.homektv.tv.net.QueueApi
import com.homektv.tv.net.QueueSnapshot
import com.homektv.tv.net.PlaybackSnapshotBridge
import com.homektv.tv.net.FavoriteApi
import com.homektv.tv.net.PlaylistApi
import com.homektv.tv.net.RecentHistoryApi
import com.homektv.tv.net.RoomHostApi
import com.homektv.tv.net.SongApi
import com.homektv.tv.net.SongDto
import com.homektv.tv.net.UserApi
import com.homektv.tv.net.WishApi
import com.homektv.tv.ui.OrderTopOutcome
import com.homektv.tv.ui.QueueOrderTopPolicy
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive

/** Coordinates controller REST operations and the read-only realtime snapshot. */
class ControllerViewModel(
    application: Application,
    private val realtimeEnabled: Boolean = true,
) : AndroidViewModel(application), KtvSocket.Listener, ControllerActions, ControllerCatalogActions,
    ControllerCatalogVisibility, ControllerPersonalActions {

    private val config = AppConfig(application)
    private val transport = KtvHttpTransport(config)
    private val songApi = SongApi(transport)
    private val queueApi = QueueApi(transport, config.userToken)
    private val userApi = UserApi(transport, config.userToken)
    private val favoriteApi = FavoriteApi(transport, config.userToken)
    private val playlistApi = PlaylistApi(transport, config.userToken)
    private val historyApi = RecentHistoryApi(transport, config.userToken)
    private val roomHostApi = RoomHostApi(transport, config.userToken)
    private val wishApi = WishApi(transport, config.userToken)
    private val libraryApi = LibraryApi(transport)
    private val socket = if (realtimeEnabled) KtvSocket(config, this, KtvSocketRole.CONTROLLER) else null
    private val actions = ActionCoordinator()
    private var searchJob: Job? = null
    private var searchMoreJob: Job? = null
    private val queueRequests = LatestRequestScope(viewModelScope)
    private val catalogRequests = LatestRequestScope(viewModelScope)
    private val personalRequests = LatestRequestScope(viewModelScope)
    private val roomHostRequests = LatestRequestScope(viewModelScope)
    private val coverRequests = LatestRequestScope(viewModelScope)
    private var catalogQuery = CatalogQuery(CatalogKind.NONE)
    private var registrationJob: Job? = null
    private var catalogStatusJob: Job? = null
    private var catalogPollingJob: Job? = null
    private var catalogVisible = false
    private val _state = MutableStateFlow(ControllerUiState())
    override val state: StateFlow<ControllerUiState> = _state.asStateFlow()
    private var snapshotBridgeJob: Job? = null
    private var bridgeConnected: Boolean? = null

    init {
        socket?.connect()
        if (!realtimeEnabled) {
            snapshotBridgeJob = viewModelScope.launch {
                PlaybackSnapshotBridge.events
                    .filter { it?.serverHost == config.serverHost }
                    .collectLatest { envelope ->
                        val current = envelope ?: return@collectLatest
                        val wasConnected = bridgeConnected
                        bridgeConnected = current.connected
                        if (!current.connected) {
                            _state.update { ControllerStateReducer.withConnection(it, false) }
                        } else {
                            if (wasConnected != true) loadRoomHost()
                            current.roomHost?.let { roomHost ->
                                _state.update { ControllerStateReducer.withRoomHost(it, roomHost) }
                            }
                            current.snapshot?.let { snapshot ->
                                _state.update { ControllerStateReducer.withSnapshot(it, snapshot) }
                            } ?: _state.update { ControllerStateReducer.withConnection(it, true) }
                        }
                    }
            }
        }
        refreshQueue()
        if (config.isConfigured) refreshCatalogStatus()
        loadRanking()
        registerUser()
    }

    /** Retry identity registration explicitly or after a socket reconnect. */
    fun retryRegistration() {
        registerUser(force = true)
    }

    /** Starts/ends catalog status polling with the owning screen lifecycle. */
    override fun setCatalogVisible(visible: Boolean) {
        catalogVisible = visible
        if (!visible) {
            catalogPollingJob?.cancel()
            catalogPollingJob = null
            return
        }
        refreshCatalogStatus()
        if (catalogPollingJob?.isActive != true) {
            catalogPollingJob = viewModelScope.launch {
                while (isActive && catalogVisible) {
                    delay(if (_state.value.catalogStatus.state == CatalogLoadState.SCANNING) 3_000L else 30_000L)
                    if (catalogVisible) refreshCatalogStatus()
                }
            }
        }
    }

    private fun refreshCatalogStatus() {
        if (!config.isConfigured) return
        catalogStatusJob?.cancel()
        catalogStatusJob = viewModelScope.launch { refreshCatalogStatusNow() }
    }

    private suspend fun refreshCatalogStatusNow() {
        when (val result = libraryApi.status()) {
            is KtvApiResult.Success -> {
                val current = _state.value
                val next = CatalogStatusPolicy.from(
                    result.value,
                    hasItems = hasCatalogItems(current),
                    hasFilter = catalogQuery.kind != CatalogKind.NONE ||
                        current.artistGender.isNotBlank() || current.artistInitial.isNotBlank(),
                )
                if (!CatalogStatusPolicy.acceptsRevision(current.catalogStatusRevision, next.revision)) return
                val becameReady = current.catalogStatus.state == CatalogLoadState.SCANNING &&
                    next.state == CatalogLoadState.READY
                _state.update { it.copy(catalogStatus = next, catalogStatusRevision = next.revision) }
                if (becameReady) reloadCurrentCatalog()
            }
            is KtvApiResult.Failure -> _state.update {
                it.copy(catalogStatus = CatalogStatusPolicy.fromError(result.error, it.catalogStatusRevision))
            }
        }
    }

    private fun hasCatalogItems(state: ControllerUiState): Boolean =
        state.artists.isNotEmpty() || state.catalogSongs.isNotEmpty() ||
            state.ranking.isNotEmpty() || state.newSongs.isNotEmpty() ||
            state.languages.isNotEmpty() || state.tags.isNotEmpty()

    private fun reloadCurrentCatalog() {
        when (catalogQuery.kind) {
            CatalogKind.ARTISTS -> loadArtists(catalogQuery.value, catalogQuery.secondary, 0)
            CatalogKind.ARTIST_SONGS -> loadArtistSongs(catalogQuery.value, 0)
            CatalogKind.LANGUAGE_SONGS -> loadLanguageSongs(catalogQuery.value, 0)
            CatalogKind.TAG_SONGS -> loadTagSongs(catalogQuery.value, 0)
            CatalogKind.NONE -> Unit
        }
    }

    private fun registerUser(force: Boolean = false) {
        if (!config.isConfigured) return
        if (!force && (_state.value.registration == RegistrationStatus.REGISTERED || registrationJob?.isActive == true)) {
            return
        }
        registrationJob?.cancel()
        registrationJob = viewModelScope.launch {
            _state.update { RegistrationReducer.started(it) }
            when (val result = userApi.register(config.nicknameFor())) {
                is KtvApiResult.Success -> {
                    config.saveNickname(result.value.nickname)
                    _state.update { RegistrationReducer.succeeded(it, result.value) }
                }
                is KtvApiResult.Failure -> _state.update {
                    RegistrationReducer.failed(it, result.error)
                }
            }
        }
    }

    override fun setQuery(value: String) {
        val query = value.take(MAX_QUERY_LENGTH)
        searchJob?.cancel()
        searchMoreJob?.cancel()
        _state.update { ControllerStateReducer.withSearchStarted(it, query) }
        if (query.isBlank()) return

        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            when (val result = songApi.search(query, page = 0)) {
                is KtvApiResult.Success -> {
                    if (_state.value.query == query) {
                        _state.update {
                            ControllerStateReducer.withSearchResults(it, query, result.value, page = 0)
                        }
                    }
                }
                is KtvApiResult.Failure -> {
                    if (_state.value.query == query) {
                        _state.update { ControllerStateReducer.withSearchFailure(it, result.error) }
                    }
                }
            }
        }
    }

    /** Loads one bounded search page. A new keyword cancels this request. */
    override fun loadMoreSearch() {
        val current = _state.value
        if (current.query.isBlank() || current.loading || current.searchLoadingMore || !current.searchHasMore) return
        val query = current.query
        val page = current.searchPage + 1
        searchMoreJob?.cancel()
        _state.update { ControllerStateReducer.withSearchLoadStarted(it) }
        searchMoreJob = viewModelScope.launch {
            when (val result = songApi.search(query, page = page)) {
                is KtvApiResult.Success -> {
                    if (_state.value.query == query) {
                        _state.update {
                            ControllerStateReducer.withSearchResults(
                                it,
                                query,
                                result.value,
                                page = page,
                            )
                        }
                    }
                }
                is KtvApiResult.Failure -> {
                    if (_state.value.query == query) {
                        _state.update { ControllerStateReducer.withSearchFailure(it, result.error) }
                    }
                }
            }
        }
    }

    override fun refreshQueue() {
        queueRequests.launch {
            when (val result = queueApi.snapshot()) {
                is KtvApiResult.Success -> _state.update {
                    if (!realtimeEnabled && bridgeConnected == false) {
                        ControllerStateReducer.withSuccessfulRead(it, UiDomain.QUEUE).copy(queue = result.value)
                    } else {
                        ControllerStateReducer.withSnapshot(it, result.value)
                    }
                }
                is KtvApiResult.Failure -> _state.update {
                    ControllerStateReducer.withDomainFailure(it, UiDomain.QUEUE, result.error)
                }
            }
        }
    }

    override fun loadRanking() {
        setCatalogVisible(true)
        catalogQuery = CatalogQuery(CatalogKind.NONE)
        resetCatalogView()
        catalogRequests.launch {
            when (val result = songApi.ranking()) {
                is KtvApiResult.Success -> _state.update {
                    ControllerStateReducer.withSuccessfulRead(it, UiDomain.CATALOG).copy(
                        ranking = result.value.take(MAX_CATALOG_ITEMS),
                        catalogDetail = false,
                    )
                }
                is KtvApiResult.Failure -> _state.update { ControllerStateReducer.withDomainFailure(it, UiDomain.CATALOG, result.error) }
            }
        }
    }

    override fun loadNewSongs() {
        setCatalogVisible(true)
        catalogQuery = CatalogQuery(CatalogKind.NONE)
        resetCatalogView()
        catalogRequests.launch {
            when (val result = songApi.newSongs()) {
                is KtvApiResult.Success -> _state.update {
                    ControllerStateReducer.withSuccessfulRead(it, UiDomain.CATALOG).copy(
                        newSongs = result.value.take(MAX_CATALOG_ITEMS),
                        catalogDetail = false,
                    )
                }
                is KtvApiResult.Failure -> _state.update { ControllerStateReducer.withDomainFailure(it, UiDomain.CATALOG, result.error) }
            }
        }
    }

    override fun loadArtists(gender: String, initial: String, restorePage: Int) {
        setCatalogVisible(true)
        val safeGender = gender.trim().take(MAX_FILTER_LENGTH)
        val safeInitial = initial.trim().take(MAX_FILTER_LENGTH)
        catalogQuery = CatalogQuery(CatalogKind.ARTISTS, safeGender, safeInitial)
        _state.update {
            it.copy(
                artistGender = safeGender,
                artistInitial = safeInitial,
                artistInitials = emptyList(),
                artists = emptyList(),
                catalogSongs = emptyList(),
                catalogPage = 0,
                catalogHasMore = false,
                catalogLoadingMore = false,
                catalogDetail = false,
                catalogValue = "",
                error = null,
                message = null,
            )
        }
        val expected = catalogQuery
        catalogRequests.launch {
            launch { loadCatalogPages(restorePage, expected) { page -> loadArtistsPage(page, expected) } }
            launch { loadArtistInitials(safeGender, expected) }
        }
    }

    fun loadArtists() = loadArtists(gender = "", initial = "", restorePage = 0)

    override fun loadArtistSongs(artistKey: String, restorePage: Int) {
        setCatalogVisible(true)
        val safeArtistKey = artistKey.trim().take(MAX_FILTER_LENGTH)
        catalogQuery = CatalogQuery(CatalogKind.ARTIST_SONGS, safeArtistKey)
        _state.update {
            it.copy(
                artists = emptyList(),
                catalogSongs = emptyList(),
                catalogPage = 0,
                catalogHasMore = false,
                catalogLoadingMore = false,
                catalogDetail = true,
                catalogValue = safeArtistKey,
                error = null,
                message = null,
            )
        }
        val expected = catalogQuery
        catalogRequests.launch { loadCatalogPages(restorePage, expected) { page -> loadSongCatalogPage(page, expected) } }
    }

    fun loadArtistSongs(artistKey: String) = loadArtistSongs(artistKey, restorePage = 0)

    override fun loadLanguageSongs(language: String, restorePage: Int) {
        setCatalogVisible(true)
        val safeLanguage = language.trim().take(MAX_FILTER_LENGTH)
        catalogQuery = CatalogQuery(CatalogKind.LANGUAGE_SONGS, safeLanguage)
        _state.update {
            it.copy(
                catalogSongs = emptyList(),
                catalogPage = 0,
                catalogHasMore = false,
                catalogLoadingMore = false,
                catalogDetail = true,
                catalogValue = safeLanguage,
                error = null,
                message = null,
            )
        }
        val expected = catalogQuery
        catalogRequests.launch { loadCatalogPages(restorePage, expected) { page -> loadSongCatalogPage(page, expected) } }
    }

    fun loadLanguageSongs(language: String) = loadLanguageSongs(language, restorePage = 0)

    override fun loadTagSongs(tag: String, restorePage: Int) {
        setCatalogVisible(true)
        val safeTag = tag.trim().take(MAX_FILTER_LENGTH)
        catalogQuery = CatalogQuery(CatalogKind.TAG_SONGS, safeTag)
        _state.update {
            it.copy(
                catalogSongs = emptyList(),
                catalogPage = 0,
                catalogHasMore = false,
                catalogLoadingMore = false,
                catalogDetail = true,
                catalogValue = safeTag,
                error = null,
                message = null,
            )
        }
        val expected = catalogQuery
        catalogRequests.launch { loadCatalogPages(restorePage, expected) { page -> loadSongCatalogPage(page, expected) } }
    }

    fun loadTagSongs(tag: String) = loadTagSongs(tag, restorePage = 0)

    fun loadArtistsWithGender(gender: String) {
        loadArtists(gender = gender, initial = "", restorePage = 0)
    }

    fun loadArtistsWithInitial(initial: String) {
        loadArtists(
            gender = _state.value.artistGender,
            initial = initial.takeUnless { it == HOT_INITIAL }.orEmpty(),
            restorePage = 0,
        )
    }

    private suspend fun loadArtistInitials(gender: String, expected: CatalogQuery) {
            when (val result = songApi.artistInitials(gender)) {
                is KtvApiResult.Success -> {
                    if (catalogQuery == expected) {
                        _state.update {
                            ControllerStateReducer.withSuccessfulRead(it, UiDomain.CATALOG).copy(
                                artistInitials = result.value.take(MAX_ARTIST_INITIALS),
                            )
                        }
                    }
                }
                is KtvApiResult.Failure -> {
                    if (catalogQuery == expected) {
                        _state.update { ControllerStateReducer.withDomainFailure(it, UiDomain.CATALOG, result.error) }
                    }
                }
            }
    }

    private fun resetCatalogView() {
        _state.update {
            it.copy(
                artists = emptyList(),
                catalogSongs = emptyList(),
                catalogPage = 0,
                catalogHasMore = false,
                catalogLoadingMore = false,
                catalogDetail = false,
                catalogValue = "",
                error = null,
                message = null,
            )
        }
    }

    override fun loadMoreCatalog() {
        val current = _state.value
        if (catalogQuery.kind == CatalogKind.NONE || current.catalogLoadingMore || !current.catalogHasMore) return
        val page = current.catalogPage + 1
        val expected = catalogQuery
        _state.update { it.copy(catalogLoadingMore = true, error = null, message = null) }
        catalogRequests.launch {
            when (expected.kind) {
                CatalogKind.ARTISTS -> loadArtistsPage(page, expected)
                CatalogKind.ARTIST_SONGS,
                CatalogKind.LANGUAGE_SONGS,
                CatalogKind.TAG_SONGS
                -> loadSongCatalogPage(page, expected)
                CatalogKind.NONE -> Unit
            }
        }
    }

    override fun loadLanguages() {
        setCatalogVisible(true)
        catalogQuery = CatalogQuery(CatalogKind.NONE)
        resetCatalogView()
        catalogRequests.launch {
            when (val result = songApi.languages()) {
                is KtvApiResult.Success -> _state.update {
                    ControllerStateReducer.withSuccessfulRead(it, UiDomain.CATALOG).copy(
                        languages = result.value.take(MAX_CATALOG_ITEMS),
                        catalogSongs = emptyList(),
                        catalogHasMore = false,
                        catalogDetail = false,
                        catalogValue = "",
                    )
                }
                is KtvApiResult.Failure -> _state.update { ControllerStateReducer.withDomainFailure(it, UiDomain.CATALOG, result.error) }
            }
        }
    }

    override fun loadTags() {
        setCatalogVisible(true)
        catalogQuery = CatalogQuery(CatalogKind.NONE)
        resetCatalogView()
        catalogRequests.launch {
            when (val result = songApi.tags()) {
                is KtvApiResult.Success -> _state.update {
                    ControllerStateReducer.withSuccessfulRead(it, UiDomain.CATALOG).copy(
                        tags = result.value.take(MAX_CATALOG_ITEMS),
                        catalogSongs = emptyList(),
                        catalogHasMore = false,
                        catalogDetail = false,
                        catalogValue = "",
                    )
                }
                is KtvApiResult.Failure -> _state.update { ControllerStateReducer.withDomainFailure(it, UiDomain.CATALOG, result.error) }
            }
        }
    }

    private suspend fun loadArtistsPage(page: Int, expected: CatalogQuery) {
            when (val result = songApi.browseArtistsPage(
                page = page,
                gender = expected.value,
                initial = expected.secondary,
            )) {
                is KtvApiResult.Success -> {
                    if (catalogQuery != expected) return
                    _state.update { state ->
                        val artists = (if (page == 0) result.value.items
                        else (state.artists + result.value.items).distinctBy { it.artistKey })
                            .take(MAX_CATALOG_ITEMS)
                        ControllerStateReducer.withSuccessfulRead(state, UiDomain.CATALOG).copy(
                            artists = artists,
                            catalogSongs = emptyList(),
                            catalogDetail = false,
                            catalogPage = page,
                            catalogHasMore = hasMoreCatalogPage(
                                page = page,
                                pageSize = result.value.size,
                                pageItemCount = result.value.items.size,
                                total = result.value.total,
                                loaded = artists.size,
                            ),
                            catalogLoadingMore = false,
                        )
                    }
                }
                is KtvApiResult.Failure -> {
                    if (catalogQuery == expected) {
                        _state.update { ControllerStateReducer.withDomainFailure(it, UiDomain.CATALOG, result.error) }
                    }
                }
            }
    }

    private suspend fun loadSongCatalogPage(page: Int, expected: CatalogQuery) {
            val result = when (expected.kind) {
                CatalogKind.ARTIST_SONGS -> songApi.browseSongPage(page = page, artistKey = expected.value)
                CatalogKind.LANGUAGE_SONGS -> songApi.browseSongPage(page = page, language = expected.value)
                CatalogKind.TAG_SONGS -> songApi.browseSongPage(page = page, tag = expected.value)
                CatalogKind.ARTISTS, CatalogKind.NONE -> return
            }
            when (result) {
                is KtvApiResult.Success -> {
                    if (catalogQuery != expected) return
                    _state.update { state ->
                        val songs = (if (page == 0) result.value.items
                        else (state.catalogSongs + result.value.items).distinctBy { it.id })
                            .take(MAX_CATALOG_ITEMS)
                        ControllerStateReducer.withSuccessfulRead(state, UiDomain.CATALOG).copy(
                            artists = if (page == 0) emptyList() else state.artists,
                            catalogSongs = songs,
                            catalogPage = page,
                            catalogHasMore = hasMoreCatalogPage(
                                page = page,
                                pageSize = result.value.size,
                                pageItemCount = result.value.items.size,
                                total = result.value.total,
                                loaded = songs.size,
                            ),
                            catalogLoadingMore = false,
                            catalogDetail = true,
                        )
                    }
                }
                is KtvApiResult.Failure -> {
                    if (catalogQuery == expected) {
                        _state.update { ControllerStateReducer.withDomainFailure(it, UiDomain.CATALOG, result.error) }
                    }
                }
            }
    }

    /** Replays bounded pages during process restoration without issuing writes. */
    private suspend fun loadCatalogPages(
        targetPage: Int,
        expected: CatalogQuery,
        loadPage: suspend (Int) -> Unit,
    ) {
        val target = targetPage.coerceIn(0, MAX_RESTORE_PAGES)
        loadPage(0)
        var page = 0
        while (
            page < target &&
            catalogQuery == expected &&
            _state.value.catalogHasMore &&
            _state.value.error == null
        ) {
            page++
            loadPage(page)
        }
    }

    private fun hasMoreCatalogPage(
        page: Int,
        pageSize: Int,
        pageItemCount: Int,
        total: Long,
        loaded: Int,
    ): Boolean {
        if (loaded >= MAX_CATALOG_ITEMS || pageItemCount == 0) return false
        val safeSize = pageSize.coerceAtLeast(1)
        return if (total > 0) (page.toLong() + 1L) * safeSize < total
        else pageItemCount >= safeSize
    }

    override fun order(song: SongDto) {
        order(song) { }
    }

    override fun order(song: SongDto, onComplete: (Boolean) -> Unit) {
        launchWrite(ActionKey("song_queue_mutation", song.id), operation = {
            queueApi.control("order", mapOf("song_id" to song.id, "force" to false))
        }, onSuccess = { snapshot ->
            _state.update {
                ControllerStateReducer.withSnapshot(it, snapshot).copy(message = "已点歌：${song.title}")
            }
        }, onResult = onComplete)
    }

    override fun orderTop(song: SongDto, onComplete: (Boolean) -> Unit) {
        val existing = _state.value.queue.list.firstOrNull { it.song?.id == song.id && it.status == "waiting" }
        if (existing != null && existing.queueId != null) {
            val canManage = QueuePermissionPolicy.canManage(existing, _state.value)
            if (!canManage) {
                _state.update {
                    it.copy(
                        error = com.homektv.tv.net.KtvApiError(
                            kind = com.homektv.tv.net.KtvApiErrorKind.BUSINESS,
                            code = "FORBIDDEN",
                            message = "只能置顶自己点的歌曲",
                        ),
                        message = "只能置顶自己点的歌曲",
                    )
                }
                onComplete(false)
                return
            }
            val queueId = existing.queueId!!
            launchWrite(
                ActionKey("song_queue_mutation", song.id),
                operation = { queueApi.control("top", mapOf("queue_id" to queueId)) },
                onSuccess = { snapshot ->
                    _state.update {
                        ControllerStateReducer.withSnapshot(it, snapshot).copy(message = "已置顶：${song.title}")
                    }
                },
                onResult = onComplete,
            )
            return
        }

        launchWrite(
            ActionKey("song_queue_mutation", song.id),
            operation = {
                val orderResult = queueApi.control(
                    "order",
                    mapOf("song_id" to song.id, "force" to false),
                )
                when (orderResult) {
                    is KtvApiResult.Failure -> orderResult
                    is KtvApiResult.Success -> when (
                        val outcome = QueueOrderTopPolicy.resolve(
                            orderResult = orderResult,
                            songId = song.id,
                            topInvoker = { queueId ->
                                queueApi.control("top", mapOf("queue_id" to queueId))
                            },
                        )
                    ) {
                        is OrderTopOutcome.OrderFailed -> KtvApiResult.Failure(
                            com.homektv.tv.net.KtvApiError(
                                kind = com.homektv.tv.net.KtvApiErrorKind.BUSINESS,
                                code = "ORDER_TOP_FAILED",
                                message = outcome.error,
                            ),
                        )
                        else -> KtvApiResult.Success(outcome)
                    }
                }
            },
            onSuccess = { outcome ->
                val snapshot = when (outcome) {
                    is OrderTopOutcome.SuccessTop -> outcome.snapshot
                    is OrderTopOutcome.FallbackOrderOnly -> outcome.snapshot
                    is OrderTopOutcome.OrderFailed -> return@launchWrite
                }
                val message = when (outcome) {
                    is OrderTopOutcome.SuccessTop -> "${outcome.message}：${song.title}"
                    is OrderTopOutcome.FallbackOrderOnly -> "${outcome.warning}：${song.title}"
                    is OrderTopOutcome.OrderFailed -> null
                }
                _state.update {
                    ControllerStateReducer.withSnapshot(it, snapshot).copy(message = message)
                }
            },
            onResult = onComplete,
        )
    }

    override fun top(queueId: Long) {
        write("top", mapOf("queue_id" to queueId), queueId, successMessage = "已置顶")
    }

    override fun cancel(queueId: Long) {
        write("cancel", mapOf("queue_id" to queueId), queueId, successMessage = "已从待播队列删除")
    }

    override fun control(action: String, params: Map<String, Any?>) {
        write(action, params, requiresRegistration = RegistrationReducer.requiresRegistration(action))
    }

    override fun loadFavorites() {
        personalRequests.launch {
            when (val result = favoriteApi.list()) {
                is KtvApiResult.Success -> _state.update {
                    val favorites = result.value.take(MAX_PERSONAL_ITEMS)
                    ControllerStateReducer.withSuccessfulRead(it, UiDomain.FAVORITES).copy(
                        favorites = favorites,
                        favoriteIds = favorites.mapTo(linkedSetOf()) { song -> song.id },
                    )
                }
                is KtvApiResult.Failure -> _state.update { ControllerStateReducer.withDomainFailure(it, UiDomain.FAVORITES, result.error) }
            }
        }
    }

    fun toggleFavorite(song: SongDto) {
        val favorite = _state.value.favoriteIds.contains(song.id)
        launchWrite(
            ActionKey(if (favorite) "favorite_remove" else "favorite_add", song.id),
            operation = { if (favorite) favoriteApi.remove(song.id) else favoriteApi.add(song.id) },
            onSuccess = {
                _state.update { state ->
                    val ids = state.favoriteIds.toMutableSet()
                    if (favorite) ids.remove(song.id) else ids.add(song.id)
                    state.copy(
                        favoriteIds = ids,
                        favorites = if (favorite) state.favorites.filterNot { it.id == song.id }
                        else (listOf(song) + state.favorites.filterNot { it.id == song.id }),
                        message = if (favorite) "已取消收藏" else "已加入收藏",
                    )
                }
            },
        )
    }

    override fun loadPlaylists() {
        coverRequests.cancel()
        personalRequests.launch {
            when (val result = playlistApi.list()) {
                is KtvApiResult.Success -> _state.update {
                    ControllerStateReducer.withSuccessfulRead(it, UiDomain.PLAYLISTS).copy(
                        playlists = result.value.take(MAX_PERSONAL_ITEMS),
                        playlistDetail = null,
                        playlistDetailLoading = false,
                        playlistCoverBytes = null,
                    )
                }
                is KtvApiResult.Failure -> _state.update { ControllerStateReducer.withDomainFailure(it, UiDomain.PLAYLISTS, result.error) }
            }
        }
    }

    override fun loadPlaylistDetail(playlistId: Long) {
        val id = playlistId.coerceAtLeast(1)
        coverRequests.cancel()
        _state.update {
            it.copy(
                playlistDetail = null,
                playlistDetailLoading = true,
                playlistCoverBytes = null,
                error = null,
                message = null,
            )
        }
        personalRequests.launch {
            when (val result = playlistApi.detail(id)) {
                is KtvApiResult.Success -> {
                    _state.update {
                        ControllerStateReducer.withSuccessfulRead(it, UiDomain.PLAYLISTS).copy(
                            playlistDetail = result.value,
                            playlistDetailLoading = false,
                        )
                    }
                    loadPlaylistCover(id)
                }
                is KtvApiResult.Failure -> _state.update {
                    ControllerStateReducer.withDomainFailure(it, UiDomain.PLAYLISTS, result.error)
                        .copy(playlistDetailLoading = false)
                }
            }
        }
    }

    override fun clearPlaylistDetail() {
        personalRequests.cancel()
        coverRequests.cancel()
        _state.update { it.copy(playlistDetail = null, playlistDetailLoading = false, playlistCoverBytes = null) }
    }

    private fun loadPlaylistCover(playlistId: Long) {
        coverRequests.launch {
            when (val result = playlistApi.cover(playlistId)) {
                is KtvApiResult.Success -> _state.update { it.copy(playlistCoverBytes = result.value) }
                is KtvApiResult.Failure -> _state.update { it.copy(playlistCoverBytes = null) }
            }
        }
    }

    override fun orderPlaylist(playlistId: Long) {
        launchWrite(
            ActionKey("playlist_order", playlistId),
            operation = { playlistApi.orderAll(playlistId) },
            onSuccess = {
                _state.update { it.copy(message = "歌单已加入队列") }
                refreshQueue()
            },
        )
    }

    override fun loadHistory(mine: Boolean) {
        personalRequests.launch {
            when (val result = historyApi.list(mine)) {
                is KtvApiResult.Success -> _state.update {
                    ControllerStateReducer.withSuccessfulRead(it, UiDomain.HISTORY).copy(
                        history = result.value.take(MAX_PERSONAL_ITEMS),
                    )
                }
                is KtvApiResult.Failure -> _state.update { ControllerStateReducer.withDomainFailure(it, UiDomain.HISTORY, result.error) }
            }
        }
    }

    fun loadHistory() = loadHistory(mine = false)

    override fun repeatHistory(historyId: Long) {
        launchWrite(
            ActionKey("history_repeat", historyId),
            operation = { historyApi.repeat(historyId) },
            onSuccess = {
                _state.update { it.copy(message = "已再次点歌") }
                refreshQueue()
            },
        )
    }

    fun submitWish(keyword: String) {
        val value = keyword.trim().take(MAX_WISH_LENGTH)
        if (value.isBlank()) return
        launchWrite(
            ActionKey("wish", value.hashCode().toLong()),
            operation = { wishApi.add(value) },
            onSuccess = { _state.update { it.copy(message = "已提交点歌心愿") } },
        )
    }

    override fun loadRoomHost() {
        roomHostRequests.launch {
            when (val result = roomHostApi.status()) {
                is KtvApiResult.Success -> _state.update {
                    ControllerStateReducer.withSuccessfulRead(
                        ControllerStateReducer.withRoomHost(it, result.value),
                        UiDomain.ROOM_HOST,
                    )
                }
                is KtvApiResult.Failure -> _state.update { ControllerStateReducer.withDomainFailure(it, UiDomain.ROOM_HOST, result.error) }
            }
        }
    }

    fun claimRoomHost() {
        launchWrite(
            ActionKey("room_host_claim"),
            operation = { roomHostApi.claim() },
            onSuccess = { status -> _state.update {
                ControllerStateReducer.withRoomHost(it, status).copy(message = "已成为房主")
            } },
        )
    }

    fun releaseRoomHost() {
        launchWrite(
            ActionKey("room_host_release"),
            operation = { roomHostApi.release() },
            onSuccess = { status -> _state.update {
                ControllerStateReducer.withRoomHost(it, status).copy(message = "已释放房主身份")
            } },
        )
    }

    fun clearMessage() {
        _state.update { it.copy(message = null, error = null) }
    }

    override fun onSnapshot(event: String, snapshot: QueueSnapshot) {
        _state.update { ControllerStateReducer.withSnapshot(it, snapshot) }
    }

    override fun onProgress(positionMs: Long) {
        _state.update { state ->
            state.copy(queue = state.queue.copy(positionMs = positionMs.coerceAtLeast(0)))
        }
    }

    override fun onConnectionChanged(connected: Boolean) {
        _state.update { ControllerStateReducer.withConnection(it, connected) }
        if (connected) {
            loadRoomHost()
            if (_state.value.registration != RegistrationStatus.REGISTERED) {
                registerUser()
            }
        }
    }

    override fun onToast(text: String) {
        if (text.isNotBlank()) _state.update { it.copy(message = text) }
    }

    override fun onRoomHostChanged(status: com.homektv.tv.net.RoomHostStatus) {
        _state.update { ControllerStateReducer.withRoomHost(it, status) }
    }

    override fun onPlayerRole(active: Boolean) {
        // Controller sessions are never eligible for a playback lease.
    }

    private fun write(
        action: String,
        params: Map<String, Any?>,
        resourceId: Long = 0L,
        requiresRegistration: Boolean = true,
        successMessage: String? = null,
    ) {
        launchWrite(
            ActionKey("control:$action", resourceId),
            operation = { queueApi.control(action, params) },
            onSuccess = { snapshot ->
                _state.update {
                    ControllerStateReducer.withSnapshot(it, snapshot).copy(message = successMessage)
                }
            },
            requiresRegistration = requiresRegistration,
        )
    }

    private fun <T> launchWrite(
        actionKey: ActionKey,
        operation: suspend () -> KtvApiResult<T>,
        onSuccess: (T) -> Unit,
        requiresRegistration: Boolean = true,
        onResult: (Boolean) -> Unit = {},
    ) {
        if (requiresRegistration && !RegistrationReducer.canWrite(_state.value)) {
            _state.update { it.copy(message = "点歌身份尚未就绪，请先完成注册") }
            registerUser()
            onResult(false)
            return
        }
        if (!actions.tryStart(actionKey)) {
            _state.update { it.copy(message = "上一个操作仍在处理中，请稍候") }
            onResult(false)
            return
        }
        viewModelScope.launch {
            _state.update {
                it.copy(
                    pendingActions = it.pendingActions + actionKey,
                    writing = true,
                    message = null,
                    error = null,
                )
            }
            var succeeded = false
            try {
                when (val result = operation()) {
                    is KtvApiResult.Success -> {
                        onSuccess(result.value)
                        succeeded = true
                    }
                    is KtvApiResult.Failure -> _state.update {
                        ControllerStateReducer.withFailure(it, result.error)
                    }
                }
            } finally {
                actions.finish(actionKey)
                _state.update {
                    val remaining = it.pendingActions - actionKey
                    it.copy(pendingActions = remaining, writing = remaining.isNotEmpty())
                }
                onResult(succeeded)
            }
        }
    }

    override fun onCleared() {
        searchJob?.cancel()
        searchMoreJob?.cancel()
        registrationJob?.cancel()
        catalogStatusJob?.cancel()
        catalogPollingJob?.cancel()
        snapshotBridgeJob?.cancel()
        queueRequests.cancel()
        catalogRequests.cancel()
        personalRequests.cancel()
        roomHostRequests.cancel()
        coverRequests.cancel()
        socket?.close()
        transport.close()
        super.onCleared()
    }

    companion object {
        private const val SEARCH_DEBOUNCE_MS = 250L
        private const val MAX_QUERY_LENGTH = 128
        private const val MAX_WISH_LENGTH = 100
        private const val MAX_FILTER_LENGTH = 32
        private const val MAX_CATALOG_ITEMS = 2000
        private const val MAX_RESTORE_PAGES = 64
        private const val MAX_PERSONAL_ITEMS = 2000
        private const val MAX_ARTIST_INITIALS = 128
        private const val HOT_INITIAL = "热门"
    }
}

class ControllerViewModelFactory(
    private val application: Application,
    private val realtimeEnabled: Boolean,
) : androidx.lifecycle.ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(ControllerViewModel::class.java))
        return ControllerViewModel(application, realtimeEnabled) as T
    }
}

private enum class CatalogKind {
    NONE,
    ARTISTS,
    ARTIST_SONGS,
    LANGUAGE_SONGS,
    TAG_SONGS,
}

private data class CatalogQuery(
    val kind: CatalogKind,
    val value: String = "",
    val secondary: String = "",
)
