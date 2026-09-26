package com.homektv.tv.ui

import android.graphics.Bitmap
import android.view.KeyEvent
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.Player
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.homektv.tv.controller.ControllerActions
import com.homektv.tv.controller.ControllerCatalogActions
import com.homektv.tv.controller.ControllerCatalogVisibility
import com.homektv.tv.controller.ControllerConnection
import com.homektv.tv.controller.ControllerPersonalActions
import com.homektv.tv.controller.ControllerUiState
import com.homektv.tv.controller.KioskCatalogActionRouter
import com.homektv.tv.controller.KioskPersonalActionRouter
import com.homektv.tv.controller.KioskPersonalPresentationPolicy
import com.homektv.tv.controller.KioskPersonalView
import com.homektv.tv.controller.KioskQueueActionRouter
import com.homektv.tv.R
import com.homektv.tv.databinding.ActivityMainBinding
import com.homektv.tv.databinding.ViewKtvKioskOverlayBinding
import com.homektv.tv.net.AppConfig
import com.homektv.tv.net.ArtistItem
import com.homektv.tv.net.KtvApiResult
import com.homektv.tv.net.KtvHttpTransport
import com.homektv.tv.net.QueueSnapshot
import com.homektv.tv.net.SongDto
import com.homektv.tv.player.EffectPlayer
import com.homektv.tv.ui.kiosk.KtvSingerFilterPolicy
import com.homektv.tv.ui.kiosk.KtvDashboardBackground
import com.homektv.tv.ui.kiosk.KioskCategoryLoadPolicy
import com.homektv.tv.ui.kiosk.KioskCategoryMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt


/**
 * 电视端商用 KTV 点歌台业务控制器。
 *
 * Coordinates kiosk ordering overlay, PiP reparenting, DPAD navigation,
 * and bottom control bar integration.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class KtvKioskOverlayController(
    private val activity: AppCompatActivity,
    private val binding: ActivityMainBinding,
    private val kioskOverlayBinding: ViewKtvKioskOverlayBinding,
    private val coordinator: KioskModeCoordinator,
    private val focusController: KtvFocusController,
    private val effectPlayer: EffectPlayer?,
    private val controllerActions: ControllerActions,
    private val catalogActions: ControllerCatalogActions,
    private val personalActions: ControllerPersonalActions,
    private val onTogglePlayback: () -> Unit,
    private val onNext: () -> Unit,
    private val onRestart: () -> Unit,
    private val onToggleVocal: () -> Unit,
    initialExternalDisplayActive: Boolean = false,
) {

    private val ActivityMainBinding.kioskOverlay: ViewKtvKioskOverlayBinding
        get() = this@KtvKioskOverlayController.kioskOverlayBinding

    private val config = AppConfig(activity)
    private val transport = KtvHttpTransport(config)
    private val catalogActionRouter = KioskCatalogActionRouter(catalogActions)
    private val personalActionRouter = KioskPersonalActionRouter(personalActions)
    private val queueActionRouter = KioskQueueActionRouter(controllerActions)
    private val pipObservedPlayer = binding.playerView.player
    private var pipSpaceAvailable = false
    private val pipPlaybackListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            renderPipState()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            renderPipState()
        }
    }
    private val pipLayoutChangeListener = View.OnLayoutChangeListener {
            _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom,
        ->
        if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
            updatePipResponsiveSize()
        }
    }

    val presentationState = KioskPresentationState(initialTab = KioskTab.DASHBOARD)
    private val dashboardPolicy = com.homektv.tv.ui.kiosk.KtvDashboardPolicy()
    private val backExitGate = com.homektv.tv.navigation.BackExitGate()

    private val searchAdapter = KtvKioskSongAdapter(
        onOrder = ::orderSong,
        onOrderTop = ::orderSongTop,
    )
    private val rankingAdapter = KtvKioskSongAdapter(
        onOrder = ::orderSong,
        onOrderTop = ::orderSongTop,
    )
    private val categoryAdapter = KtvKioskSongAdapter(
        onOrder = ::orderSong,
        onOrderTop = ::orderSongTop,
    )
    private val categoryNameAdapter = KtvKioskNamedCountAdapter(::onCategorySelected)
    private val personalSongAdapter = KtvKioskSongAdapter(
        onOrder = ::orderSong,
        onOrderTop = ::orderSongTop,
    )
    private val playlistAdapter = KtvKioskPlaylistAdapter(
        onOpen = ::openPlaylist,
        onOrder = { playlist -> personalActionRouter.orderPlaylist(playlist.id) },
    )
    private val singerAvatarCache = WeightedLruCache<String, android.graphics.Bitmap>(
        capacity = 128,
        maxBytes = 16L * 1024L * 1024L,
        weight = { it.allocationByteCount.toLong() },
    )
    private val pendingAvatarCallbacks = mutableMapOf<String, MutableList<(android.graphics.Bitmap?) -> Unit>>()

    private val singerAdapter = SingerCardAdapter(
        onArtistClick = ::onArtistSelected,
        imageLoader = { url, callback ->
            val key = url
            val cached = singerAvatarCache.get(key)
            if (cached != null) {
                callback(cached)
            } else {
                val subscribers = pendingAvatarCallbacks.getOrPut(key) { mutableListOf() }
                subscribers.add(callback)
                if (singerAvatarCache.tryStartLoad(key)) {
                    activity.lifecycleScope.launch {
                        val bytes = withContext(Dispatchers.IO) {
                            val bytesRes = transport.getBytes(url)
                            (bytesRes as? KtvApiResult.Success)?.value
                        }
                        val bitmap = bytes?.let {
                            withContext(Dispatchers.Default) {
                                ArtworkDecoder.decode(it, ArtworkProfile.AVATAR)
                            }
                        }
                        val waiting = pendingAvatarCallbacks.remove(key) ?: emptyList()
                        if (bitmap != null) {
                            singerAvatarCache.complete(key, bitmap)
                            waiting.forEach { it(bitmap) }
                        } else {
                            singerAvatarCache.fail(key)
                            waiting.forEach { it(null) }
                        }
                    }
                }
            }
        },
    )

    private var currentSnapshot: QueueSnapshot? = null
    private var controllerState = ControllerUiState()
    private var lastControllerFeedback: String? = null
    private var queueDialog: KtvQueueDrawerDialog? = null
    private var qrDialog: KtvQrDialog? = null
    private var cachedQrBitmap: Bitmap? = null
    private var qrLoadJob: Job? = null
    private var artistsLoaded = false
    private var rankingsLoaded = false
    private val categoryLoadPolicy = KioskCategoryLoadPolicy()
    private var categoryMode = KioskCategoryMode.TAGS
    private var categoryDetail = false
    private var categoryFocusRestorePending = false
    private var playlistDetail = false
    private var externalDisplayActive = initialExternalDisplayActive

    init {
        KtvDashboardBackground.applyTo(kioskOverlayBinding.kioskRootOverlay)
        pipObservedPlayer?.addListener(pipPlaybackListener)
        setupViews()
        observeState()
    }

    private fun setupViews() {
        val overlay = binding.kioskOverlay
        overlay.kioskContentStage.addOnLayoutChangeListener(pipLayoutChangeListener)

        // 1. 列表布局绑定
        overlay.searchRecyclerView.layoutManager = LinearLayoutManager(activity)
        overlay.searchRecyclerView.adapter = searchAdapter

        overlay.singerRecyclerView.layoutManager = GridLayoutManager(activity, 4)
        overlay.singerRecyclerView.adapter = singerAdapter

        val singerFilters = KtvSingerFilterPolicy.filters
        overlay.btnSingerAll.setOnClickListener { loadSingerGender(singerFilters[0].gender) }
        overlay.btnSingerMale.setOnClickListener { loadSingerGender(singerFilters[1].gender) }
        overlay.btnSingerFemale.setOnClickListener { loadSingerGender(singerFilters[2].gender) }
        overlay.btnSingerGroup.setOnClickListener { loadSingerGender(singerFilters[3].gender) }
        overlay.btnSingerRetry.setOnClickListener {
            coordinator.resetIdleTimer()
            focusController.hasInnerDetailBack = false
            presentationState.clearArtistSelection()
            catalogActions.loadArtists(
                gender = controllerState.artistGender,
                initial = controllerState.artistInitial,
                restorePage = controllerState.catalogPage,
            )
        }

        overlay.rankingRecyclerView.layoutManager = LinearLayoutManager(activity)
        overlay.rankingRecyclerView.adapter = rankingAdapter

        val categoryGridLayoutManager = GridLayoutManager(activity, 4)
        categoryGridLayoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int =
                if (overlay.categoryRecyclerView.adapter === categoryNameAdapter) 1
                else categoryGridLayoutManager.spanCount
        }
        overlay.categoryRecyclerView.layoutManager = categoryGridLayoutManager
        overlay.categoryRecyclerView.adapter = categoryAdapter
        overlay.btnCategoryRetry.setOnClickListener {
            coordinator.resetIdleTimer()
            when (categoryMode) {
                KioskCategoryMode.LANGUAGES -> {
                    catalogActionRouter.languages()
                    overlay.btnCategoryLanguages.requestFocus()
                }
                KioskCategoryMode.TAGS -> {
                    catalogActionRouter.tags()
                    overlay.btnCategoryTags.requestFocus()
                }
                KioskCategoryMode.NEW -> loadCategories()
            }
        }
        overlay.btnPersonalRetry.setOnClickListener {
            when (presentationState.currentTab.value) {
                KioskTab.FAVORITES -> {
                    loadFavorites()
                    overlay.tabFavorites.requestFocus()
                }
                KioskTab.HISTORY -> {
                    loadHistory()
                    overlay.tabHistory.requestFocus()
                }
                else -> Unit
            }
        }

        overlay.personalRecyclerView.layoutManager = LinearLayoutManager(activity)
        overlay.personalRecyclerView.adapter = personalSongAdapter

        installPaging(overlay.searchRecyclerView) {
            if (presentationState.selectedArtist.value != null) {
                catalogActions.loadMoreCatalog()
            } else {
                catalogActions.loadMoreSearch()
            }
        }
        installPaging(overlay.singerRecyclerView) {
            catalogActions.loadMoreCatalog()
        }
        installPaging(overlay.categoryRecyclerView) {
            if (categoryDetail) catalogActions.loadMoreCatalog()
        }

        // 2. 标签切换绑定
        overlay.tabDashboard.setOnClickListener {
            presentationState.selectTab(KioskTab.DASHBOARD)
        }
        overlay.kioskDashboardView.onTileClick = { tile ->
            coordinator.resetIdleTimer()
            dashboardPolicy.onTileClicked(tile)
            overlay.kioskDashboardView.lastFocusedTile = tile
            when (tile) {
                com.homektv.tv.ui.kiosk.KtvDashboardTile.PINYIN -> presentationState.selectTab(KioskTab.PINYIN)
                com.homektv.tv.ui.kiosk.KtvDashboardTile.SINGER -> presentationState.selectTab(KioskTab.SINGERS)
                com.homektv.tv.ui.kiosk.KtvDashboardTile.CATEGORY -> {
                    openCategoryRoot(KioskCategoryMode.TAGS)
                }
                com.homektv.tv.ui.kiosk.KtvDashboardTile.LANGUAGE -> openCategoryRoot(KioskCategoryMode.LANGUAGES)
                com.homektv.tv.ui.kiosk.KtvDashboardTile.RANKING -> presentationState.selectTab(KioskTab.DASHBOARD)
                com.homektv.tv.ui.kiosk.KtvDashboardTile.FAVORITES -> presentationState.selectTab(KioskTab.FAVORITES)
                com.homektv.tv.ui.kiosk.KtvDashboardTile.HISTORY -> presentationState.selectTab(KioskTab.HISTORY)
                com.homektv.tv.ui.kiosk.KtvDashboardTile.ORDERED_QUEUE -> openQueueDrawer()
            }
        }
        overlay.tabPinyin.setOnClickListener {
            if (presentationState.selectedArtist.value != null) {
                presentationState.clearArtistSelection()
                focusController.hasInnerDetailBack = false
                overlay.kioskKeyboard.clear()
                overlay.txtSearchCount.text = "输入首字母检索曲目"
                loadDefaultSongs()
            }
            presentationState.selectTab(KioskTab.PINYIN)
        }
        overlay.tabSingers.setOnClickListener { presentationState.selectTab(KioskTab.SINGERS) }
        overlay.tabRankings.setOnClickListener { presentationState.selectTab(KioskTab.RANKINGS) }
        overlay.tabCategories.setOnClickListener { openCategoryRoot(KioskCategoryMode.TAGS) }
        overlay.tabLanguages.setOnClickListener { openCategoryRoot(KioskCategoryMode.LANGUAGES) }
        overlay.tabFavorites.setOnClickListener { presentationState.selectTab(KioskTab.FAVORITES) }
        overlay.tabPlaylists.setOnClickListener { presentationState.selectTab(KioskTab.PLAYLISTS) }
        overlay.tabHistory.setOnClickListener { presentationState.selectTab(KioskTab.HISTORY) }
        overlay.btnCategoryNew.setOnClickListener {
            categoryMode = KioskCategoryMode.NEW
            categoryDetail = false
            focusController.hasInnerDetailBack = false
            overlay.categoryRecyclerView.adapter = categoryAdapter
            loadCategories()
        }
        overlay.btnCategoryLanguages.setOnClickListener {
            categoryMode = KioskCategoryMode.LANGUAGES
            categoryDetail = false
            focusController.hasInnerDetailBack = false
            categoryNameAdapter.setIconResource(R.drawable.ic_language)
            overlay.categoryRecyclerView.adapter = categoryNameAdapter
            overlay.txtCategoryHeader.text = "选择语种"
            catalogActionRouter.languages()
        }
        overlay.btnCategoryTags.setOnClickListener {
            categoryMode = KioskCategoryMode.TAGS
            categoryDetail = false
            focusController.hasInnerDetailBack = false
            categoryNameAdapter.setIconResource(R.drawable.ic_category)
            overlay.categoryRecyclerView.adapter = categoryNameAdapter
            overlay.txtCategoryHeader.text = "选择标签"
            catalogActionRouter.tags()
        }
        overlay.btnKioskExit.setOnClickListener { toggleKiosk(false) }

        // 3. 软键盘绑定
        overlay.kioskKeyboard.onKeywordChanged = { keyword ->
            coordinator.resetIdleTimer()
            focusController.hasInputText = keyword.isNotEmpty()
            onKeywordInput(keyword)
        }
        overlay.kioskKeyboard.onImeRequest = {
            coordinator.resetIdleTimer()
            coordinator.setModalActive(true)
            val inputView = android.widget.EditText(activity).apply {
                setText(overlay.kioskKeyboard.state.currentKeyword)
                setSelection(text.length)
                hint = "输入首字母或歌曲名"
            }
            androidx.appcompat.app.AlertDialog.Builder(activity)
                .setTitle("切换系统输入法")
                .setView(inputView)
                .setPositiveButton("确定") { _, _ ->
                    val text = inputView.text.toString().trim()
                    overlay.kioskKeyboard.setKeyword(text)
                }
                .setNegativeButton("取消", null)
                .setOnDismissListener {
                    coordinator.setModalActive(false)
                }
                .show()
        }

        // 4. 底部播控条绑定
        val bottomBar = overlay.kioskBottomBar
        bottomBar.onPlayPauseClick = {
            coordinator.resetIdleTimer()
            onTogglePlayback()
        }
        bottomBar.onNextClick = {
            coordinator.resetIdleTimer()
            onNext()
        }
        bottomBar.onRestartClick = {
            coordinator.resetIdleTimer()
            onRestart()
        }
        bottomBar.onVocalToggleClick = {
            coordinator.resetIdleTimer()
            onToggleVocal()
        }
        bottomBar.onEffectClick = { effect ->
            coordinator.resetIdleTimer()
            effectPlayer?.play(
                effect,
                currentSnapshot?.volume ?: 60,
                currentSnapshot?.muted ?: false,
            )
        }
        bottomBar.onQueueClick = {
            coordinator.resetIdleTimer()
            openQueueDrawer()
        }
        bottomBar.onQrCodeClick = {
            coordinator.resetIdleTimer()
            openQrDialog()
        }
    }

    private fun observeState() {
        activity.lifecycleScope.launch {
            presentationState.currentTab.collectLatest { tab ->
                applyTabSelection(tab)
            }
        }

        activity.lifecycleScope.launch {
            coordinator.isKioskActive.collectLatest { active ->
                applyKioskActive(active)
            }
        }

        activity.lifecycleScope.launch {
            controllerActions.state.collectLatest { state ->
                controllerState = state
                renderCatalogState(state)
                if (state.connection != ControllerConnection.CONNECTING) {
                    updateSnapshot(state.queue, state.queueProjection)
                }
                queueDialog?.updateActor(
                    currentUserId = state.currentUser?.id,
                    isHost = state.roomHost.isHost,
                )
                queueDialog?.updatePendingActions(state.pendingActions)

                val feedback = state.message
                val inlineFeedback = inlineFeedbackForCurrentPage(state)
                when (KioskFeedbackRoutingPolicy.resolve(
                    message = feedback,
                    inlineErrorMessage = inlineFeedback?.first,
                    inlineErrorIsLatest = inlineFeedback?.second == true,
                )) {
                    KioskFeedbackDestination.TRANSIENT -> if (feedback != lastControllerFeedback) {
                        Toast.makeText(activity, feedback, Toast.LENGTH_SHORT).show()
                    }
                    KioskFeedbackDestination.NONE,
                    KioskFeedbackDestination.INLINE -> Unit
                }
                lastControllerFeedback = feedback
            }
        }
    }

    private fun installPaging(
        recyclerView: androidx.recyclerview.widget.RecyclerView,
        loadMore: () -> Unit,
    ) {
        recyclerView.addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrolled(
                recyclerView: androidx.recyclerview.widget.RecyclerView,
                dx: Int,
                dy: Int,
            ) {
                coordinator.resetIdleTimer()
                if (dy <= 0 || recyclerView.canScrollVertically(1)) return
                loadMore()
            }
        })
    }

    private fun inlineFeedbackForCurrentPage(state: ControllerUiState): Pair<String, Boolean>? {
        val overlay = binding.kioskOverlay
        val tab = presentationState.currentTab.value
        val domain = when (tab) {
            KioskTab.CATEGORIES -> if (!categoryDetail && categoryMode != KioskCategoryMode.NEW &&
                overlay.categoryErrorPanel.visibility == View.VISIBLE
            ) com.homektv.tv.controller.UiDomain.CATALOG else null
            KioskTab.SINGERS -> if (overlay.txtSingerStatus.visibility == View.VISIBLE) {
                com.homektv.tv.controller.UiDomain.CATALOG
            } else null
            KioskTab.PINYIN -> when {
                presentationState.selectedArtist.value != null && overlay.txtSearchCount.visibility == View.VISIBLE ->
                    com.homektv.tv.controller.UiDomain.CATALOG
                state.query.isNotBlank() && overlay.txtSearchCount.visibility == View.VISIBLE ->
                    com.homektv.tv.controller.UiDomain.SEARCH
                else -> null
            }
            KioskTab.FAVORITES -> if (overlay.personalStatusPanel.visibility == View.VISIBLE) {
                com.homektv.tv.controller.UiDomain.FAVORITES
            } else null
            KioskTab.HISTORY -> if (overlay.personalStatusPanel.visibility == View.VISIBLE) {
                com.homektv.tv.controller.UiDomain.HISTORY
            } else null
            KioskTab.PLAYLISTS -> {
                com.homektv.tv.controller.UiDomain.PLAYLISTS
            }
            else -> null
        } ?: return null

        val domainError = state.errorFor(domain) ?: return null
        val message = state.messageFor(domain)?.takeIf(String::isNotBlank) ?: return null
        return message to (state.error === domainError)
    }

    private fun renderCatalogState(state: ControllerUiState) {
        val overlay = binding.kioskOverlay
        val selectedArtist = presentationState.selectedArtist.value

        singerAdapter.submitList(state.artists)
        overlay.txtSingerStatus.text = when {
            state.catalogLoading -> "正在加载歌星..."
            state.errorFor(com.homektv.tv.controller.UiDomain.CATALOG) != null ->
                "歌星加载失败：${state.messageFor(com.homektv.tv.controller.UiDomain.CATALOG) ?: "请稍后重试"}"
            state.artists.isEmpty() -> when (state.catalogStatus.state) {
                com.homektv.tv.controller.CatalogLoadState.SCANNING -> "曲库正在扫描，歌手目录暂不可用"
                com.homektv.tv.controller.CatalogLoadState.OFFLINE -> "点歌服务离线，无法加载歌手目录"
                else -> "暂无歌手"
            }
            else -> "共 ${state.artists.size} 位歌星"
        }
        val singerFailure = state.errorFor(com.homektv.tv.controller.UiDomain.CATALOG) != null ||
            state.catalogStatus.state in setOf(
                com.homektv.tv.controller.CatalogLoadState.OFFLINE,
                com.homektv.tv.controller.CatalogLoadState.HTTP_ERROR,
                com.homektv.tv.controller.CatalogLoadState.ROOT_UNAVAILABLE,
                com.homektv.tv.controller.CatalogLoadState.PARTIAL_FAILURE,
            )
        val wasSingerRetryVisible = overlay.btnSingerRetry.visibility == View.VISIBLE
        overlay.btnSingerRetry.visibility = if (!state.catalogLoading && singerFailure) View.VISIBLE else View.GONE
        if (!wasSingerRetryVisible && overlay.btnSingerRetry.visibility == View.VISIBLE) {
            overlay.btnSingerRetry.post {
                if (presentationState.currentTab.value == KioskTab.SINGERS &&
                    overlay.btnSingerRetry.visibility == View.VISIBLE
                ) overlay.btnSingerRetry.requestFocus()
            }
        }
        updateSingerFilterButtons(state.artistGender)
        rankingAdapter.submitList(state.ranking)
        when (categoryMode) {
            KioskCategoryMode.NEW -> categoryAdapter.submitList(state.newSongs) {
                restoreCategoryModeFocusIfReady(state)
            }
            KioskCategoryMode.LANGUAGES -> if (categoryDetail) {
                categoryAdapter.submitList(state.catalogSongs)
            } else {
                categoryNameAdapter.submitList(state.languages) {
                    restoreCategoryModeFocusIfReady(state)
                }
            }
            KioskCategoryMode.TAGS -> if (categoryDetail) {
                categoryAdapter.submitList(state.catalogSongs)
            } else {
                categoryNameAdapter.submitList(state.tags) {
                    restoreCategoryModeFocusIfReady(state)
                }
            }
        }
        when (presentationState.currentTab.value) {
            KioskTab.FAVORITES -> {
                overlay.personalRecyclerView.adapter = personalSongAdapter
                personalSongAdapter.submitList(state.favorites)
                val favoritesError = state.errorFor(com.homektv.tv.controller.UiDomain.FAVORITES)
                overlay.txtPersonalHeader.text = if (state.favoritesLoading) {
                    "正在加载收藏..."
                } else if (favoritesError != null) {
                    "我的收藏暂不可用"
                } else {
                    "我的收藏（${state.favorites.size} 首）"
                }
            }
            KioskTab.PLAYLISTS -> {
                playlistAdapter.updatePendingActions(state.pendingActions)
                when (KioskPersonalPresentationPolicy.resolve(
                    inDetail = playlistDetail,
                    loading = state.playlistDetailLoading,
                    hasDetail = state.playlistDetail != null,
                    hasError = state.errorFor(com.homektv.tv.controller.UiDomain.PLAYLISTS) != null,
                )) {
                    KioskPersonalView.DETAIL_LOADING -> {
                        overlay.personalRecyclerView.adapter = personalSongAdapter
                        personalSongAdapter.submitList(emptyList())
                        overlay.txtPersonalHeader.text = "正在加载歌单详情..."
                    }
                    KioskPersonalView.DETAIL -> {
                        overlay.personalRecyclerView.adapter = personalSongAdapter
                        personalSongAdapter.submitList(state.playlistDetail?.songs.orEmpty())
                        overlay.txtPersonalHeader.text = activity.getString(
                            R.string.playlist_detail_header,
                            state.playlistDetail?.name.orEmpty(),
                            state.playlistDetail?.songs?.size ?: 0,
                        )
                    }
                    KioskPersonalView.DETAIL_ERROR -> {
                        overlay.personalRecyclerView.adapter = personalSongAdapter
                        personalSongAdapter.submitList(emptyList())
                        overlay.txtPersonalHeader.text = activity.getString(
                            R.string.playlist_detail_error,
                            state.messageFor(com.homektv.tv.controller.UiDomain.PLAYLISTS)
                                ?: activity.getString(R.string.common_try_again_later),
                        )
                    }
                    KioskPersonalView.LIST -> {
                        overlay.personalRecyclerView.adapter = playlistAdapter
                        playlistAdapter.submitList(state.playlists)
                        overlay.txtPersonalHeader.text = if (state.errorFor(com.homektv.tv.controller.UiDomain.PLAYLISTS) != null) {
                            "歌单加载失败：${state.messageFor(com.homektv.tv.controller.UiDomain.PLAYLISTS) ?: "请稍后重试"}"
                        } else {
                            "主题歌单（${state.playlists.size} 个）"
                        }
                    }
                }
            }
            KioskTab.HISTORY -> {
                overlay.personalRecyclerView.adapter = personalSongAdapter
                val visibleHistory = state.history.mapNotNull { it.song }.distinctBy { it.id }
                personalSongAdapter.submitList(visibleHistory)
                val historyError = state.errorFor(com.homektv.tv.controller.UiDomain.HISTORY)
                overlay.txtPersonalHeader.text = if (state.historyLoading) {
                    "正在加载最近唱过..."
                } else if (historyError != null) {
                    "最近唱过暂不可用"
                } else {
                    "最近唱过（${visibleHistory.size} 首）"
                }
            }
            else -> Unit
        }
        renderPersonalStatus()
        if (state.artists.isNotEmpty() && presentationState.currentTab.value == KioskTab.SINGERS) {
            artistsLoaded = true
        }
        if (state.ranking.isNotEmpty()) rankingsLoaded = true
        if (state.newSongs.isNotEmpty()) categoryLoadPolicy.markLoaded(KioskCategoryMode.NEW)
        if (state.languages.isNotEmpty()) categoryLoadPolicy.markLoaded(KioskCategoryMode.LANGUAGES)
        if (state.tags.isNotEmpty()) categoryLoadPolicy.markLoaded(KioskCategoryMode.TAGS)

        when {
            state.query.isNotBlank() -> {
                searchAdapter.submitList(state.results)
                val searchError = state.errorFor(com.homektv.tv.controller.UiDomain.SEARCH)
                overlay.txtSearchCount.text = when {
                    state.loading -> "正在检索: ${state.query} ..."
                    searchError != null -> "检索失败：${state.messageFor(com.homektv.tv.controller.UiDomain.SEARCH) ?: "请稍后重试"}"
                    else -> "找到 ${state.results.size} 首歌曲"
                }
            }
            selectedArtist != null -> {
                searchAdapter.submitList(state.catalogSongs)
                val catalogError = state.errorFor(com.homektv.tv.controller.UiDomain.CATALOG)
                overlay.txtSearchCount.text = if (catalogError != null) {
                    "歌手【$selectedArtist】加载失败：${state.messageFor(com.homektv.tv.controller.UiDomain.CATALOG) ?: "请稍后重试"}"
                } else {
                    "歌手【$selectedArtist】共 ${state.catalogSongs.size} 首"
                }
            }
            else -> {
                searchAdapter.submitList(emptyList())
                overlay.txtSearchCount.text = "支持歌名/歌手、中文、全拼和首字母搜索"
            }
        }

        renderSearchEmptyState()

        if (presentationState.currentTab.value == KioskTab.CATEGORIES && !categoryDetail) {
            val isLanguage = categoryMode == KioskCategoryMode.LANGUAGES
            val label = if (isLanguage) "语种" else "分类"
            val count = if (isLanguage) state.languages.size else state.tags.size
            val catalogError = state.errorFor(com.homektv.tv.controller.UiDomain.CATALOG)
            val showError = categoryMode != KioskCategoryMode.NEW && !state.catalogLoading &&
                (catalogError != null || state.catalogStatus.state == com.homektv.tv.controller.CatalogLoadState.OFFLINE)
            val wasErrorVisible = overlay.categoryErrorPanel.visibility == View.VISIBLE
            overlay.categoryErrorPanel.visibility = if (showError) View.VISIBLE else View.GONE
            overlay.categoryRecyclerView.visibility = if (showError) View.GONE else View.VISIBLE
            if (showError) {
                overlay.txtCategoryErrorDetail.text = state.messageFor(com.homektv.tv.controller.UiDomain.CATALOG)
                    ?.takeIf(String::isNotBlank)
                    ?: "点歌服务离线，无法加载$label；请检查连接后重试"
                if (!wasErrorVisible) {
                    overlay.btnCategoryRetry.post {
                        if (presentationState.currentTab.value == KioskTab.CATEGORIES &&
                            overlay.categoryErrorPanel.visibility == View.VISIBLE
                        ) overlay.btnCategoryRetry.requestFocus()
                    }
                }
            }
            overlay.txtCategoryHeader.text = when {
                state.catalogLoading -> "正在加载$label…"
                showError -> "选择$label"
                state.catalogStatus.state == com.homektv.tv.controller.CatalogLoadState.SCANNING -> "曲库正在扫描，暂时没有可用$label"
                state.catalogStatus.state == com.homektv.tv.controller.CatalogLoadState.OFFLINE -> "点歌服务离线，无法加载$label"
                count == 0 -> "暂无$label"
                else -> "选择$label（$count 项）"
            }
        }
    }

    private fun applyTabSelection(tab: KioskTab) {
        val overlay = binding.kioskOverlay
        val activeBg = R.drawable.btn_gold
        val inactiveBg = R.drawable.btn_keyboard_key
        val activeColor = 0xFF111317.toInt()
        val inactiveColor = activity.resources.getColor(R.color.color_keyboard_key_text, null)

        fun updateTabBtn(btn: android.widget.Button, active: Boolean) {
            btn.setBackgroundResource(if (active) activeBg else inactiveBg)
            btn.setTextColor(if (active) activeColor else inactiveColor)
        }

        fun updateNavTab(btn: android.widget.Button, selected: Boolean) {
            btn.setBackgroundResource(R.drawable.bg_kiosk_nav_tab)
            btn.isSelected = selected
            btn.setTextColor(
                activity.resources.getColor(if (selected) R.color.ktv_dashboard_gold else R.color.dim, null),
            )
        }

        updateNavTab(overlay.tabDashboard, tab == KioskTab.DASHBOARD)
        updateNavTab(overlay.tabPinyin, tab == KioskTab.PINYIN)
        updateNavTab(overlay.tabSingers, tab == KioskTab.SINGERS)
        updateNavTab(overlay.tabRankings, tab == KioskTab.RANKINGS)
        updateCategoryNavSelection(tab)
        updateNavTab(overlay.tabFavorites, tab == KioskTab.FAVORITES)
        updateNavTab(overlay.tabPlaylists, tab == KioskTab.PLAYLISTS)
        updateNavTab(overlay.tabHistory, tab == KioskTab.HISTORY)
        updateTabBtn(overlay.btnCategoryNew, categoryMode == KioskCategoryMode.NEW && !categoryDetail)
        updateTabBtn(overlay.btnCategoryLanguages, categoryMode == KioskCategoryMode.LANGUAGES)
        updateTabBtn(overlay.btnCategoryTags, categoryMode == KioskCategoryMode.TAGS)

        overlay.kioskNavTabs.visibility = View.VISIBLE
        overlay.kioskDashboardView.visibility = if (tab == KioskTab.DASHBOARD) View.VISIBLE else View.GONE
        overlay.panelPinyin.visibility = if (tab == KioskTab.PINYIN) View.VISIBLE else View.GONE
        overlay.panelSingers.visibility = if (tab == KioskTab.SINGERS) View.VISIBLE else View.GONE
        overlay.panelRankings.visibility = if (tab == KioskTab.RANKINGS) View.VISIBLE else View.GONE
        overlay.panelCategories.visibility = if (tab == KioskTab.CATEGORIES) View.VISIBLE else View.GONE
        overlay.panelPersonal.visibility = if (
            tab == KioskTab.FAVORITES || tab == KioskTab.PLAYLISTS || tab == KioskTab.HISTORY
        ) View.VISIBLE else View.GONE

        if (tab == KioskTab.DASHBOARD) {
            overlay.kioskDashboardView.requestDashboardFocus()
        }

        if (tab != KioskTab.CATEGORIES && categoryDetail) {
            categoryDetail = false
            focusController.hasInnerDetailBack = false
        }
        if (tab != KioskTab.CATEGORIES) categoryFocusRestorePending = false
        if (tab != KioskTab.PLAYLISTS && playlistDetail) {
            playlistDetail = false
            personalActionRouter.clearPlaylistDetail()
            focusController.hasInnerDetailBack = false
        }

        if (tab != KioskTab.PINYIN) {
            val wasArtistSelected = presentationState.selectedArtist.value != null
            presentationState.clearArtistSelection()
            focusController.hasInnerDetailBack = false
            if (wasArtistSelected) {
                overlay.kioskKeyboard.clear()
                overlay.txtSearchCount.text = "输入首字母检索曲目"
                loadDefaultSongs()
            }
        }

        (catalogActions as? ControllerCatalogVisibility)?.setCatalogVisible(tab != KioskTab.DASHBOARD)
        when (tab) {
            KioskTab.DASHBOARD -> Unit
            KioskTab.PINYIN -> Unit
            KioskTab.SINGERS -> if (!artistsLoaded || controllerState.artists.isEmpty()) loadArtists()
            KioskTab.RANKINGS -> if (!rankingsLoaded) loadRankings()
            KioskTab.CATEGORIES -> if (categoryLoadPolicy.shouldLoad(categoryMode)) loadCategories()
            KioskTab.FAVORITES -> loadFavorites()
            KioskTab.PLAYLISTS -> loadPlaylists()
            KioskTab.HISTORY -> loadHistory()
        }
        renderSearchEmptyState()
    }

    private fun onKeywordInput(keyword: String) {
        if (keyword.isBlank()) {
            binding.kioskOverlay.txtSearchCount.text = "支持歌名/歌手、中文、全拼和首字母搜索"
            loadDefaultSongs()
            return
        }

        binding.kioskOverlay.txtSearchCount.text = activity.getString(R.string.searching_keyword, keyword)
        catalogActionRouter.search(keyword)
    }

    private fun renderSearchEmptyState() {
        val overlay = binding.kioskOverlay
        val state = controllerState
        val selectedArtist = presentationState.selectedArtist.value
        val isEmptySearchPrompt = presentationState.currentTab.value == KioskTab.PINYIN &&
            selectedArtist == null && !state.loading &&
            (state.query.isBlank() || state.results.isEmpty())
        overlay.searchEmptyState.visibility = if (isEmptySearchPrompt) View.VISIBLE else View.GONE
        overlay.txtSearchCount.visibility = if (
            isEmptySearchPrompt && state.query.isBlank()
        ) View.GONE else View.VISIBLE
        if (isEmptySearchPrompt) {
            val searchError = state.errorFor(com.homektv.tv.controller.UiDomain.SEARCH)
            overlay.txtSearchEmptyTitle.text = when {
                state.query.isBlank() -> "开始搜索"
                searchError != null -> "搜索暂时失败"
                else -> "没有找到匹配歌曲"
            }
            overlay.txtSearchEmptyMessage.text = when {
                state.query.isBlank() -> "输入歌名、歌手或首字母"
                searchError != null -> state.messageFor(com.homektv.tv.controller.UiDomain.SEARCH)
                    ?: "请稍后重试"
                else -> "换个歌名、歌手或首字母试试"
            }
        }
    }

    private fun loadDefaultSongs() {
        catalogActionRouter.defaultSongs()
    }

    private fun loadArtists() {
        catalogActionRouter.artists()
    }

    private fun loadSingerGender(gender: String) {
        coordinator.resetIdleTimer()
        focusController.hasInnerDetailBack = false
        presentationState.clearArtistSelection()
        catalogActions.loadArtists(gender = gender, initial = "", restorePage = 0)
        binding.kioskOverlay.singerRecyclerView.requestFocus()
    }

    private fun updateSingerFilterButtons(gender: String) {
        val buttons = listOf(
            binding.kioskOverlay.btnSingerAll to KtvSingerFilterPolicy.filters[0],
            binding.kioskOverlay.btnSingerMale to KtvSingerFilterPolicy.filters[1],
            binding.kioskOverlay.btnSingerFemale to KtvSingerFilterPolicy.filters[2],
            binding.kioskOverlay.btnSingerGroup to KtvSingerFilterPolicy.filters[3],
        )
        buttons.forEach { (button, filter) ->
            val selected = KtvSingerFilterPolicy.isSelected(filter, gender)
            button.setBackgroundResource(
                if (selected) R.drawable.btn_gold else R.drawable.btn_keyboard_key,
            )
            button.setTextColor(
                if (selected) 0xFF111317.toInt()
                else activity.resources.getColor(R.color.color_keyboard_key_text, null),
            )
        }
    }

    private fun loadRankings() {
        catalogActionRouter.rankings()
    }

    private fun loadCategories() {
        when (categoryMode) {
            KioskCategoryMode.TAGS -> {
                binding.kioskOverlay.txtCategoryHeader.text = "选择标签"
                catalogActionRouter.tags()
            }
            KioskCategoryMode.LANGUAGES -> {
                binding.kioskOverlay.txtCategoryHeader.text = "选择语种"
                catalogActionRouter.languages()
            }
            KioskCategoryMode.NEW -> {
                binding.kioskOverlay.txtCategoryHeader.text = "✨ 最新入库曲目推荐"
                catalogActionRouter.newSongs()
            }
        }
    }

    private fun updateCategoryNavSelection(tab: KioskTab) {
        val overlay = binding.kioskOverlay
        val categoryActive = tab == KioskTab.CATEGORIES && categoryMode == KioskCategoryMode.TAGS
        val languageActive = tab == KioskTab.CATEGORIES && categoryMode == KioskCategoryMode.LANGUAGES

        listOf(
            overlay.tabCategories to categoryActive,
            overlay.tabLanguages to languageActive,
        ).forEach { (button, selected) ->
            button.setBackgroundResource(R.drawable.bg_kiosk_nav_tab)
            button.isSelected = selected
            button.setTextColor(
                activity.resources.getColor(if (selected) R.color.gold else R.color.dim, null),
            )
        }
    }

    private fun openCategoryRoot(mode: KioskCategoryMode) {
        val alreadyOnCategories = presentationState.currentTab.value == KioskTab.CATEGORIES
        categoryMode = mode
        categoryDetail = false
        focusController.hasInnerDetailBack = false
        categoryNameAdapter.setIconResource(
            if (mode == KioskCategoryMode.LANGUAGES) R.drawable.ic_language else R.drawable.ic_category,
        )
        binding.kioskOverlay.categoryRecyclerView.adapter = categoryNameAdapter
        binding.kioskOverlay.txtCategoryHeader.text =
            if (mode == KioskCategoryMode.LANGUAGES) "选择语种" else "选择分类"
        categoryNameAdapter.submitList(
            if (mode == KioskCategoryMode.LANGUAGES) controllerState.languages else controllerState.tags,
        )
        if (alreadyOnCategories) {
            updateCategoryNavSelection(KioskTab.CATEGORIES)
            if (categoryLoadPolicy.shouldLoad(mode)) loadCategories()
        } else {
            presentationState.selectTab(KioskTab.CATEGORIES)
        }
    }

    private fun onArtistSelected(artist: ArtistItem) {
        coordinator.resetIdleTimer()
        focusController.hasInnerDetailBack = true
        presentationState.selectArtist(artist.name)
        binding.kioskOverlay.kioskKeyboard.clear()
        binding.kioskOverlay.txtSearchCount.text = activity.getString(R.string.loading_artist_songs, artist.name)
        binding.kioskOverlay.tabPinyin.post {
            binding.kioskOverlay.searchRecyclerView.requestFocus()
        }
        catalogActions.setQuery("")
        catalogActionRouter.artistSongs(artist.artistKey)
    }

    private fun onCategorySelected(item: com.homektv.tv.net.NamedCount) {
        coordinator.resetIdleTimer()
        categoryDetail = true
        focusController.hasInnerDetailBack = true
        binding.kioskOverlay.categoryRecyclerView.adapter = categoryAdapter
        binding.kioskOverlay.txtCategoryHeader.text = when (categoryMode) {
            KioskCategoryMode.LANGUAGES -> "语种 · ${item.name}"
            KioskCategoryMode.TAGS -> "标签 · ${item.name}"
            KioskCategoryMode.NEW -> "最新入库曲目推荐"
        }
        when (categoryMode) {
            KioskCategoryMode.LANGUAGES -> catalogActionRouter.languageSongs(item.name)
            KioskCategoryMode.TAGS -> catalogActionRouter.tagSongs(item.name)
            KioskCategoryMode.NEW -> Unit
        }
        binding.kioskOverlay.categoryRecyclerView.requestFocus()
    }

    private fun loadFavorites() {
        playlistDetail = false
        binding.kioskOverlay.personalRecyclerView.adapter = personalSongAdapter
        binding.kioskOverlay.personalStatusPanel.visibility = View.GONE
        binding.kioskOverlay.personalRecyclerView.visibility = View.VISIBLE
        binding.kioskOverlay.txtPersonalHeader.text = "正在加载收藏..."
        personalActionRouter.favorites()
    }

    private fun loadPlaylists() {
        playlistDetail = false
        binding.kioskOverlay.personalRecyclerView.adapter = playlistAdapter
        binding.kioskOverlay.txtPersonalHeader.text = "正在加载歌单..."
        personalActionRouter.playlists()
    }

    private fun loadHistory() {
        playlistDetail = false
        binding.kioskOverlay.personalRecyclerView.adapter = personalSongAdapter
        binding.kioskOverlay.personalStatusPanel.visibility = View.GONE
        binding.kioskOverlay.personalRecyclerView.visibility = View.VISIBLE
        binding.kioskOverlay.txtPersonalHeader.text = "正在加载最近唱过..."
        personalActionRouter.history()
    }

    private fun renderPersonalStatus() {
        val overlay = binding.kioskOverlay
        val tab = presentationState.currentTab.value
        val isFavorites = tab == KioskTab.FAVORITES
        val isHistory = tab == KioskTab.HISTORY
        if (!isFavorites && !isHistory) {
            overlay.personalStatusPanel.visibility = View.GONE
            overlay.personalRecyclerView.visibility = View.VISIBLE
            return
        }

        val domain = if (isFavorites) {
            com.homektv.tv.controller.UiDomain.FAVORITES
        } else {
            com.homektv.tv.controller.UiDomain.HISTORY
        }
        val itemCount = if (isFavorites) controllerState.favorites.size else controllerState.history.count { it.song != null }
        val error = controllerState.errorFor(domain)
        val isLoading = if (isFavorites) controllerState.favoritesLoading else controllerState.historyLoading
        val hasEmptyResult = itemCount == 0 && !isLoading
        val showStatus = isLoading || error != null || hasEmptyResult
        overlay.personalStatusPanel.visibility = if (showStatus) View.VISIBLE else View.GONE
        overlay.personalRecyclerView.visibility = if (showStatus) View.GONE else View.VISIBLE
        if (showStatus) {
            overlay.txtPersonalStatusTitle.text = when {
                isLoading && isFavorites -> "正在加载收藏..."
                isLoading -> "正在加载最近唱过..."
                error != null -> "暂时无法加载"
                isFavorites -> "还没有收藏歌曲"
                else -> "还没有点唱记录"
            }
            overlay.txtPersonalStatusMessage.text = when {
                isLoading -> "正在连接点歌服务，请稍候"
                error != null -> controllerState.messageFor(domain)?.takeIf(String::isNotBlank) ?: "请稍后重试"
                isFavorites -> "收藏喜欢的歌曲后会显示在这里"
                else -> "点唱过的歌曲会显示在这里"
            }
            overlay.btnPersonalRetry.visibility = if (!isLoading && error != null) View.VISIBLE else View.GONE
            if (error != null && overlay.btnPersonalRetry.hasFocus().not()) {
                overlay.btnPersonalRetry.post {
                    if ((presentationState.currentTab.value == KioskTab.FAVORITES ||
                            presentationState.currentTab.value == KioskTab.HISTORY) &&
                        overlay.btnPersonalRetry.visibility == View.VISIBLE
                    ) overlay.btnPersonalRetry.requestFocus()
                }
            }
        }
    }

    private fun openPlaylist(playlist: com.homektv.tv.net.PlaylistSummary) {
        coordinator.resetIdleTimer()
        playlistDetail = true
        focusController.hasInnerDetailBack = true
        binding.kioskOverlay.personalRecyclerView.adapter = personalSongAdapter
        binding.kioskOverlay.txtPersonalHeader.text = activity.getString(
            R.string.loading_playlist_detail,
            playlist.name,
        )
        personalActionRouter.playlistDetail(playlist.id)
        binding.kioskOverlay.personalRecyclerView.requestFocus()
    }

    private fun orderSong(song: SongDto, onComplete: (Boolean) -> Unit = {}) {
        coordinator.resetIdleTimer()
        queueActionRouter.order(song, onComplete)
    }

    private fun orderSongTop(song: SongDto, onComplete: (Boolean) -> Unit = {}) {
        coordinator.resetIdleTimer()
        queueActionRouter.orderTop(song, onComplete)
    }

    fun openQueueDrawer() {
        val state = controllerActions.state.value
        val snapshot = currentSnapshot
            ?: state.queue.takeIf { state.connection != ControllerConnection.CONNECTING }
            ?: return
        val currentTitle = snapshot.playing?.song?.let { "${it.title} · ${it.artist}" } ?: "当前暂无歌曲播放"
        val waitingEntries = snapshot.list.filter { it.status == "waiting" }

        val dialog = KtvQueueDrawerDialog(
            context = activity,
            currentUserId = state.currentUser?.id,
            isHost = state.roomHost.isHost,
            coordinator = coordinator,
            onTop = queueActionRouter::top,
            onCancel = queueActionRouter::cancel,
            onShuffle = queueActionRouter::shuffle,
        )
        dialog.onDismissDrawer = {
            focusController.isDrawerOpen = false
            queueDialog = null
        }
        dialog.show()
        dialog.submitQueue(currentTitle, waitingEntries)
        dialog.updateActor(state.currentUser?.id, state.roomHost.isHost)
        dialog.updatePendingActions(state.pendingActions)
        focusController.isDrawerOpen = true
        queueDialog = dialog
    }

    fun setCachedQrBitmap(bitmap: Bitmap) {
        cachedQrBitmap = bitmap
        qrDialog?.updateQrBitmap(bitmap)
    }

    fun openQrDialog() {
        val portalUrl = KtvQrPolicy.formatPortalUrl(config.serverHost)
        val dialog = KtvQrDialog(
            context = activity,
            portalUrl = portalUrl,
            qrBitmap = cachedQrBitmap,
            coordinator = coordinator,
        )
        dialog.onDismissQr = {
            if (qrDialog === dialog) {
                qrDialog = null
            }
        }
        qrDialog = dialog
        dialog.show()

        if (cachedQrBitmap == null && qrLoadJob?.isActive != true) {
            qrLoadJob = activity.lifecycleScope.launch {
                try {
                    val qrUrl = KtvQrPolicy.buildQrUrl(config.apiBase(), 540)
                    val bytes = withContext(Dispatchers.IO) {
                        val res = transport.getBytes(qrUrl)
                        (res as? KtvApiResult.Success)?.value
                    }
                    val bmp = bytes?.let {
                        withContext(Dispatchers.Default) {
                            ArtworkDecoder.decode(it, ArtworkProfile.QR)
                        }
                    }
                    if (bmp != null) {
                        cachedQrBitmap = bmp
                        if (qrDialog?.isShowing == true) {
                            qrDialog?.updateQrBitmap(bmp)
                        }
                    }
                } finally {
                    qrLoadJob = null
                }
            }
        }
    }

    fun updateSnapshot(
        snapshot: QueueSnapshot,
        queueProjection: Map<Long, SongQueueState> = emptyMap(),
    ) {
        currentSnapshot = snapshot
        coordinator.setPlaybackActive(snapshot.state == "playing" || snapshot.playing != null)
        searchAdapter.updateQueueProjection(queueProjection)
        rankingAdapter.updateQueueProjection(queueProjection)
        categoryAdapter.updateQueueProjection(queueProjection)
        personalSongAdapter.updateQueueProjection(queueProjection)

        val title = snapshot.playing?.song?.title
        val artist = snapshot.playing?.song?.artist
        val waitingCount = snapshot.list.count { it.status == "waiting" }

        binding.kioskOverlay.kioskDashboardView.updateQueueCount(waitingCount)

        binding.kioskOverlay.kioskBottomBar.bindPlayback(
            title = title,
            artist = artist,
            queueCount = waitingCount,
            layout = snapshot.audioLayout.layout,
            audioTracks = if (snapshot.audioLayout.layout == "DUAL_TRACK") 2 else 1,
            isPlaying = snapshot.state == "playing",
            vocalMode = snapshot.vocalMode,
            positionMs = snapshot.positionMs,
            durationMs = snapshot.playing?.song?.durationMs?.toLong() ?: 0L,
        )

        presentationState.updateAudioLayout(
            snapshot.audioLayout,
            if (snapshot.audioLayout.layout == "DUAL_TRACK") 2 else 1,
        )

        renderPipState()

        if (queueDialog?.isShowing == true) {
            val currentTitle = snapshot.playing?.song?.let { "${it.title} · ${it.artist}" } ?: "当前暂无歌曲播放"
            val waitingEntries = snapshot.list.filter { it.status == "waiting" }
            queueDialog?.submitQueue(currentTitle, waitingEntries)
        }
    }

    fun toggleKiosk(active: Boolean) {
        coordinator.toggleKiosk(active)
    }

    fun updateExternalDisplay(active: Boolean) {
        externalDisplayActive = active
        if (focusController.isKioskActive) renderPipState()
    }

    private fun applyKioskActive(active: Boolean) {
        focusController.isKioskActive = active
        if (active) {
            binding.kioskOverlay.kioskRootOverlay.visibility = View.VISIBLE
            reparentView(binding.playerView, binding.kioskOverlay.pipVideoAnchor)
            (binding.playerView.videoSurfaceView as? SurfaceView)?.setZOrderMediaOverlay(true)
            renderPipState()
            when (presentationState.currentTab.value) {
                KioskTab.DASHBOARD -> binding.kioskOverlay.kioskDashboardView.requestDashboardFocus()
                KioskTab.PINYIN -> binding.kioskOverlay.tabPinyin.requestFocus()
                KioskTab.SINGERS -> binding.kioskOverlay.tabSingers.requestFocus()
                KioskTab.RANKINGS -> binding.kioskOverlay.tabRankings.requestFocus()
                KioskTab.CATEGORIES -> (if (categoryMode == KioskCategoryMode.LANGUAGES) {
                    binding.kioskOverlay.tabLanguages
                } else {
                    binding.kioskOverlay.tabCategories
                }).requestFocus()
                KioskTab.FAVORITES -> binding.kioskOverlay.tabFavorites.requestFocus()
                KioskTab.PLAYLISTS -> binding.kioskOverlay.tabPlaylists.requestFocus()
                KioskTab.HISTORY -> binding.kioskOverlay.tabHistory.requestFocus()
            }
        } else {
            focusController.hasInnerDetailBack = false
            presentationState.clearArtistSelection()
            binding.kioskOverlay.kioskKeyboard.clear()
            binding.kioskOverlay.txtSearchCount.text = "输入首字母检索曲目"
            (binding.playerView.videoSurfaceView as? SurfaceView)?.setZOrderMediaOverlay(false)
            reparentView(binding.playerView, binding.fullscreenVideoAnchor)
            binding.kioskOverlay.kioskRootOverlay.visibility = View.GONE
            binding.playerView.requestFocus()
        }
    }

    private fun renderPipState() {
        val locallyBuffering = pipObservedPlayer?.let {
            it.playbackState == Player.STATE_BUFFERING && it.playWhenReady
        } == true
        val state = KioskPipDisplayPolicy.resolve(
            externalDisplayActive = externalDisplayActive,
            playbackState = if (locallyBuffering) "buffering" else currentSnapshot?.state,
            hasPlaying = currentSnapshot?.playing != null,
        )
        if (!state.videoVisible && binding.kioskOverlay.btnKioskExit.hasFocus()) {
            binding.kioskOverlay.tabHistory.requestFocus()
        }
        binding.kioskOverlay.btnKioskExit.visibility =
            if (state.videoVisible) View.VISIBLE else View.GONE
        binding.kioskOverlay.tabHistory.nextFocusRightId =
            if (state.videoVisible) R.id.btnKioskExit else R.id.tabDashboard
        binding.kioskOverlay.pipVideoFrame.visibility =
            if (state.videoVisible && pipSpaceAvailable) View.VISIBLE else View.GONE
        val playingTitle = currentSnapshot?.playing?.song?.let { song ->
            listOfNotNull(song.title, song.artist?.takeIf(String::isNotBlank)).joinToString(" · ")
        }.orEmpty()
        binding.kioskOverlay.txtPipLabel.text = listOf(state.label, playingTitle)
            .filter(String::isNotBlank)
            .joinToString("  ")
    }

    private fun updatePipResponsiveSize() {
        val stage = binding.kioskOverlay.kioskContentStage
        if (stage.width <= 0 || stage.height <= 0) return

        val density = activity.resources.displayMetrics.density
        val size = KioskPipResponsivePolicy.resolve(
            availableWidthDp = ((stage.width - stage.paddingLeft - stage.paddingRight) / density).roundToInt(),
            availableHeightDp = ((stage.height - stage.paddingTop - stage.paddingBottom) / density).roundToInt(),
        )
        val frame = binding.kioskOverlay.pipVideoFrame
        val params = frame.layoutParams as? ConstraintLayout.LayoutParams ?: return
        val widthPx = (size.widthDp * density).roundToInt()
        params.matchConstraintMinWidth = widthPx
        params.matchConstraintMaxWidth = widthPx
        frame.layoutParams = params
        pipSpaceAvailable = size.visible
        renderPipState()
    }

    fun destroy() {
        pipObservedPlayer?.removeListener(pipPlaybackListener)
        binding.kioskOverlay.kioskContentStage.removeOnLayoutChangeListener(pipLayoutChangeListener)
        queueDialog?.dismiss()
        queueDialog = null
        qrDialog?.dismiss()
        qrDialog = null
        qrLoadJob?.cancel()
        qrLoadJob = null
        cachedQrBitmap = null
        coordinator.destroy()
        pendingAvatarCallbacks.clear()
        singerAvatarCache.clear()
        transport.close()
    }

    private fun reparentView(view: View, target: ViewGroup) {
        if (view.parent === target) return
        (view.parent as? ViewGroup)?.removeView(view)
        target.addView(
            view,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    private fun resetToSingersTab() {
        val wasAlreadyOnSingersTab = presentationState.currentTab.value == KioskTab.SINGERS
        artistsLoaded = false
        focusController.hasInnerDetailBack = false
        presentationState.clearArtistSelection()
        presentationState.selectTab(KioskTab.SINGERS)
        binding.kioskOverlay.kioskKeyboard.clear()
        binding.kioskOverlay.txtSearchCount.text = "输入首字母检索曲目"
        loadDefaultSongs()
        if (wasAlreadyOnSingersTab) loadArtists()
        binding.kioskOverlay.btnSingerAll.post {
            binding.kioskOverlay.btnSingerAll.requestFocus()
        }
    }

    private fun resetCategoryDetail() {
        categoryDetail = false
        categoryFocusRestorePending = true
        focusController.hasInnerDetailBack = false
        categoryNameAdapter.setIconResource(
            if (categoryMode == KioskCategoryMode.LANGUAGES) R.drawable.ic_language else R.drawable.ic_category,
        )
        binding.kioskOverlay.categoryRecyclerView.adapter = categoryNameAdapter
        binding.kioskOverlay.txtCategoryHeader.text = when (categoryMode) {
            KioskCategoryMode.LANGUAGES -> "选择语种"
            KioskCategoryMode.TAGS -> "选择标签"
            KioskCategoryMode.NEW -> "✨ 最新入库曲目推荐"
        }
        when (categoryMode) {
            KioskCategoryMode.LANGUAGES -> catalogActionRouter.languages()
            KioskCategoryMode.TAGS -> catalogActionRouter.tags()
            KioskCategoryMode.NEW -> {
                binding.kioskOverlay.categoryRecyclerView.adapter = categoryAdapter
                loadCategories()
            }
        }
    }

    private fun restoreCategoryModeFocusIfReady(state: ControllerUiState) {
        if (!categoryFocusRestorePending || state.catalogLoading || categoryDetail) return
        if (presentationState.currentTab.value != KioskTab.CATEGORIES) return

        categoryFocusRestorePending = false
        val target = if (categoryMode == KioskCategoryMode.LANGUAGES) {
            binding.kioskOverlay.btnCategoryLanguages
        } else {
            binding.kioskOverlay.btnCategoryTags
        }
        target.post {
            if (presentationState.currentTab.value == KioskTab.CATEGORIES && !categoryDetail) {
                target.requestFocus()
            }
        }
    }

    private fun resetInnerDetail() {
        if (presentationState.selectedArtist.value != null) {
            resetToSingersTab()
        } else if (categoryDetail) {
            resetCategoryDetail()
        } else if (playlistDetail) {
            playlistDetail = false
            focusController.hasInnerDetailBack = false
            binding.kioskOverlay.personalRecyclerView.adapter = playlistAdapter
            binding.kioskOverlay.txtPersonalHeader.text = "主题歌单"
            personalActionRouter.clearPlaylistDetail()
            personalActionRouter.playlists()
        }
    }

    fun dispatchKeyEvent(event: KeyEvent): Boolean {
        coordinator.resetIdleTimer()
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode != KeyEvent.KEYCODE_BACK) {
            backExitGate.reset()
        }
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (!focusController.shouldInterceptBack(event.action)) {
                return false
            }
            if (event.action == KeyEvent.ACTION_DOWN) {
                return true
            }
            if (event.action == KeyEvent.ACTION_UP) {
                focusController.handleBackPress(
                    onDismissDrawer = { queueDialog?.dismiss() },
                    onClearInputText = {
                        binding.kioskOverlay.kioskKeyboard.clear()
                        focusController.hasInputText = false
                        loadDefaultSongs()
                    },
                    onInnerDetailBack = { resetInnerDetail() },
                    onExitKiosk = {
                        if (presentationState.currentTab.value != KioskTab.DASHBOARD) {
                            dashboardPolicy.handleBack()
                            presentationState.selectTab(KioskTab.DASHBOARD)
                            backExitGate.reset()
                        } else {
                            val hasPlaying = currentSnapshot?.playing != null || currentSnapshot?.state == "playing"
                            val action = KioskLifecyclePolicy.resolveDashboardBackAction(hasPlaying)
                            if (action == KioskBackAction.RETURN_TO_FULLSCREEN_MV) {
                                backExitGate.reset()
                                toggleKiosk(false)
                            } else {
                                val decision = backExitGate.onBack(android.os.SystemClock.elapsedRealtime(), isAtTopLevel = true)
                                when (decision) {
                                    com.homektv.tv.navigation.BackExitDecision.CONSUMED_AND_PROMPTED -> {
                                        Toast.makeText(activity, "当前已在大厅首页，再次按返回键退出应用", Toast.LENGTH_SHORT).show()
                                    }
                                    com.homektv.tv.navigation.BackExitDecision.EXIT_APP -> {
                                        activity.finish()
                                    }
                                    com.homektv.tv.navigation.BackExitDecision.IGNORED -> {}
                                }
                            }
                        }
                    },
                    onExitApp = { /* handled by activity when not intercepted */ },
                )
                return true
            }
        }
        return false
    }
}
