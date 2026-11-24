package com.homektv.tv.controller

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.homektv.tv.net.AppConfig
import com.homektv.tv.net.KtvApiResult
import com.homektv.tv.net.KtvHttpTransport
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
import com.homektv.tv.ui.OrderTopOutcome
import com.homektv.tv.ui.QueueOrderTopPolicy
import com.homektv.tv.ui.kiosk.T9PinyinSearchPolicy
import android.os.SystemClock
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
) : AndroidViewModel(application), ControllerActions, ControllerCatalogActions,
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
    private val libraryApi = LibraryApi(transport)
    private val actions = ActionCoordinator()
    private var searchJob: Job? = null
    private var searchMoreJob: Job? = null
    private val searchRequestGate = SearchRequestGate()
    private val searchCache = SearchResultLruCache(clockMs = { SystemClock.elapsedRealtime() })
    private val queueRequests = LatestRequestScope(viewModelScope)
    private val catalogRequests = LatestRequestScope(viewModelScope)
    private val tagMetadataRequests = LatestRequestScope(viewModelScope)
    private val kioskLibraryRequests = LatestRequestScope(viewModelScope)
    private val personalRequests = LatestRequestScope(viewModelScope)
    private val roomHostRequests = LatestRequestScope(viewModelScope)
    private val coverRequests = LatestRequestScope(viewModelScope)
    private var catalogQuery = CatalogQuery(CatalogKind.NONE)
    private var catalogRoot = CatalogRoot.NONE
    private var registrationJob: Job? = null
    private var catalogStatusJob: Job? = null
    private var catalogPollingJob: Job? = null
    private var catalogVisible = false
    private var kioskLibraryActive = false
    private var kioskLibraryRequestId = 0L
    private val _state = MutableStateFlow(ControllerUiState())
    override val state: StateFlow<ControllerUiState> = _state.asStateFlow()
    private var snapshotBridgeJob: Job? = null
    private var bridgeConnected: Boolean? = null

    init {
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
                        if (RegistrationReducer.shouldRegisterOnConnect(_state.value.registration, current.connected, wasConnected)) {
                            registerUser()
                        }
                        current.roomHost?.let { roomHost ->
                            _state.update { ControllerStateReducer.withRoomHost(it, roomHost) }
                        }
                        if (current.event == "progress") {
                            current.snapshot?.let { snapshot ->
                                _state.update { ControllerStateReducer.withSnapshot(it, snapshot) }
                            }
                            current.progress?.let { progress ->
                                _state.update { ControllerStateReducer.withProgress(it, progress) }
                            }
                            return@collectLatest
                        }
                        current.snapshot?.let { snapshot ->
                            _state.update { ControllerStateReducer.withSnapshot(it, snapshot) }
                        } ?: _state.update { ControllerStateReducer.withConnection(it, true) }
                    }
                }
        }
        refreshQueue()
        if (config.isConfigured) refreshCatalogStatus()
        registerUser()
    }

    fun clearSearchCache() {
        searchCache.clear()
    }

    /** Starts/ends catalog status polling with the owning screen lifecycle. */
    override fun setCatalogVisible(visible: Boolean) {
        catalogVisible = visible
        if (!visible) {
            catalogPollingJob?.cancel()
            catalogPollingJob = null
            stopKioskAllSongs()
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
                val revisionChanged = current.catalogStatusRevision > 0L &&
                    next.revision > current.catalogStatusRevision
                _state.update { it.copy(catalogStatus = next, catalogStatusRevision = next.revision) }
                if (becameReady || revisionChanged) {
                    searchCache.clear()
                    reloadCurrentCatalog()
                    if (kioskLibraryActive) reloadKioskAllSongs()
                }
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
        when (catalogRoot) {
            CatalogRoot.RANKING -> reloadRankingCatalog()
            CatalogRoot.NEW_SONGS -> reloadNewSongsCatalog()
            CatalogRoot.LANGUAGES -> reloadLanguagesCatalog()
            CatalogRoot.TAGS -> reloadTagsCatalog()
            CatalogRoot.NONE -> when (catalogQuery.kind) {
                CatalogKind.ARTISTS -> loadArtists(
                    catalogQuery.value, catalogQuery.secondary, 0, catalogQuery.region,
                )
                CatalogKind.ARTIST_SONGS -> loadArtistSongs(catalogQuery.value, 0)
                CatalogKind.LANGUAGE_SONGS -> loadLanguageSongs(catalogQuery.value, 0)
                CatalogKind.TAG_SONGS -> loadTagSongs(catalogQuery.value, 0)
                CatalogKind.NONE -> Unit
            }
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
        executeSearch(value, restorePage = 0, debounce = true)
    }

    override fun restoreSearch(query: String, restorePage: Int) {
        executeSearch(query, restorePage = restorePage, debounce = false)
    }

    private fun executeSearch(value: String, restorePage: Int, debounce: Boolean) {
        val query = T9PinyinSearchPolicy.normalizeSearchKeyword(value).take(MAX_QUERY_LENGTH)
        val changedAtMs = SystemClock.elapsedRealtime()
        val request = searchRequestGate.begin(query)
        searchJob?.cancel()
        searchMoreJob?.cancel()
        if (query.isBlank()) {
            _state.update { ControllerStateReducer.withSearchStarted(it, query) }
            return
        }

        val targetPage = if (restorePage > 0) {
            restorePage.coerceIn(0, MAX_RESTORE_PAGES)
        } else if (query.codePointCount(0, query.length) <= 2) {
            1
        } else {
            0
        }
        val revision = _state.value.catalogStatusRevision
        val cached = searchCache.getSongs(query, revision)
        if (cached != null && (cached.lastPage >= targetPage || !cached.hasMore)) {
            _state.update { state ->
                val nextDomainErrors = state.domainErrors - UiDomain.SEARCH
                state.copy(
                    query = query,
                    results = cached.songs,
                    searchPage = cached.lastPage,
                    searchHasMore = cached.hasMore,
                    searchLoadingMore = false,
                    loading = false,
                    error = nextDomainErrors.values.firstOrNull(),
                    domainErrors = nextDomainErrors,
                    message = null,
                )
            }
            return
        }

        val startPage = if (cached != null && cached.hasMore) cached.lastPage + 1 else 0
        if (cached != null) {
            _state.update { state ->
                val nextDomainErrors = state.domainErrors - UiDomain.SEARCH
                state.copy(
                    query = query,
                    results = cached.songs,
                    searchPage = cached.lastPage,
                    searchHasMore = cached.hasMore,
                    searchLoadingMore = true,
                    loading = false,
                    error = nextDomainErrors.values.firstOrNull(),
                    domainErrors = nextDomainErrors,
                    message = null,
                )
            }
        } else {
            _state.update { ControllerStateReducer.withSearchStarted(it, query) }
        }
        searchJob = viewModelScope.launch {
            if (debounce && startPage == 0) {
                delay(T9PinyinSearchPolicy.remainingDebounceMs(SystemClock.elapsedRealtime() - changedAtMs))
            }
            var page = startPage
            while (page <= targetPage && searchRequestGate.isCurrent(request)) {
                when (val result = searchSongs(query, page = page)) {
                    is KtvApiResult.Success -> {
                        if (!searchRequestGate.isCurrent(request)) return@launch
                        var updatedState: ControllerUiState? = null
                        _state.update {
                            ControllerStateReducer.withSearchResults(it, query, result.value, page = page)
                                .also { next -> updatedState = next }
                        }
                        updatedState?.let { next ->
                            searchCache.putSongs(
                                query = query,
                                catalogRevision = next.catalogStatusRevision,
                                songs = next.results,
                                lastPage = next.searchPage,
                                hasMore = next.searchHasMore,
                            )
                        }
                        if (!_state.value.searchHasMore) break
                        page++
                    }
                    is KtvApiResult.Failure -> {
                        if (searchRequestGate.isCurrent(request)) {
                            _state.update { ControllerStateReducer.withSearchFailure(it, result.error) }
                        }
                        return@launch
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
        val request = searchRequestGate.begin(query)
        val page = current.searchPage + 1
        searchMoreJob?.cancel()
        _state.update { ControllerStateReducer.withSearchLoadStarted(it) }
        searchMoreJob = viewModelScope.launch {
            when (val result = searchSongs(query, page = page)) {
                is KtvApiResult.Success -> {
                    if (searchRequestGate.isCurrent(request)) {
                        var updatedState: ControllerUiState? = null
                        _state.update {
                            ControllerStateReducer.withSearchResults(
                                it,
                                query,
                                result.value,
                                page = page,
                            ).also { next -> updatedState = next }
                        }
                        updatedState?.let { next ->
                            searchCache.putSongs(
                                query = query,
                                catalogRevision = next.catalogStatusRevision,
                                songs = next.results,
                                lastPage = next.searchPage,
                                hasMore = next.searchHasMore,
                            )
                        }
                    }
                }
                is KtvApiResult.Failure -> {
                    if (searchRequestGate.isCurrent(request)) {
                        _state.update { ControllerStateReducer.withSearchFailure(it, result.error) }
                    }
                }
            }
        }
    }

    private suspend fun searchSongs(query: String, page: Int): KtvApiResult<List<SongDto>> =
        if (T9PinyinSearchPolicy.isAcronymQuery(query)) {
            songApi.searchAcronym(query, page = page)
        } else {
            songApi.search(query, page = page)
        }

    override fun refreshQueue() {
        _state.update { ControllerStateReducer.withQueueLoadStarted(it) }
        queueRequests.launch {
            when (val result = queueApi.snapshot()) {
                is KtvApiResult.Success -> _state.update {
                    if (bridgeConnected == false) {
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
        catalogRoot = CatalogRoot.RANKING
        catalogQuery = CatalogQuery(CatalogKind.NONE)
        resetCatalogView()
        catalogRequests.launch {
            when (val result = songApi.ranking()) {
                is KtvApiResult.Success -> _state.update {
                    ControllerStateReducer.withSuccessfulRead(it, UiDomain.CATALOG).copy(
                        ranking = result.value.take(MAX_CATALOG_ITEMS),
                        catalogLoading = false,
                        catalogDetail = false,
                    )
                }
                is KtvApiResult.Failure -> _state.update { ControllerStateReducer.withDomainFailure(it, UiDomain.CATALOG, result.error) }
            }
        }
    }

    override fun loadNewSongs() {
        setCatalogVisible(true)
        catalogRoot = CatalogRoot.NEW_SONGS
        catalogQuery = CatalogQuery(CatalogKind.NONE)
        resetCatalogView()
        catalogRequests.launch {
            when (val result = songApi.newSongs()) {
                is KtvApiResult.Success -> _state.update {
                    ControllerStateReducer.withSuccessfulRead(it, UiDomain.CATALOG).copy(
                        newSongs = result.value.take(MAX_CATALOG_ITEMS),
                        catalogLoading = false,
                        catalogDetail = false,
                    )
                }
                is KtvApiResult.Failure -> _state.update { ControllerStateReducer.withDomainFailure(it, UiDomain.CATALOG, result.error) }
            }
        }
    }

    override fun loadArtists(gender: String, initial: String, restorePage: Int, region: String) {
        setCatalogVisible(true)
        catalogRoot = CatalogRoot.NONE
        val safeGender = gender.trim().take(MAX_FILTER_LENGTH)
        val safeInitial = initial.trim().take(MAX_FILTER_LENGTH)
        val safeRegion = region.trim().take(MAX_FILTER_LENGTH)
        catalogQuery = CatalogQuery(CatalogKind.ARTISTS, safeGender, safeInitial, safeRegion)
        _state.update {
            it.copy(
                artistGender = safeGender,
                artistInitial = safeInitial,
                artistRegion = safeRegion,
                artistInitials = emptyList(),
                artists = emptyList(),
                catalogSongs = emptyList(),
                catalogPage = 0,
                catalogTotal = 0,
                catalogHasMore = false,
                catalogLoadingMore = false,
                catalogLoading = true,
                catalogDetail = false,
                catalogValue = "",
                error = null,
                message = null,
            )
        }
        val expected = catalogQuery
        catalogRequests.launch {
            if (safeRegion.isBlank()) {
                launch {
                    loadCatalogPages(
                        restorePage,
                        expected,
                        loadPage = { page -> loadArtistsPage(page, expected) },
                    )
                }
                launch { loadArtistInitials(safeGender, safeRegion, expected) }
            } else {
                val regionFilterAccepted = loadArtistsPage(0, expected)
                if (!regionFilterAccepted || catalogQuery != expected) return@launch
                launch { loadArtistInitials(safeGender, safeRegion, expected) }
                loadCatalogPages(
                    restorePage,
                    expected,
                    loadPage = { page -> loadArtistsPage(page, expected) },
                    firstPageAlreadyLoaded = true,
                )
            }
        }
    }

    fun loadArtists() = loadArtists(gender = "", initial = "", restorePage = 0)

    override fun searchArtists(gender: String, initial: String, region: String, keyword: String) {
        executeArtistsSearch(gender, initial, region, keyword, restorePage = 0, debounce = true)
    }

    override fun restoreArtistsSearch(
        gender: String,
        initial: String,
        region: String,
        keyword: String,
        restorePage: Int,
    ) {
        executeArtistsSearch(gender, initial, region, keyword, restorePage = restorePage, debounce = false)
    }

    private fun executeArtistsSearch(
        gender: String,
        initial: String,
        region: String,
        keyword: String,
        restorePage: Int,
        debounce: Boolean,
    ) {
        setCatalogVisible(true)
        catalogRoot = CatalogRoot.NONE
        val changedAtMs = SystemClock.elapsedRealtime()
        val safeGender = gender.trim().take(MAX_FILTER_LENGTH)
        val safeInitial = initial.trim().take(MAX_FILTER_LENGTH)
        val safeRegion = region.trim().take(MAX_FILTER_LENGTH)
        val safeKeyword = keyword.trim().take(MAX_QUERY_LENGTH)
        catalogQuery = CatalogQuery(CatalogKind.ARTISTS, safeGender, safeInitial, safeRegion, safeKeyword)
        val expected = catalogQuery
        val targetPage = restorePage.coerceIn(0, MAX_RESTORE_PAGES)
        val revision = _state.value.catalogStatusRevision
        val cached = searchCache.getArtists(safeGender, safeInitial, safeRegion, safeKeyword, revision)
        if (cached != null && (cached.lastPage >= targetPage || !cached.hasMore)) {
            catalogRequests.cancel()
            _state.update {
                ControllerStateReducer.withSuccessfulRead(it, UiDomain.CATALOG).copy(
                    artistGender = safeGender,
                    artistInitial = safeInitial,
                    artistRegion = safeRegion,
                    artists = cached.artists,
                    catalogSongs = emptyList(),
                    catalogPage = cached.lastPage,
                    catalogTotal = cached.total,
                    catalogHasMore = cached.hasMore,
                    catalogLoadingMore = false,
                    catalogLoading = false,
                    catalogDetail = false,
                    catalogValue = "",
                    message = null,
                )
            }
            if (_state.value.artistInitials.isEmpty()) {
                catalogRequests.launch { loadArtistInitials(safeGender, safeRegion, expected) }
            }
            return
        }

        val preserveArtists = cached == null && _state.value.let {
            !it.catalogDetail &&
                it.artists.isNotEmpty() &&
                it.artistGender == safeGender &&
                it.artistInitial == safeInitial &&
                it.artistRegion == safeRegion
        }
        _state.update {
            it.copy(
                artistGender = safeGender,
                artistInitial = safeInitial,
                artistRegion = safeRegion,
                artists = cached?.artists ?: if (preserveArtists) it.artists else emptyList(),
                catalogSongs = emptyList(),
                catalogPage = cached?.lastPage ?: 0,
                catalogTotal = cached?.total ?: 0,
                catalogHasMore = cached?.hasMore ?: false,
                catalogLoadingMore = cached != null,
                catalogLoading = cached == null,
                catalogDetail = false,
                catalogValue = "",
                error = null,
                message = null,
            )
        }
        catalogRequests.launch {
            if (debounce && cached == null) {
                delay(T9PinyinSearchPolicy.remainingDebounceMs(SystemClock.elapsedRealtime() - changedAtMs))
            }
            if (_state.value.artistInitials.isEmpty()) {
                launch { loadArtistInitials(safeGender, safeRegion, expected) }
            }
            if (cached != null) {
                var page = cached.lastPage
                while (
                    page < targetPage &&
                    catalogQuery == expected &&
                    _state.value.catalogHasMore &&
                    _state.value.errorFor(UiDomain.CATALOG) == null
                ) {
                    page++
                    loadArtistsPage(page, expected)
                }
            } else {
                loadCatalogPages(
                    restorePage,
                    expected,
                    loadPage = { page -> loadArtistsPage(page, expected) },
                )
            }
        }
    }

    override fun searchArtistSongs(artistKey: String, keyword: String) {
        executeArtistSongsSearch(artistKey, keyword, restorePage = 0, debounce = true)
    }

    override fun restoreArtistSongsSearch(artistKey: String, keyword: String, restorePage: Int) {
        executeArtistSongsSearch(artistKey, keyword, restorePage = restorePage, debounce = false)
    }

    private fun executeArtistSongsSearch(
        artistKey: String,
        keyword: String,
        restorePage: Int,
        debounce: Boolean,
    ) {
        setCatalogVisible(true)
        catalogRoot = CatalogRoot.NONE
        val changedAtMs = SystemClock.elapsedRealtime()
        val safeArtistKey = artistKey.trim().take(MAX_FILTER_LENGTH)
        val safeKeyword = keyword.trim().take(MAX_QUERY_LENGTH)
        catalogQuery = CatalogQuery(CatalogKind.ARTIST_SONGS, safeArtistKey, keyword = safeKeyword)
        val expected = catalogQuery
        val targetPage = restorePage.coerceIn(0, MAX_RESTORE_PAGES)
        val revision = _state.value.catalogStatusRevision
        val cached = searchCache.getArtistSongs(safeArtistKey, safeKeyword, revision)
        if (cached != null && (cached.lastPage >= targetPage || !cached.hasMore)) {
            catalogRequests.cancel()
            _state.update {
                ControllerStateReducer.withSuccessfulRead(it, UiDomain.CATALOG).copy(
                    artists = emptyList(),
                    catalogSongs = cached.songs,
                    catalogPage = cached.lastPage,
                    catalogTotal = cached.total,
                    catalogHasMore = cached.hasMore,
                    catalogLoadingMore = false,
                    catalogLoading = false,
                    catalogDetail = true,
                    catalogValue = safeArtistKey,
                    message = null,
                )
            }
            return
        }

        val preserveSongs = cached == null && _state.value.let {
            it.catalogDetail && it.catalogValue == safeArtistKey && it.catalogSongs.isNotEmpty()
        }
        _state.update {
            it.copy(
                artists = emptyList(),
                catalogSongs = cached?.songs ?: if (preserveSongs) it.catalogSongs else emptyList(),
                catalogPage = cached?.lastPage ?: 0,
                catalogTotal = cached?.total ?: 0,
                catalogHasMore = cached?.hasMore ?: false,
                catalogLoadingMore = cached != null,
                catalogLoading = cached == null,
                catalogDetail = true,
                catalogValue = safeArtistKey,
                error = null,
                message = null,
            )
        }
        catalogRequests.launch {
            if (debounce && cached == null) {
                delay(T9PinyinSearchPolicy.remainingDebounceMs(SystemClock.elapsedRealtime() - changedAtMs))
            }
            if (cached != null) {
                var page = cached.lastPage
                while (
                    page < targetPage &&
                    catalogQuery == expected &&
                    _state.value.catalogHasMore &&
                    _state.value.errorFor(UiDomain.CATALOG) == null
                ) {
                    page++
                    loadSongCatalogPage(page, expected)
                }
            } else {
                loadCatalogPages(restorePage, expected, loadPage = { page -> loadSongCatalogPage(page, expected) })
            }
        }
    }

    override fun loadArtistSongs(artistKey: String, restorePage: Int) {
        setCatalogVisible(true)
        catalogRoot = CatalogRoot.NONE
        val safeArtistKey = artistKey.trim().take(MAX_FILTER_LENGTH)
        catalogQuery = CatalogQuery(CatalogKind.ARTIST_SONGS, safeArtistKey)
        _state.update {
            it.copy(
                artists = emptyList(),
                catalogSongs = emptyList(),
                catalogPage = 0,
                catalogTotal = 0,
                catalogHasMore = false,
                catalogLoadingMore = false,
                catalogLoading = true,
                catalogDetail = true,
                catalogValue = safeArtistKey,
                error = null,
                message = null,
            )
        }
        val expected = catalogQuery
        catalogRequests.launch {
            loadCatalogPages(restorePage, expected, loadPage = { page -> loadSongCatalogPage(page, expected) })
        }
    }

    fun loadArtistSongs(artistKey: String) = loadArtistSongs(artistKey, restorePage = 0)

    override fun loadLanguageSongs(language: String, restorePage: Int) {
        setCatalogVisible(true)
        catalogRoot = CatalogRoot.NONE
        val safeLanguage = language.trim().take(MAX_FILTER_LENGTH)
        catalogQuery = CatalogQuery(CatalogKind.LANGUAGE_SONGS, safeLanguage)
        _state.update {
            it.copy(
                catalogSongs = emptyList(),
                catalogPage = 0,
                catalogTotal = 0,
                catalogHasMore = false,
                catalogLoadingMore = false,
                catalogLoading = true,
                catalogDetail = true,
                catalogValue = safeLanguage,
                error = null,
                message = null,
            )
        }
        val expected = catalogQuery
        catalogRequests.launch {
            loadCatalogPages(restorePage, expected, loadPage = { page -> loadSongCatalogPage(page, expected) })
        }
    }

    fun loadLanguageSongs(language: String) = loadLanguageSongs(language, restorePage = 0)

    override fun loadTagSongs(tag: String, restorePage: Int) {
        setCatalogVisible(true)
        catalogRoot = CatalogRoot.NONE
        val safeTag = tag.trim().take(MAX_FILTER_LENGTH)
        catalogQuery = CatalogQuery(CatalogKind.TAG_SONGS, safeTag)
        _state.update {
            it.copy(
                catalogSongs = emptyList(),
                catalogPage = 0,
                catalogTotal = 0,
                catalogHasMore = false,
                catalogLoadingMore = false,
                catalogLoading = true,
                catalogDetail = true,
                catalogValue = safeTag,
                error = null,
                message = null,
            )
        }
        val expected = catalogQuery
        catalogRequests.launch {
            loadCatalogPages(restorePage, expected, loadPage = { page -> loadSongCatalogPage(page, expected) })
        }
    }

    fun loadTagSongs(tag: String) = loadTagSongs(tag, restorePage = 0)

    private suspend fun loadArtistInitials(gender: String, region: String, expected: CatalogQuery) {
            when (val result = songApi.artistInitials(gender, region)) {
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
                        _state.update { state ->
                            ControllerStateReducer.withDomainFailure(state, UiDomain.CATALOG, result.error)
                                .copy(catalogLoading = state.catalogLoading)
                        }
                    }
                }
            }
    }

    private fun resetCatalogView(tagRootRequest: Boolean = false) {
        _state.update {
            it.copy(
                artists = emptyList(),
                catalogSongs = emptyList(),
                ranking = emptyList(),
                newSongs = emptyList(),
                languages = emptyList(),
                tags = emptyList(),
                catalogPage = 0,
                catalogTotal = 0,
                catalogHasMore = false,
                catalogLoadingMore = false,
                catalogLoading = true,
                catalogDetail = false,
                catalogValue = "",
                tagRootRequestRevision = if (tagRootRequest) it.tagRootRequestRevision + 1 else it.tagRootRequestRevision,
                error = null,
                message = null,
            )
        }
    }

    private fun reloadRankingCatalog() {
        resetCatalogView()
        catalogRequests.launch {
            when (val result = songApi.ranking()) {
                is KtvApiResult.Success -> _state.update {
                    ControllerStateReducer.withSuccessfulRead(it, UiDomain.CATALOG).copy(
                        ranking = result.value.take(MAX_CATALOG_ITEMS),
                        catalogLoading = false,
                        catalogDetail = false,
                    )
                }
                is KtvApiResult.Failure -> _state.update {
                    ControllerStateReducer.withDomainFailure(it, UiDomain.CATALOG, result.error)
                }
            }
        }
    }

    private fun reloadNewSongsCatalog() {
        resetCatalogView()
        catalogRequests.launch {
            when (val result = songApi.newSongs()) {
                is KtvApiResult.Success -> _state.update {
                    ControllerStateReducer.withSuccessfulRead(it, UiDomain.CATALOG).copy(
                        newSongs = result.value.take(MAX_CATALOG_ITEMS),
                        catalogLoading = false,
                        catalogDetail = false,
                    )
                }
                is KtvApiResult.Failure -> _state.update {
                    ControllerStateReducer.withDomainFailure(it, UiDomain.CATALOG, result.error)
                }
            }
        }
    }

    private fun reloadLanguagesCatalog() {
        resetCatalogView()
        catalogRequests.launch {
            when (val result = songApi.languages()) {
                is KtvApiResult.Success -> _state.update {
                    ControllerStateReducer.withCatalogRootSuccess(it).copy(
                        languages = result.value.take(MAX_CATALOG_ITEMS),
                        catalogLoading = false,
                        catalogDetail = false,
                    )
                }
                is KtvApiResult.Failure -> _state.update {
                    ControllerStateReducer.withDomainFailure(it, UiDomain.CATALOG, result.error)
                }
            }
        }
    }

    private fun reloadTagsCatalog() {
        resetCatalogView(tagRootRequest = true)
        catalogRequests.launch {
            when (val result = songApi.tags()) {
                is KtvApiResult.Success -> _state.update {
                    ControllerStateReducer.withCatalogRootSuccess(it).copy(
                        tags = result.value.take(MAX_CATALOG_ITEMS),
                        catalogLoading = false,
                        catalogDetail = false,
                    )
                }
                is KtvApiResult.Failure -> _state.update {
                    ControllerStateReducer.withDomainFailure(it, UiDomain.CATALOG, result.error)
                }
            }
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

    /** Loads the full Home KTV library on a TV-only state/request path. */
    override fun loadKioskAllSongs() {
        restoreKioskAllSongs(restorePage = 0)
    }

    override fun restoreKioskAllSongs(restorePage: Int) {
        setCatalogVisible(true)
        kioskLibraryActive = true
        reloadKioskAllSongs(restorePage)
    }

    private fun reloadKioskAllSongs(restorePage: Int = 0) {
        val requestId = ++kioskLibraryRequestId
        val targetPage = restorePage.coerceIn(0, MAX_RESTORE_PAGES)
        _state.update {
            it.copy(kioskLibraryBrowse = KioskLibraryBrowseReducer.started(requestId))
        }
        kioskLibraryRequests.launch {
            var page = 0
            while (page <= targetPage && kioskLibraryActive && kioskLibraryRequestId == requestId) {
                loadKioskAllSongsPage(page = page, requestId = requestId)
                val browse = _state.value.kioskLibraryBrowse
                if (browse.requestId != requestId || browse.error != null || !browse.hasMore) break
                page++
            }
        }
    }

    override fun loadMoreKioskAllSongs() {
        val current = _state.value.kioskLibraryBrowse
        if (!kioskLibraryActive || current.loading || current.loadingMore || !current.hasMore) return
        val requestId = current.requestId
        val page = current.page + 1
        _state.update {
            it.copy(kioskLibraryBrowse = KioskLibraryBrowseReducer.pageStarted(it.kioskLibraryBrowse))
        }
        kioskLibraryRequests.launch { loadKioskAllSongsPage(page = page, requestId = requestId) }
    }

    override fun stopKioskAllSongs() {
        kioskLibraryActive = false
        val stoppedRequestId = ++kioskLibraryRequestId
        kioskLibraryRequests.cancel()
        _state.update { state ->
            val browse = state.kioskLibraryBrowse
            state.copy(
                kioskLibraryBrowse = KioskLibraryBrowseReducer.stopped(browse, stoppedRequestId),
            )
        }
    }

    private suspend fun loadKioskAllSongsPage(page: Int, requestId: Long) {
        when (val result = songApi.browseSongPage(page = page, size = SONG_CATALOG_PAGE_SIZE)) {
            is KtvApiResult.Success -> _state.update { state ->
                state.copy(
                    kioskLibraryBrowse = KioskLibraryBrowseReducer.pageSucceeded(
                        state.kioskLibraryBrowse,
                        requestId,
                        page,
                        result.value,
                        MAX_CATALOG_ITEMS,
                    ),
                )
            }
            is KtvApiResult.Failure -> _state.update { state ->
                state.copy(
                    kioskLibraryBrowse = KioskLibraryBrowseReducer.failed(
                        state.kioskLibraryBrowse,
                        requestId,
                        result.error,
                    ),
                )
            }
        }
    }

    override fun loadLanguages() {
        setCatalogVisible(true)
        catalogRoot = CatalogRoot.LANGUAGES
        catalogQuery = CatalogQuery(CatalogKind.NONE)
        resetCatalogView()
        catalogRequests.launch {
            when (val result = songApi.languages()) {
                is KtvApiResult.Success -> _state.update {
                    ControllerStateReducer.withCatalogRootSuccess(it).copy(
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
        catalogRoot = CatalogRoot.TAGS
        catalogQuery = CatalogQuery(CatalogKind.NONE)
        resetCatalogView(tagRootRequest = true)
        catalogRequests.launch {
            when (val result = songApi.tags()) {
                is KtvApiResult.Success -> _state.update {
                    ControllerStateReducer.withCatalogRootSuccess(it).copy(
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

    /** Populates tag chips in the background without cancelling an active tag-songs query. */
    override fun prefetchTags() {
        if (_state.value.tags.isNotEmpty()) return
        tagMetadataRequests.launch {
            when (val result = songApi.tags()) {
                is KtvApiResult.Success -> _state.update { state ->
                    if (state.tags.isNotEmpty()) state
                    else state.copy(tags = result.value.take(MAX_CATALOG_ITEMS))
                }
                is KtvApiResult.Failure -> Unit
            }
        }
    }

    private suspend fun loadArtistsPage(page: Int, expected: CatalogQuery): Boolean {
            when (val result = songApi.browseArtistsPage(
                page = page,
                size = ARTIST_GRID_PAGE_SIZE,
                gender = expected.value,
                initial = expected.secondary,
                region = expected.region,
                keyword = expected.keyword,
            )) {
                is KtvApiResult.Success -> {
                    if (catalogQuery != expected) return false
                    if (!ArtistRegionResponsePolicy.accepts(
                            expected.region, result.value.region, result.value.regionDataAvailable,
                        )
                    ) {
                        val status = ArtistRegionResponsePolicy.unavailableMessage(result.value.regionDataAvailable)
                        _state.update { state ->
                            state.copy(
                                artists = emptyList(),
                                artistInitials = emptyList(),
                                catalogPage = 0,
                                catalogTotal = 0,
                                catalogHasMore = false,
                                catalogLoadingMore = false,
                                catalogLoading = false,
                                catalogStatus = CatalogUiStatus(
                                    CatalogLoadState.PARTIAL_FAILURE,
                                    state.catalogStatus.revision,
                                    "ARTIST_REGION_FILTER_UNCONFIRMED",
                                    status,
                                ),
                            )
                        }
                        return false
                    }
                    var updatedState: ControllerUiState? = null
                    _state.update { state ->
                        val artists = (if (page == 0) result.value.items
                        else (state.artists + result.value.items).distinctBy { it.artistKey })
                            .take(MAX_CATALOG_ITEMS)
                        ControllerStateReducer.withSuccessfulRead(state, UiDomain.CATALOG).copy(
                            artists = artists,
                            catalogSongs = emptyList(),
                            catalogDetail = false,
                            catalogPage = page,
                            catalogTotal = result.value.total.coerceAtLeast(0L),
                            catalogHasMore = hasMoreCatalogPage(
                                page = page,
                                pageSize = result.value.size,
                                pageItemCount = result.value.items.size,
                                total = result.value.total,
                                loaded = artists.size,
                            ),
                            catalogLoadingMore = false,
                            catalogLoading = false,
                        ).also { updatedState = it }
                    }
                    updatedState?.let { next ->
                        searchCache.putArtists(
                            gender = expected.value,
                            initial = expected.secondary,
                            region = expected.region,
                            keyword = expected.keyword,
                            catalogRevision = next.catalogStatusRevision,
                            artists = next.artists,
                            lastPage = next.catalogPage,
                            total = next.catalogTotal,
                            hasMore = next.catalogHasMore,
                        )
                    }
                    return true
                }
                is KtvApiResult.Failure -> {
                    if (catalogQuery == expected) {
                        _state.update { state ->
                            val wasInitialLoading = state.catalogLoading && !state.catalogLoadingMore
                            ControllerStateReducer.withDomainFailure(state, UiDomain.CATALOG, result.error)
                                .let { failed -> if (wasInitialLoading) failed.copy(artists = emptyList()) else failed }
                        }
                    }
                    return false
                }
            }
    }

    private suspend fun loadSongCatalogPage(page: Int, expected: CatalogQuery) {
            val result = when (expected.kind) {
                CatalogKind.ARTIST_SONGS -> songApi.browseSongPage(
                    page = page,
                    size = SONG_CATALOG_PAGE_SIZE,
                    artistKey = expected.value,
                    keyword = expected.keyword,
                )
                CatalogKind.LANGUAGE_SONGS -> songApi.browseSongPage(page = page, size = SONG_CATALOG_PAGE_SIZE, language = expected.value)
                CatalogKind.TAG_SONGS -> songApi.browseSongPage(page = page, size = SONG_CATALOG_PAGE_SIZE, tag = expected.value)
                CatalogKind.ARTISTS, CatalogKind.NONE -> return
            }
            when (result) {
                is KtvApiResult.Success -> {
                    if (catalogQuery != expected) return
                    var updatedState: ControllerUiState? = null
                    _state.update { state ->
                        val songs = (if (page == 0) result.value.items
                        else (state.catalogSongs + result.value.items).distinctBy { it.id })
                            .take(MAX_CATALOG_ITEMS)
                        ControllerStateReducer.withSuccessfulRead(state, UiDomain.CATALOG).copy(
                            artists = if (page == 0) emptyList() else state.artists,
                            catalogSongs = songs,
                            catalogPage = page,
                            catalogTotal = result.value.total.coerceAtLeast(0L),
                            catalogHasMore = hasMoreCatalogPage(
                                page = page,
                                pageSize = result.value.size,
                                pageItemCount = result.value.items.size,
                                total = result.value.total,
                                loaded = songs.size,
                            ),
                            catalogLoadingMore = false,
                            catalogLoading = false,
                            catalogDetail = true,
                        ).also { updatedState = it }
                    }
                    if (expected.kind == CatalogKind.ARTIST_SONGS) {
                        updatedState?.let { next ->
                            searchCache.putArtistSongs(
                                artistKey = expected.value,
                                keyword = expected.keyword,
                                catalogRevision = next.catalogStatusRevision,
                                songs = next.catalogSongs,
                                lastPage = next.catalogPage,
                                total = next.catalogTotal,
                                hasMore = next.catalogHasMore,
                            )
                        }
                    }
                }
                is KtvApiResult.Failure -> {
                    if (catalogQuery == expected) {
                        _state.update { state ->
                            val wasInitialLoading = state.catalogLoading && !state.catalogLoadingMore
                            ControllerStateReducer.withDomainFailure(state, UiDomain.CATALOG, result.error)
                                .let { failed -> if (wasInitialLoading) failed.copy(catalogSongs = emptyList()) else failed }
                        }
                    }
                }
            }
    }

    /** Replays bounded pages during process restoration without issuing writes. */
    private suspend fun loadCatalogPages(
        targetPage: Int,
        expected: CatalogQuery,
        loadPage: suspend (Int) -> Unit,
        firstPageAlreadyLoaded: Boolean = false,
    ) {
        val target = targetPage.coerceIn(0, MAX_RESTORE_PAGES)
        if (!firstPageAlreadyLoaded) loadPage(0)
        var page = 0
        while (
            page < target &&
            catalogQuery == expected &&
            _state.value.catalogHasMore &&
            _state.value.errorFor(UiDomain.CATALOG) == null
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
        _state.update { ControllerStateReducer.withPersonalLoadStarted(it, UiDomain.FAVORITES) }
        personalRequests.launch {
            when (val result = favoriteApi.list()) {
                is KtvApiResult.Success -> _state.update {
                    val favorites = result.value.take(MAX_PERSONAL_ITEMS)
                    ControllerStateReducer.withPersonalLoadFinished(
                        ControllerStateReducer.withSuccessfulRead(it, UiDomain.FAVORITES).copy(
                        favorites = favorites,
                        favoriteIds = favorites.mapTo(linkedSetOf()) { song -> song.id },
                        ),
                        UiDomain.FAVORITES,
                    )
                }
                is KtvApiResult.Failure -> _state.update { ControllerStateReducer.withDomainFailure(it, UiDomain.FAVORITES, result.error) }
            }
        }
    }

    override fun toggleFavorite(song: SongDto) {
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
        _state.update { ControllerStateReducer.withPersonalLoadCancelled(it) }
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
            val personalLoadsCleared = ControllerStateReducer.withPersonalLoadCancelled(it)
            personalLoadsCleared.copy(
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
        _state.update {
            ControllerStateReducer.withPersonalLoadCancelled(it).copy(
                playlistDetail = null,
                playlistDetailLoading = false,
                playlistCoverBytes = null,
            )
        }
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
        _state.update { ControllerStateReducer.withPersonalLoadStarted(it, UiDomain.HISTORY) }
        personalRequests.launch {
            when (val result = historyApi.list(mine)) {
                is KtvApiResult.Success -> _state.update {
                    ControllerStateReducer.withPersonalLoadFinished(
                        ControllerStateReducer.withSuccessfulRead(it, UiDomain.HISTORY).copy(
                            history = result.value.take(MAX_PERSONAL_ITEMS),
                        ),
                        UiDomain.HISTORY,
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
        tagMetadataRequests.cancel()
        kioskLibraryRequests.cancel()
        personalRequests.cancel()
        roomHostRequests.cancel()
        coverRequests.cancel()
        transport.close()
        super.onCleared()
    }

    companion object {
        private const val MAX_QUERY_LENGTH = 128
        private const val MAX_WISH_LENGTH = 100
        private const val MAX_FILTER_LENGTH = 32
        private const val MAX_CATALOG_ITEMS = 2000
        private const val MAX_RESTORE_PAGES = 64
        private const val MAX_PERSONAL_ITEMS = 2000
        private const val MAX_ARTIST_INITIALS = 128
        private const val ARTIST_GRID_PAGE_SIZE = 32
        private const val SONG_CATALOG_PAGE_SIZE = 15
        private const val HOT_INITIAL = "热门"
    }
}

class ControllerViewModelFactory(
    private val application: Application,
) : androidx.lifecycle.ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(ControllerViewModel::class.java))
        return ControllerViewModel(application) as T
    }
}

private enum class CatalogRoot {
    NONE,
    RANKING,
    NEW_SONGS,
    LANGUAGES,
    TAGS,
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
    val region: String = "",
    val keyword: String = "",
)
