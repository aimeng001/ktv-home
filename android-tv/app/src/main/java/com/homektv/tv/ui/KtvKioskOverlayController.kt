package com.homektv.tv.ui

import android.graphics.Bitmap
import android.util.TypedValue
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.Player
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.homektv.tv.controller.ControllerActions
import com.homektv.tv.controller.ControllerCatalogActions
import com.homektv.tv.controller.ControllerCatalogVisibility
import com.homektv.tv.controller.ControllerConnection
import com.homektv.tv.controller.ControllerPersonalActions
import com.homektv.tv.controller.ControllerStateReducer
import com.homektv.tv.controller.ControllerUiState
import com.homektv.tv.controller.KioskCatalogActionRouter
import com.homektv.tv.controller.KioskPersonalActionRouter
import com.homektv.tv.controller.KioskQueueActionRouter
import com.homektv.tv.R
import com.homektv.tv.databinding.ActivityMainBinding
import com.homektv.tv.databinding.ViewKtvKioskOverlayBinding
import com.homektv.tv.databinding.ViewKtvThreeColumnBrowserBinding
import com.homektv.tv.net.AppConfig
import com.homektv.tv.net.ArtistItem
import com.homektv.tv.net.KtvApiResult
import com.homektv.tv.net.KtvHttpTransport
import com.homektv.tv.net.QueueSnapshot
import com.homektv.tv.net.SongDto
import com.homektv.tv.ui.kiosk.BrowserRailOptionSpec
import com.homektv.tv.ui.kiosk.BrowserRailSyncPolicy
import com.homektv.tv.ui.kiosk.KtvSingerFilterPolicy
import com.homektv.tv.ui.kiosk.KtvSingerRegionFilter
import com.homektv.tv.ui.kiosk.KtvSingerRegionPolicy
import com.homektv.tv.ui.kiosk.KioskCategoryLoadPolicy
import com.homektv.tv.ui.kiosk.KioskCommercialTagPolicy
import com.homektv.tv.ui.kiosk.KioskCategoryMode
import com.homektv.tv.ui.kiosk.KtvDashboardPhotoCarousel
import com.homektv.tv.ui.kiosk.KtvGlobalSearchPolicy
import com.homektv.tv.ui.kiosk.T9PinyinSearchPolicy
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
    private val bottomDockController: KtvBottomDockController,
    private val coordinator: KioskModeCoordinator,
    private val focusController: KtvFocusController,
    private val controllerActions: ControllerActions,
    private val catalogActions: ControllerCatalogActions,
    private val personalActions: ControllerPersonalActions,
    private val shouldExitOnBack: () -> Boolean = { true },
    initialExternalDisplayActive: Boolean = false,
    private val globalSearchEnabled: () -> Boolean = { true },
) {

    private val ActivityMainBinding.kioskOverlay: ViewKtvKioskOverlayBinding
        get() = this@KtvKioskOverlayController.kioskOverlayBinding

    private val config = AppConfig(activity)
    private val transport = KtvHttpTransport(config)
    private val catalogActionRouter = KioskCatalogActionRouter(catalogActions)
    private val personalActionRouter = KioskPersonalActionRouter(personalActions)
    private val queueActionRouter = KioskQueueActionRouter(controllerActions)
    private val transientFeedbackHost = KioskTransientFeedbackHost(
        kioskOverlayBinding.kioskTransientFeedback,
        activity.lifecycleScope,
    )
    private val pipObservedPlayer = bottomDockController.playerView.player
    private var queueDrawerVisible = false
    private var globalSearchReturnFocus: View? = null
    private val pipPlaybackListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            renderPipState()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            renderPipState()
        }
    }

    val presentationState = KioskPresentationState(initialTab = KioskTab.DASHBOARD)
    private val backExitGate = com.homektv.tv.navigation.BackExitGate()

    private val browserSongAdapter = KtvKioskBrowserSongAdapter(
        onOrder = ::orderSong,
        onOrderTop = ::orderSongTop,
        onToggleFavorite = personalActionRouter::toggleFavorite,
        imageLoader = ::loadSingerAvatar,
    )
    private val singerAvatarCache = WeightedLruCache<String, android.graphics.Bitmap>(
        capacity = 128,
        maxBytes = 16L * 1024L * 1024L,
        weight = { it.allocationByteCount.toLong() },
    )
    private val pendingAvatarCallbacks = mutableMapOf<String, MutableList<(android.graphics.Bitmap?) -> Unit>>()

    private val singerAdapter = SingerCardAdapter(
        onArtistClick = ::onArtistSelected,
        imageLoader = ::loadSingerAvatar,
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
    private var browsingAllSongs = false
    private var pendingBrowserPageTarget: Int? = null
    private var pendingSingerPageTarget: Int? = null
    private var pendingCommercialTagRoute: KioskCommercialRoute? = null
    private var pendingCommercialTagRevision: Long? = null
    // Hold the activation focus intent through async content refreshes. The first
    // explicit D-pad direction from a route capsule ends the hold.
    private var pendingCommercialRouteFocus: KioskCommercialRoute? = null
    private var commercialRouteNotice: String? = null
    private val categoryLoadPolicy = KioskCategoryLoadPolicy()
    private var categoryMode = KioskCategoryMode.TAGS
    private var lastRequestedCategoryMode: KioskCategoryMode? = null
    private var categoryDetail = false
    private var playlistDetail = false
    private var pendingAutoSelectFirstPlaylist = false
    private var skipNextDashboardAutoFocus = false
    private var externalDisplayActive = initialExternalDisplayActive
    private var activeFilterOptionsCollector: MutableList<BrowserRailOptionSpec>? = null
    private lateinit var browserBinding: ViewKtvThreeColumnBrowserBinding

    init {
        pipObservedPlayer?.addListener(pipPlaybackListener)
        setupViews()
        observeState()
    }

    private fun setupViews() {
        val overlay = binding.kioskOverlay
        browserBinding = ViewKtvThreeColumnBrowserBinding.inflate(
            LayoutInflater.from(activity),
            overlay.kioskMainContent,
            false,
        )
        browserBinding.root.visibility = View.GONE
        overlay.kioskMainContent.addView(
            browserBinding.root,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        // 1. 列表布局绑定
        overlay.singerRecyclerView.layoutManager = LinearLayoutManager(activity)
        overlay.singerRecyclerView.adapter = singerAdapter

        browserBinding.browserResultsRecyclerView.layoutManager = GridLayoutManager(activity, 3)
        browserBinding.browserResultsRecyclerView.adapter = browserSongAdapter
        browserBinding.browserResultsRecyclerView.addOnLayoutChangeListener { view, _, top, _, bottom, _, _, _, _ ->
            val availableHeight = (bottom - top) - view.paddingTop
            if (availableHeight <= 0) return@addOnLayoutChangeListener
            val rowStepPx = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                50f,
                view.resources.displayMetrics,
            ).roundToInt().coerceAtLeast(1)
            val visibleRows = (availableHeight / rowStepPx).coerceIn(1, 5)
            val desiredPaddingBottom = (availableHeight - visibleRows * rowStepPx).coerceAtLeast(0)
            if (view.paddingBottom != desiredPaddingBottom) {
                view.post {
                    if (view.paddingBottom != desiredPaddingBottom) {
                        view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, desiredPaddingBottom)
                    }
                }
            }
        }
        browserBinding.browserSingerRecyclerView.layoutManager = GridLayoutManager(activity, 8)
        browserBinding.browserSingerRecyclerView.adapter = singerAdapter
        bindCommercialCapsuleDpadNavigation()
        installPaging(browserBinding.browserSingerRecyclerView) {
            catalogActions.loadMoreCatalog()
        }
        reparentView(overlay.kioskKeyboard, browserBinding.browserKeyboardContainer)
        reparentView(overlay.singerRecyclerView, browserBinding.browserSingerListContainer)
        val browserSingerFilters = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        moveIntoLinearLayout(
            overlay.singerFilterRow,
            browserSingerFilters,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        moveIntoLinearLayout(
            overlay.txtSingerStatus,
            browserSingerFilters,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        moveIntoLinearLayout(
            overlay.btnSingerRetry,
            browserSingerFilters,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        browserBinding.browserSingerFilterContainer.addView(
            browserSingerFilters,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

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
                region = controllerState.artistRegion,
            )
        }

        installPaging(overlay.singerRecyclerView) {
            catalogActions.loadMoreCatalog()
        }
        installPaging(browserBinding.browserResultsRecyclerView) {
            if (browsingAllSongs && presentationState.currentTab.value == KioskTab.PINYIN) {
                catalogActionRouter.moreAllSongs()
            } else if (presentationState.selectedArtist.value != null || categoryDetail) {
                catalogActions.loadMoreCatalog()
            } else if (presentationState.currentTab.value == KioskTab.PINYIN) {
                catalogActions.loadMoreSearch()
            }
        }

        // 2. 磁贴与三栏路由绑定
        overlay.kioskDashboardView.onTileSelected = { tile ->
            coordinator.resetIdleTimer()
            overlay.kioskDashboardView.lastFocusedTile = tile
            showVarietyPanel(false)
            when (tile) {
                com.homektv.tv.ui.kiosk.KtvDashboardTile.HOT_RANKING -> {
                    openCommercialRoute(KioskCommercialRoute.HOT_NEW)
                    browserBinding.btnTabHotNew.post { browserBinding.btnTabHotNew.requestFocus() }
                }
                com.homektv.tv.ui.kiosk.KtvDashboardTile.NEW_SONGS -> {
                    openCommercialRoute(KioskCommercialRoute.FEATURED)
                    browserBinding.btnTabFeatured.post { browserBinding.btnTabFeatured.requestFocus() }
                }
                com.homektv.tv.ui.kiosk.KtvDashboardTile.SONG_NAME -> {
                    openCommercialRoute(KioskCommercialRoute.SONG_NAME)
                    browserBinding.btnTabSongName.post { browserBinding.btnTabSongName.requestFocus() }
                }
                com.homektv.tv.ui.kiosk.KtvDashboardTile.LOCAL -> {
                    openCommercialRoute(KioskCommercialRoute.LOCAL)
                    browserBinding.btnTabLocal.post { browserBinding.btnTabLocal.requestFocus() }
                }
                com.homektv.tv.ui.kiosk.KtvDashboardTile.SINGER -> openCommercialRoute(KioskCommercialRoute.SINGERS)
                com.homektv.tv.ui.kiosk.KtvDashboardTile.TIKTOK -> openCommercialRoute(KioskCommercialRoute.TIKTOK)
                com.homektv.tv.ui.kiosk.KtvDashboardTile.THEME -> openCommercialRoute(KioskCommercialRoute.THEMES)
                com.homektv.tv.ui.kiosk.KtvDashboardTile.OPERA -> openCommercialRoute(KioskCommercialRoute.VARIETY)
                com.homektv.tv.ui.kiosk.KtvDashboardTile.LANGUAGE -> openCommercialRoute(KioskCommercialRoute.LANGUAGES)
                com.homektv.tv.ui.kiosk.KtvDashboardTile.FAVORITES -> openCommercialRoute(KioskCommercialRoute.FAVORITES)
            }
        }

        browserBinding.browserNavHome.setOnClickListener {
            presentationState.selectTab(KioskTab.DASHBOARD)
        }
        browserBinding.browserNavSingers.setOnClickListener {
            focusController.hasInnerDetailBack = false
            presentationState.selectTab(KioskTab.SINGERS)
        }
        browserBinding.browserNavLanguages.setOnClickListener { openCategoryRoot(KioskCategoryMode.LANGUAGES) }
        browserBinding.browserNavTags.setOnClickListener { openCategoryRoot(KioskCategoryMode.TAGS) }
        browserBinding.browserNavRankings.setOnClickListener { presentationState.selectTab(KioskTab.RANKINGS) }
        browserBinding.browserNavPlaylists.setOnClickListener { presentationState.selectTab(KioskTab.PLAYLISTS) }
        browserBinding.browserNavSearch.setOnClickListener { selectPinyinTab() }
        browserBinding.browserNavFavorites.setOnClickListener { presentationState.selectTab(KioskTab.FAVORITES) }
        browserBinding.browserNavHistory.setOnClickListener { presentationState.selectTab(KioskTab.HISTORY) }

        // Bind all 11 reference destinations to Home KTV's real data families.
        browserBinding.btnTabSongName.setOnClickListener {
            openCommercialRoute(KioskCommercialRoute.SONG_NAME)
        }
        browserBinding.btnTabHd.setOnClickListener { openCommercialRoute(KioskCommercialRoute.HIGH_DEFINITION) }
        browserBinding.btnTabSingers.setOnClickListener { openCommercialRoute(KioskCommercialRoute.SINGERS) }
        browserBinding.btnTabHotNew.setOnClickListener { openCommercialRoute(KioskCommercialRoute.HOT_NEW) }
        browserBinding.btnTabFeatured.setOnClickListener { openCommercialRoute(KioskCommercialRoute.FEATURED) }
        browserBinding.btnTabLocal.setOnClickListener { openCommercialRoute(KioskCommercialRoute.LOCAL) }
        browserBinding.btnTabFavorites.setOnClickListener { openCommercialRoute(KioskCommercialRoute.FAVORITES) }
        browserBinding.btnTabLanguages.setOnClickListener { openCommercialRoute(KioskCommercialRoute.LANGUAGES) }
        browserBinding.btnTabTopics.setOnClickListener { openCommercialRoute(KioskCommercialRoute.THEMES) }
        browserBinding.btnTabVariety.setOnClickListener { openCommercialRoute(KioskCommercialRoute.VARIETY) }
        browserBinding.btnTabTikTok.setOnClickListener { openCommercialRoute(KioskCommercialRoute.TIKTOK) }

        // 底部分页栏与返回键绑定
        browserBinding.btnBrowserBack.setOnClickListener {
            when (
                KioskBackNavigationPolicy.resolveBrowserBackTarget(
                    hasInnerDetailBack = focusController.hasInnerDetailBack,
                    hasPendingSearchReturnFocus = globalSearchReturnFocus != null,
                )
            ) {
                KioskBrowserReturnTarget.INNER_DETAIL -> resetInnerDetail()
                KioskBrowserReturnTarget.SEARCH_ORIGIN,
                KioskBrowserReturnTarget.DASHBOARD -> returnToDashboardFromBrowser()
            }
        }
        browserBinding.btnPagePrev.setOnClickListener {
            scrollResultsPage(-1)
        }
        browserBinding.btnPageNext.setOnClickListener {
            scrollResultsPage(1)
        }

        // 8 大综艺台标快捷点击
        browserBinding.varietyVoice.setOnClickListener { selectVarietyProgram("中国好声音") }
        browserBinding.varietySinger.setOnClickListener { selectVarietyProgram("我是歌手") }
        browserBinding.varietyHarmony.setOnClickListener { selectVarietyProgram("最美和声") }
        browserBinding.varietyStrong.setOnClickListener { selectVarietyProgram("中国最强音") }
        browserBinding.varietyDream.setOnClickListener { selectVarietyProgram("中国梦之声") }
        browserBinding.varietyMad.setOnClickListener { selectVarietyProgram("我为歌狂") }
        browserBinding.varietyStar.setOnClickListener { selectVarietyProgram("全能星战") }
        browserBinding.varietySong.setOnClickListener { selectVarietyProgram("中国好歌曲") }

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
    }

    /** Keeps left/right remote navigation inside the 11-route capsule row. */
    private fun bindCommercialCapsuleDpadNavigation() {
        val buttons = listOf(
            browserBinding.btnTabSongName,
            browserBinding.btnTabHd,
            browserBinding.btnTabSingers,
            browserBinding.btnTabHotNew,
            browserBinding.btnTabFeatured,
            browserBinding.btnTabLocal,
            browserBinding.btnTabFavorites,
            browserBinding.btnTabLanguages,
            browserBinding.btnTabTopics,
            browserBinding.btnTabVariety,
            browserBinding.btnTabTikTok,
        )
        buttons.forEachIndexed { index, button ->
            button.setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus && pendingCommercialRouteFocus == presentationState.commercialRoute.value) {
                    val focusedView = activity.currentFocus
                    if (focusedView?.isShown == true && focusedView.isFocusable) {
                        pendingCommercialRouteFocus = null
                    }
                }
            }
            button.setOnKeyListener { _, keyCode, event ->
                val isVertical = keyCode == KeyEvent.KEYCODE_DPAD_UP ||
                    keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                if (isVertical) {
                    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                        pendingCommercialRouteFocus = null
                    }
                    return@setOnKeyListener false
                }
                val isHorizontal = keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
                    keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                if (!isHorizontal) return@setOnKeyListener false
                if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount > 0) {
                    return@setOnKeyListener true
                }
                pendingCommercialRouteFocus = null

                val targetIndex = if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                    (index - 1 + buttons.size) % buttons.size
                } else {
                    (index + 1) % buttons.size
                }
                buttons[targetIndex].requestRemoteFocus()
                true
            }
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
                if (!state.queueLoading && state.errorFor(com.homektv.tv.controller.UiDomain.QUEUE) == null) {
                    updateSnapshot(state.queue, state.queueProjection)
                }
                queueDialog?.updateActor(
                    currentUserId = state.currentUser?.id,
                    isHost = state.roomHost.isHost,
                )
                queueDialog?.updatePendingActions(state.pendingActions)
                renderQueueDrawerState(state)

                val feedback = state.message
                val inlineFeedback = inlineFeedbackForCurrentPage(state)
                when (KioskFeedbackRoutingPolicy.resolve(
                    message = feedback,
                    inlineErrorMessage = inlineFeedback?.first,
                    inlineErrorIsLatest = inlineFeedback?.second == true,
                )) {
                    KioskFeedbackDestination.TRANSIENT -> if (feedback != null && feedback != lastControllerFeedback) {
                        if (presentationState.currentTab.value == KioskTab.DASHBOARD &&
                            feedback == "无法连接点歌服务"
                        ) {
                            transientFeedbackHost.clear()
                        } else {
                            transientFeedbackHost.show(feedback)
                        }
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
            override fun onScrollStateChanged(
                recyclerView: androidx.recyclerview.widget.RecyclerView,
                newState: Int,
            ) {
                if (newState == androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_IDLE) {
                    updatePageIndicator()
                }
            }

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
        val emptyStateVisible = browserBinding.txtBrowserEmptyState.visibility == View.VISIBLE
        val domain = when (tab) {
            KioskTab.CATEGORIES -> if (!categoryDetail && categoryMode != KioskCategoryMode.NEW && emptyStateVisible) {
                com.homektv.tv.controller.UiDomain.CATALOG
            } else null
            KioskTab.SINGERS -> if (overlay.txtSingerStatus.visibility == View.VISIBLE) {
                com.homektv.tv.controller.UiDomain.CATALOG
            } else null
            KioskTab.PINYIN -> when {
                presentationState.selectedArtist.value != null && emptyStateVisible ->
                    com.homektv.tv.controller.UiDomain.CATALOG
                state.query.isNotBlank() && emptyStateVisible ->
                    com.homektv.tv.controller.UiDomain.SEARCH
                else -> null
            }
            KioskTab.FAVORITES -> if (emptyStateVisible) {
                com.homektv.tv.controller.UiDomain.FAVORITES
            } else null
            KioskTab.HISTORY -> if (emptyStateVisible) {
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

    private fun loadSingerAvatar(url: String, callback: (Bitmap?) -> Unit) {
        val cached = singerAvatarCache.get(url)
        if (cached != null) {
            callback(cached)
            return
        }

        val subscribers = pendingAvatarCallbacks.getOrPut(url) { mutableListOf() }
        subscribers.add(callback)
        if (!singerAvatarCache.tryStartLoad(url)) return

        activity.lifecycleScope.launch {
            val bytes = withContext(Dispatchers.IO) {
                val response = transport.getBytes(url)
                (response as? KtvApiResult.Success)?.value
            }
            val bitmap = bytes?.let {
                withContext(Dispatchers.Default) {
                    ArtworkDecoder.decode(it, ArtworkProfile.AVATAR)
                }
            }
            val waiting = pendingAvatarCallbacks.remove(url).orEmpty()
            if (bitmap != null) {
                singerAvatarCache.complete(url, bitmap)
                waiting.forEach { it(bitmap) }
            } else {
                singerAvatarCache.fail(url)
                waiting.forEach { it(null) }
            }
        }
    }

    private fun renderCatalogState(state: ControllerUiState) {
        val overlay = binding.kioskOverlay
        val selectedArtist = presentationState.selectedArtist.value

        singerAdapter.submitList(state.artists) {
            val pendingTarget = pendingSingerPageTarget
            if (pendingTarget != null && pendingTarget < state.artists.size) {
                pendingSingerPageTarget = null
                browserBinding.browserSingerRecyclerView.scrollToPosition(pendingTarget)
                updatePageIndicator(state)
            }
        }
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
        if (state.artists.isNotEmpty()) {
            artistsLoaded = true
        }
        if (state.ranking.isNotEmpty()) rankingsLoaded = true
        if (state.newSongs.isNotEmpty()) categoryLoadPolicy.markLoaded(KioskCategoryMode.NEW)
        if (state.languages.isNotEmpty()) categoryLoadPolicy.markLoaded(KioskCategoryMode.LANGUAGES)
        if (state.tags.isNotEmpty()) categoryLoadPolicy.markLoaded(KioskCategoryMode.TAGS)

        renderThreeColumnBrowser(state)
    }

    private fun renderThreeColumnBrowser(state: ControllerUiState) {
        val tab = presentationState.currentTab.value
        if (!isThreeColumnTab(tab)) return
        val browserHadFocusBeforeRender = browserBinding.root.hasFocus()
        resolvePendingCommercialTagRoute(state)

        val browser = browserBinding
        val selectedArtist = presentationState.selectedArtist.value
        val navButtons = listOf(
            browser.browserNavSingers to (tab == KioskTab.SINGERS),
            browser.browserNavLanguages to (tab == KioskTab.CATEGORIES && categoryMode == KioskCategoryMode.LANGUAGES),
            browser.browserNavTags to (tab == KioskTab.CATEGORIES && categoryMode != KioskCategoryMode.LANGUAGES),
            browser.browserNavRankings to (tab == KioskTab.RANKINGS),
            browser.browserNavPlaylists to (tab == KioskTab.PLAYLISTS),
            browser.browserNavSearch to (tab == KioskTab.PINYIN),
            browser.browserNavFavorites to (tab == KioskTab.FAVORITES),
            browser.browserNavHistory to (tab == KioskTab.HISTORY),
        )
        navButtons.forEach { (button, selected) ->
            button.setBackgroundResource(if (selected) R.drawable.btn_gold else R.drawable.bg_ktv_card_focused)
            button.setTextColor(
                if (selected) 0xFF111317.toInt()
                else ContextCompat.getColor(activity, R.color.color_keyboard_key_text),
            )
        }
        navButtons.firstOrNull { it.second }?.first?.let { selectedButton ->
            browser.browserNavigationScroll.post {
                val scroll = browser.browserNavigationScroll
                if (scroll.height == 0 || selectedButton.width == 0 || selectedButton.height == 0) {
                    return@post
                }

                val scrollLocation = IntArray(2)
                val buttonLocation = IntArray(2)
                scroll.getLocationOnScreen(scrollLocation)
                selectedButton.getLocationOnScreen(buttonLocation)
                val viewportTop = scrollLocation[1] + scroll.paddingTop
                // Align the active category at the top of the rail. This keeps
                // the selected row fully visible and prevents the preceding row
                // from being left partially clipped at the viewport edge.
                val scrollDelta = buttonLocation[1] - viewportTop
                if (scrollDelta != 0) scroll.scrollBy(0, scrollDelta)
            }
        }

        renderAvailableVarietyPrograms(state.tags)
        val isVariety = browser.panelVarietyShows.visibility == View.VISIBLE
        val shouldShowKeyboard = KtvGlobalSearchPolicy.shouldShowKeyboard(
            tab = tab,
            isVariety = isVariety,
            enabled = globalSearchEnabled(),
        )
        applyKeyboardVisibility(shouldShowKeyboard)
        browser.browserFilterScroll.visibility = View.GONE
        browser.browserSingerPanel.visibility = View.GONE
        browser.txtBrowserCenterHint.visibility = View.GONE
        browser.browserInitialRail.visibility = View.VISIBLE
        val stagedFilterOptions = mutableListOf<BrowserRailOptionSpec>()
        activeFilterOptionsCollector = stagedFilterOptions

        val visibleSongs = when (tab) {
            KioskTab.PINYIN -> when {
                selectedArtist != null -> state.catalogSongs
                state.query.isNotBlank() -> state.results
                browsingAllSongs -> state.kioskLibraryBrowse.songs
                state.ranking.isNotEmpty() -> state.ranking
                else -> state.newSongs
            }
            KioskTab.SINGERS -> if (selectedArtist != null) state.catalogSongs else emptyList()
            KioskTab.RANKINGS -> state.ranking
            KioskTab.CATEGORIES -> when {
                categoryDetail -> state.catalogSongs
                categoryMode == KioskCategoryMode.NEW -> state.newSongs
                else -> emptyList()
            }
            KioskTab.FAVORITES -> state.favorites
            KioskTab.PLAYLISTS -> if (playlistDetail) state.playlistDetail?.songs.orEmpty() else emptyList()
            KioskTab.HISTORY -> state.history.mapNotNull { it.song }.distinctBy { it.id }
            else -> emptyList()
        }

        browserSongAdapter.setSearchKeyword(
            if (tab == KioskTab.PINYIN && selectedArtist == null) state.query else "",
        )
        browserSongAdapter.updateQueueProjection(state.queueProjection)
        browserSongAdapter.updateFavoriteIds(state.favoriteIds)
        browserSongAdapter.submitList(visibleSongs) {
            val pendingTarget = pendingBrowserPageTarget
            if (pendingTarget != null && pendingTarget < browserSongAdapter.itemCount) {
                pendingBrowserPageTarget = null
                browser.browserResultsRecyclerView.scrollToPosition(pendingTarget)
                updatePageIndicator()
            }
        }

        val isSingersRoot = (tab == KioskTab.SINGERS && selectedArtist == null)
        if (isSingersRoot) {
            val catalogError = state.errorFor(com.homektv.tv.controller.UiDomain.CATALOG)
            val hasSingerRows = state.artists.isNotEmpty()
            val regionFilterUnconfirmed = state.catalogStatus.code == "ARTIST_REGION_FILTER_UNCONFIRMED"
            val retrySingerList = !hasSingerRows && !state.catalogLoading &&
                (catalogError != null || regionFilterUnconfirmed)
            browser.browserSingerRecyclerView.visibility = if (hasSingerRows) View.VISIBLE else View.GONE
            browser.browserResultsRecyclerView.visibility = View.GONE
            browser.txtBrowserEmptyState.visibility = if (hasSingerRows) View.GONE else View.VISIBLE
            browser.txtBrowserEmptyState.text = when {
                state.catalogLoading -> "正在加载歌手列表…"
                regionFilterUnconfirmed -> state.catalogStatus.message ?: "地区筛选尚未确认，未显示未过滤结果"
                catalogError != null -> "歌手列表加载失败，按确定重试"
                else -> "当前没有可用歌手"
            }
            browser.txtBrowserEmptyState.isFocusable = retrySingerList
            browser.txtBrowserEmptyState.setOnClickListener {
                if (retrySingerList) {
                    catalogActions.loadArtists(
                        gender = state.artistGender,
                        initial = state.artistInitial,
                        restorePage = 0,
                        region = state.artistRegion,
                    )
                }
            }
            browser.txtBrowserEmptyState.contentDescription = when {
                regionFilterUnconfirmed && retrySingerList -> "${state.catalogStatus.message}，按确定重试"
                retrySingerList -> "歌手列表加载失败，按确定重试"
                else -> null
            }
            browser.btnPagePrev.visibility = if (hasSingerRows) View.VISIBLE else View.GONE
            browser.txtPageNumber.visibility = if (hasSingerRows) View.VISIBLE else View.GONE
            browser.btnPageNext.visibility = if (hasSingerRows) View.VISIBLE else View.GONE
            browser.btnBrowserBack.visibility = View.VISIBLE
        } else if (visibleSongs.isEmpty()) {
            browser.browserSingerRecyclerView.visibility = View.GONE
            browser.browserResultsRecyclerView.visibility = View.GONE
            browser.txtBrowserEmptyState.visibility = View.VISIBLE
            browser.txtBrowserEmptyState.text = when {
                tab == KioskTab.FAVORITES -> "您还没有收藏过歌曲"
                tab == KioskTab.PINYIN && browsingAllSongs && state.kioskLibraryBrowse.loading -> "正在加载全曲库…"
                tab == KioskTab.PINYIN && browsingAllSongs && state.kioskLibraryBrowse.error != null -> "全曲库加载失败，按确定重试"
                tab == KioskTab.PINYIN && browsingAllSongs -> "当前曲库暂无可点歌曲"
                tab == KioskTab.PINYIN && state.query.isBlank() && state.catalogLoading -> "正在加载精选歌曲…"
                tab == KioskTab.PINYIN && state.query.isBlank() -> "暂无精选推荐歌曲"
                tab == KioskTab.PINYIN && state.loading -> "正在搜索歌曲…"
                tab == KioskTab.PINYIN && state.query.isNotBlank() -> "未找到相关歌曲"
                tab == KioskTab.CATEGORIES && categoryDetail && state.catalogLoading -> "正在加载分类歌曲…"
                tab == KioskTab.CATEGORIES && categoryDetail && state.errorFor(com.homektv.tv.controller.UiDomain.CATALOG) != null ->
                    "分类歌曲加载失败，按确定重试"
                tab == KioskTab.CATEGORIES && !categoryDetail -> commercialRouteNotice ?: "选择一个分类查看歌曲"
                tab == KioskTab.PLAYLISTS && (state.playlistDetailLoading ||
                    (pendingAutoSelectFirstPlaylist && state.playlists.isNotEmpty())) -> "正在加载精选歌单…"
                tab == KioskTab.PLAYLISTS && state.errorFor(com.homektv.tv.controller.UiDomain.PLAYLISTS) != null ->
                    "精选歌单加载失败，按确定重试"
                tab == KioskTab.PLAYLISTS && state.playlists.isEmpty() -> "暂无精选歌单"
                else -> "暂无歌曲"
            }
            val retryAllSongs = tab == KioskTab.PINYIN && browsingAllSongs &&
                state.kioskLibraryBrowse.error != null
            val retryCategory = tab == KioskTab.CATEGORIES && categoryDetail &&
                state.errorFor(com.homektv.tv.controller.UiDomain.CATALOG) != null && state.catalogValue.isNotBlank()
            val retryPlaylists = tab == KioskTab.PLAYLISTS &&
                state.errorFor(com.homektv.tv.controller.UiDomain.PLAYLISTS) != null
            browser.txtBrowserEmptyState.isFocusable = retryAllSongs || retryCategory || retryPlaylists
            browser.txtBrowserEmptyState.setOnClickListener {
                when {
                    retryAllSongs -> catalogActionRouter.allSongs()
                    retryCategory && categoryMode == KioskCategoryMode.LANGUAGES -> catalogActionRouter.languageSongs(state.catalogValue)
                    retryCategory -> catalogActionRouter.tagSongs(state.catalogValue)
                    retryPlaylists -> loadPlaylists()
                }
            }
            browser.txtBrowserEmptyState.contentDescription = when {
                retryAllSongs -> "全曲库加载失败，按确定重试"
                retryCategory -> "分类歌曲加载失败，按确定重试"
                retryPlaylists -> "精选歌单加载失败，按确定重试"
                else -> null
            }
            browser.btnPagePrev.visibility = View.INVISIBLE
            browser.txtPageNumber.visibility = View.INVISIBLE
            browser.btnPageNext.visibility = View.INVISIBLE
            browser.btnBrowserBack.visibility = View.VISIBLE
        } else {
            browser.browserSingerRecyclerView.visibility = View.GONE
            browser.browserResultsRecyclerView.visibility = View.VISIBLE
            browser.txtBrowserEmptyState.visibility = View.GONE
            browser.txtBrowserEmptyState.isFocusable = false
            browser.txtBrowserEmptyState.setOnClickListener(null)
            browser.txtBrowserEmptyState.contentDescription = null
            browser.btnPagePrev.visibility = View.VISIBLE
            browser.txtPageNumber.visibility = View.VISIBLE
            browser.btnPageNext.visibility = View.VISIBLE
            browser.btnBrowserBack.visibility = View.VISIBLE
        }

        val keyboardContentTarget = when (KtvGlobalSearchPolicy.keyboardContentTarget(
            tab = tab,
            singerListVisible = browser.browserSingerRecyclerView.visibility == View.VISIBLE,
            songResultsVisible = browser.browserResultsRecyclerView.visibility == View.VISIBLE,
        )) {
            KtvGlobalSearchPolicy.KeyboardContentTarget.SINGER_LIST -> browser.browserSingerRecyclerView
            KtvGlobalSearchPolicy.KeyboardContentTarget.SONG_RESULTS -> browser.browserResultsRecyclerView
            KtvGlobalSearchPolicy.KeyboardContentTarget.EMPTY_STATE -> browser.txtBrowserEmptyState
        }
        if (shouldShowKeyboard && keyboardContentTarget === browser.txtBrowserEmptyState) {
            browser.txtBrowserEmptyState.isFocusable = true
        }
        binding.kioskOverlay.kioskKeyboard.setContentFocusTarget(keyboardContentTarget.id)
        val keyboardEntryId = if (shouldShowKeyboard) {
            binding.kioskOverlay.kioskKeyboard.focusTargetFromResults()
        } else {
            View.NO_ID
        }
        browser.browserResultsRecyclerView.nextFocusRightId = if (
            shouldShowKeyboard && keyboardContentTarget === browser.browserResultsRecyclerView
        ) keyboardEntryId else View.NO_ID
        browser.browserSingerRecyclerView.nextFocusRightId = if (
            shouldShowKeyboard && keyboardContentTarget === browser.browserSingerRecyclerView
        ) keyboardEntryId else View.NO_ID
        if (shouldShowKeyboard && keyboardContentTarget === browser.txtBrowserEmptyState) {
            browser.txtBrowserEmptyState.nextFocusRightId = keyboardEntryId
        }

        when (tab) {
            KioskTab.PINYIN -> {
                browser.txtBrowserCenterHint.text = ""
            }
            KioskTab.SINGERS -> {
                if (selectedArtist != null) {
                    browser.browserFilterScroll.visibility = View.VISIBLE
                    addBrowserFilterOption("返回歌手列表", selected = false, onClick = ::resetToSingersTab)
                    if (state.errorFor(com.homektv.tv.controller.UiDomain.CATALOG) != null) {
                        addBrowserFilterOption("重试加载歌手歌曲", selected = false) {
                            catalogActionRouter.artistSongs(state.catalogValue)
                        }
                    }
                } else {
                    browser.browserSingerPanel.visibility = View.GONE
                }
            }
            KioskTab.RANKINGS -> {
                if (state.errorFor(com.homektv.tv.controller.UiDomain.CATALOG) != null) {
                    showBrowserRetry("重试热歌榜", visibleSongs.isNotEmpty(), ::loadRankings)
                } else {
                    showBrowserHint("近 30 天\n热歌榜")
                }
            }
            KioskTab.CATEGORIES -> {
                val isLanguage = categoryMode == KioskCategoryMode.LANGUAGES
                val route = presentationState.commercialRoute.value
                val label = when {
                    isLanguage -> "语种"
                    route == KioskCommercialRoute.HIGH_DEFINITION -> "高清专区"
                    route == KioskCommercialRoute.TIKTOK -> "抖音神曲"
                    route == KioskCommercialRoute.THEMES -> "主题点歌"
                    route == KioskCommercialRoute.VARIETY -> "综艺点歌"
                    else -> "曲风标签"
                }
                if (categoryMode == KioskCategoryMode.NEW) {
                    if (state.errorFor(com.homektv.tv.controller.UiDomain.CATALOG) != null) {
                        showBrowserRetry("重试加载新歌", visibleSongs.isNotEmpty(), ::loadCategories)
                    } else {
                        showBrowserHint("最新入库\n歌曲推荐")
                    }
                } else {
                    val showFilterList = !shouldShowKeyboard
                    browser.browserFilterScroll.visibility = if (showFilterList) View.VISIBLE else View.GONE
                    val options = if (isLanguage) {
                        state.languages
                    } else {
                        when (route) {
                            KioskCommercialRoute.THEMES -> KioskCommercialTagPolicy.themeTags(state.tags)
                            KioskCommercialRoute.HIGH_DEFINITION -> KioskCommercialTagPolicy.findExactTag(
                                state.tags,
                                KioskCommercialTagPolicy.HIGH_DEFINITION_TAG,
                            )?.let { listOf(it) } ?: state.tags
                            KioskCommercialRoute.TIKTOK -> KioskCommercialTagPolicy.findExactTag(
                                state.tags,
                                KioskCommercialTagPolicy.TIKTOK_TAG,
                            )?.let { listOf(it) } ?: state.tags
                            KioskCommercialRoute.VARIETY -> state.tags.filter { tag ->
                                KioskCommercialTagPolicy.varietyProgramNames.any { it.equals(tag.name.trim(), true) }
                            }
                            else -> state.tags
                        }
                    }
                    if (categoryDetail && showFilterList) {
                        addBrowserFilterOption("返回${label}列表", selected = false, onClick = ::resetCategoryDetail)
                    }
                    if (state.errorFor(com.homektv.tv.controller.UiDomain.CATALOG) != null && !state.catalogLoading) {
                        if (showFilterList || visibleSongs.isNotEmpty()) {
                            browser.browserFilterScroll.visibility = View.VISIBLE
                            addBrowserFilterOption("重试加载$label", selected = false) {
                                if (isLanguage) catalogActionRouter.languages() else catalogActionRouter.tags()
                            }
                        } else {
                            browser.txtBrowserEmptyState.text = "${label}加载失败，按确定重试"
                            browser.txtBrowserEmptyState.visibility = View.VISIBLE
                            browser.txtBrowserEmptyState.setOnClickListener {
                                if (isLanguage) catalogActionRouter.languages() else catalogActionRouter.tags()
                            }
                        }
                    }
                    if (showFilterList) {
                        options.forEach { item ->
                            addBrowserFilterOption(
                                label = "${item.name}    ${item.songCount}",
                                selected = false,
                            ) { onCategorySelected(item) }
                        }
                    }
                }
            }
            KioskTab.FAVORITES -> {
                if (state.errorFor(com.homektv.tv.controller.UiDomain.FAVORITES) != null) {
                    showBrowserRetry("重试加载收藏", visibleSongs.isNotEmpty(), ::loadFavorites)
                } else {
                    showBrowserHint("收藏歌曲\n在此点唱")
                }
            }
            KioskTab.PLAYLISTS -> {
                val playlists = state.playlists
                if (pendingAutoSelectFirstPlaylist && playlists.isNotEmpty() && !state.playlistDetailLoading) {
                    pendingAutoSelectFirstPlaylist = false
                    openPlaylist(playlists.first())
                }
                if (state.errorFor(com.homektv.tv.controller.UiDomain.PLAYLISTS) != null) {
                    showBrowserRetry("重试加载歌单", visibleSongs.isNotEmpty(), ::loadPlaylists)
                } else {
                    showBrowserHint("精选歌单\n点击分类切换")
                }
            }
            KioskTab.HISTORY -> {
                if (state.errorFor(com.homektv.tv.controller.UiDomain.HISTORY) != null) {
                    showBrowserRetry("重试加载历史", visibleSongs.isNotEmpty(), ::loadHistory)
                } else {
                    showBrowserHint("最近唱过的\n歌曲")
                }
            }
            else -> Unit
        }

        activeFilterOptionsCollector = null
        syncBrowserOptionRail(browser.browserFilterOptions, stagedFilterOptions, FILTER_RAIL_STYLE)
        val hasKeyboard = browser.browserKeyboardContainer.visibility == View.VISIBLE
        val hasSingerPanel = browser.browserSingerPanel.visibility == View.VISIBLE
        val hasFilterOptions = browser.browserFilterScroll.visibility == View.VISIBLE && browser.browserFilterOptions.childCount > 0
        val hasCenterHint = browser.txtBrowserCenterHint.visibility == View.VISIBLE
        val isVarietyActive = browser.panelVarietyShows.visibility == View.VISIBLE
        browser.browserCenterColumn.visibility = if (!isVarietyActive &&
            (hasKeyboard || hasSingerPanel || hasFilterOptions || hasCenterHint)
        ) View.VISIBLE else View.GONE

        // 同步 11 大主导航胶囊选中状态
        val route = presentationState.commercialRoute.value
        val capsuleTabs = listOf(
            browser.btnTabSongName to (route == KioskCommercialRoute.SONG_NAME),
            browser.btnTabHd to (route == KioskCommercialRoute.HIGH_DEFINITION),
            browser.btnTabSingers to (route == KioskCommercialRoute.SINGERS),
            browser.btnTabHotNew to (route == KioskCommercialRoute.HOT_NEW),
            browser.btnTabFeatured to (route == KioskCommercialRoute.FEATURED),
            browser.btnTabLocal to (route == KioskCommercialRoute.LOCAL),
            browser.btnTabFavorites to (route == KioskCommercialRoute.FAVORITES),
            browser.btnTabLanguages to (route == KioskCommercialRoute.LANGUAGES),
            browser.btnTabTopics to (route == KioskCommercialRoute.THEMES),
            browser.btnTabVariety to (route == KioskCommercialRoute.VARIETY),
            browser.btnTabTikTok to (route == KioskCommercialRoute.TIKTOK),
        )
        capsuleTabs.forEach { (btn, isSelected) ->
            btn.isSelected = isSelected
            // Keep the selector installed by the layout so focused state can
            // remain visible while selected state is updated during rendering.
            btn.setBackgroundResource(R.drawable.bg_ktv_capsule_tab)
            btn.setTypeface(null, if (isSelected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        }

        renderSubCategoryRail(tab, route)
        updatePageIndicator(state)

        val routeAtRenderEnd = presentationState.commercialRoute.value
        val routeActivationPending = pendingCommercialRouteFocus == routeAtRenderEnd
        val noVisibleViewHasFocusBeforeRender = activity.currentFocus?.isShown != true
        val routeFocusRequested = browserHadFocusBeforeRender || routeActivationPending || noVisibleViewHasFocusBeforeRender
        if (routeFocusRequested) {
            commercialRouteButton(routeAtRenderEnd)?.let { routeFocusTarget ->
                routeFocusTarget.post {
                    val focusedView = activity.currentFocus
                    val anotherVisibleActionHasFocus = focusedView?.let {
                        it.isShown && it.isFocusable && it.isClickable &&
                            !it.isDescendantOf(browserBinding.root)
                    } == true
                    val selectedRouteStillCurrent =
                        presentationState.commercialRoute.value == routeAtRenderEnd &&
                            commercialRouteButton(routeAtRenderEnd) === routeFocusTarget
                    val routeActivationStillPending =
                        pendingCommercialRouteFocus == routeAtRenderEnd
                    val shouldRestore = KtvBrowserFocusRestorationPolicy.shouldRestoreRouteFocus(
                        browserVisible = browserBinding.root.visibility == View.VISIBLE,
                        selectedRouteStillCurrent = selectedRouteStillCurrent,
                        routeActivationPending = routeActivationStillPending,
                        browserHadFocusBeforeRender = browserHadFocusBeforeRender,
                        browserHasFocus = browserBinding.root.hasFocus(),
                        anotherVisibleActionHasFocus = anotherVisibleActionHasFocus,
                        noVisibleViewHasFocus = focusedView?.isShown != true,
                    )
                    val focusRestored = if (!shouldRestore) {
                        false
                    } else {
                        routeFocusTarget.requestRemoteFocus()
                    }
                    if (shouldRestore && !focusRestored) {
                        if (selectedRouteStillCurrent && routeActivationStillPending) {
                            pendingCommercialRouteFocus = routeAtRenderEnd
                        }
                        android.util.Log.w(
                            "KtvBrowserFocus",
                            "focus request failed for route=$routeAtRenderEnd attached=${routeFocusTarget.isAttachedToWindow} " +
                                "shown=${routeFocusTarget.isShown} focusable=${routeFocusTarget.isFocusable} " +
                                "touchMode=${routeFocusTarget.isInTouchMode} current=${focusedView?.javaClass?.simpleName} " +
                                "currentId=${focusedView?.id}",
                        )
                    } else if (!shouldRestore && routeActivationPending && selectedRouteStillCurrent &&
                        browserBinding.root.visibility == View.VISIBLE && anotherVisibleActionHasFocus
                    ) {
                        android.util.Log.w(
                            "KtvBrowserFocus",
                            "preserving external control focus for route=$routeAtRenderEnd " +
                                "current=${focusedView?.javaClass?.simpleName} currentId=${focusedView?.id}",
                        )
                    }
                }
            }
        }

    }

    private fun showVarietyPanel(show: Boolean) {
        browserBinding.panelVarietyShows.visibility = if (show) View.VISIBLE else View.GONE
        if (show) {
            categoryMode = KioskCategoryMode.TAGS
            categoryDetail = false
            browserBinding.browserCenterColumn.visibility = View.GONE
        }
    }

    private fun selectVarietyProgram(name: String) {
        val actualTag = KioskCommercialTagPolicy.findExactTag(controllerState.tags, name)
        if (actualTag != null) {
            selectCommercialTag(actualTag)
            browserBinding.browserResultsRecyclerView.post {
                if (browserBinding.panelVarietyShows.visibility == View.VISIBLE) {
                    browserBinding.browserResultsRecyclerView.requestFocus()
                }
            }
        } else {
            categoryMode = KioskCategoryMode.TAGS
            categoryDetail = true
            focusController.hasInnerDetailBack = true
            commercialRouteNotice = "暂无“$name”相关歌曲"
            catalogActions.setQuery("")
            catalogActionRouter.tagSongs(name)
        }
    }

    private fun availableVarietyProgramNames(tags: List<com.homektv.tv.net.NamedCount>): Set<String> {
        return KioskCommercialTagPolicy.varietyProgramNames
            .filter { KioskCommercialTagPolicy.findExactTag(tags, it) != null }
            .toSet()
    }

    private fun renderAvailableVarietyPrograms(tags: List<com.homektv.tv.net.NamedCount>) {
        val available = availableVarietyProgramNames(tags)
        listOf(
            "中国好声音" to browserBinding.varietyVoice,
            "我是歌手" to browserBinding.varietySinger,
            "最美和声" to browserBinding.varietyHarmony,
            "中国最强音" to browserBinding.varietyStrong,
            "中国梦之声" to browserBinding.varietyDream,
            "我为歌狂" to browserBinding.varietyMad,
            "全能星战" to browserBinding.varietyStar,
            "中国好歌曲" to browserBinding.varietySong,
        ).forEach { (name, icon) ->
            (icon.parent as? View)?.visibility = View.VISIBLE
            icon.alpha = if (name in available) 1f else 0.62f
            icon.isEnabled = true
            icon.contentDescription = if (name in available) "节目标签：$name" else "$name；当前 Home KTV 曲库没有对应精确标签"
        }
        browserBinding.btnTabVariety.visibility = View.VISIBLE
        browserBinding.txtVarietyStatus.text = if (available.size == KioskCommercialTagPolicy.varietyProgramNames.size) {
            "选择节目台标，按曲库中的对应标签筛选"
        } else {
            "曲库匹配 ${available.size}/${KioskCommercialTagPolicy.varietyProgramNames.size} 个节目；未匹配入口仍保留并可查看说明"
        }
    }

    private fun scrollResultsPage(delta: Int) {
        val state = controllerState
        if (presentationState.currentTab.value == KioskTab.SINGERS &&
            presentationState.selectedArtist.value == null
        ) {
            val rv = browserBinding.browserSingerRecyclerView
            val lm = rv.layoutManager as? GridLayoutManager ?: return
            val first = lm.findFirstVisibleItemPosition().coerceAtLeast(0)
            val currentPageStart = (first / SINGER_GRID_PAGE_SIZE) * SINGER_GRID_PAGE_SIZE
            val target = if (delta > 0) currentPageStart + SINGER_GRID_PAGE_SIZE
                else maxOf(0, currentPageStart - SINGER_GRID_PAGE_SIZE)
            if (target >= singerAdapter.itemCount && delta > 0 && state.catalogHasMore) {
                pendingSingerPageTarget = target
                catalogActions.loadMoreCatalog()
            } else if (singerAdapter.itemCount > 0) {
                rv.smoothScrollToPosition(target.coerceAtMost(singerAdapter.itemCount - 1))
            }
            updatePageIndicator(state)
            return
        }

        val rv = browserBinding.browserResultsRecyclerView
        val lm = rv.layoutManager as? GridLayoutManager ?: return
        val first = lm.findFirstVisibleItemPosition().coerceAtLeast(0)
        val currentPageStart = (first / SONG_GRID_PAGE_SIZE) * SONG_GRID_PAGE_SIZE
        val target = if (delta > 0) currentPageStart + SONG_GRID_PAGE_SIZE
            else maxOf(0, currentPageStart - SONG_GRID_PAGE_SIZE)
        if (target >= browserSongAdapter.itemCount && delta > 0) {
            val hasMore = when {
                browsingAllSongs -> state.kioskLibraryBrowse.hasMore
                presentationState.selectedArtist.value != null || categoryDetail -> state.catalogHasMore
                presentationState.currentTab.value == KioskTab.PINYIN -> state.searchHasMore
                else -> false
            }
            if (hasMore) {
                pendingBrowserPageTarget = target
                when {
                    browsingAllSongs -> catalogActionRouter.moreAllSongs()
                    presentationState.selectedArtist.value != null || categoryDetail -> catalogActions.loadMoreCatalog()
                    presentationState.currentTab.value == KioskTab.PINYIN -> catalogActions.loadMoreSearch()
                }
            } else if (browserSongAdapter.itemCount > 0) {
                rv.smoothScrollToPosition(browserSongAdapter.itemCount - 1)
            }
        } else if (browserSongAdapter.itemCount > 0) {
            rv.smoothScrollToPosition(target.coerceAtMost(browserSongAdapter.itemCount - 1))
        }
        updatePageIndicator(state)
    }

    private fun updatePageIndicator(state: ControllerUiState = controllerState) {
        val isSingerRoot = presentationState.currentTab.value == KioskTab.SINGERS &&
            presentationState.selectedArtist.value == null
        val rv = if (isSingerRoot) browserBinding.browserSingerRecyclerView
            else browserBinding.browserResultsRecyclerView
        val lm = rv.layoutManager as? GridLayoutManager ?: return
        val first = lm.findFirstVisibleItemPosition().coerceAtLeast(0)
        val pageSize = if (isSingerRoot) SINGER_GRID_PAGE_SIZE else SONG_GRID_PAGE_SIZE
        val count = if (isSingerRoot) singerAdapter.itemCount else browserSongAdapter.itemCount
        val total = when {
            isSingerRoot -> state.catalogTotal
            browsingAllSongs -> state.kioskLibraryBrowse.total
            presentationState.selectedArtist.value != null || categoryDetail -> state.catalogTotal
            presentationState.currentTab.value == KioskTab.PINYIN && state.searchHasMore -> (count + pageSize).toLong()
            else -> count.toLong()
        }.coerceAtMost(MAX_VISIBLE_CATALOG_ITEMS.toLong())
        val currentPage = (first / pageSize) + 1
        val totalPages = ((total + pageSize - 1) / pageSize).toInt().coerceAtLeast(1)
        browserBinding.txtPageNumber.text = "$currentPage / $totalPages"
    }

    private fun renderSubCategoryRail(tab: KioskTab, route: KioskCommercialRoute?) {
        val rail = browserBinding.browserSubCategoryRail
        val container = browserBinding.browserSubCategoryItems
        val preferredFocusLabel = (container.findFocus() as? Button)?.text?.toString()

        fun item(label: String, selected: Boolean = false, action: () -> Unit) = Triple(label, selected, action)
        val subcategories: List<Triple<String, Boolean, () -> Unit>> = when {
            route == KioskCommercialRoute.HOT_NEW -> listOf(
                item("热歌榜", selected = tab == KioskTab.RANKINGS) { openCommercialRoute(KioskCommercialRoute.HOT_NEW) },
                item("新歌速递", selected = tab == KioskTab.CATEGORIES && categoryMode == KioskCategoryMode.NEW) {
                    leaveKioskAllSongs()
                    showVarietyPanel(false)
                    categoryMode = KioskCategoryMode.NEW
                    categoryDetail = false
                    focusController.hasInnerDetailBack = false
                    if (controllerState.newSongs.isEmpty()) catalogActionRouter.newSongs()
                    presentationState.selectTab(KioskTab.CATEGORIES, KioskCommercialRoute.HOT_NEW)
                    renderThreeColumnBrowser(controllerState)
                },
            )
            tab == KioskTab.SINGERS -> buildList {
                KtvSingerFilterPolicy.filters.forEach { filter ->
                    add(item(
                        filter.label,
                        controllerState.artistRegion.isBlank() &&
                            KtvSingerFilterPolicy.isSelected(filter, controllerState.artistGender),
                    ) {
                        loadSingerGender(filter.gender, requestLegacyFocus = false)
                    })
                }
                KtvSingerRegionPolicy.filters.forEach { filter ->
                    add(item(
                        filter.label,
                        KtvSingerRegionPolicy.isSelected(
                            filter, controllerState.artistRegion, controllerState.artistGender,
                        ),
                    ) { loadSingerRegion(filter) })
                }
            }
            tab == KioskTab.CATEGORIES && categoryMode == KioskCategoryMode.LANGUAGES -> buildList {
                add(item("全部", selected = !categoryDetail) { openCategoryRoot(KioskCategoryMode.LANGUAGES, route) })
                controllerState.languages.distinctBy { it.name.trim() }.forEach { language ->
                    add(item(language.name, selected = categoryDetail && controllerState.catalogValue == language.name) {
                        selectQuickLanguage(language.name)
                    })
                }
            }
            tab == KioskTab.CATEGORIES && categoryMode == KioskCategoryMode.TAGS &&
                browserBinding.panelVarietyShows.visibility != View.VISIBLE -> buildList {
                    add(item("全部标签", selected = !categoryDetail) { openCategoryRoot(KioskCategoryMode.TAGS, route) })
                    val topics = if (route == KioskCommercialRoute.THEMES || route == null) {
                        KioskCommercialTagPolicy.themeTags(controllerState.tags)
                    } else {
                        controllerState.tags.filter { tag ->
                            val expected = when (route) {
                                KioskCommercialRoute.HIGH_DEFINITION -> KioskCommercialTagPolicy.HIGH_DEFINITION_TAG
                                KioskCommercialRoute.TIKTOK -> KioskCommercialTagPolicy.TIKTOK_TAG
                                else -> null
                            }
                            expected != null && tag.name.trim().equals(expected, ignoreCase = true)
                        }
                    }
                    topics.forEach { tag ->
                        add(item(tag.name, selected = categoryDetail && controllerState.catalogValue == tag.name) {
                            selectCommercialTag(tag)
                        })
                    }
                }
            tab == KioskTab.PLAYLISTS -> controllerState.playlists.map { playlist ->
                item(
                    label = playlist.name,
                    selected = playlistDetail && controllerState.playlistDetail?.id == playlist.id,
                ) {
                    openPlaylist(playlist)
                }
            }
            else -> emptyList()
        }

        if (subcategories.isEmpty()) {
            container.removeAllViews()
            rail.visibility = View.GONE
            for (index in 0 until browserBinding.browserCapsuleTabs.childCount) {
                browserBinding.browserCapsuleTabs.getChildAt(index).nextFocusDownId = View.NO_ID
            }
            return
        }

        rail.visibility = View.VISIBLE
        val activeTopButton = commercialRouteButton(route)
        val desiredDownId = if (tab == KioskTab.SINGERS) {
            browserBinding.browserSingerRecyclerView.id
        } else {
            browserBinding.browserResultsRecyclerView.id
        }
        val existingLabels = (0 until container.childCount).mapNotNull {
            (container.getChildAt(it) as? Button)?.text?.toString()
        }
        val canReuse = existingLabels.size == container.childCount &&
            existingLabels == subcategories.map { it.first }

        if (canReuse) {
            subcategories.forEachIndexed { index, (_, selected, action) ->
                val btn = container.getChildAt(index) as Button
                btn.isSelected = selected
                activeTopButton?.let { btn.nextFocusUpId = it.id }
                btn.nextFocusDownId = desiredDownId
                btn.setOnClickListener {
                    coordinator.resetIdleTimer()
                    action()
                }
                if (index == 0) activeTopButton?.nextFocusDownId = btn.id
            }
            return
        }

        container.removeAllViews()
        subcategories.forEachIndexed { index, (label, selected, action) ->
            val btn = Button(activity, null, android.R.attr.buttonBarButtonStyle).apply {
                text = label
                textSize = 12f
                setTextColor(0xFFFFFFFF.toInt())
                background = ContextCompat.getDrawable(activity, R.drawable.bg_ktv_capsule_subtab)
                isSelected = selected
                isFocusable = true
                minWidth = 0
                setPadding(dp(10), 0, dp(10), 0)
                activeTopButton?.let { nextFocusUpId = it.id }
                nextFocusDownId = desiredDownId
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    dp(28),
                ).apply {
                    marginEnd = dp(4)
                }
                setOnClickListener {
                    coordinator.resetIdleTimer()
                    action()
                }
            }
            if (index == 0) activeTopButton?.nextFocusDownId = btn.id
            container.addView(btn)
            if (label == preferredFocusLabel) {
                btn.post {
                    if (rail.visibility == View.VISIBLE && btn.isAttachedToWindow) btn.requestFocus()
                }
            }
        }
    }

    private fun commercialRouteButton(route: KioskCommercialRoute?) = when (route) {
        KioskCommercialRoute.SONG_NAME -> browserBinding.btnTabSongName
        KioskCommercialRoute.HIGH_DEFINITION -> browserBinding.btnTabHd
        KioskCommercialRoute.SINGERS -> browserBinding.btnTabSingers
        KioskCommercialRoute.HOT_NEW -> browserBinding.btnTabHotNew
        KioskCommercialRoute.FEATURED -> browserBinding.btnTabFeatured
        KioskCommercialRoute.LOCAL -> browserBinding.btnTabLocal
        KioskCommercialRoute.FAVORITES -> browserBinding.btnTabFavorites
        KioskCommercialRoute.LANGUAGES -> browserBinding.btnTabLanguages
        KioskCommercialRoute.THEMES -> browserBinding.btnTabTopics
        KioskCommercialRoute.VARIETY -> browserBinding.btnTabVariety
        KioskCommercialRoute.TIKTOK -> browserBinding.btnTabTikTok
        null -> null
    }

    /** Returns a focusable control that is actually visible in the aggregate browser. */
    private fun threeColumnFocusTarget(tab: KioskTab): View {
        val route = when (tab) {
            KioskTab.PINYIN -> KioskCommercialRoute.SONG_NAME
            KioskTab.SINGERS -> KioskCommercialRoute.SINGERS
            KioskTab.RANKINGS -> KioskCommercialRoute.HOT_NEW
            KioskTab.FAVORITES -> KioskCommercialRoute.FAVORITES
            KioskTab.PLAYLISTS -> KioskCommercialRoute.FEATURED
            KioskTab.CATEGORIES -> presentationState.commercialRoute.value
            KioskTab.HISTORY,
            KioskTab.DASHBOARD -> null
        }
        commercialRouteButton(route)?.let { return it }
        return when (tab) {
            KioskTab.PINYIN -> browserBinding.btnTabSongName
            KioskTab.SINGERS -> browserBinding.btnTabSingers
            KioskTab.RANKINGS -> browserBinding.btnTabHotNew
            KioskTab.CATEGORIES -> if (categoryMode == KioskCategoryMode.LANGUAGES) {
                browserBinding.btnTabLanguages
            } else browserBinding.btnTabTopics
            KioskTab.FAVORITES -> browserBinding.btnTabFavorites
            KioskTab.PLAYLISTS -> browserBinding.btnTabFeatured
            KioskTab.HISTORY -> browserBinding.browserResultsRecyclerView
            KioskTab.DASHBOARD -> browserBinding.btnTabSongName
        }
    }

    private fun selectQuickLanguage(language: String) {
        if (controllerState.languages.none { it.name == language }) return
        categoryMode = KioskCategoryMode.LANGUAGES
        categoryDetail = true
        focusController.hasInnerDetailBack = true
        commercialRouteNotice = null
        catalogActions.setQuery("")
        catalogActionRouter.languageSongs(language)
        browserBinding.browserResultsRecyclerView.requestFocus()
    }

    private fun selectQuickTag(tag: String) {
        val actualTag = controllerState.tags.firstOrNull { it.name == tag } ?: return
        selectCommercialTag(actualTag)
    }

    private fun selectCommercialTag(actualTag: com.homektv.tv.net.NamedCount) {
        categoryMode = KioskCategoryMode.TAGS
        categoryDetail = true
        focusController.hasInnerDetailBack = true
        commercialRouteNotice = null
        when (presentationState.commercialRoute.value) {
            KioskCommercialRoute.HIGH_DEFINITION -> if (!actualTag.name.trim().equals(KioskCommercialTagPolicy.HIGH_DEFINITION_TAG, true)) {
                presentationState.selectCommercialRoute(KioskCommercialRoute.THEMES)
            }
            KioskCommercialRoute.TIKTOK -> if (!actualTag.name.trim().equals(KioskCommercialTagPolicy.TIKTOK_TAG, true)) {
                presentationState.selectCommercialRoute(KioskCommercialRoute.THEMES)
            }
            else -> Unit
        }
        catalogActions.setQuery("")
        catalogActionRouter.tagSongs(actualTag.name)
        browserBinding.browserResultsRecyclerView.requestFocus()
    }

    private fun isThreeColumnTab(tab: KioskTab): Boolean = when (tab) {
        KioskTab.DASHBOARD -> false
        KioskTab.PINYIN,
        KioskTab.SINGERS,
        KioskTab.RANKINGS,
        KioskTab.CATEGORIES,
        KioskTab.FAVORITES,
        KioskTab.PLAYLISTS,
        KioskTab.HISTORY -> true
    }

    private fun showBrowserHint(text: String) {
        browserBinding.txtBrowserCenterHint.text = text
        browserBinding.txtBrowserCenterHint.visibility = View.VISIBLE
    }

    private fun isSearchKeyboardShowing(): Boolean {
        return KtvGlobalSearchPolicy.shouldShowKeyboard(
            tab = presentationState.currentTab.value,
            isVariety = browserBinding.panelVarietyShows.visibility == View.VISIBLE,
            enabled = globalSearchEnabled(),
        )
    }

    private fun applyKeyboardVisibility(shouldShowKeyboard: Boolean) {
        val container = browserBinding.browserKeyboardContainer
        val keyboard = binding.kioskOverlay.kioskKeyboard
        when (KtvGlobalSearchPolicy.resolveKeyboardVisibility(shouldShowKeyboard, queueDrawerVisible)) {
            KtvGlobalSearchPolicy.KeyboardVisibilityMode.GONE -> {
                container.visibility = View.GONE
                keyboard.visibility = View.GONE
            }
            KtvGlobalSearchPolicy.KeyboardVisibilityMode.INVISIBLE -> {
                container.visibility = View.VISIBLE
                keyboard.visibility = View.INVISIBLE
            }
            KtvGlobalSearchPolicy.KeyboardVisibilityMode.VISIBLE -> {
                container.visibility = View.VISIBLE
                keyboard.visibility = View.VISIBLE
            }
        }
    }

    private fun showBrowserRetry(label: String, hasVisibleResults: Boolean, retry: () -> Unit) {
        val shouldShowKeyboard = isSearchKeyboardShowing()
        if (shouldShowKeyboard && !hasVisibleResults) {
            browserBinding.txtBrowserEmptyState.text = "$label，按确定重试"
            browserBinding.txtBrowserEmptyState.visibility = View.VISIBLE
            browserBinding.txtBrowserEmptyState.setOnClickListener { retry() }
            browserBinding.browserFilterScroll.visibility = View.GONE
        } else {
            browserBinding.browserFilterScroll.visibility = View.VISIBLE
            addBrowserFilterOption(label, selected = false, onClick = retry)
        }
    }

    private fun renderSingerInitialRail(state: ControllerUiState) {
        val rail = browserBinding.browserInitialOptions
        val availableInitials = state.artistInitials
            .map { it.trim().uppercase() }
            .filter { it.length == 1 && it[0] in 'A'..'Z' }
            .distinct()
        val initials = if (availableInitials.isEmpty() && state.artistRegion.isNotBlank()) {
            emptyList()
        } else {
            availableInitials.ifEmpty { ('A'..'Z').map(Char::toString) }
        }
        val specs = initials.map { initial ->
            BrowserRailOptionSpec(
                label = initial,
                selected = state.artistInitial.equals(initial, ignoreCase = true),
            ) {
                focusController.hasInnerDetailBack = false
                presentationState.clearArtistSelection()
                catalogActions.loadArtists(
                    gender = controllerState.artistGender,
                    initial = initial,
                    restorePage = 0,
                    region = controllerState.artistRegion,
                )
            }
        }
        syncBrowserOptionRail(rail, specs, INITIAL_RAIL_STYLE)
    }

    private fun addBrowserFilterOption(
        label: String,
        selected: Boolean,
        onClick: () -> Unit,
    ) {
        val spec = BrowserRailOptionSpec(label = label, selected = selected, onClick = onClick)
        val collector = activeFilterOptionsCollector
        if (collector != null) {
            collector += spec
            return
        }
        syncBrowserOptionRail(browserBinding.browserFilterOptions, listOf(spec), FILTER_RAIL_STYLE)
    }

    private data class RailButtonStyle(
        val heightDp: Int,
        val bottomMarginDp: Int,
        val zeroPadding: Boolean,
        val textSizeSp: Float?,
        val unselectedDrawableRes: Int,
    )

    private class RailOptionHolder(
        val label: String,
        var onClick: () -> Unit,
    )

    private fun syncBrowserOptionRail(
        container: LinearLayout,
        specs: List<BrowserRailOptionSpec>,
        style: RailButtonStyle,
    ) {
        val existingLabels = (0 until container.childCount).map { idx ->
            val button = container.getChildAt(idx) as? Button ?: return@map ""
            (button.tag as? RailOptionHolder)?.label ?: button.text?.toString().orEmpty()
        }
        if (BrowserRailSyncPolicy.canReuseButtons(existingLabels, specs)) {
            specs.forEachIndexed { idx, spec ->
                val button = container.getChildAt(idx) as Button
                (button.tag as? RailOptionHolder)?.onClick = spec.onClick
                applyRailButtonStyle(button, spec.selected, style)
            }
            return
        }
        container.removeAllViews()
        specs.forEach { spec ->
            val holder = RailOptionHolder(spec.label, spec.onClick)
            val button = Button(activity).apply {
                tag = holder
                text = spec.label
                isFocusable = true
                minWidth = 0
                if (style.zeroPadding) setPadding(0, 0, 0, 0)
                if (style.textSizeSp != null) textSize = style.textSizeSp
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(style.heightDp),
                ).apply { bottomMargin = dp(style.bottomMarginDp) }
                setOnClickListener {
                    coordinator.resetIdleTimer()
                    (tag as? RailOptionHolder)?.onClick?.invoke()
                }
            }
            applyRailButtonStyle(button, spec.selected, style)
            container.addView(button)
        }
    }

    private fun applyRailButtonStyle(
        button: Button,
        selected: Boolean,
        style: RailButtonStyle,
    ) {
        button.setBackgroundResource(if (selected) R.drawable.btn_gold else style.unselectedDrawableRes)
        button.setTextColor(
            if (selected) 0xFF111317.toInt()
            else ContextCompat.getColor(activity, R.color.color_keyboard_key_text),
        )
    }

    private fun dp(value: Int): Int =
        (value * activity.resources.displayMetrics.density + 0.5f).toInt()

    private fun View.isDescendantOf(ancestor: View): Boolean {
        var current: View? = this
        while (current != null) {
            if (current === ancestor) return true
            current = current.parent as? View
        }
        return false
    }

    private fun applyTabSelection(tab: KioskTab) {
        bottomDockController.updateFocusUpTargets(tab == KioskTab.DASHBOARD)
        val overlay = binding.kioskOverlay
        if (pendingCommercialRouteFocus != presentationState.commercialRoute.value) {
            pendingCommercialRouteFocus = null
        }
        val browserHadFocus = browserBinding.root.hasFocus()
        val isThreeColumnTab = isThreeColumnTab(tab)

        browserBinding.root.visibility = if (isThreeColumnTab) View.VISIBLE else View.GONE
        overlay.kioskDashboardView.visibility = if (tab == KioskTab.DASHBOARD) View.VISIBLE else View.GONE

        if (tab == KioskTab.DASHBOARD) {
            val skipAutoFocus = skipNextDashboardAutoFocus
            skipNextDashboardAutoFocus = false
            if (!skipAutoFocus) {
                overlay.kioskDashboardView.requestDashboardFocus()
            }
        }

        if (tab != KioskTab.CATEGORIES && categoryDetail) {
            categoryDetail = false
            focusController.hasInnerDetailBack = false
        }
        if (tab != KioskTab.PLAYLISTS) {
            pendingAutoSelectFirstPlaylist = false
            if (playlistDetail) {
                playlistDetail = false
                personalActionRouter.clearPlaylistDetail()
                focusController.hasInnerDetailBack = false
            }
        }

        if (tab != KioskTab.PINYIN) {
            leaveKioskAllSongs()
            val wasArtistSelected = presentationState.selectedArtist.value != null
            presentationState.clearArtistSelection()
            focusController.hasInnerDetailBack = false
            if (wasArtistSelected) {
                overlay.kioskKeyboard.clear()
                loadDefaultSongs()
            }
        }
        if (tab != KioskTab.SINGERS) pendingSingerPageTarget = null

        (catalogActions as? ControllerCatalogVisibility)?.setCatalogVisible(tab != KioskTab.DASHBOARD)
        when (tab) {
            KioskTab.DASHBOARD -> if (
                focusController.isKioskActive && !artistsLoaded && controllerState.artists.isEmpty()
            ) loadArtists()
            KioskTab.PINYIN -> if (!rankingsLoaded && controllerState.ranking.isEmpty()) loadRankings()
            KioskTab.SINGERS -> if (!artistsLoaded || controllerState.artists.isEmpty()) loadArtists()
            KioskTab.RANKINGS -> if (!rankingsLoaded) loadRankings()
            KioskTab.CATEGORIES -> if (
                shouldLoadCategoryRoot(categoryMode)
            ) loadCategories()
            KioskTab.FAVORITES -> loadFavorites()
            KioskTab.PLAYLISTS -> {
                pendingAutoSelectFirstPlaylist = true
                loadPlaylists()
            }
            KioskTab.HISTORY -> loadHistory()
        }
        renderThreeColumnBrowser(controllerState)
        if (isThreeColumnTab(tab) && !browserHadFocus) {
            val focusTarget = threeColumnFocusTarget(tab)
            focusTarget.post {
                if (presentationState.currentTab.value == tab && browserBinding.root.visibility == View.VISIBLE) {
                    focusTarget.requestRemoteFocus()
                }
            }
        }
    }

    private fun onKeywordInput(keyword: String) {
        val currentTab = presentationState.currentTab.value
        val globalSearchIsEnabled = globalSearchEnabled()
        if (currentTab != KioskTab.PINYIN && !globalSearchIsEnabled) return

        leaveKioskAllSongs()
        val normalizedKeyword = T9PinyinSearchPolicy.normalizeSearchKeyword(keyword)
        browserSongAdapter.setSearchKeyword(normalizedKeyword)
        if (normalizedKeyword.isBlank()) {
            if (currentTab != KioskTab.PINYIN) return
            loadDefaultSongs()
            return
        }

        val targetTab = KtvGlobalSearchPolicy.targetTabForQuery(currentTab, globalSearchIsEnabled)
        if (targetTab != currentTab) {
            presentationState.selectTab(targetTab, KioskCommercialRoute.SONG_NAME)
        }

        catalogActionRouter.search(normalizedKeyword)
    }

    /** Re-renders the active browser when the persisted cross-tab keyboard preference changes. */
    fun refreshGlobalSearchPreference() {
        val tab = presentationState.currentTab.value
        if (!isThreeColumnTab(tab)) return
        val keyboardHadFocus = binding.kioskOverlay.kioskKeyboard.hasFocus()
        renderThreeColumnBrowser(controllerState)
        if (keyboardHadFocus && !globalSearchEnabled()) {
            val focusTarget = threeColumnFocusTarget(tab)
            focusTarget.post {
                if (presentationState.currentTab.value == tab && focusTarget.isShown) {
                    focusTarget.requestRemoteFocus()
                }
            }
        }
    }

    private fun selectPinyinTab() {
        leaveKioskAllSongs()
        showVarietyPanel(false)
        if (presentationState.selectedArtist.value != null) {
            presentationState.clearArtistSelection()
            focusController.hasInnerDetailBack = false
            binding.kioskOverlay.kioskKeyboard.clear()
        }
        if (controllerState.catalogSongs.isEmpty()) {
            loadDefaultSongs()
        }
        presentationState.selectTab(KioskTab.PINYIN)
    }

    private fun openCommercialRoute(route: KioskCommercialRoute) {
        coordinator.resetIdleTimer()
        pendingCommercialRouteFocus = route
        pendingCommercialTagRoute = null
        pendingCommercialTagRevision = null
        commercialRouteNotice = null
        showVarietyPanel(false)

        when (route) {
            KioskCommercialRoute.SONG_NAME -> selectPinyinTab()
            KioskCommercialRoute.HIGH_DEFINITION,
            KioskCommercialRoute.TIKTOK -> openCommercialTagRoute(route)
            KioskCommercialRoute.SINGERS -> {
                leaveKioskAllSongs()
                focusController.hasInnerDetailBack = false
                presentationState.clearArtistSelection()
                if (controllerState.artistGender.isNotBlank() || controllerState.artistInitial.isNotBlank() ||
                    controllerState.artistRegion.isNotBlank()
                ) {
                    catalogActions.loadArtists(gender = "", initial = "", restorePage = 0, region = "")
                }
                presentationState.selectTab(KioskTab.SINGERS)
            }
            KioskCommercialRoute.HOT_NEW -> {
                leaveKioskAllSongs()
                categoryMode = KioskCategoryMode.NEW
                categoryDetail = false
                focusController.hasInnerDetailBack = false
                presentationState.selectTab(KioskTab.RANKINGS)
            }
            KioskCommercialRoute.FEATURED -> {
                leaveKioskAllSongs()
                playlistDetail = false
                pendingAutoSelectFirstPlaylist = true
                presentationState.selectTab(KioskTab.PLAYLISTS)
            }
            KioskCommercialRoute.LOCAL -> {
                leaveKioskAllSongs()
                presentationState.clearArtistSelection()
                focusController.hasInnerDetailBack = false
                binding.kioskOverlay.kioskKeyboard.clear()
                browsingAllSongs = true
                catalogActions.setQuery("")
                catalogActionRouter.allSongs()
                presentationState.selectTab(KioskTab.PINYIN, KioskCommercialRoute.LOCAL)
            }
            KioskCommercialRoute.FAVORITES -> {
                leaveKioskAllSongs()
                presentationState.selectTab(KioskTab.FAVORITES)
            }
            KioskCommercialRoute.LANGUAGES -> openCategoryRoot(KioskCategoryMode.LANGUAGES, route)
            KioskCommercialRoute.THEMES -> openCategoryRoot(KioskCategoryMode.TAGS, route)
            KioskCommercialRoute.VARIETY -> {
                leaveKioskAllSongs()
                categoryMode = KioskCategoryMode.TAGS
                focusController.hasInnerDetailBack = false
                openCategoryRoot(KioskCategoryMode.TAGS, route)
                showVarietyPanel(true)
                selectVarietyProgram("中国好声音")
                browserBinding.varietyVoice.post { browserBinding.varietyVoice.requestFocus() }
            }
        }

    }

    private fun openCommercialTagRoute(route: KioskCommercialRoute) {
        val exactName = when (route) {
            KioskCommercialRoute.HIGH_DEFINITION -> KioskCommercialTagPolicy.HIGH_DEFINITION_TAG
            KioskCommercialRoute.TIKTOK -> KioskCommercialTagPolicy.TIKTOK_TAG
            else -> return
        }
        val exactTag = KioskCommercialTagPolicy.findExactTag(controllerState.tags, exactName)
        if (exactTag == null && controllerState.tags.isEmpty()) {
            pendingCommercialTagRoute = route
            val sameRootRequestInFlight = presentationState.currentTab.value == KioskTab.CATEGORIES &&
                categoryMode == KioskCategoryMode.TAGS && controllerState.catalogLoading &&
                lastRequestedCategoryMode == KioskCategoryMode.TAGS
            pendingCommercialTagRevision = controllerState.tagRootRequestRevision + if (sameRootRequestInFlight) 0 else 1
        }
        openCategoryRoot(KioskCategoryMode.TAGS, route)
        if (exactTag != null) {
            selectCommercialTag(exactTag)
        } else if (controllerState.tags.isNotEmpty()) {
            commercialRouteNotice = KioskCommercialTagPolicy.noticeForMissingTag(exactName, isSearchKeyboardShowing())
            renderThreeColumnBrowser(controllerState)
        }
    }

    private fun resolvePendingCommercialTagRoute(state: ControllerUiState) {
        val route = pendingCommercialTagRoute ?: return
        val expectedRevision = pendingCommercialTagRevision ?: return
        if (presentationState.commercialRoute.value != route ||
            presentationState.currentTab.value != KioskTab.CATEGORIES ||
            categoryMode != KioskCategoryMode.TAGS
        ) {
            pendingCommercialTagRoute = null
            pendingCommercialTagRevision = null
            return
        }
        if (state.tagRootRequestRevision < expectedRevision || state.catalogLoading) return
        if (state.errorFor(com.homektv.tv.controller.UiDomain.CATALOG) != null) {
            commercialRouteNotice = KioskCommercialTagPolicy.noticeForLoadFailure(isSearchKeyboardShowing())
            return
        }

        val exactName = if (route == KioskCommercialRoute.HIGH_DEFINITION) {
            KioskCommercialTagPolicy.HIGH_DEFINITION_TAG
        } else {
            KioskCommercialTagPolicy.TIKTOK_TAG
        }
        val exactTag = KioskCommercialTagPolicy.findExactTag(state.tags, exactName)
        pendingCommercialTagRoute = null
        pendingCommercialTagRevision = null
        if (exactTag != null) {
            selectCommercialTag(exactTag)
        } else {
            commercialRouteNotice = KioskCommercialTagPolicy.noticeForMissingTag(exactName, isSearchKeyboardShowing())
        }
    }

    private fun loadDefaultSongs() {
        catalogActionRouter.defaultSongs()
    }

    private fun leaveKioskAllSongs() {
        val wasBrowsingAllSongs = browsingAllSongs
        browsingAllSongs = false
        pendingBrowserPageTarget = null
        if (wasBrowsingAllSongs) catalogActionRouter.stopAllSongs()
    }

    private fun loadArtists() {
        catalogActionRouter.artists()
    }

    private fun loadSingerGender(gender: String, requestLegacyFocus: Boolean = true) {
        coordinator.resetIdleTimer()
        pendingSingerPageTarget = null
        focusController.hasInnerDetailBack = false
        presentationState.clearArtistSelection()
        catalogActions.loadArtists(gender = gender, initial = "", restorePage = 0)
        if (requestLegacyFocus) binding.kioskOverlay.singerRecyclerView.requestFocus()
    }

    private fun loadSingerRegion(filter: KtvSingerRegionFilter) {
        coordinator.resetIdleTimer()
        pendingSingerPageTarget = null
        focusController.hasInnerDetailBack = false
        presentationState.clearArtistSelection()
        catalogActions.loadArtists(
            gender = filter.gender,
            initial = "",
            restorePage = 0,
            region = filter.region,
        )
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

    private fun hasCategoryRootData(mode: KioskCategoryMode): Boolean = when (mode) {
        KioskCategoryMode.NEW -> controllerState.newSongs.isNotEmpty()
        KioskCategoryMode.LANGUAGES -> controllerState.languages.isNotEmpty()
        KioskCategoryMode.TAGS -> controllerState.tags.isNotEmpty()
    }

    private fun shouldLoadCategoryRoot(mode: KioskCategoryMode): Boolean =
        categoryLoadPolicy.shouldLoad(mode, hasCategoryRootData(mode)) &&
            !(controllerState.catalogLoading && lastRequestedCategoryMode == mode)

    private fun loadCategories() {
        lastRequestedCategoryMode = categoryMode
        when (categoryMode) {
            KioskCategoryMode.TAGS -> catalogActionRouter.tags()
            KioskCategoryMode.LANGUAGES -> catalogActionRouter.languages()
            KioskCategoryMode.NEW -> catalogActionRouter.newSongs()
        }
    }

    private fun openCategoryRoot(mode: KioskCategoryMode, route: KioskCommercialRoute? = null) {
        leaveKioskAllSongs()
        showVarietyPanel(false)
        val alreadyOnCategories = presentationState.currentTab.value == KioskTab.CATEGORIES
        categoryMode = mode
        categoryDetail = false
        focusController.hasInnerDetailBack = false
        if (alreadyOnCategories) {
            presentationState.selectTab(KioskTab.CATEGORIES, route)
            if (shouldLoadCategoryRoot(mode)) loadCategories()
            renderThreeColumnBrowser(controllerState)
        } else {
            presentationState.selectTab(KioskTab.CATEGORIES, route)
        }
    }

    private fun onArtistSelected(artist: ArtistItem) {
        leaveKioskAllSongs()
        pendingSingerPageTarget = null
        coordinator.resetIdleTimer()
        focusController.hasInnerDetailBack = true
        presentationState.selectArtist(artist.name)
        binding.kioskOverlay.kioskKeyboard.clear()
        browserBinding.browserResultsRecyclerView.post {
            browserBinding.browserResultsRecyclerView.requestFocus()
        }
        catalogActions.setQuery("")
        catalogActionRouter.artistSongs(artist.artistKey)
    }

    private fun onCategorySelected(item: com.homektv.tv.net.NamedCount) {
        leaveKioskAllSongs()
        coordinator.resetIdleTimer()
        when (categoryMode) {
            KioskCategoryMode.LANGUAGES -> selectQuickLanguage(item.name)
            KioskCategoryMode.TAGS -> selectCommercialTag(item)
            KioskCategoryMode.NEW -> Unit
        }
        browserBinding.browserResultsRecyclerView.requestFocus()
    }

    private fun loadFavorites() {
        playlistDetail = false
        personalActionRouter.favorites()
    }

    private fun loadPlaylists() {
        playlistDetail = false
        personalActionRouter.playlists()
    }

    private fun loadHistory() {
        playlistDetail = false
        personalActionRouter.history()
    }

    private fun openPlaylist(playlist: com.homektv.tv.net.PlaylistSummary) {
        coordinator.resetIdleTimer()
        pendingAutoSelectFirstPlaylist = false
        playlistDetail = true
        focusController.hasInnerDetailBack = false
        personalActionRouter.playlistDetail(playlist.id)
    }

    private fun orderSong(song: SongDto, onComplete: (Boolean) -> Unit = {}) {
        coordinator.resetIdleTimer()
        queueActionRouter.order(song, onComplete)
    }

    private fun orderSongTop(song: SongDto, onComplete: (Boolean) -> Unit = {}) {
        coordinator.resetIdleTimer()
        queueActionRouter.orderTop(song, onComplete)
    }

    fun openQueueDrawer(
        onDismiss: (() -> Unit)? = null,
        initialTab: KtvQueueDrawerTab = KtvQueueDrawerTab.QUEUE,
    ) {
        if (queueDialog?.isShowing == true) return
        val overlay = binding.kioskOverlay
        val keyboardWasVisible = overlay.kioskKeyboard.visibility == View.VISIBLE
        val initiatingFocusView = activity.currentFocus
        if (keyboardWasVisible) {
            // A modal queue drawer should not leave the search keyboard visible behind it.
            // INVISIBLE preserves its measured slot so the playing PiP does not collapse.
            overlay.kioskKeyboard.visibility = View.INVISIBLE
        }
        val state = controllerActions.state.value
        val dialog = KtvQueueDrawerDialog(
            context = activity,
            currentUserId = state.currentUser?.id,
            isHost = state.roomHost.isHost,
            coordinator = coordinator,
            onTop = queueActionRouter::top,
            onCancel = queueActionRouter::cancel,
            onShuffle = queueActionRouter::shuffle,
            initialTab = initialTab,
            initiatingFocusView = initiatingFocusView,
        )
        dialog.onHistoryTabSelected = personalActionRouter::history
        dialog.onRepeatHistory = personalActionRouter::repeatHistory
        dialog.onRetryHistory = personalActionRouter::history
        dialog.onDismissDrawer = {
            queueDrawerVisible = false
            focusController.isDrawerOpen = false
            queueDialog = null
            overlay.kioskDashboardView.resumePhotoCarousel(
                KtvDashboardPhotoCarousel.PauseReason.QUEUE_DRAWER,
            )
            if (keyboardWasVisible && isSearchKeyboardShowing()) {
                overlay.kioskKeyboard.visibility = View.VISIBLE
            } else {
                applyKeyboardVisibility(isSearchKeyboardShowing())
            }
            onDismiss?.invoke()
        }
        dialog.onRetryQueue = controllerActions::refreshQueue
        queueDialog = dialog
        overlay.kioskDashboardView.pausePhotoCarousel(
            KtvDashboardPhotoCarousel.PauseReason.QUEUE_DRAWER,
        )
        dialog.show()
        dialog.selectInitialTab()
        queueDrawerVisible = true
        dialog.updateActor(state.currentUser?.id, state.roomHost.isHost)
        dialog.updatePendingActions(state.pendingActions)
        renderQueueDrawerState(state)
        focusController.isDrawerOpen = true
    }

    fun pauseDashboardPhotoCarousel(reason: KtvDashboardPhotoCarousel.PauseReason) {
        kioskOverlayBinding.kioskDashboardView.pausePhotoCarousel(reason)
    }

    fun resumeDashboardPhotoCarousel(reason: KtvDashboardPhotoCarousel.PauseReason) {
        kioskOverlayBinding.kioskDashboardView.resumePhotoCarousel(reason)
    }

    private fun renderQueueDrawerState(state: ControllerUiState) {
        val dialog = queueDialog?.takeIf { it.isShowing } ?: return
        val historyError = state.errorFor(com.homektv.tv.controller.UiDomain.HISTORY)
        dialog.renderHistoryState(
            history = state.history,
            loading = state.historyLoading,
            errorMessage = historyError?.let {
                "演唱历史加载失败：${state.messageFor(com.homektv.tv.controller.UiDomain.HISTORY) ?: "请重试"}"
            },
            retryAvailable = historyError != null && !state.historyLoading,
        )
        val queueError = state.errorFor(com.homektv.tv.controller.UiDomain.QUEUE)
        val lastSnapshot = currentSnapshot
        val stateSnapshot = state.queue.takeIf {
            !state.queueLoading && queueError == null
        }
        val snapshot = lastSnapshot ?: stateSnapshot
        val actionsAvailable = state.connection == ControllerConnection.ONLINE &&
            !state.queueLoading && queueError == null
        dialog.setActionsAvailable(actionsAvailable)

        if (snapshot != null) {
            val currentTitle = snapshot.playing?.song?.let { "${it.title} · ${it.artist}" }
                ?: "当前暂无歌曲播放"
            val staleMessage = when {
                state.queueLoading -> "正在同步队列；当前显示最后一次已同步的数据。"
                queueError != null -> "队列刷新失败，当前显示最后一次已同步的数据：${state.messageFor(com.homektv.tv.controller.UiDomain.QUEUE) ?: "请重试"}"
                state.connection == ControllerConnection.OFFLINE -> "点歌服务离线，当前显示最后一次已同步的数据。"
                else -> null
            }
            dialog.submitQueue(
                nowPlayingText = currentTitle,
                waitingList = snapshot.list.filter { it.status == "waiting" },
                isPlaying = snapshot.state == "playing",
                statusMessage = staleMessage,
                retryAvailable = !state.queueLoading,
            )
        } else {
            val message = when {
                state.queueLoading -> "正在加载队列…"
                queueError != null -> "队列加载失败：${state.messageFor(com.homektv.tv.controller.UiDomain.QUEUE) ?: "请重试"}"
                state.connection == ControllerConnection.OFFLINE -> "点歌服务离线，队列状态尚未同步。"
                else -> "队列状态尚未同步。"
            }
            dialog.showUnavailable(message, retryAvailable = !state.queueLoading)
        }
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
                    } else if (qrDialog?.isShowing == true) {
                        qrDialog?.showLoadError(
                            "二维码暂不可用，请检查 Home KTV 服务连接后重新打开。",
                            retryable = false,
                        )
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
        coordinator.setPlaybackActive(snapshot.state == "playing")

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

    /** Opens the global search surface and restores the initiating control when it closes. */
    fun openGlobalSearch(returnFocus: View) {
        globalSearchReturnFocus = returnFocus
        presentationState.selectTab(KioskTab.PINYIN, KioskCommercialRoute.SONG_NAME)
        toggleKiosk(true)
        browserBinding.btnTabSongName.post {
            if (focusController.isKioskActive && browserBinding.root.visibility == View.VISIBLE) {
                // Header entry is often reached by a pointer/automation tap. Requesting
                // focus while Android is still in touch mode silently fails for normal
                // TV buttons, leaving DPAD input on the previous top-bar action.
                browserBinding.btnTabSongName.requestFocusFromTouch()
            }
        }
    }

    fun selectTab(tab: KioskTab) {
        presentationState.selectTab(tab)
    }

    fun updateExternalDisplay(active: Boolean) {
        externalDisplayActive = active
        if (focusController.isKioskActive) renderPipState()
    }

    private fun applyKioskActive(active: Boolean) {
        focusController.isKioskActive = active
        if (active) {
            binding.kioskOverlay.kioskRootOverlay.visibility = View.VISIBLE
            bottomDockController.ensurePlayerInDock()
            renderPipState()
            val currentTab = presentationState.currentTab.value
            if (currentTab == KioskTab.DASHBOARD) {
                binding.kioskOverlay.kioskDashboardView.requestDashboardFocus()
                if (!artistsLoaded && controllerState.artists.isEmpty()) loadArtists()
            } else if (isThreeColumnTab(currentTab)) {
                threeColumnFocusTarget(currentTab).requestFocus()
                threeColumnFocusTarget(currentTab).post {
                    if (focusController.isKioskActive && isThreeColumnTab(presentationState.currentTab.value)) {
                        threeColumnFocusTarget(presentationState.currentTab.value).requestRemoteFocus()
                    }
                }
            }
        } else {
            focusController.hasInnerDetailBack = false
            presentationState.clearArtistSelection()
            binding.kioskOverlay.kioskKeyboard.clear()
            bottomDockController.ensurePlayerInDock()
            binding.kioskOverlay.kioskRootOverlay.visibility = View.GONE
            bottomDockController.focusHome()
            val returnFocus = globalSearchReturnFocus
            globalSearchReturnFocus = null
            returnFocus?.post {
                if (returnFocus.isAttachedToWindow && returnFocus.isShown) returnFocus.requestFocus()
            }
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
        bottomDockController.setVideoVisible(state.videoVisible && !externalDisplayActive)
    }

    fun destroy() {
        transientFeedbackHost.clear()
        pipObservedPlayer?.removeListener(pipPlaybackListener)
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

    fun serviceStatusSummary(): String {
        val networkError = (controllerState.domainErrors.values + listOfNotNull(controllerState.error))
            .firstOrNull { it.kind == com.homektv.tv.net.KtvApiErrorKind.NETWORK }
        val apiStatus = when {
            networkError != null -> "不可用（${controllerState.message?.takeIf(String::isNotBlank) ?: networkError.message}）"
            controllerState.connection == ControllerConnection.CONNECTING -> "请求状态尚未确认"
            else -> "最近请求未报告网络错误"
        }
        val controlStatus = when (controllerState.connection) {
            ControllerConnection.CONNECTING -> "正在连接"
            ControllerConnection.ONLINE -> "已连接"
            ControllerConnection.OFFLINE -> "未连接"
        }
        return "点歌请求：$apiStatus；实时控制：$controlStatus"
    }

    private companion object {
        const val MINIMUM_PINYIN_VIEWPORT_DP = 140
        const val MINIMUM_LIST_VIEWPORT_DP = 128
        const val SONG_GRID_PAGE_SIZE = 15
        const val SINGER_GRID_PAGE_SIZE = 16
        const val MAX_VISIBLE_CATALOG_ITEMS = 2000
        private val INITIAL_RAIL_STYLE = RailButtonStyle(
            heightDp = 38,
            bottomMarginDp = 3,
            zeroPadding = true,
            textSizeSp = null,
            unselectedDrawableRes = R.drawable.btn_keyboard_key,
        )
        private val FILTER_RAIL_STYLE = RailButtonStyle(
            heightDp = 48,
            bottomMarginDp = 6,
            zeroPadding = false,
            textSizeSp = 14f,
            unselectedDrawableRes = R.drawable.bg_ktv_card_focused,
        )
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

    private fun moveIntoLinearLayout(
        view: View,
        target: LinearLayout,
        params: LinearLayout.LayoutParams,
    ) {
        if (view.parent === target) return
        (view.parent as? ViewGroup)?.removeView(view)
        target.addView(view, params)
    }

    private fun resetToSingersTab() {
        val wasAlreadyOnSingersTab = presentationState.currentTab.value == KioskTab.SINGERS
        pendingSingerPageTarget = null
        artistsLoaded = false
        focusController.hasInnerDetailBack = false
        presentationState.clearArtistSelection()
        presentationState.selectTab(KioskTab.SINGERS)
        binding.kioskOverlay.kioskKeyboard.clear()
        loadDefaultSongs()
        if (wasAlreadyOnSingersTab) loadArtists()
        browserBinding.browserNavSingers.post {
            if (presentationState.currentTab.value == KioskTab.SINGERS) {
                if (browserBinding.browserSingerRecyclerView.isShown) {
                    browserBinding.browserSingerRecyclerView.requestFocus()
                } else {
                    browserBinding.browserNavSingers.requestRemoteFocus()
                }
            }
        }
    }

    private fun resetCategoryDetail() {
        categoryDetail = false
        focusController.hasInnerDetailBack = false
        when (categoryMode) {
            KioskCategoryMode.LANGUAGES -> catalogActionRouter.languages()
            KioskCategoryMode.TAGS -> catalogActionRouter.tags()
            KioskCategoryMode.NEW -> loadCategories()
        }
    }

    private fun returnToDashboardFromBrowser() {
        showVarietyPanel(false)
        backExitGate.reset()
        if (globalSearchReturnFocus != null) {
            val returnFocus = globalSearchReturnFocus
            globalSearchReturnFocus = null
            skipNextDashboardAutoFocus = true
            presentationState.selectTab(KioskTab.DASHBOARD)
            returnFocus?.post {
                if (returnFocus.isAttachedToWindow && returnFocus.isShown) {
                    returnFocus.requestFocus()
                } else {
                    binding.kioskOverlay.kioskDashboardView.requestDashboardFocus()
                }
            }
        } else {
            presentationState.selectTab(KioskTab.DASHBOARD)
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
            personalActionRouter.clearPlaylistDetail()
            personalActionRouter.playlists()
        }
    }

    fun dispatchKeyEvent(event: KeyEvent): Boolean {
        coordinator.resetIdleTimer()
        if (event.keyCode == KeyEvent.KEYCODE_BACK && qrDialog?.isShowing == true) {
            if (event.action == KeyEvent.ACTION_UP) qrDialog?.dismiss()
            return true
        }
        if (event.keyCode == KeyEvent.KEYCODE_BACK &&
            binding.kioskOverlay.kioskKeyboard.consumeBackForActiveDrawing(event.action)
        ) {
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN && coordinator.isKioskActive.value &&
            presentationState.currentTab.value == KioskTab.PINYIN &&
            isSearchKeyboardShowing() &&
            binding.kioskOverlay.kioskKeyboard.visibility == View.VISIBLE &&
            activity.currentFocus !is android.widget.EditText
        ) {
            val digit = when (event.keyCode) {
                KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_NUMPAD_0 -> '0'
                KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_NUMPAD_1 -> '1'
                KeyEvent.KEYCODE_2, KeyEvent.KEYCODE_NUMPAD_2 -> '2'
                KeyEvent.KEYCODE_3, KeyEvent.KEYCODE_NUMPAD_3 -> '3'
                KeyEvent.KEYCODE_4, KeyEvent.KEYCODE_NUMPAD_4 -> '4'
                KeyEvent.KEYCODE_5, KeyEvent.KEYCODE_NUMPAD_5 -> '5'
                KeyEvent.KEYCODE_6, KeyEvent.KEYCODE_NUMPAD_6 -> '6'
                KeyEvent.KEYCODE_7, KeyEvent.KEYCODE_NUMPAD_7 -> '7'
                KeyEvent.KEYCODE_8, KeyEvent.KEYCODE_NUMPAD_8 -> '8'
                KeyEvent.KEYCODE_9, KeyEvent.KEYCODE_NUMPAD_9 -> '9'
                else -> null
            }
            if (digit != null) {
                binding.kioskOverlay.kioskKeyboard.onRemoteDigitPressed(digit)
                return true
            }
        }
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
                        if (globalSearchReturnFocus != null || presentationState.currentTab.value != KioskTab.DASHBOARD) {
                            returnToDashboardFromBrowser()
                        } else {
                            val hasPlaying = currentSnapshot?.playing != null || currentSnapshot?.state == "playing"
                            val action = KioskLifecyclePolicy.resolveDashboardBackAction(hasPlaying)
                            if (action == KioskBackAction.RETURN_TO_FULLSCREEN_MV) {
                                backExitGate.reset()
                                bottomDockController.enterFullscreen()
                            } else if (!shouldExitOnBack()) {
                                backExitGate.reset()
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
