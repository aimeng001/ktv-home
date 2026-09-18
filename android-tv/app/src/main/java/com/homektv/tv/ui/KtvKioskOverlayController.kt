package com.homektv.tv.ui

import android.graphics.Bitmap
import android.view.KeyEvent
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.homektv.tv.controller.ControllerActions
import com.homektv.tv.controller.ControllerCatalogActions
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
import com.homektv.tv.net.AppConfig
import com.homektv.tv.net.ArtistItem
import com.homektv.tv.net.KtvApiResult
import com.homektv.tv.net.KtvHttpTransport
import com.homektv.tv.net.QueueSnapshot
import com.homektv.tv.net.SongDto
import com.homektv.tv.player.EffectPlayer
import com.homektv.tv.ui.kiosk.KtvSingerFilterPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class KioskCategoryMode {
    NEW,
    LANGUAGES,
    TAGS,
}

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

    private val config = AppConfig(activity)
    private val transport = KtvHttpTransport(config)
    private val catalogActionRouter = KioskCatalogActionRouter(catalogActions)
    private val personalActionRouter = KioskPersonalActionRouter(personalActions)
    private val queueActionRouter = KioskQueueActionRouter(controllerActions)

    val presentationState = KioskPresentationState(initialTab = KioskTab.DASHBOARD)
    private val dashboardPolicy = com.homektv.tv.ui.kiosk.KtvDashboardPolicy()

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
    private var artistsLoaded = false
    private var rankingsLoaded = false
    private var categoriesLoaded = false
    private var categoryMode = KioskCategoryMode.NEW
    private var categoryDetail = false
    private var playlistDetail = false
    private var externalDisplayActive = initialExternalDisplayActive

    init {
        setupViews()
        observeState()
    }

    private fun setupViews() {
        val overlay = binding.kioskOverlay

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

        overlay.rankingRecyclerView.layoutManager = LinearLayoutManager(activity)
        overlay.rankingRecyclerView.adapter = rankingAdapter

        overlay.categoryRecyclerView.layoutManager = LinearLayoutManager(activity)
        overlay.categoryRecyclerView.adapter = categoryAdapter

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
                    categoryMode = when (com.homektv.tv.ui.kiosk.KtvCategoryRoutePolicy.dashboardCategory()) {
                        com.homektv.tv.ui.kiosk.KtvCategoryRoute.TAGS -> KioskCategoryMode.TAGS
                        com.homektv.tv.ui.kiosk.KtvCategoryRoute.LANGUAGES -> KioskCategoryMode.LANGUAGES
                        com.homektv.tv.ui.kiosk.KtvCategoryRoute.NEW -> KioskCategoryMode.NEW
                    }
                    categoryDetail = false
                    focusController.hasInnerDetailBack = false
                    overlay.categoryRecyclerView.adapter = categoryNameAdapter
                    overlay.txtCategoryHeader.text = "选择标签"
                    presentationState.selectTab(KioskTab.CATEGORIES)
                    loadCategories()
                }
                com.homektv.tv.ui.kiosk.KtvDashboardTile.LANGUAGE -> {
                    categoryMode = KioskCategoryMode.LANGUAGES
                    categoryDetail = false
                    focusController.hasInnerDetailBack = false
                    overlay.categoryRecyclerView.adapter = categoryNameAdapter
                    overlay.txtCategoryHeader.text = "选择语种"
                    presentationState.selectTab(KioskTab.CATEGORIES)
                    catalogActionRouter.languages()
                }
                com.homektv.tv.ui.kiosk.KtvDashboardTile.RANKING -> presentationState.selectTab(KioskTab.RANKINGS)
                com.homektv.tv.ui.kiosk.KtvDashboardTile.FAVORITES -> {
                    loadFavorites()
                    presentationState.selectTab(KioskTab.FAVORITES)
                }
                com.homektv.tv.ui.kiosk.KtvDashboardTile.HISTORY -> {
                    loadHistory()
                    presentationState.selectTab(KioskTab.HISTORY)
                }
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
        overlay.tabCategories.setOnClickListener { presentationState.selectTab(KioskTab.CATEGORIES) }
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
            overlay.categoryRecyclerView.adapter = categoryNameAdapter
            overlay.txtCategoryHeader.text = "选择语种"
            catalogActionRouter.languages()
        }
        overlay.btnCategoryTags.setOnClickListener {
            categoryMode = KioskCategoryMode.TAGS
            categoryDetail = false
            focusController.hasInnerDetailBack = false
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
                if (!feedback.isNullOrBlank() && feedback != lastControllerFeedback) {
                    Toast.makeText(activity, feedback, Toast.LENGTH_SHORT).show()
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

    private fun renderCatalogState(state: ControllerUiState) {
        val overlay = binding.kioskOverlay
        val selectedArtist = presentationState.selectedArtist.value

        singerAdapter.submitList(state.artists)
        updateSingerFilterButtons(state.artistGender)
        rankingAdapter.submitList(state.ranking)
        when (categoryMode) {
            KioskCategoryMode.NEW -> categoryAdapter.submitList(state.newSongs)
            KioskCategoryMode.LANGUAGES -> if (categoryDetail) {
                categoryAdapter.submitList(state.catalogSongs)
            } else {
                categoryNameAdapter.submitList(state.languages)
            }
            KioskCategoryMode.TAGS -> if (categoryDetail) {
                categoryAdapter.submitList(state.catalogSongs)
            } else {
                categoryNameAdapter.submitList(state.tags)
            }
        }
        when (presentationState.currentTab.value) {
            KioskTab.FAVORITES -> {
                overlay.personalRecyclerView.adapter = personalSongAdapter
                personalSongAdapter.submitList(state.favorites)
                overlay.txtPersonalHeader.text = if (state.error != null) {
                    "收藏加载失败：${state.message ?: "请稍后重试"}"
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
                    hasError = state.error != null,
                )) {
                    KioskPersonalView.DETAIL_LOADING -> {
                        overlay.personalRecyclerView.adapter = personalSongAdapter
                        personalSongAdapter.submitList(emptyList())
                        overlay.txtPersonalHeader.text = "正在加载歌单详情..."
                    }
                    KioskPersonalView.DETAIL -> {
                        overlay.personalRecyclerView.adapter = personalSongAdapter
                        personalSongAdapter.submitList(state.playlistDetail?.songs.orEmpty())
                        overlay.txtPersonalHeader.text =
                            "歌单 · ${state.playlistDetail?.name.orEmpty()}（${state.playlistDetail?.songs?.size ?: 0} 首）"
                    }
                    KioskPersonalView.DETAIL_ERROR -> {
                        overlay.personalRecyclerView.adapter = personalSongAdapter
                        personalSongAdapter.submitList(emptyList())
                        overlay.txtPersonalHeader.text = "歌单加载失败：${state.message ?: "请稍后重试"}"
                    }
                    KioskPersonalView.LIST -> {
                        overlay.personalRecyclerView.adapter = playlistAdapter
                        playlistAdapter.submitList(state.playlists)
                        overlay.txtPersonalHeader.text = if (state.error != null) {
                            "歌单加载失败：${state.message ?: "请稍后重试"}"
                        } else {
                            "主题歌单（${state.playlists.size} 个）"
                        }
                    }
                }
            }
            KioskTab.HISTORY -> {
                overlay.personalRecyclerView.adapter = personalSongAdapter
                personalSongAdapter.submitList(state.history.mapNotNull { it.song }.distinctBy { it.id })
                overlay.txtPersonalHeader.text = if (state.error != null) {
                    "历史加载失败：${state.message ?: "请稍后重试"}"
                } else {
                    "最近唱过（${state.history.size} 首）"
                }
            }
            else -> Unit
        }
        if (state.artists.isNotEmpty()) artistsLoaded = true
        if (state.ranking.isNotEmpty()) rankingsLoaded = true
        if (state.newSongs.isNotEmpty()) categoriesLoaded = true

        when {
            state.query.isNotBlank() -> {
                searchAdapter.submitList(state.results)
                overlay.txtSearchCount.text = when {
                    state.loading -> "正在检索: ${state.query} ..."
                    state.error != null -> "检索失败：${state.message ?: "请稍后重试"}"
                    else -> "找到 ${state.results.size} 首歌曲"
                }
            }
            selectedArtist != null -> {
                searchAdapter.submitList(state.catalogSongs)
                overlay.txtSearchCount.text = if (state.error != null) {
                    "歌手【$selectedArtist】加载失败：${state.message ?: "请稍后重试"}"
                } else {
                    "歌手【$selectedArtist】共 ${state.catalogSongs.size} 首"
                }
            }
            else -> {
                searchAdapter.submitList(state.ranking)
                if (state.error != null && presentationState.currentTab.value == KioskTab.PINYIN) {
                    overlay.txtSearchCount.text = "推荐加载失败：${state.message ?: "请稍后重试"}"
                }
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

        updateTabBtn(overlay.tabDashboard, tab == KioskTab.DASHBOARD)
        updateTabBtn(overlay.tabPinyin, tab == KioskTab.PINYIN)
        updateTabBtn(overlay.tabSingers, tab == KioskTab.SINGERS)
        updateTabBtn(overlay.tabRankings, tab == KioskTab.RANKINGS)
        updateTabBtn(overlay.tabCategories, tab == KioskTab.CATEGORIES)
        updateTabBtn(overlay.tabFavorites, tab == KioskTab.FAVORITES)
        updateTabBtn(overlay.tabPlaylists, tab == KioskTab.PLAYLISTS)
        updateTabBtn(overlay.tabHistory, tab == KioskTab.HISTORY)
        updateTabBtn(overlay.btnCategoryNew, categoryMode == KioskCategoryMode.NEW && !categoryDetail)
        updateTabBtn(overlay.btnCategoryLanguages, categoryMode == KioskCategoryMode.LANGUAGES)
        updateTabBtn(overlay.btnCategoryTags, categoryMode == KioskCategoryMode.TAGS)

        overlay.kioskNavTabs.visibility = if (tab == KioskTab.DASHBOARD) View.GONE else View.VISIBLE
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

        when (tab) {
            KioskTab.DASHBOARD -> Unit
            KioskTab.PINYIN -> Unit
            KioskTab.SINGERS -> if (!artistsLoaded) loadArtists()
            KioskTab.RANKINGS -> if (!rankingsLoaded) loadRankings()
            KioskTab.CATEGORIES -> if (!categoriesLoaded) loadCategories()
            KioskTab.FAVORITES -> loadFavorites()
            KioskTab.PLAYLISTS -> loadPlaylists()
            KioskTab.HISTORY -> loadHistory()
        }
    }

    private fun onKeywordInput(keyword: String) {
        if (keyword.isBlank()) {
            binding.kioskOverlay.txtSearchCount.text = "输入首字母检索曲目"
            loadDefaultSongs()
            return
        }

        binding.kioskOverlay.txtSearchCount.text = "正在检索: $keyword ..."
        catalogActionRouter.search(keyword)
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

    private fun onArtistSelected(artist: ArtistItem) {
        coordinator.resetIdleTimer()
        focusController.hasInnerDetailBack = true
        presentationState.selectArtist(artist.name)
        binding.kioskOverlay.kioskKeyboard.clear()
        binding.kioskOverlay.txtSearchCount.text = "正在加载【${artist.name}】的歌曲..."
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
        binding.kioskOverlay.txtPersonalHeader.text = "正在加载最近唱过..."
        personalActionRouter.history()
    }

    private fun openPlaylist(playlist: com.homektv.tv.net.PlaylistSummary) {
        coordinator.resetIdleTimer()
        playlistDetail = true
        focusController.hasInnerDetailBack = true
        binding.kioskOverlay.personalRecyclerView.adapter = personalSongAdapter
        binding.kioskOverlay.txtPersonalHeader.text = "正在加载歌单 · ${playlist.name}..."
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

        if (cachedQrBitmap == null) {
            activity.lifecycleScope.launch {
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
                    dialog.updateQrBitmap(bmp)
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
            if (presentationState.currentTab.value == KioskTab.DASHBOARD) {
                binding.kioskOverlay.kioskDashboardView.requestDashboardFocus()
            } else {
                binding.kioskOverlay.tabPinyin.requestFocus()
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
        val state = KioskPipDisplayPolicy.resolve(
            externalDisplayActive = externalDisplayActive,
            playbackState = currentSnapshot?.state,
            hasPlaying = currentSnapshot?.playing != null,
        )
        binding.kioskOverlay.pipVideoFrame.visibility = if (state.videoVisible) View.VISIBLE else View.GONE
        binding.kioskOverlay.txtPipLabel.text = state.label
    }

    fun destroy() {
        queueDialog?.dismiss()
        queueDialog = null
        qrDialog?.dismiss()
        qrDialog = null
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
        focusController.hasInnerDetailBack = false
        presentationState.clearArtistSelection()
        presentationState.selectTab(KioskTab.SINGERS)
        binding.kioskOverlay.kioskKeyboard.clear()
        binding.kioskOverlay.txtSearchCount.text = "输入首字母检索曲目"
        loadDefaultSongs()
        binding.kioskOverlay.singerRecyclerView.post {
            binding.kioskOverlay.singerRecyclerView.requestFocus()
        }
    }

    private fun resetCategoryDetail() {
        categoryDetail = false
        focusController.hasInnerDetailBack = false
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
                        } else {
                            val hasPlaying = currentSnapshot?.playing != null || currentSnapshot?.state == "playing"
                            val action = KioskLifecyclePolicy.resolveDashboardBackAction(hasPlaying)
                            if (action == KioskBackAction.RETURN_TO_FULLSCREEN_MV) {
                                toggleKiosk(false)
                            } else {
                                Toast.makeText(activity, "当前已在大厅首页，再次按返回键退出应用", Toast.LENGTH_SHORT).show()
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
