package com.homektv.tv.ui.kiosk

import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.View
import com.homektv.tv.R

internal data class DashboardGradientStop(val position: Float, val colorHex: String)
internal data class BackgroundSourceCropWindow(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

/** Selected blue-stage artwork shared by full-screen app surfaces; graphite is the safe fallback. */
internal object KtvDashboardBackground {
    private const val PORTRAIT_CROP_MAX_ASPECT = 0.8f

    @Volatile
    private var cachedArtwork: Bitmap? = null

    val referenceStops = listOf(
        DashboardGradientStop(0.000f, "#222226"),
        DashboardGradientStop(0.085f, "#1E1F24"),
        DashboardGradientStop(0.213f, "#191B1D"),
        DashboardGradientStop(0.426f, "#121718"),
        DashboardGradientStop(0.638f, "#0C0E12"),
        DashboardGradientStop(0.851f, "#0A0B0D"),
        DashboardGradientStop(1.000f, "#0A0B0D"),
    )

    fun applyTo(view: View) {
        val artwork = loadArtwork(view)
        view.background = artwork?.let(::DashboardArtworkDrawable) ?: ReferenceGraphiteDrawable()
    }

    fun isUsingSelectedArtwork(view: View): Boolean = view.background is DashboardArtworkDrawable

    private fun loadArtwork(view: View): Bitmap? {
        cachedArtwork?.let { return it }
        return synchronized(this) {
            cachedArtwork ?: BitmapFactory.decodeResource(
                view.resources,
                R.drawable.ktv_dashboard_background_blue_stage,
            )?.also { cachedArtwork = it }
        }
    }

    internal fun sourceCropWindow(
        imageWidth: Int,
        imageHeight: Int,
        viewportWidth: Int,
        viewportHeight: Int,
    ): BackgroundSourceCropWindow {
        val imageAspect = imageWidth.toFloat() / imageHeight.coerceAtLeast(1)
        val viewportAspect = viewportWidth.toFloat() / viewportHeight.coerceAtLeast(1)
        return if (imageAspect > viewportAspect) {
            val sourceWidth = (imageHeight * viewportAspect).toInt().coerceIn(1, imageWidth)
            val horizontalOverflow = imageWidth - sourceWidth
            val left = if (viewportAspect < PORTRAIT_CROP_MAX_ASPECT) 0 else horizontalOverflow / 2
            BackgroundSourceCropWindow(left, 0, left + sourceWidth, imageHeight)
        } else {
            val sourceHeight = (imageWidth / viewportAspect.coerceAtLeast(0.0001f))
                .toInt()
                .coerceIn(1, imageHeight)
            val top = (imageHeight - sourceHeight) / 2
            BackgroundSourceCropWindow(0, top, imageWidth, top + sourceHeight)
        }
    }

    private class DashboardArtworkDrawable(private val bitmap: Bitmap) : Drawable() {
        private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
        private val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(SCRIM_ALPHA, 0, 0, 0)
        }
        private val source = Rect()
        private val destination = Rect()

        override fun onBoundsChange(bounds: Rect) {
            super.onBoundsChange(bounds)
            val crop = sourceCropWindow(bitmap.width, bitmap.height, bounds.width(), bounds.height())
            source.set(crop.left, crop.top, crop.right, crop.bottom)
            destination.set(bounds)
        }

        override fun draw(canvas: Canvas) {
            if (bounds.isEmpty || source.isEmpty) return
            canvas.drawBitmap(bitmap, source, destination, bitmapPaint)
            canvas.drawRect(destination, scrimPaint)
        }

        override fun setAlpha(alpha: Int) {
            val boundedAlpha = alpha.coerceIn(0, 255)
            bitmapPaint.alpha = boundedAlpha
            scrimPaint.alpha = SCRIM_ALPHA * boundedAlpha / 255
            invalidateSelf()
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            bitmapPaint.colorFilter = colorFilter
            scrimPaint.colorFilter = colorFilter
            invalidateSelf()
        }

        @Deprecated("Deprecated in Android, retained for Drawable compatibility")
        override fun getOpacity(): Int = PixelFormat.OPAQUE

        private companion object {
            const val SCRIM_ALPHA = 72
        }
    }

    private class ReferenceGraphiteDrawable : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val colors = referenceStops.map { Color.parseColor(it.colorHex) }.toIntArray()
        private val positions = referenceStops.map { it.position }.toFloatArray()

        override fun onBoundsChange(bounds: Rect) {
            super.onBoundsChange(bounds)
            paint.shader = if (bounds.height() > 0) {
                LinearGradient(
                    0f,
                    bounds.top.toFloat(),
                    0f,
                    bounds.bottom.toFloat(),
                    colors,
                    positions,
                    Shader.TileMode.CLAMP,
                )
            } else {
                null
            }
        }

        override fun draw(canvas: Canvas) {
            if (!bounds.isEmpty) canvas.drawRect(bounds, paint)
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
            invalidateSelf()
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
            invalidateSelf()
        }

        @Deprecated("Deprecated in Android, retained for Drawable compatibility")
        override fun getOpacity(): Int = PixelFormat.OPAQUE
    }
}
