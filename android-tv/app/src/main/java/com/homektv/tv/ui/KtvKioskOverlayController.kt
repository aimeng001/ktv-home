package com.homektv.tv.ui

import android.graphics.Bitmap
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
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
import com.homektv.tv.controller.KioskPersonalPresentationPolicy
import com.homektv.tv.controller.KioskPersonalView
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
import com.homektv.tv.player.EffectPlayer
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
    private val effectPlayer: EffectPlayer?,
    private val controllerActions: ControllerActions,
    private val catalogActions: ControllerCatalogActions,
    private val personalActions: ControllerPersonalActions,
    private val onTogglePlayback: () -> Unit,
    private val onNext: () -> Unit,
    private val onRestart: () -> Unit,
    private val onToggleVocal: () -> Unit,
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
    private val contentRequirementMeasurer = KioskContentRequirementMeasurer(activity)
    private val transientFeedbackHost = KioskTransientFeedbackHost(
        kioskOverlayBinding.kioskTransientFeedback,
        activity.lifecycleScope,
    )
    private val pipObservedPlayer = bottomDockController.playerView.player
    private var pipSpaceAvailable = false
    private var pipResponsiveUpdatePosted = false
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
    private val pipLayoutChangeListener = View.OnLayoutChangeListener {
            _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom,
        ->
        if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
            schedulePipResponsiveSizeUpdate()
        }
    }
    private val pipRecyclerAttachListener = object : RecyclerView.OnChildAttachStateChangeListener {
        override fun onChildViewAttachedToWindow(view: View) {
            schedulePipResponsiveSizeUpdate()
        }

        override fun onChildViewDetachedFromWindow(view: View) = Unit
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
    private val browserSongAdapter = KtvKioskBrowserSongAdapter(
        onOrder = ::orderSong,
        onOrderTop = ::orderSongTop,
        onToggleFavorite = personalActionRouter::toggleFavorite,
        imageLoader = ::loadSingerAvatar,
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
    private var categoryFocusRestorePending = false
    private var playlistDetail = false
    private var externalDisplayActive = initialExternalDisplayActive
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
        browserBinding.root.addOnLayoutChangeListener(pipLayoutChangeListener)
        overlay.kioskContentStage.addOnLayoutChangeListener(pipLayoutChangeListener)
        listOf(
            overlay.kioskMainContent,
            overlay.kioskDashboardView,
            overlay.panelPinyin,
            overlay.panelSingers,
            overlay.panelRankings,
            overlay.panelCategories,
            overlay.panelPersonal,
        ).forEach { it.addOnLayoutChangeListener(pipLayoutChangeListener) }

        // 1. 列表布局绑定
        overlay.searchRecyclerView.layoutManager = LinearLayoutManager(activity)
        overlay.searchRecyclerView.adapter = searchAdapter

        overlay.singerRecyclerView.layoutManager = LinearLayoutManager(activity)
        overlay.singerRecyclerView.adapter = singerAdapter

        browserBinding.browserResultsRecyclerView.layoutManager = GridLayoutManager(activity, 3)
        browserBinding.browserResultsRecyclerView.adapter = browserSongAdapter
        browserBinding.browserResultsRecyclerView.addOnChildAttachStateChangeListener(pipRecyclerAttachListener)
        browserBinding.browserSingerRecyclerView.layoutManager = GridLayoutManager(activity, 4)
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

        listOf(
            overlay.searchRecyclerView,
            overlay.singerRecyclerView,
            overlay.rankingRecyclerView,
            overlay.categoryRecyclerView,
            overlay.personalRecyclerView,
        ).forEach { recycler ->
            recycler.addOnChildAttachStateChangeListener(pipRecyclerAttachListener)
        }
        overlay.kioskKeyboard.binding.keyboardContentRoot.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            schedulePipResponsiveSizeUpdate()
        }

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
        installPaging(browserBinding.browserResultsRecyclerView) {
            if (browsingAllSongs && presentationState.currentTab.value == KioskTab.PINYIN) {
                catalogActionRouter.moreAllSongs()
            } else if (presentationState.selectedArtist.value != null || categoryDetail) {
                catalogActions.loadMoreCatalog()
            } else if (presentationState.currentTab.value == KioskTab.PINYIN) {
                catalogActions.loadMoreSearch()
            }
        }
        installPaging(overlay.categoryRecyclerView) {
            if (categoryDetail) catalogActions.loadMoreCatalog()
        }

        // 2. 标签切换绑定
        overlay.tabDashboard.setOnClickListener {
            presentationState.selectTab(KioskTab.DASHBOARD)
        }
        overlay.kioskDashboardView.onTileSelected = { tile ->
            coordinator.resetIdleTimer()
            dashboardPolicy.onTileClicked(tile)
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
        overlay.tabPinyin.setOnClickListener {
            selectPinyinTab()
        }
        overlay.tabSingers.setOnClickListener { presentationState.selectTab(KioskTab.SINGERS) }
        overlay.tabRankings.setOnClickListener { presentationState.selectTab(KioskTab.RANKINGS) }
        overlay.tabCategories.setOnClickListener { openCategoryRoot(KioskCategoryMode.TAGS) }
        overlay.tabLanguages.setOnClickListener { openCategoryRoot(KioskCategoryMode.LANGUAGES) }
        overlay.tabFavorites.setOnClickListener { presentationState.selectTab(KioskTab.FAVORITES) }
        overlay.tabPlaylists.setOnClickListener { presentationState.selectTab(KioskTab.PLAYLISTS) }
        overlay.tabHistory.setOnClickListener { presentationState.selectTab(KioskTab.HISTORY) }

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
            showVarietyPanel(false)
            presentationState.selectTab(KioskTab.DASHBOARD)
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
        overlay.kioskKeyboard.onLayoutModeChanged = {
            schedulePipResponsiveSizeUpdate()
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
                schedulePipResponsiveSizeUpdate()
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
        if (!isThreeColumnTab(presentationState.currentTab.value)) {
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
        if (state.artists.isNotEmpty()) {
            artistsLoaded = true
        }
        if (state.ranking.isNotEmpty()) rankingsLoaded = true
        if (state.newSongs.isNotEmpty()) categoryLoadPolicy.markLoaded(KioskCategoryMode.NEW)
        if (state.languages.isNotEmpty()) categoryLoadPolicy.markLoaded(KioskCategoryMode.LANGUAGES)
        if (state.tags.isNotEmpty()) categoryLoadPolicy.markLoaded(KioskCategoryMode.TAGS)

        when {
            state.query.isNotBlank() -> {
                val searchError = state.errorFor(com.homektv.tv.controller.UiDomain.SEARCH)
                overlay.txtSearchCount.text = when {
                    state.loading -> "正在检索: ${state.query} ..."
                    searchError != null -> "检索失败：${state.messageFor(com.homektv.tv.controller.UiDomain.SEARCH) ?: "请稍后重试"}"
                    else -> "找到 ${state.results.size} 首歌曲"
                }
            }
            selectedArtist != null -> {
                val catalogError = state.errorFor(com.homektv.tv.controller.UiDomain.CATALOG)
                overlay.txtSearchCount.text = if (catalogError != null) {
                    "歌手【$selectedArtist】加载失败：${state.messageFor(com.homektv.tv.controller.UiDomain.CATALOG) ?: "请稍后重试"}"
                } else {
                    "歌手【$selectedArtist】共 ${state.catalogSongs.size} 首"
                }
            }
            else -> {
                overlay.txtSearchCount.text = "支持歌名/歌手、中文、全拼和首字母搜索"
            }
        }

        renderSearchEmptyState()
        renderThreeColumnBrowser(state)
        schedulePipResponsiveSizeUpdate()

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
                        val activeTab = presentationState.currentTab.value
                        val legacyCategoryPanelIsActive = activeTab == KioskTab.CATEGORIES &&
                            !isThreeColumnTab(activeTab) && overlay.panelCategories.isShown
                        if (legacyCategoryPanelIsActive && overlay.btnCategoryRetry.isShown) {
                            overlay.btnCategoryRetry.requestFocus()
                        }
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
        browser.browserKeyboardContainer.visibility = if (shouldShowKeyboard) View.VISIBLE else View.GONE
        binding.kioskOverlay.kioskKeyboard.visibility = if (shouldShowKeyboard) View.VISIBLE else View.GONE
        browser.browserFilterScroll.visibility = View.GONE
        browser.browserSingerPanel.visibility = View.GONE
        browser.txtBrowserCenterHint.visibility = View.GONE
        browser.browserInitialRail.visibility = View.VISIBLE
        browser.browserFilterOptions.removeAllViews()

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
                tab == KioskTab.CATEGORIES && categoryDetail && state.catalogLoading -> "正在加载分类歌曲…"
                tab == KioskTab.CATEGORIES && categoryDetail && state.errorFor(com.homektv.tv.controller.UiDomain.CATALOG) != null ->
                    "分类歌曲加载失败，按确定重试"
                tab == KioskTab.CATEGORIES && !categoryDetail -> commercialRouteNotice ?: "选择一个分类查看歌曲"
                else -> "暂无歌曲"
            }
            val retryAllSongs = tab == KioskTab.PINYIN && browsingAllSongs &&
                state.kioskLibraryBrowse.error != null
            val retryCategory = tab == KioskTab.CATEGORIES && categoryDetail &&
                state.errorFor(com.homektv.tv.controller.UiDomain.CATALOG) != null && state.catalogValue.isNotBlank()
            browser.txtBrowserEmptyState.isFocusable = retryAllSongs || retryCategory
            browser.txtBrowserEmptyState.setOnClickListener {
                when {
                    retryAllSongs -> catalogActionRouter.allSongs()
                    retryCategory && categoryMode == KioskCategoryMode.LANGUAGES -> catalogActionRouter.languageSongs(state.catalogValue)
                    retryCategory -> catalogActionRouter.tagSongs(state.catalogValue)
                }
            }
            browser.txtBrowserEmptyState.contentDescription = when {
                retryAllSongs -> "全曲库加载失败，按确定重试"
                retryCategory -> "分类歌曲加载失败，按确定重试"
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
                browser.txtBrowserCenterTitle.text = if (browsingAllSongs) "全部曲库" else "拼音 / 简拼搜索"
                browser.txtBrowserCenterHint.text = ""
                browser.txtBrowserResultsStatus.text = when {
                    selectedArtist != null -> "歌手「$selectedArtist」 · ${state.catalogSongs.size} 首"
                    browsingAllSongs && state.kioskLibraryBrowse.loading -> "正在加载全曲库…"
                    browsingAllSongs && state.kioskLibraryBrowse.error != null ->
                        "全曲库加载失败：${ControllerStateReducer.userMessage(state.kioskLibraryBrowse.error)}"
                    browsingAllSongs -> "全部曲库 · ${state.kioskLibraryBrowse.total} 首"
                    state.loading && state.query.isNotBlank() -> "正在检索：${state.query}…"
                    state.errorFor(com.homektv.tv.controller.UiDomain.SEARCH) != null ->
                        "检索失败：${state.messageFor(com.homektv.tv.controller.UiDomain.SEARCH) ?: "请稍后重试"}"
state.query.isNotBlank() -> "找到 ${state.results.size} 首歌曲 · ${state.query}"
                    visibleSongs.isEmpty() -> "输入歌名、歌手或首字母搜索"
                    else -> "热门推荐 · 共 ${visibleSongs.size} 首"
                }
            }
            KioskTab.SINGERS -> {
                browser.txtBrowserCenterTitle.text = if (selectedArtist == null) "选择歌手" else "歌手歌曲"
                browser.txtBrowserResultsStatus.text = when {
                    selectedArtist != null && state.errorFor(com.homektv.tv.controller.UiDomain.CATALOG) != null ->
                        "歌手歌曲加载失败：${state.messageFor(com.homektv.tv.controller.UiDomain.CATALOG) ?: "请稍后重试"}"
                    selectedArtist != null -> "歌手「$selectedArtist」 · ${state.catalogSongs.size} 首"
                    state.catalogLoading -> "正在加载歌手列表…"
                    state.catalogStatus.code == "ARTIST_REGION_FILTER_UNCONFIRMED" ->
                        state.catalogStatus.message ?: "地区筛选尚未确认，未显示未过滤结果"
                    state.errorFor(com.homektv.tv.controller.UiDomain.CATALOG) != null ->
                        "歌手列表加载失败：${state.messageFor(com.homektv.tv.controller.UiDomain.CATALOG) ?: "请稍后重试"}"
                    else -> "选择歌手开始点歌 · ${state.artists.size} 位"
                }
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
                browser.txtBrowserCenterTitle.text = "榜单筛选"
                browser.txtBrowserResultsStatus.text = when {
                    state.catalogLoading -> "正在加载热歌榜…"
                    state.errorFor(com.homektv.tv.controller.UiDomain.CATALOG) != null ->
                        "热歌榜加载失败：${state.messageFor(com.homektv.tv.controller.UiDomain.CATALOG) ?: "请稍后重试"}"
                    else -> "近 30 天热门歌曲 · ${state.ranking.size} 首"
                }
                if (state.errorFor(com.homektv.tv.controller.UiDomain.CATALOG) != null) {
                    showBrowserRetry("重试热歌榜", ::loadRankings)
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
                browser.txtBrowserCenterTitle.text = if (categoryDetail && commercialRouteNotice == null) "${label}歌曲" else "选择$label"
                if (categoryMode == KioskCategoryMode.NEW) {
                    browser.txtBrowserCenterTitle.text = "新歌速递"
                    browser.txtBrowserResultsStatus.text = "最新入库歌曲 · ${state.newSongs.size} 首"
                    if (state.errorFor(com.homektv.tv.controller.UiDomain.CATALOG) != null) {
                        browser.txtBrowserResultsStatus.text =
                            "新歌加载失败：${state.messageFor(com.homektv.tv.controller.UiDomain.CATALOG) ?: "请稍后重试"}"
                        showBrowserRetry("重试加载新歌", ::loadCategories)
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
                    browser.txtBrowserResultsStatus.text = when {
                        commercialRouteNotice != null -> commercialRouteNotice
                        categoryDetail -> "${label}歌曲 · ${state.catalogSongs.size} 首"
                        state.catalogLoading -> "正在加载$label…"
                        state.errorFor(com.homektv.tv.controller.UiDomain.CATALOG) != null ->
                            "${label}加载失败：${state.messageFor(com.homektv.tv.controller.UiDomain.CATALOG) ?: "请稍后重试"}"
                        else -> "选择$label · ${options.size} 项"
                    }
                    if (categoryDetail && showFilterList) {
                        addBrowserFilterOption("返回${label}列表", selected = false, onClick = ::resetCategoryDetail)
                    }
                    if (state.errorFor(com.homektv.tv.controller.UiDomain.CATALOG) != null && !state.catalogLoading) {
                        if (showFilterList) {
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
                browser.txtBrowserCenterTitle.text = "我的收藏"
                browser.txtBrowserResultsStatus.text = when {
                    state.favoritesLoading -> "正在加载收藏…"
                    state.errorFor(com.homektv.tv.controller.UiDomain.FAVORITES) != null ->
                        "收藏暂不可用：${state.messageFor(com.homektv.tv.controller.UiDomain.FAVORITES) ?: "请稍后重试"}"
                    else -> "我的收藏 · ${state.favorites.size} 首"
                }
                if (state.errorFor(com.homektv.tv.controller.UiDomain.FAVORITES) != null) {
                    showBrowserRetry("重试加载收藏", ::loadFavorites)
                } else {
                    showBrowserHint("收藏歌曲\n在此点唱")
                }
            }
            KioskTab.PLAYLISTS -> {
                browser.txtBrowserCenterTitle.text = if (playlistDetail) "歌单歌曲" else "精选歌单"
                val playlists = state.playlists
                browser.txtBrowserResultsStatus.text = when {
                    playlistDetail -> "${state.playlistDetail?.name ?: "歌单"} · ${state.playlistDetail?.songs?.size ?: 0} 首"
                    state.playlistDetailLoading -> "正在加载歌单详情…"
                    state.errorFor(com.homektv.tv.controller.UiDomain.PLAYLISTS) != null ->
                        "歌单加载失败：${state.messageFor(com.homektv.tv.controller.UiDomain.PLAYLISTS) ?: "请稍后重试"}"
                    else -> "精选歌单 · ${playlists.size} 个"
                }
                browser.browserFilterScroll.visibility = View.VISIBLE
                if (playlistDetail) {
                    addBrowserFilterOption("返回歌单列表", selected = false) {
                        playlistDetail = false
                        personalActionRouter.clearPlaylistDetail()
                        personalActionRouter.playlists()
                    }
                }
                if (state.errorFor(com.homektv.tv.controller.UiDomain.PLAYLISTS) != null && !state.playlistDetailLoading) {
                    addBrowserFilterOption("重试加载歌单", selected = false, onClick = ::loadPlaylists)
                }
                playlists.forEach { item ->
                    val countStr = "    ${item.songCount}首"
                    addBrowserFilterOption(
                        label = "${item.name}$countStr",
                        selected = state.playlistDetail?.id == item.id,
                    ) { openPlaylist(item) }
                }
            }
            KioskTab.HISTORY -> {
                browser.txtBrowserCenterTitle.text = "已唱历史"
                browser.txtBrowserResultsStatus.text = when {
                    state.historyLoading -> "正在加载演唱历史…"
                    state.errorFor(com.homektv.tv.controller.UiDomain.HISTORY) != null ->
                        "演唱历史暂不可用：${state.messageFor(com.homektv.tv.controller.UiDomain.HISTORY) ?: "请稍后重试"}"
                    else -> "最近唱过 · ${visibleSongs.size} 首"
                }
                if (state.errorFor(com.homektv.tv.controller.UiDomain.HISTORY) != null) {
                    showBrowserRetry("重试加载历史", ::loadHistory)
                } else {
                    showBrowserHint("最近唱过的\n歌曲")
                }
            }
            else -> Unit
        }

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
            when {
                browsingAllSongs && state.kioskLibraryBrowse.hasMore -> {
                    pendingBrowserPageTarget = target
                    catalogActionRouter.moreAllSongs()
                }
                (presentationState.selectedArtist.value != null || categoryDetail) && state.catalogHasMore -> {
                    pendingBrowserPageTarget = target
                    catalogActions.loadMoreCatalog()
                }
                presentationState.currentTab.value == KioskTab.PINYIN && state.searchHasMore -> {
                    pendingBrowserPageTarget = target
                    catalogActions.loadMoreSearch()
                }
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
        container.removeAllViews()

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
            else -> emptyList()
        }

        if (subcategories.isEmpty()) {
            rail.visibility = View.GONE
            for (index in 0 until browserBinding.browserCapsuleTabs.childCount) {
                browserBinding.browserCapsuleTabs.getChildAt(index).nextFocusDownId = View.NO_ID
            }
            return
        }

        rail.visibility = View.VISIBLE
        val activeTopButton = commercialRouteButton(route)
        subcategories.forEachIndexed { index, (label, selected, action) ->
            val btn = Button(activity, null, android.R.attr.buttonBarButtonStyle).apply {
                text = label
                textSize = 13f
                setTextColor(0xFFFFFFFF.toInt())
                background = ContextCompat.getDrawable(activity, R.drawable.bg_ktv_capsule_subtab)
                isSelected = selected
                isFocusable = true
                minWidth = 0
                setPadding(dp(12), 0, dp(12), 0)
                activeTopButton?.let { nextFocusUpId = it.id }
                nextFocusDownId = if (tab == KioskTab.SINGERS) {
                    browserBinding.browserSingerRecyclerView.id
                } else {
                    browserBinding.browserResultsRecyclerView.id
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    dp(30),
                ).apply {
                    marginEnd = dp(6)
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

    private fun showBrowserRetry(label: String, retry: () -> Unit) {
        val shouldShowKeyboard = KtvGlobalSearchPolicy.shouldShowKeyboard(
            tab = presentationState.currentTab.value,
            isVariety = browserBinding.panelVarietyShows.visibility == View.VISIBLE,
            enabled = globalSearchEnabled(),
        )
        if (shouldShowKeyboard) {
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
        rail.removeAllViews()
        val availableInitials = state.artistInitials
            .map { it.trim().uppercase() }
            .filter { it.length == 1 && it[0] in 'A'..'Z' }
            .distinct()
        val initials = if (availableInitials.isEmpty() && state.artistRegion.isNotBlank()) {
            emptyList()
        } else {
            availableInitials.ifEmpty { ('A'..'Z').map(Char::toString) }
        }
        initials.forEach { initial ->
            val button = Button(activity).apply {
                text = initial
                isFocusable = true
                minWidth = 0
                setPadding(0, 0, 0, 0)
                setBackgroundResource(
                    if (state.artistInitial.equals(initial, ignoreCase = true)) R.drawable.btn_gold
                    else R.drawable.btn_keyboard_key,
                )
                setTextColor(
                    if (state.artistInitial.equals(initial, ignoreCase = true)) 0xFF111317.toInt()
                    else ContextCompat.getColor(activity, R.color.color_keyboard_key_text),
                )
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(38),
                ).apply { bottomMargin = dp(3) }
                setOnClickListener {
                    coordinator.resetIdleTimer()
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
            rail.addView(button)
        }
    }

    private fun addBrowserFilterOption(
        label: String,
        selected: Boolean,
        onClick: () -> Unit,
    ) {
        val button = Button(activity).apply {
            text = label
            isFocusable = true
            minWidth = 0
            setBackgroundResource(if (selected) R.drawable.btn_gold else R.drawable.bg_ktv_card_focused)
            setTextColor(
                if (selected) 0xFF111317.toInt()
                else ContextCompat.getColor(activity, R.color.color_keyboard_key_text),
            )
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(48),
            ).apply { bottomMargin = dp(6) }
            setOnClickListener {
                coordinator.resetIdleTimer()
                onClick()
            }
        }
        browserBinding.browserFilterOptions.addView(button)
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
            btn.setTextColor(ContextCompat.getColorStateList(activity, R.color.ktv_kiosk_nav_text))
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

        overlay.kioskNavTabs.visibility = if (isThreeColumnTab) View.GONE else View.VISIBLE
        browserBinding.root.visibility = if (isThreeColumnTab) View.VISIBLE else View.GONE
        overlay.kioskDashboardView.visibility = if (tab == KioskTab.DASHBOARD) View.VISIBLE else View.GONE
        overlay.panelPinyin.visibility = if (!isThreeColumnTab && tab == KioskTab.PINYIN) View.VISIBLE else View.GONE
        overlay.panelSingers.visibility = if (!isThreeColumnTab && tab == KioskTab.SINGERS) View.VISIBLE else View.GONE
        overlay.panelRankings.visibility = if (!isThreeColumnTab && tab == KioskTab.RANKINGS) View.VISIBLE else View.GONE
        overlay.panelCategories.visibility = if (!isThreeColumnTab && tab == KioskTab.CATEGORIES) View.VISIBLE else View.GONE
        overlay.panelPersonal.visibility = if (
            !isThreeColumnTab && tab == KioskTab.PLAYLISTS
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
            leaveKioskAllSongs()
            val wasArtistSelected = presentationState.selectedArtist.value != null
            presentationState.clearArtistSelection()
            focusController.hasInnerDetailBack = false
            if (wasArtistSelected) {
                overlay.kioskKeyboard.clear()
                overlay.txtSearchCount.text = "输入首字母检索曲目"
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
            KioskTab.PLAYLISTS -> loadPlaylists()
            KioskTab.HISTORY -> loadHistory()
        }
        renderThreeColumnBrowser(controllerState)
        if (isThreeColumnTab(tab) && !browserHadFocus) {
            val focusTarget = threeColumnFocusTarget(tab)
            focusTarget.post {
                if (presentationState.currentTab.value == tab && browserBinding.root.visibility == View.VISIBLE) {
                    focusTarget.requestFocus()
                }
            }
        }
        renderSearchEmptyState()
        schedulePipResponsiveSizeUpdate()
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
            binding.kioskOverlay.txtSearchCount.text = "支持歌名/歌手、中文、全拼和首字母搜索"
            loadDefaultSongs()
            return
        }

        val targetTab = KtvGlobalSearchPolicy.targetTabForQuery(currentTab, globalSearchIsEnabled)
        if (targetTab != currentTab) {
            presentationState.selectTab(targetTab, KioskCommercialRoute.SONG_NAME)
        }

        binding.kioskOverlay.txtSearchCount.text = activity.getString(R.string.searching_keyword, normalizedKeyword)
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
            binding.kioskOverlay.txtSearchCount.text = "输入首字母检索曲目"
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
            commercialRouteNotice = "当前曲库没有精确的“$exactName”标签；可从右侧现有标签中选择。"
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
            commercialRouteNotice = "标签列表加载失败，暂时无法确认对应分类；可在右侧重试。"
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
            commercialRouteNotice = "当前曲库没有精确的“$exactName”标签；可从右侧现有标签中选择。"
        }
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
            button.setTextColor(ContextCompat.getColorStateList(activity, R.color.ktv_kiosk_nav_text))
        }
    }

    private fun openCategoryRoot(mode: KioskCategoryMode, route: KioskCommercialRoute? = null) {
        leaveKioskAllSongs()
        showVarietyPanel(false)
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
            presentationState.selectTab(KioskTab.CATEGORIES, route)
            updateCategoryNavSelection(KioskTab.CATEGORIES)
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
        binding.kioskOverlay.txtSearchCount.text = activity.getString(R.string.loading_artist_songs, artist.name)
        browserBinding.browserResultsRecyclerView.post {
            browserBinding.browserResultsRecyclerView.requestFocus()
        }
        catalogActions.setQuery("")
        catalogActionRouter.artistSongs(artist.artistKey)
    }

    private fun onCategorySelected(item: com.homektv.tv.net.NamedCount) {
        leaveKioskAllSongs()
        coordinator.resetIdleTimer()
        binding.kioskOverlay.categoryRecyclerView.adapter = categoryAdapter
        binding.kioskOverlay.txtCategoryHeader.text = when (categoryMode) {
            KioskCategoryMode.LANGUAGES -> "语种 · ${item.name}"
            KioskCategoryMode.TAGS -> "标签 · ${item.name}"
            KioskCategoryMode.NEW -> "最新入库曲目推荐"
        }
        when (categoryMode) {
            KioskCategoryMode.LANGUAGES -> selectQuickLanguage(item.name)
            KioskCategoryMode.TAGS -> selectCommercialTag(item)
            KioskCategoryMode.NEW -> Unit
        }
        browserBinding.browserResultsRecyclerView.requestFocus()
        schedulePipResponsiveSizeUpdate()
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
        schedulePipResponsiveSizeUpdate()
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
            schedulePipResponsiveSizeUpdate()
            if (keyboardWasVisible && presentationState.currentTab.value == KioskTab.PINYIN) {
                overlay.kioskKeyboard.visibility = View.VISIBLE
                schedulePipResponsiveSizeUpdate()
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
        schedulePipResponsiveSizeUpdate()
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
            dialog.submitQueue(
                nowPlayingText = currentTitle,
                waitingList = snapshot.list.filter { it.status == "waiting" },
                isPlaying = snapshot.state == "playing",
            )
            val staleMessage = when {
                state.queueLoading -> "正在同步队列；当前显示最后一次已同步的数据。"
                queueError != null -> "队列刷新失败，当前显示最后一次已同步的数据：${state.messageFor(com.homektv.tv.controller.UiDomain.QUEUE) ?: "请重试"}"
                state.connection == ControllerConnection.OFFLINE -> "点歌服务离线，当前显示最后一次已同步的数据。"
                else -> null
            }
            dialog.setQueueStatus(staleMessage, retryAvailable = !state.queueLoading)
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
        searchAdapter.updateQueueProjection(queueProjection)
        rankingAdapter.updateQueueProjection(queueProjection)
        categoryAdapter.updateQueueProjection(queueProjection)
        personalSongAdapter.updateQueueProjection(queueProjection)

        val title = snapshot.playing?.song?.title
        val artist = snapshot.playing?.song?.artist
        val waitingCount = snapshot.list.count { it.status == "waiting" }

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
            }
        } else {
            focusController.hasInnerDetailBack = false
            presentationState.clearArtistSelection()
            binding.kioskOverlay.kioskKeyboard.clear()
            binding.kioskOverlay.txtSearchCount.text = "输入首字母检索曲目"
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
        val effectivePipVisible = state.videoVisible && pipSpaceAvailable
        bottomDockController.setVideoVisible(state.videoVisible && !externalDisplayActive)
        if (!effectivePipVisible && binding.kioskOverlay.btnKioskExit.hasFocus()) {
            binding.kioskOverlay.tabHistory.requestFocus()
        }
        binding.kioskOverlay.btnKioskExit.visibility =
            if (effectivePipVisible) View.VISIBLE else View.GONE
        binding.kioskOverlay.tabHistory.nextFocusRightId =
            if (effectivePipVisible) R.id.btnKioskExit else R.id.tabDashboard
        binding.kioskOverlay.pipVideoFrame.visibility = View.GONE
        val playingTitle = currentSnapshot?.playing?.song?.let { song ->
            listOfNotNull(song.title, song.artist?.takeIf(String::isNotBlank)).joinToString(" · ")
        }.orEmpty()
        binding.kioskOverlay.txtPipLabel.text = listOf(state.label, playingTitle)
            .filter(String::isNotBlank)
            .joinToString("  ")
    }

    private fun schedulePipResponsiveSizeUpdate() {
        if (pipResponsiveUpdatePosted) return
        val stage = binding.kioskOverlay.kioskContentStage
        pipResponsiveUpdatePosted = true
        if (!stage.post {
                pipResponsiveUpdatePosted = false
                updatePipResponsiveSize()
            }
        ) {
            pipResponsiveUpdatePosted = false
        }
    }

    private fun updatePipResponsiveSize() {
        val stage = binding.kioskOverlay.kioskContentStage
        val availableWidthPx = stage.width - stage.paddingLeft - stage.paddingRight
        val availableHeightPx = stage.height - stage.paddingTop - stage.paddingBottom
        if (availableWidthPx <= 0 || availableHeightPx <= 0) return

        val density = activity.resources.displayMetrics.density
        val requirement = activePanelRequirement()
        if (requirement == null) {
            pipSpaceAvailable = false
            renderPipState()
            return
        }
        val size = KioskPipResponsivePolicy.resolve(
            availableWidthDp = (availableWidthPx / density).toInt(),
            availableHeightDp = (availableHeightPx / density).toInt(),
            minContentHeightDp = requirement.minimumHeightDp,
        )
        val frame = binding.kioskOverlay.pipVideoFrame
        val params = frame.layoutParams as? ConstraintLayout.LayoutParams ?: return
        val widthPx = (size.widthDp * density).toInt()
        params.matchConstraintMinWidth = widthPx
        params.matchConstraintMaxWidth = widthPx
        val drawerOnRight = stage.layoutDirection != View.LAYOUT_DIRECTION_RTL
        val drawerPaddingPx = if (drawerOnRight) stage.paddingRight else stage.paddingLeft
        val drawerOcclusionPx = if (queueDrawerVisible) {
            (activity.resources.getDimensionPixelSize(R.dimen.ktv_queue_drawer_width) - drawerPaddingPx)
                .coerceAtLeast(0)
        } else {
            0
        }
        params.horizontalBias = QueueDrawerPipPositionPolicy.horizontalBias(
            parentWidthPx = availableWidthPx,
            pipWidthPx = widthPx,
            drawerWidthPx = drawerOcclusionPx,
            drawerVisibleOnRight = drawerOnRight,
        )
        frame.layoutParams = params
        pipSpaceAvailable = size.visible
        renderPipState()
    }

    private fun activePanelRequirement(): KioskContentHeightRequirement? {
        val overlay = binding.kioskOverlay
        return when {
            overlay.kioskDashboardView.visibility == View.VISIBLE -> contentRequirementMeasurer.dashboard(
                overlay.kioskDashboardView,
            )
            overlay.panelPinyin.visibility == View.VISIBLE -> contentRequirementMeasurer.pinyin(overlay)
            overlay.panelSingers.visibility == View.VISIBLE -> contentRequirementMeasurer.listPage(
                chrome = listOf(overlay.singerFilterRow, overlay.txtSingerStatus, overlay.btnSingerRetry),
                recycler = overlay.singerRecyclerView,
                fallbackItemLayout = R.layout.item_singer_card,
            )
            overlay.panelRankings.visibility == View.VISIBLE -> contentRequirementMeasurer.listPage(
                chrome = listOf(overlay.txtRankingHeader),
                recycler = overlay.rankingRecyclerView,
                fallbackItemLayout = R.layout.item_kiosk_song_row,
            )
            overlay.panelCategories.visibility == View.VISIBLE -> {
                val fallbackLayout = if (overlay.categoryRecyclerView.adapter === categoryNameAdapter) {
                    R.layout.item_kiosk_named_count
                } else {
                    R.layout.item_kiosk_song_row
                }
                contentRequirementMeasurer.listPage(
                    chrome = listOf(overlay.categoryFilterRow, overlay.txtCategoryHeader),
                    recycler = overlay.categoryRecyclerView,
                    fallbackItemLayout = fallbackLayout,
                    statusPanel = overlay.categoryErrorPanel,
                )
            }
            overlay.panelPersonal.visibility == View.VISIBLE -> contentRequirementMeasurer.listPage(
                chrome = listOf(overlay.txtPersonalHeader),
                recycler = overlay.personalRecyclerView,
                fallbackItemLayout = null,
                statusPanel = overlay.personalStatusPanel,
            )
            else -> null
        }
    }

    fun destroy() {
        transientFeedbackHost.clear()
        pipObservedPlayer?.removeListener(pipPlaybackListener)
        binding.kioskOverlay.kioskContentStage.removeOnLayoutChangeListener(pipLayoutChangeListener)
        browserBinding.root.removeOnLayoutChangeListener(pipLayoutChangeListener)
        listOf(
            binding.kioskOverlay.kioskMainContent,
            binding.kioskOverlay.kioskDashboardView,
            binding.kioskOverlay.panelPinyin,
            binding.kioskOverlay.panelSingers,
            binding.kioskOverlay.panelRankings,
            binding.kioskOverlay.panelCategories,
            binding.kioskOverlay.panelPersonal,
        ).forEach { it.removeOnLayoutChangeListener(pipLayoutChangeListener) }
        listOf(
            binding.kioskOverlay.searchRecyclerView,
            binding.kioskOverlay.singerRecyclerView,
            binding.kioskOverlay.rankingRecyclerView,
            binding.kioskOverlay.categoryRecyclerView,
            binding.kioskOverlay.personalRecyclerView,
        ).forEach { it.removeOnChildAttachStateChangeListener(pipRecyclerAttachListener) }
        browserBinding.browserResultsRecyclerView.removeOnChildAttachStateChangeListener(pipRecyclerAttachListener)
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
        const val SINGER_GRID_PAGE_SIZE = 8
        const val MAX_VISIBLE_CATALOG_ITEMS = 2000
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
        val legacyTarget = if (categoryMode == KioskCategoryMode.LANGUAGES) {
            binding.kioskOverlay.btnCategoryLanguages
        } else binding.kioskOverlay.btnCategoryTags
        val target = if (browserBinding.root.visibility == View.VISIBLE) {
            browserBinding.browserFilterOptions.getChildAt(0) ?: legacyTarget
        } else legacyTarget
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
            presentationState.currentTab.value == KioskTab.PINYIN
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
                        if (globalSearchReturnFocus != null) {
                            backExitGate.reset()
                            toggleKiosk(false)
                        } else if (presentationState.currentTab.value != KioskTab.DASHBOARD) {
                            dashboardPolicy.handleBack()
                            presentationState.selectTab(KioskTab.DASHBOARD)
                            backExitGate.reset()
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
