package com.homektv.tv.ui.kiosk

import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.animation.AnimationUtils
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ImageSwitcher
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.homektv.tv.R
import com.homektv.tv.databinding.ViewKtvBentoDashboardBinding
import kotlin.math.roundToInt

/** Ten fixed reference-art tiles with explicit TV focus links. */
class KtvCommercialDashboardView @JvmOverloads constructor(
    context: android.content.Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {
    val binding = ViewKtvBentoDashboardBinding.inflate(LayoutInflater.from(context), this, true)

    var onTileSelected: ((KtvDashboardTile) -> Unit)? = null
    var lastFocusedTile: KtvDashboardTile = KtvDashboardTile.HOT_RANKING

    private data class TileContent(
        val root: View,
        val title: TextView,
    )

    private val tileContent = mapOf(
        KtvDashboardTile.HOT_RANKING to TileContent(binding.tileHotRanking, binding.txtHotRankingTitle),
        KtvDashboardTile.NEW_SONGS to TileContent(binding.tileNewSongs, binding.txtNewSongsTitle),
        KtvDashboardTile.SINGER to TileContent(binding.tileSinger, binding.txtSingerTileTitle),
        KtvDashboardTile.SONG_NAME to TileContent(binding.tileSongName, binding.txtSongNameTitle),
        KtvDashboardTile.LANGUAGE to TileContent(binding.tileLanguage, binding.txtLanguageTitle),
        KtvDashboardTile.THEME to TileContent(binding.tileTheme, binding.txtThemeTitle),
        KtvDashboardTile.OPERA to TileContent(binding.tileOpera, binding.txtOperaTitle),
        KtvDashboardTile.TIKTOK to TileContent(binding.tileTikTok, binding.txtTikTokTitle),
        KtvDashboardTile.FAVORITES to TileContent(binding.tileFavorites, binding.txtFavoritesTitle),
        KtvDashboardTile.LOCAL to TileContent(binding.tileLocal, binding.txtLocalTitle),
    )
    private val photoSwitchers = mutableMapOf<KtvDashboardTile, ImageSwitcher>()
    private val photoPairs = listOf(
        KtvDashboardPhotoPair(R.drawable.ktv_ref_v50_tile_singers_01, R.drawable.ktv_ref_v50_tile_songs_01),
        KtvDashboardPhotoPair(R.drawable.ktv_ref_v50_tile_singers_02, R.drawable.ktv_ref_v50_tile_songs_02),
        KtvDashboardPhotoPair(R.drawable.ktv_ref_v50_tile_singers_03, R.drawable.ktv_ref_v50_tile_songs_03),
    )
    private val photoHandler = Handler(Looper.getMainLooper())
    private val photoCarousel = KtvDashboardPhotoCarousel(
        postDelayed = { task, delay -> photoHandler.postDelayed(task, delay) },
        removeCallbacks = photoHandler::removeCallbacks,
        onIndexChanged = ::showPhotoPair,
        itemCount = photoPairs.size,
    )

    init {
        clipChildren = false
        clipToPadding = false
        binding.dashboardRoot.clipChildren = false
        binding.dashboardRoot.clipToPadding = false
        initializeTiles()
    }

    private fun initializeTiles() {
        KtvDashboardTile.dashboardEntries.forEach { tile ->
            val content = tileContent.getValue(tile)
            content.title.text = tile.title
            addTileArtwork(tile, content.root)
            content.root.clipToOutline = true
            content.root.background = createRoundedClipSurface()
            content.root.foreground = createFocusOverlay()
            content.root.contentDescription = "${tile.title}，${tile.subtitle}"
            content.root.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) lastFocusedTile = tile
            }
            content.root.setOnClickListener {
                lastFocusedTile = tile
                onTileSelected?.invoke(tile)
            }
        }
    }

    private fun addTileArtwork(tile: KtvDashboardTile, tileRoot: View) {
        if (tile == KtvDashboardTile.SINGER || tile == KtvDashboardTile.SONG_NAME) {
            val switcher = ImageSwitcher(context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                )
                isFocusable = false
                isClickable = false
                setAnimateFirstView(false)
                setFactory {
                    ImageView(context).apply {
                        layoutParams = FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT,
                        )
                        scaleType = ImageView.ScaleType.FIT_XY
                        contentDescription = null
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                        isClickable = false
                        isFocusable = false
                    }
                }
                inAnimation = AnimationUtils.loadAnimation(context, R.anim.ktv_photo_carousel_in)
                outAnimation = AnimationUtils.loadAnimation(context, R.anim.ktv_photo_carousel_out)
                setImageResource(if (tile == KtvDashboardTile.SINGER) {
                    photoPairs.first().singerImageRes
                } else {
                    photoPairs.first().songImageRes
                })
            }
            photoSwitchers[tile] = switcher
            (tileRoot as FrameLayout).addView(switcher, 0)
            return
        }

        val artwork = ImageView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
            scaleType = ImageView.ScaleType.CENTER_CROP
            contentDescription = null
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            isClickable = false
            isFocusable = false
            setImageResource(tile.artworkRes)
        }
        (tileRoot as FrameLayout).addView(artwork, 0)
    }

    private fun showPhotoPair(index: Int) {
        val pair = photoPairs[index]
        photoSwitchers[KtvDashboardTile.SINGER]?.setImageResource(pair.singerImageRes)
        photoSwitchers[KtvDashboardTile.SONG_NAME]?.setImageResource(pair.songImageRes)
    }

    fun pausePhotoCarousel(reason: KtvDashboardPhotoCarousel.PauseReason) {
        photoCarousel.pause(reason)
    }

    fun resumePhotoCarousel(reason: KtvDashboardPhotoCarousel.PauseReason) {
        photoCarousel.resume(reason)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        photoCarousel.setAttached(true)
        photoCarousel.setVisible(isShown)
    }

    override fun onDetachedFromWindow() {
        photoCarousel.setAttached(false)
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        photoCarousel.setVisible(isShown)
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        photoCarousel.setVisible(isShown)
    }

    private fun createRoundedClipSurface(): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(android.graphics.Color.TRANSPARENT)
        setCornerRadius(resources.getDimension(R.dimen.ktv_theme_card_corner_radius))
    }

    private fun createFocusOverlay(): StateListDrawable {
        val cornerRadius = resources.getDimension(R.dimen.ktv_theme_card_corner_radius)
        val focusStroke = resources.getDimension(R.dimen.ktv_theme_focus_stroke_width).roundToInt().coerceAtLeast(2)
        val focusColor = ContextCompat.getColor(context, R.color.ktv_focus_blue)

        val focused = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(android.graphics.Color.TRANSPARENT)
            setCornerRadius(cornerRadius)
            setStroke(focusStroke, focusColor)
        }

        val normal = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(android.graphics.Color.TRANSPARENT)
            setCornerRadius(cornerRadius)
        }

        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), focused)
            addState(intArrayOf(), normal)
        }
    }

    fun requestDashboardFocus(): Boolean {
        val focusTile = KtvDashboardTile.restoreDashboardFocus(lastFocusedTile)
        lastFocusedTile = focusTile
        return tileContent.getValue(focusTile).root.requestFocus()
    }

    private data class KtvDashboardPhotoPair(
        val singerImageRes: Int,
        val songImageRes: Int,
    )
}
