package com.homektv.tv.ui.controller

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.core.view.descendants
import androidx.core.view.setPadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.homektv.tv.R
import com.homektv.tv.ui.kiosk.KtvDashboardBackground
import com.homektv.tv.controller.ActionKey
import com.homektv.tv.controller.ControllerBackNavigationPolicy
import com.homektv.tv.controller.ControllerBackTarget
import com.homektv.tv.controller.CatalogLoadState
import com.homektv.tv.controller.ControllerConnection
import com.homektv.tv.controller.ControllerUiState
import com.homektv.tv.controller.ControllerViewModel
import com.homektv.tv.controller.ControllerViewModelFactory
import com.homektv.tv.controller.QueuePermissionPolicy
import com.homektv.tv.controller.UiDomain
import com.homektv.tv.databinding.FragmentControllerBinding
import com.homektv.tv.net.SongDto
import com.homektv.tv.ui.ArtworkDecoder
import com.homektv.tv.ui.ArtworkProfile
import com.homektv.tv.ui.SetupActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Native controller screen. The Activity only hosts this Fragment; all page
 * state and rendering stays inside the controller UI package.
 */
class ControllerFragment : Fragment() {

    private val viewModel: ControllerViewModel by lazy {
        ViewModelProvider(
            requireActivity(),
            ControllerViewModelFactory(
                requireActivity().application,
                requireArguments().getBoolean(ARG_REALTIME, true),
            ),
        )[ControllerViewModel::class.java]
    }
    private lateinit var binding: FragmentControllerBinding
    private lateinit var statusText: TextView
    private lateinit var searchInput: EditText
    private lateinit var resultContainer: LinearLayout
    private lateinit var resultList: RecyclerView
    private lateinit var catalogContainer: LinearLayout
    private lateinit var catalogList: RecyclerView
    private lateinit var artistFilterContainer: LinearLayout
    private lateinit var catalogTitle: TextView
    private lateinit var personalContainer: LinearLayout
    private lateinit var personalHeaderContainer: LinearLayout
    private lateinit var personalList: RecyclerView
    private lateinit var personalTitle: TextView
    private lateinit var queueNowText: TextView
    private lateinit var queueContainer: LinearLayout
    private lateinit var queueList: RecyclerView
    private lateinit var messageText: TextView
    private lateinit var volumeSeek: SeekBar
    private lateinit var seekBar: SeekBar
    private lateinit var contentScroll: ScrollView
    private val phonePanelViews = mutableMapOf<ControllerPhonePanel, List<View>>()
    private var rootView: ViewGroup? = null
    private var renderedQuery: String? = null
    private var personalMode = PersonalMode.FAVORITES
    private var catalogMode = CatalogMode.ARTISTS
    private var phonePanel = ControllerPhonePanel.CATALOG
    private var lastRenderedState: ControllerUiState? = null
    private var restoredArtistGender = ""
    private var restoredArtistInitial = ""
    private var restoredCatalogValue = ""
    private var restoredCatalogDetail = false
    private var restoredCatalogPage = 0
    private var restoredPlaylistDetailId = 0L
    private var restoredScrollY = 0
    private var playlistCoverJob: Job? = null
    private lateinit var panelAdapter: ControllerListAdapter<PanelRow>
    private lateinit var catalogAdapter: ControllerListAdapter<PanelRow>
    private lateinit var personalAdapter: ControllerListAdapter<PanelRow>
    private lateinit var queueAdapter: ControllerListAdapter<PanelRow>
    private lateinit var rowBinder: ControllerPanelRowBinder
    private val phoneNavButtons = mutableMapOf<ControllerPhonePanel, Button>()
    private var isUserAdjustingSeekBar = false
    private var lastUserSeekAdjustMs = 0L
    private val seekDebounceHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val commitSeekRunnable = Runnable {
        isUserAdjustingSeekBar = false
        val duration = viewModel.state.value.queue.playing?.song?.durationMs ?: 0
        if (duration > 0 && ::seekBar.isInitialized) {
            viewModel.control(
                "seek",
                mapOf("position_ms" to (duration.toLong() * seekBar.progress / 1000L)),
            )
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = FragmentControllerBinding.inflate(inflater, container, false).root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding = FragmentControllerBinding.bind(view)
        KtvDashboardBackground.applyTo(binding.root)
        savedInstanceState?.getString(KEY_PERSONAL_MODE)?.let {
            personalMode = runCatching {
                PersonalMode.valueOf(ControllerSavedStatePolicy.personalMode(it, isPhoneLayout()))
            }.getOrDefault(PersonalMode.FAVORITES)
        }
        savedInstanceState?.getString(KEY_CATALOG_MODE)?.let {
            catalogMode = runCatching {
                CatalogMode.valueOf(ControllerSavedStatePolicy.catalogMode(it))
            }.getOrDefault(CatalogMode.ARTISTS)
        }
        savedInstanceState?.getString(KEY_PHONE_PANEL)?.let {
            phonePanel = runCatching {
                ControllerPhonePanel.valueOf(ControllerSavedStatePolicy.phonePanel(it))
            }.getOrDefault(ControllerPhonePanel.CATALOG)
        }
        restoredArtistGender = savedInstanceState?.getString(KEY_ARTIST_GENDER).orEmpty()
        restoredArtistInitial = savedInstanceState?.getString(KEY_ARTIST_INITIAL).orEmpty()
        restoredCatalogValue = savedInstanceState?.getString(KEY_CATALOG_VALUE).orEmpty()
        restoredCatalogDetail = savedInstanceState?.getBoolean(KEY_CATALOG_DETAIL, false) == true
        restoredCatalogPage = savedInstanceState?.getInt(KEY_CATALOG_PAGE, 0)?.coerceIn(0, 64) ?: 0
        restoredPlaylistDetailId = savedInstanceState?.getLong(KEY_PLAYLIST_DETAIL_ID, 0L) ?: 0L
        restoredScrollY = savedInstanceState?.getInt(KEY_SCROLL_Y, 0)?.coerceAtLeast(0) ?: 0
        bindContent(view)
        view.post { rebuildFocusChain() }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when (ControllerBackNavigationPolicy.resolve(
                    playlistDetail = personalMode == PersonalMode.PLAYLISTS &&
                        viewModel.state.value.playlistDetail != null,
                    catalogDetail = viewModel.state.value.catalogDetail,
                )) {
                    ControllerBackTarget.CLOSE_PLAYLIST_DETAIL -> {
                        personalTitle.text = "歌单"
                        viewModel.clearPlaylistDetail()
                        viewModel.loadPlaylists()
                    }
                    ControllerBackTarget.CLOSE_CATALOG_DETAIL -> when (catalogMode) {
                        CatalogMode.ARTISTS -> {
                            catalogTitle.text = "歌手"
                            viewModel.loadArtists(
                                gender = viewModel.state.value.artistGender,
                                initial = viewModel.state.value.artistInitial,
                                restorePage = 0,
                            )
                        }
                        CatalogMode.LANGUAGES -> {
                            catalogTitle.text = "语种"
                            viewModel.loadLanguages()
                        }
                        CatalogMode.TAGS -> {
                            catalogTitle.text = "标签"
                            viewModel.loadTags()
                        }
                        else -> requireActivity().finish()
                    }
                    ControllerBackTarget.EXIT -> requireActivity().finish()
                }
            }
        })
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val value = s?.toString().orEmpty()
                if (renderedQuery != value) viewModel.setQuery(value)
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        savedInstanceState?.getString(KEY_QUERY)?.takeIf { it.isNotBlank() }?.let(searchInput::setText)
        viewLifecycleOwner.lifecycleScope.launch {
        viewLifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect(::render)
            }
        }
        viewModel.loadRoomHost()
        restorePanelData()
        contentScroll.post { contentScroll.scrollTo(0, restoredScrollY) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (::searchInput.isInitialized) {
            outState.putString(KEY_QUERY, searchInput.text?.toString().orEmpty())
            outState.putString(KEY_PERSONAL_MODE, personalMode.name)
            outState.putString(KEY_CATALOG_MODE, catalogMode.name)
            outState.putString(KEY_PHONE_PANEL, phonePanel.name)
            val snapshot = viewModel.state.value
            outState.putString(KEY_ARTIST_GENDER, snapshot.artistGender)
            outState.putString(KEY_ARTIST_INITIAL, snapshot.artistInitial)
            outState.putString(KEY_CATALOG_VALUE, snapshot.catalogValue)
            outState.putBoolean(KEY_CATALOG_DETAIL, snapshot.catalogDetail)
            outState.putInt(KEY_CATALOG_PAGE, snapshot.catalogPage)
            outState.putLong(KEY_PLAYLIST_DETAIL_ID, snapshot.playlistDetail?.id ?: 0L)
            outState.putInt(KEY_SCROLL_Y, contentScroll.scrollY)
        }
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        viewModel.setCatalogVisible(true)
        viewModel.refreshQueue()
    }

    override fun onPause() {
        viewModel.setCatalogVisible(false)
        super.onPause()
    }

    override fun onDestroyView() {
        seekDebounceHandler.removeCallbacksAndMessages(null)
        playlistCoverJob?.cancel()
        playlistCoverJob = null
        phonePanelViews.clear()
        phoneNavButtons.clear()
        rootView = null
        super.onDestroyView()
    }

    private fun isTelevisionDevice(): Boolean =
        requireContext().packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK) ||
            (requireContext().resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) ==
            Configuration.UI_MODE_TYPE_TELEVISION

    private fun bindContent(root: View) {
        binding = FragmentControllerBinding.bind(root)
        rootView = binding.root
        val television = isTelevisionDevice()
        val phone = !television && requireContext().resources.configuration.smallestScreenWidthDp < 600
        val tablet = !television && !phone
        contentScroll = binding.contentScroll
        binding.controllerContent.setPadding(
            dp(20), dp(20), dp(20), if (phone) dp(128) else dp(20),
        )
        (contentScroll.layoutParams as? android.widget.FrameLayout.LayoutParams)?.let { params ->
            params.bottomMargin = if (phone) dp(116) else 0
            contentScroll.layoutParams = params
        }
        binding.columns.orientation = if (tablet) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        binding.primaryColumn.layoutParams = if (tablet) columnParams() else matchWrapParams()
        binding.secondaryColumn.layoutParams = if (tablet) {
            columnParams(start = 16)
        } else {
            matchWrapParams()
        }

        statusText = binding.statusText
        searchInput = binding.searchInput
        resultContainer = binding.resultContainer
        resultList = binding.resultList
        catalogContainer = binding.catalogContainer
        catalogList = binding.catalogList
        artistFilterContainer = binding.artistFilterContainer
        catalogTitle = binding.catalogTitle
        personalContainer = binding.personalContainer
        personalHeaderContainer = binding.personalHeaderContainer
        personalList = binding.personalList
        personalTitle = binding.personalTitle
        queueNowText = binding.queueNowText
        queueContainer = binding.queueContainer
        queueList = binding.queueList
        messageText = binding.messageText
        volumeSeek = binding.volumeSeek
        seekBar = binding.seekBar
        listOf(catalogList, resultList, personalList, queueList).forEach(::configurePanelRecycler)
        setupPanelAdapters()

        fun openServerSetup() {
            startActivity(Intent(requireContext(), SetupActivity::class.java).apply {
                putExtra(SetupActivity.EXTRA_FORCE_SETUP, true)
                putExtra(SetupActivity.EXTRA_RETURN_TO_CALLER, true)
            })
        }

        binding.headerActions.removeAllViews()
        binding.headerActions.addView(button("重新连接") { viewModel.refreshQueue() }, wrapParams())
        binding.headerActions.addView(button("房主") {
            if (viewModel.state.value.roomHost.isHost) {
                confirmDangerous("释放房主身份", "释放后其他人可以认领房主身份。") { viewModel.releaseRoomHost() }
            } else {
                viewModel.claimRoomHost()
            }
        }, wrapParams())
        binding.headerActions.addView(button("服务器") {
            openServerSetup()
        }, wrapParams())
        binding.btnMyServerSetup.setOnClickListener { openServerSetup() }

        binding.catalogTabsContainer.removeViews(1, (binding.catalogTabsContainer.childCount - 1).coerceAtLeast(0))
        fun selectCatalog(mode: CatalogMode) {
            catalogMode = mode
            when (mode) {
                CatalogMode.ARTISTS -> {
                    catalogTitle.text = "歌手"
                    viewModel.loadArtists()
                }
                CatalogMode.LANGUAGES -> {
                    catalogTitle.text = "语种"
                    viewModel.loadLanguages()
                }
                CatalogMode.TAGS -> {
                    catalogTitle.text = "分类"
                    viewModel.loadTags()
                }
                else -> Unit
            }
        }
        binding.btnPhoneCatalogArtists.setOnClickListener { selectCatalog(CatalogMode.ARTISTS) }
        binding.btnPhoneCatalogLanguages.setOnClickListener { selectCatalog(CatalogMode.LANGUAGES) }
        binding.btnPhoneCatalogCategories.setOnClickListener { selectCatalog(CatalogMode.TAGS) }
        if (phone) {
            binding.catalogPhoneEntries.visibility = View.VISIBLE
        } else {
            binding.catalogPhoneEntries.visibility = View.GONE
            listOf(
                "歌手" to { selectCatalog(CatalogMode.ARTISTS) },
                "语种" to { selectCatalog(CatalogMode.LANGUAGES) },
                "分类" to { selectCatalog(CatalogMode.TAGS) },
            ).forEach { (title, action) ->
                binding.catalogTabsContainer.addView(button(title, action), wrapParams())
            }
        }

        binding.personalTabsContainer.removeViews(1, (binding.personalTabsContainer.childCount - 1).coerceAtLeast(0))
        val personalTabs = mutableListOf(
            "收藏" to {
                personalMode = PersonalMode.FAVORITES
                personalTitle.text = "收藏"
                viewModel.clearPlaylistDetail()
                viewModel.loadFavorites()
            },
            "最近唱过" to {
                personalMode = PersonalMode.HISTORY
                personalTitle.text = "最近唱过"
                viewModel.clearPlaylistDetail()
                viewModel.loadHistory()
            },
        )
        if (!phone) personalTabs.add(1, "主题歌单" to {
            personalMode = PersonalMode.PLAYLISTS
            personalTitle.text = "主题歌单"
            viewModel.loadPlaylists()
        })
        personalTabs.forEach { (title, action) ->
            binding.personalTabsContainer.addView(button(title, action), wrapParams())
        }

        binding.remoteControlsContainer.removeAllViews()
        listOf(
            "播放/暂停" to {
                val action = if (viewModel.state.value.queue.state == "playing") "pause" else "play"
                viewModel.control(action)
            },
            "下一首" to {
                confirmDangerous("确认切歌", "将跳过当前正在播放的歌曲。") { viewModel.control("next") }
            },
            "重唱" to { viewModel.control("restart") },
            "原唱" to { viewModel.control("set_vocal", mapOf("mode" to "original")) },
            "伴唱" to { viewModel.control("set_vocal", mapOf("mode" to "accompaniment")) },
            "交换声道" to { viewModel.control("swap_vocal_tracks") },
            "静音" to {
                viewModel.control("mute", mapOf("muted" to !viewModel.state.value.queue.muted))
            },
            "打散" to {
                confirmDangerous("确认打散", "将重新安排待唱歌曲顺序。") { viewModel.control("shuffle") }
            },
        ).forEach { (title, action) ->
            binding.remoteControlsContainer.addView(button(title, action), GridLayout.LayoutParams().apply {
                width = 0
                height = dp(52)
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
            })
        }

        volumeSeek.max = 100
        volumeSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) = Unit
            override fun onStartTrackingTouch(bar: SeekBar?) = Unit
            override fun onStopTrackingTouch(bar: SeekBar?) {
                viewModel.control("set_volume", mapOf("volume" to (bar?.progress ?: 0)))
            }
        })
        seekBar.max = 1000
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    isUserAdjustingSeekBar = true
                    lastUserSeekAdjustMs = android.os.SystemClock.elapsedRealtime()
                    seekDebounceHandler.removeCallbacks(commitSeekRunnable)
                    seekDebounceHandler.postDelayed(commitSeekRunnable, 300L)
                }
            }
            override fun onStartTrackingTouch(bar: SeekBar?) {
                isUserAdjustingSeekBar = true
                seekDebounceHandler.removeCallbacks(commitSeekRunnable)
            }
            override fun onStopTrackingTouch(bar: SeekBar?) {
                seekDebounceHandler.removeCallbacks(commitSeekRunnable)
                commitSeekRunnable.run()
            }
        })
        seekBar.setOnKeyListener { _, keyCode, event ->
            if (event.action == android.view.KeyEvent.ACTION_DOWN) {
                when (keyCode) {
                    android.view.KeyEvent.KEYCODE_DPAD_LEFT -> {
                        isUserAdjustingSeekBar = true
                        lastUserSeekAdjustMs = android.os.SystemClock.elapsedRealtime()
                        seekBar.progress = (seekBar.progress - 20).coerceAtLeast(0)
                        seekDebounceHandler.removeCallbacks(commitSeekRunnable)
                        seekDebounceHandler.postDelayed(commitSeekRunnable, 300L)
                        true
                    }
                    android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        isUserAdjustingSeekBar = true
                        lastUserSeekAdjustMs = android.os.SystemClock.elapsedRealtime()
                        seekBar.progress = (seekBar.progress + 20).coerceAtMost(1000)
                        seekDebounceHandler.removeCallbacks(commitSeekRunnable)
                        seekDebounceHandler.postDelayed(commitSeekRunnable, 300L)
                        true
                    }
                    else -> false
                }
            } else false
        }
        messageText.setOnClickListener { viewModel.clearMessage() }
        binding.miniPlayerToggle.setOnClickListener {
            val action = if (viewModel.state.value.queue.state == "playing") "pause" else "play"
            viewModel.control(action)
        }
        binding.miniPlayerQueue.setOnClickListener {
            showPhonePanel(ControllerPhonePanel.QUEUE)
        }

        phonePanelViews.clear()
        phonePanelViews[ControllerPhonePanel.CATALOG] = listOf(
            binding.catalogTabsContainer, binding.catalogPhoneEntries, artistFilterContainer, catalogContainer,
        )
        phonePanelViews[ControllerPhonePanel.SEARCH] = listOf(searchInput, binding.resultTitle, resultContainer)
        if (phone) binding.myConnectionCard.visibility = View.VISIBLE
        phonePanelViews[ControllerPhonePanel.PERSONAL] = listOf(
            binding.myConnectionCard, binding.personalTabsContainer, personalContainer,
        )
        phonePanelViews[ControllerPhonePanel.QUEUE] = listOf(binding.queueTitle, queueNowText, queueContainer)
        if (phone) binding.remoteNowPlayingCard.visibility = View.VISIBLE
        phonePanelViews[ControllerPhonePanel.REMOTE] = listOf(
            binding.remoteNowPlayingCard, binding.remoteTitle, binding.remoteControlsContainer, binding.volumeTitle, volumeSeek,
            binding.progressTitle, seekBar, messageText,
        )
        binding.phoneNav.removeAllViews()
        phoneNavButtons.clear()
        binding.phoneBottomBar.visibility = if (phone) View.VISIBLE else View.GONE
        if (phone) {
            binding.phoneNav.visibility = View.VISIBLE
            ControllerPhoneNavigationPolicy.tabs.forEach { tab ->
                val navButton = button(tab.title) {
                    showPhonePanel(tab.panel)
                    if (tab.panel == ControllerPhonePanel.SEARCH) searchInput.requestFocus()
                }.apply {
                    textSize = 12f
                    setPadding(dp(2), 0, dp(2), 0)
                }
                phoneNavButtons[tab.panel] = navButton
                binding.phoneNav.addView(navButton, LinearLayout.LayoutParams(0, -1, 1f).apply {
                    setMargins(dp(2), dp(4), dp(2), dp(4))
                })
            }
        } else {
            binding.phoneNav.visibility = View.GONE
        }
        updatePhoneNavigationSelection()
        applyPhonePanelVisibility()
    }

    private fun configurePanelRecycler(recycler: RecyclerView) {
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.setHasFixedSize(false)
        recycler.isFocusable = true
        recycler.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        recycler.contentDescription = "可滚动列表"
        recycler.isNestedScrollingEnabled = true
        recycler.overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
    }

    private fun restorePanelData() {
        when (catalogMode) {
            CatalogMode.RANKING -> catalogTitle.text = "热歌"
            CatalogMode.NEW -> {
                catalogTitle.text = "新歌"
                viewModel.loadNewSongs()
            }
            CatalogMode.ARTISTS -> {
                catalogTitle.text = if (restoredCatalogDetail) "歌手详情" else "歌手"
                if (restoredCatalogDetail && restoredCatalogValue.isNotBlank()) {
                    viewModel.loadArtistSongs(restoredCatalogValue, restoredCatalogPage)
                } else {
                    viewModel.loadArtists(restoredArtistGender, restoredArtistInitial, restoredCatalogPage)
                }
            }
            CatalogMode.LANGUAGES -> {
                if (restoredCatalogDetail && restoredCatalogValue.isNotBlank()) {
                    catalogTitle.text = "语种详情"
                    viewModel.loadLanguageSongs(restoredCatalogValue, restoredCatalogPage)
                } else {
                    catalogTitle.text = "语种"
                    viewModel.loadLanguages()
                }
            }
            CatalogMode.TAGS -> {
                if (restoredCatalogDetail && restoredCatalogValue.isNotBlank()) {
                    catalogTitle.text = "标签详情"
                    viewModel.loadTagSongs(restoredCatalogValue, restoredCatalogPage)
                } else {
                    catalogTitle.text = "标签"
                    viewModel.loadTags()
                }
            }
        }
        when (personalMode) {
            PersonalMode.FAVORITES -> personalTitle.text = "收藏"
            PersonalMode.PLAYLISTS -> {
                personalTitle.text = "歌单"
                if (restoredPlaylistDetailId > 0L) viewModel.loadPlaylistDetail(restoredPlaylistDetailId)
                else viewModel.loadPlaylists()
            }
            PersonalMode.HISTORY -> {
                personalTitle.text = "最近唱过"
                viewModel.loadHistory()
            }
        }
    }

    private fun showPhonePanel(panel: ControllerPhonePanel) {
        phonePanel = panel
        updatePhoneNavigationSelection()
        applyPhonePanelVisibility()
        contentScroll.scrollTo(0, 0)
    }

    private fun updatePhoneNavigationSelection() {
        phoneNavButtons.forEach { (panel, navButton) ->
            val selected = panel == phonePanel
            navButton.setBackgroundResource(if (selected) R.drawable.btn_gold else R.drawable.btn_keyboard_key)
            navButton.setTextColor(
                if (selected) requireContext().getColor(R.color.bg)
                else requireContext().getColor(android.R.color.white),
            )
        }
    }

    private fun applyPhonePanelVisibility() {
        if (!isPhoneLayout()) return
        val state = viewModel.state.value
        phonePanelViews.forEach { (panel, views) ->
            val panelVisible = panel == phonePanel
            views.forEach { view ->
                view.visibility = when (view) {
                    artistFilterContainer -> if (panelVisible && catalogMode == CatalogMode.ARTISTS && !state.catalogDetail) View.VISIBLE else View.GONE
                    messageText -> if (panelVisible && !state.message.isNullOrBlank()) View.VISIBLE else View.GONE
                    else -> if (panelVisible) View.VISIBLE else View.GONE
                }
            }
        }
    }

    private fun isPhoneLayout(): Boolean =
        !isTelevisionDevice() && requireContext().resources.configuration.smallestScreenWidthDp < 600

    private fun render(state: ControllerUiState) {
        val previous = lastRenderedState
        if (previous != null && previous.copy(
                queue = previous.queue.copy(positionMs = state.queue.positionMs),
            ) == state
        ) {
            updateQueueProgress(state)
            lastRenderedState = state
            return
        }
        lastRenderedState = state
        val connectionSummary = ControllerConnectionSummaryPolicy.label(state.connection, state.queue.tvOnline)
        statusText.text = connectionSummary
        binding.myConnectionStatus.text = connectionSummary
        statusText.setTextColor(
            if (state.connection == ControllerConnection.ONLINE) requireContext().getColor(R.color.tag_mv)
            else requireContext().getColor(R.color.dim),
        )
        messageText.text = state.message.orEmpty()
        renderedQuery = state.query

        renderArtistFilters(state)
        panelAdapter.submitList(searchRows(state))
        catalogAdapter.submitList(catalogRows(state))
        renderPersonalPanel(state)
        updateQueueProgress(state)
        queueAdapter.submitList(queueRows(state))
        applyPhonePanelVisibility()
        messageText.visibility = if (!state.message.isNullOrBlank() &&
            (!isPhoneLayout() || phonePanel == ControllerPhonePanel.REMOTE)) View.VISIBLE else View.GONE
        rootView?.post { rebuildFocusChain() }
    }

    /** Progress broadcasts are frequent; do not rebuild every bounded row for them. */
    private fun updateQueueProgress(state: ControllerUiState) {
        val now = state.queue.playing
        val currentSong = now?.song
        queueNowText.text = now?.song?.let { "正在演唱：${it.title} · ${it.artist}" } ?: "正在演唱：暂无"
        binding.miniPlayerTitle.text = now?.song?.let { "${it.title} · ${it.artist}" } ?: "暂无歌曲播放"
        binding.miniPlayerToggle.text = if (state.queue.state == "playing") "暂停" else "播放"
        binding.miniPlayerToggle.isEnabled = now != null || state.queue.list.isNotEmpty()
        binding.remoteNowPlayingTitle.text = currentSong?.let { "${it.title} · ${it.artist}" }
            ?: getString(R.string.controller_now_playing_empty)
        binding.remoteNowPlayingState.text = ControllerRemoteStatusPolicy.label(
            connection = state.connection,
            tvOnline = state.queue.tvOnline,
            hasSong = currentSong != null,
            playbackState = state.queue.state,
        )
        volumeSeek.progress = state.queue.volume.coerceIn(0, 100)
        val duration = now?.song?.durationMs ?: 0
        if (!isUserAdjustingSeekBar && (android.os.SystemClock.elapsedRealtime() - lastUserSeekAdjustMs > 500L)) {
            seekBar.progress = if (duration <= 0) 0
            else ((state.queue.positionMs.coerceIn(0, duration.toLong()) * 1000L) / duration).toInt()
        }
    }

    private fun queueRows(state: ControllerUiState): List<PanelRow> {
        val rows = state.queue.list.mapIndexed { index, entry ->
            QueuePanelRow(
                index,
                entry,
                isPending(state, "control:top", entry.queueId ?: Long.MIN_VALUE),
                isPending(state, "control:cancel", entry.queueId ?: Long.MIN_VALUE),
                QueuePermissionPolicy.canManage(entry, state.currentUser?.id, state.roomHost.isHost),
            )
        }.toMutableList<PanelRow>()
        val queueError = state.errorFor(UiDomain.QUEUE)
        when {
            queueError != null -> {
                rows.add(0, MessagePanelRow(-100L, "队列加载失败：${state.messageFor(UiDomain.QUEUE) ?: "请重试"}"))
                rows.add(1, ActionPanelRow(
                    id = -101L,
                    text = "重试队列",
                    enabled = true,
                    contentDescription = "重新加载已点歌曲队列",
                    actionKey = "retry_queue",
                ) { viewModel.refreshQueue() })
            }
            state.queueLoading -> rows.add(0, MessagePanelRow(-100L, "正在加载队列…"))
            rows.isEmpty() -> rows += MessagePanelRow(-100L, "队列里还没有歌曲")
        }
        if (!state.queueLoading && state.queue.list.isEmpty()) {
            rows += ActionPanelRow(
                id = -102L,
                text = "去选歌",
                enabled = true,
                contentDescription = "打开搜索选择歌曲",
                actionKey = "queue_empty_search",
            ) {
                showPhonePanel(ControllerPhonePanel.SEARCH)
                searchInput.requestFocus()
            }
        }
        return rows
    }

    private fun setupPanelAdapters() {
        rowBinder = ControllerPanelRowBinder(object : ControllerPanelActions {
            override fun order(song: SongDto) = viewModel.order(song)
            override fun toggleFavorite(song: SongDto) = viewModel.toggleFavorite(song)
            override fun orderPlaylist(id: Long) = viewModel.orderPlaylist(id)
            override fun loadPlaylistDetail(id: Long) = viewModel.loadPlaylistDetail(id)
            override fun repeatHistory(historyId: Long) = viewModel.repeatHistory(historyId)
            override fun top(queueId: Long) = viewModel.top(queueId)
            override fun cancel(queueId: Long, title: String) = viewModel.cancel(queueId)
            override fun confirmDangerous(title: String, message: String, action: () -> Unit) =
                this@ControllerFragment.confirmDangerous(title, message, action)
        })
        fun adapter() = ControllerListAdapter<PanelRow>(
            itemId = { it.stableId },
            createView = rowBinder::createView,
            bindView = rowBinder::bindView,
            contentsSame = ::panelRowsContentSame,
            viewType = PanelRowViewType::from,
        )
        panelAdapter = adapter()
        catalogAdapter = adapter()
        personalAdapter = adapter()
        queueAdapter = adapter()
        resultList.adapter = panelAdapter
        catalogList.adapter = catalogAdapter
        personalList.adapter = personalAdapter
        queueList.adapter = queueAdapter
    }

    /** Ignore freshly-created click lambdas so unrelated state changes do not rebind every row. */
    private fun panelRowsContentSame(old: PanelRow, new: PanelRow): Boolean =
        PanelRowDiffPolicy.areContentsTheSame(old, new)

    private fun searchRows(state: ControllerUiState): List<PanelRow> {
        if (state.loading) return listOf(MessagePanelRow(-1L, "搜索中…"))
        if (state.query.isBlank()) return listOf(
            MessagePanelRow(-5L, "支持歌名/歌手、中文、全拼和首字母搜索"),
        )
        if (state.results.isEmpty()) {
            val searchError = state.errorFor(UiDomain.SEARCH)
            val isWish = searchError == null
            return listOf(
                MessagePanelRow(-2L, if (isWish) "没有找到匹配歌曲" else "搜索失败，请检查服务连接"),
                ActionPanelRow(
                    id = -3L,
                    text = if (isWish) "告诉我们想唱《${state.query}》" else "重试",
                    enabled = !state.writing,
                    contentDescription = if (isWish) "提交点歌心愿" else "重试搜索",
                    actionKey = if (isWish) "wish:${state.query}" else "retry:${state.query}",
                ) {
                    if (isWish) viewModel.submitWish(state.query) else viewModel.setQuery(state.query)
                },
            )
        }
        val rows = state.results.map { songPanelRow(state, it) }.toMutableList<PanelRow>()
        if (state.searchHasMore) rows += ActionPanelRow(
            id = -4L,
            text = if (state.searchLoadingMore) "加载中…" else "加载更多",
            enabled = !state.writing && !state.searchLoadingMore,
            contentDescription = "加载更多搜索结果",
            actionKey = "load_more_search:${state.query}:${state.results.size}",
        ) { viewModel.loadMoreSearch() }
        return rows
    }

    private fun songPanelRow(state: ControllerUiState, song: SongDto) = SongPanelRow(
        song = song,
        orderPending = isPending(state, "song_queue_mutation", song.id) || isPending(state, "order", song.id),
        favorite = state.favoriteIds.contains(song.id),
        favoritePending = isPending(state, if (state.favoriteIds.contains(song.id)) "favorite_remove" else "favorite_add", song.id),
        queueState = state.queueProjection[song.id],
    )

    private fun isPending(state: ControllerUiState, kind: String, resourceId: Long = 0L): Boolean =
        state.pendingActions.contains(ActionKey(kind, resourceId))

    private fun catalogRows(state: ControllerUiState): List<PanelRow> {
        val rows = when (catalogMode) {
            CatalogMode.RANKING -> state.ranking.map { songPanelRow(state, it) }
            CatalogMode.NEW -> state.newSongs.map { songPanelRow(state, it) }
            CatalogMode.ARTISTS -> if (state.catalogDetail) {
                state.catalogSongs.map { songPanelRow(state, it) }
            } else state.artists.map { artist ->
                ArtistPanelRow(artist) {
                    catalogTitle.text = getString(R.string.controller_artist_title, artist.name)
                    viewModel.loadArtistSongs(artist.artistKey)
                }
            }
            CatalogMode.LANGUAGES -> if (state.catalogDetail) {
                state.catalogSongs.map { songPanelRow(state, it) }
            } else state.languages.map { item ->
                NamedCountPanelRow(item) {
                    catalogTitle.text = getString(R.string.controller_language_title, item.name)
                    viewModel.loadLanguageSongs(item.name)
                }
            }
            CatalogMode.TAGS -> if (state.catalogDetail) {
                state.catalogSongs.map { songPanelRow(state, it) }
            } else state.tags.map { item ->
                NamedCountPanelRow(item) {
                    catalogTitle.text = getString(R.string.controller_tag_title, item.name)
                    viewModel.loadTagSongs(item.name)
                }
            }
        }.toMutableList<PanelRow>()
        val catError = state.errorFor(UiDomain.CATALOG)
        val catalogRetryAvailable = ControllerCatalogRetryPolicy.shouldOfferRetry(
            hasDomainError = catError != null,
            status = state.catalogStatus.state,
        )
        if (catalogRetryAvailable) {
            val retryMessage = when (state.catalogStatus.state) {
                CatalogLoadState.OFFLINE -> "点歌服务离线，请检查网络后重试"
                CatalogLoadState.HTTP_ERROR -> "曲库目录请求失败，请重试"
                CatalogLoadState.ROOT_UNAVAILABLE -> "曲库目录暂不可用，可重新检查"
                CatalogLoadState.PARTIAL_FAILURE -> "部分目录内容加载失败，可重试"
                else -> "分类内容加载失败：${state.messageFor(UiDomain.CATALOG) ?: "请重试"}"
            }
            rows.add(0, MessagePanelRow(-20L, retryMessage))
            rows.add(1, ActionPanelRow(
                id = -21L,
                text = "重试当前目录",
                enabled = !state.catalogLoading && !state.catalogLoadingMore,
                contentDescription = "重新加载当前目录",
                actionKey = "retry_catalog:$catalogMode:${state.catalogValue}:${state.catalogPage}",
            ) { retryCurrentCatalog(state) })
        } else if (rows.isEmpty()) {
            rows += MessagePanelRow(-10L, when {
                catError != null -> "分类内容加载失败，请检查服务连接"
                state.catalogLoading -> "正在加载目录…"
                state.catalogStatus.state == CatalogLoadState.OFFLINE -> "点歌服务离线，请检查网络"
                state.catalogStatus.state == CatalogLoadState.HTTP_ERROR -> "曲库状态获取失败，请重试"
                state.catalogStatus.state == CatalogLoadState.ROOT_UNAVAILABLE -> "曲库目录暂不可用"
                state.catalogStatus.state == CatalogLoadState.SCANNING -> "曲库正在扫描，歌曲准备好后会自动刷新"
                state.catalogStatus.state == CatalogLoadState.FILTER_EMPTY -> "当前筛选没有匹配歌曲"
                catalogMode == CatalogMode.ARTISTS && !state.catalogDetail -> "暂无歌手"
                catalogMode == CatalogMode.LANGUAGES && !state.catalogDetail -> "暂无语种"
                catalogMode == CatalogMode.TAGS && !state.catalogDetail -> "暂无标签"
                else -> "暂无歌曲"
            })
        }
        if (state.catalogHasMore && (catalogMode == CatalogMode.ARTISTS || state.catalogDetail)) {
            rows += ActionPanelRow(
                id = -11L,
                text = if (state.catalogLoadingMore) "加载中…" else "加载更多",
                enabled = !state.writing && !state.catalogLoadingMore,
                contentDescription = "加载更多分类结果",
                actionKey = "load_more_catalog:${catalogMode}:${state.catalogPage}",
            ) { viewModel.loadMoreCatalog() }
        }
        return rows
    }

    private fun retryCurrentCatalog(state: ControllerUiState) {
        when (catalogMode) {
            CatalogMode.RANKING -> viewModel.loadRanking()
            CatalogMode.NEW -> viewModel.loadNewSongs()
            CatalogMode.ARTISTS -> if (state.catalogDetail) {
                viewModel.loadArtistSongs(state.catalogValue, state.catalogPage)
            } else {
                viewModel.loadArtists(state.artistGender, state.artistInitial, state.catalogPage)
            }
            CatalogMode.LANGUAGES -> if (state.catalogDetail) {
                viewModel.loadLanguageSongs(state.catalogValue, state.catalogPage)
            } else {
                viewModel.loadLanguages()
            }
            CatalogMode.TAGS -> if (state.catalogDetail) {
                viewModel.loadTagSongs(state.catalogValue, state.catalogPage)
            } else {
                viewModel.loadTags()
            }
        }
    }

    private fun renderPersonalPanel(state: ControllerUiState) {
        personalHeaderContainer.removeAllViews()
        val rows = when (personalMode) {
            PersonalMode.FAVORITES -> {
                val favError = state.errorFor(UiDomain.FAVORITES)
                if (state.favorites.isEmpty()) {
                    listOf(MessagePanelRow(-20L, if (favError != null) "收藏加载失败，请检查服务连接" else "暂无收藏歌曲"))
                } else state.favorites.map { songPanelRow(state, it) }
            }
            PersonalMode.PLAYLISTS -> when {
                state.playlistDetailLoading -> listOf(MessagePanelRow(-20L, "主题歌单加载中…"))
                state.playlistDetail != null -> {
                    renderPlaylistDetailHeader(state, state.playlistDetail)
                    if (state.playlistDetail.songs.isEmpty()) listOf(MessagePanelRow(-21L, "歌单暂无歌曲"))
                    else state.playlistDetail.songs.map { songPanelRow(state, it) }
                }
                else -> {
                    val plError = state.errorFor(UiDomain.PLAYLISTS)
                    if (state.playlists.isEmpty()) {
                        listOf(MessagePanelRow(-22L, if (plError != null) "歌单加载失败，请检查服务连接" else "暂无歌单"))
                    } else state.playlists.map { PlaylistPanelRow(it, isPending(state, "playlist_order", it.id)) }
                }
            }
            PersonalMode.HISTORY -> {
                val histError = state.errorFor(UiDomain.HISTORY)
                if (state.history.isEmpty()) {
                    listOf(MessagePanelRow(-23L, if (histError != null) "历史记录加载失败，请检查服务连接" else "暂无最近唱过歌曲"))
                } else state.history.map { HistoryPanelRow(it, isPending(state, "history_repeat", it.historyId)) }
            }
        }
        personalAdapter.submitList(rows)
    }

    private fun renderPlaylistDetailHeader(state: ControllerUiState, detail: com.homektv.tv.net.PlaylistDetail) {
        playlistCoverJob?.cancel()
        playlistCoverJob = null
        personalHeaderContainer.addView(button("返回主题歌单") {
            viewModel.clearPlaylistDetail()
            viewModel.loadPlaylists()
        }.apply { contentDescription = "返回主题歌单列表" }, matchWrapParams())
        val description = listOf(detail.name, detail.description, detail.theme)
            .map(String::trim).filter(String::isNotEmpty).joinToString(" · ")
        personalHeaderContainer.addView(label(description.ifEmpty { "未命名主题歌单" }, 16f, Color.WHITE, true), matchWrapParams(top = 6))
        personalHeaderContainer.addView(
            label(
                when {
                    detail.coverUrl.isNullOrBlank() -> "封面：未设置"
                    state.playlistCoverBytes != null -> "封面：已加载"
                    else -> "封面：已配置（加载中或暂不可用）"
                },
                12f,
                requireContext().getColor(R.color.dim),
                false,
            ),
            matchWrapParams(top = 2),
        )
        state.playlistCoverBytes?.let { bytes ->
            val coverView = ImageView(requireContext()).apply {
                contentDescription = "主题歌单封面 ${detail.name}"
                adjustViewBounds = true
                minimumHeight = dp(96)
            }
            personalHeaderContainer.addView(coverView, matchWrapParams(top = 4))
            playlistCoverJob = viewLifecycleOwner.lifecycleScope.launch {
                val bitmap = withContext(Dispatchers.Default) {
                    ArtworkDecoder.decode(bytes, ArtworkProfile.PLAYLIST)
                }
                if (bitmap != null && isAdded && view != null && coverView.parent != null) {
                    coverView.setImageBitmap(bitmap)
                }
            }
        }
    }

    private fun renderArtistFilters(state: ControllerUiState) {
        val visible = catalogMode == CatalogMode.ARTISTS && !state.catalogDetail &&
            (!isPhoneLayout() || phonePanel == ControllerPhonePanel.CATALOG)
        artistFilterContainer.visibility = if (visible) View.VISIBLE else View.GONE
        if (!visible) return
        artistFilterContainer.removeAllViews()

        val genderRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        listOf("" to "全部歌手", "男歌手" to "男歌手", "女歌手" to "女歌手", "组合" to "组合")
            .forEach { (value, title) ->
                genderRow.addView(
                    button(title) { viewModel.loadArtistsWithGender(value) }.apply {
                        setTextColor(if (state.artistGender == value) requireContext().getColor(R.color.gold) else Color.WHITE)
                        contentDescription = title
                    },
                    wrapParams(),
                )
            }
        artistFilterContainer.addView(genderRow, matchWrapParams())

        val initialRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        (listOf("热门") + state.artistInitials.filter { it != "热门" })
            .distinct()
            .forEach { initial ->
                val value = initial.takeUnless { it == "热门" }.orEmpty()
                initialRow.addView(
                    button(initial) { viewModel.loadArtistsWithInitial(initial) }.apply {
                        setTextColor(
                            if (state.artistInitial == value) requireContext().getColor(R.color.gold) else Color.WHITE,
                        )
                        contentDescription = "按首字母 $initial 筛选歌手"
                    },
                    wrapParams(),
                )
            }
        val initialScroll = android.widget.HorizontalScrollView(requireContext()).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(initialRow, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        artistFilterContainer.addView(initialScroll, matchWrapParams(top = 2))
    }

    /** Give DPAD devices a deterministic vertical order after dynamic rows rebuild. */
    private fun rebuildFocusChain() {
        val root = rootView ?: return
        val focusables = root.descendants
            .filter {
                it.visibility == View.VISIBLE && it.isFocusable && it.id != View.NO_ID &&
                    !it.isInsideRecyclerView()
            }
            .toList()
        focusables.forEachIndexed { index, view ->
            val previous = focusables.getOrNull(index - 1)
            val next = focusables.getOrNull(index + 1)
            view.nextFocusUpId = previous?.id ?: View.NO_ID
            view.nextFocusDownId = next?.id ?: View.NO_ID
        }
        root.descendants.filterIsInstance<ViewGroup>().forEach { group ->
            if (group is RecyclerView) return@forEach
            val direct = (0 until group.childCount)
                .map { group.getChildAt(it) }
                .filter { it.visibility == View.VISIBLE && it.isFocusable && it.id != View.NO_ID }
            direct.forEachIndexed { index, view ->
                view.nextFocusLeftId = direct.getOrNull(index - 1)?.id ?: View.NO_ID
                view.nextFocusRightId = direct.getOrNull(index + 1)?.id ?: View.NO_ID
            }
        }
    }

    private fun View.isInsideRecyclerView(): Boolean {
        var current = parent
        while (current is View) {
            if (current is RecyclerView) return true
            current = current.parent
        }
        return false
    }

    private fun label(text: String, size: Float, color: Int, bold: Boolean) = TextView(requireContext()).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun button(text: String, action: () -> Unit) = Button(requireContext()).apply {
        id = View.generateViewId()
        this.text = text
        setOnClickListener { action() }
        minHeight = dp(48)
        minWidth = dp(48)
        isAllCaps = false
        isFocusable = true
        setOnFocusChangeListener { focused, hasFocus ->
            if (hasFocus && ::contentScroll.isInitialized) {
                focused.post {
                    val rect = android.graphics.Rect()
                    focused.getDrawingRect(rect)
                    focused.requestRectangleOnScreen(rect, true)
                }
            }
        }
    }

    private fun confirmDangerous(title: String, message: String, action: () -> Unit) {
        if (requireActivity().isFinishing || requireActivity().isDestroyed) return
        AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton("取消", null)
            .setPositiveButton("确认") { _, _ ->
                if (!requireActivity().isFinishing && !requireActivity().isDestroyed) action()
            }
            .show()
    }

    private fun dp(value: Int): Int = (value * requireContext().resources.displayMetrics.density).toInt()

    private fun matchWrapParams(top: Int = 0, height: Int = -2) =
        LinearLayout.LayoutParams(-1, if (height == -2) -2 else dp(height)).apply {
            if (top > 0) topMargin = dp(top)
        }

    private fun wrapParams() = LinearLayout.LayoutParams(-2, -2)

    private fun columnParams(top: Int = 0, start: Int = 0) =
        LinearLayout.LayoutParams(0, -2, 1f).apply {
            if (top > 0) topMargin = dp(top)
            if (start > 0) marginStart = dp(start)
        }

    companion object {
        const val EXTRA_REALTIME = "controller_realtime"
        private const val ARG_REALTIME = "controller_realtime_arg"
        private const val KEY_QUERY = "controller_query"
        private const val KEY_PERSONAL_MODE = "controller_personal_mode"
        private const val KEY_CATALOG_MODE = "controller_catalog_mode"
        private const val KEY_PHONE_PANEL = "controller_phone_panel"
        private const val KEY_ARTIST_GENDER = "controller_artist_gender"
        private const val KEY_ARTIST_INITIAL = "controller_artist_initial"
        private const val KEY_CATALOG_VALUE = "controller_catalog_value"
        private const val KEY_CATALOG_DETAIL = "controller_catalog_detail"
        private const val KEY_CATALOG_PAGE = "controller_catalog_page"
        private const val KEY_PLAYLIST_DETAIL_ID = "controller_playlist_detail_id"
        private const val KEY_SCROLL_Y = "controller_scroll_y"

        fun newInstance(realtime: Boolean): ControllerFragment = ControllerFragment().apply {
            arguments = Bundle().apply { putBoolean(ARG_REALTIME, realtime) }
        }
    }

    private enum class PersonalMode { FAVORITES, PLAYLISTS, HISTORY }
    private enum class CatalogMode { RANKING, NEW, ARTISTS, LANGUAGES, TAGS }
}
