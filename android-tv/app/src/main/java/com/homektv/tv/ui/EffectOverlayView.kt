package com.homektv.tv.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.os.SystemClock
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * 卡拉 OK 氛围特效叠加层，负责绘制粒子、闪光和文字效果。
 *
 * Karaoke ambience-effect overlay that renders particles, flashes, and glyphs.
 */
class EffectOverlayView(context: Context) : View(context) {
    private data class Particle(
        val x: Float,
        val y: Float,
        val vx: Float,
        val vy: Float,
        val size: Float,
        val rotation: Float,
        val rotationSpeed: Float,
        val color: Int,
        val glyph: String?,
        val delay: Float,
    )

    private data class Burst(
        val spec: EffectVisualSpec,
        val startedAt: Long,
        val particles: List<Particle>,
    )

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val panel = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bursts = mutableListOf<Burst>()
    private val density = resources.displayMetrics.density

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            setLayerType(LAYER_TYPE_SOFTWARE, null)
        }
    }

    /**
     * 播放指定特效；未知特效 ID 会被安全忽略。
     *
     * Plays the requested effect and safely ignores unknown effect IDs.
     */
    fun play(effectId: String) {
        val spec = EffectVisuals.find(effectId) ?: return
        val seed = SystemClock.elapsedRealtime().toInt() xor effectId.hashCode()
        bursts += Burst(spec, SystemClock.elapsedRealtime(), createParticles(spec, Random(seed)))
        if (bursts.size > 3) bursts.removeAt(0)
        visibility = VISIBLE
        invalidate()
    }

    fun clear() {
        bursts.clear()
        visibility = GONE
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = SystemClock.elapsedRealtime()
        bursts.removeAll { now - it.startedAt >= it.spec.durationMs }
        bursts.forEach { drawBurst(canvas, it, now) }
        if (bursts.isNotEmpty()) postInvalidateOnAnimation() else visibility = GONE
    }

    private fun drawBurst(canvas: Canvas, burst: Burst, now: Long) {
        val progress = ((now - burst.startedAt).toFloat() / burst.spec.durationMs).coerceIn(0f, 1f)
        val fade = when {
            progress < 0.12f -> progress / 0.12f
            progress > 0.78f -> (1f - progress) / 0.22f
            else -> 1f
        }.coerceIn(0f, 1f)
        drawCenterBadge(canvas, burst.spec, progress, fade)
        burst.particles.forEach { particle ->
            val local = ((progress - particle.delay) / (1f - particle.delay)).coerceIn(0f, 1f)
            if (local <= 0f) return@forEach
            canvas.save()
            val x = particle.x * width + particle.vx * width * local
            val gravity = if (burst.spec.kind == EffectVisualKind.CHEER) 0.5f else -0.08f
            val y = particle.y * height + particle.vy * height * local + gravity * height * local * local
            canvas.rotate(particle.rotation + particle.rotationSpeed * local, x, y)
            paint.alpha = (255 * fade * (1f - local * 0.35f)).toInt().coerceIn(0, 255)
            if (particle.glyph != null) {
                paint.color = Color.WHITE
                paint.textAlign = Paint.Align.CENTER
                paint.textSize = particle.size * density
                canvas.drawText(particle.glyph, x, y, paint)
            } else if (burst.spec.kind == EffectVisualKind.TOAST) {
                paint.color = particle.color
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 2f * density
                canvas.drawCircle(x, y, particle.size * density * (0.25f + local * 0.45f), paint)
                paint.style = Paint.Style.FILL
            } else {
                paint.color = particle.color
                paint.style = Paint.Style.FILL
                val half = particle.size * density / 2f
                canvas.drawRoundRect(x - half, y - half * 0.28f, x + half, y + half * 0.28f, 3f, 3f, paint)
            }
            canvas.restore()
        }
    }

    private fun drawCenterBadge(canvas: Canvas, spec: EffectVisualSpec, progress: Float, fade: Float) {
        val bounce = 1f + 0.12f * sin((progress.coerceAtMost(0.42f) / 0.42f) * PI).toFloat()
        val centerX = width / 2f
        val centerY = height * if (spec.kind == EffectVisualKind.TOAST) 0.44f else 0.34f
        val badgeWidth = 360f * density * bounce
        val badgeHeight = 116f * density * bounce
        val rect = RectF(centerX - badgeWidth / 2, centerY - badgeHeight / 2, centerX + badgeWidth / 2, centerY + badgeHeight / 2)
        panel.color = Color.argb((185 * fade).toInt(), 8, 9, 13)
        panel.setShadowLayer(28f * density, 0f, 8f * density, spec.colors.first().toInt())
        canvas.drawRoundRect(rect, 28f * density, 28f * density, panel)
        panel.clearShadowLayer()
        paint.alpha = (255 * fade).toInt()
        paint.textAlign = Paint.Align.CENTER
        paint.color = Color.WHITE
        paint.textSize = 48f * density * bounce
        canvas.drawText(spec.emoji, centerX - 112f * density * bounce, centerY + 17f * density * bounce, paint)
        paint.color = spec.colors.first().toInt()
        paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
        paint.textSize = 30f * density * bounce
        canvas.drawText(spec.title, centerX + 48f * density * bounce, centerY + 10f * density * bounce, paint)
        paint.typeface = android.graphics.Typeface.DEFAULT
    }

    private fun createParticles(spec: EffectVisualSpec, random: Random): List<Particle> =
        List(spec.particleCount) { index ->
            when (spec.kind) {
                EffectVisualKind.CLAP -> Particle(
                    x = random.nextFloat(), y = 1.06f + random.nextFloat() * 0.18f,
                    vx = (random.nextFloat() - 0.5f) * 0.26f, vy = -0.75f - random.nextFloat() * 0.45f,
                    size = 24f + random.nextFloat() * 32f, rotation = random.nextFloat() * 24f - 12f,
                    rotationSpeed = random.nextFloat() * 80f - 40f,
                    color = spec.colors[index % spec.colors.size].toInt(), glyph = if (index % 2 == 0) "👏" else null,
                    delay = random.nextFloat() * 0.34f,
                )
                EffectVisualKind.CHEER -> Particle(
                    x = random.nextFloat(), y = -0.1f - random.nextFloat() * 0.45f,
                    vx = (random.nextFloat() - 0.5f) * 0.3f, vy = 0.65f + random.nextFloat() * 0.45f,
                    size = 8f + random.nextFloat() * 13f, rotation = random.nextFloat() * 180f,
                    rotationSpeed = random.nextFloat() * 720f - 360f,
                    color = spec.colors[index % spec.colors.size].toInt(), glyph = if (index % 18 == 0) "🎉" else null,
                    delay = random.nextFloat() * 0.28f,
                )
                EffectVisualKind.BOO -> {
                    val left = index % 2 == 0
                    Particle(
                        x = if (left) -0.08f else 1.08f, y = 0.16f + random.nextFloat() * 0.68f,
                        vx = if (left) 0.38f + random.nextFloat() * 0.28f else -0.38f - random.nextFloat() * 0.28f,
                        vy = (random.nextFloat() - 0.5f) * 0.18f,
                        size = 24f + random.nextFloat() * 30f, rotation = if (left) -18f else 18f,
                        rotationSpeed = random.nextFloat() * 90f - 45f,
                        color = spec.colors[index % spec.colors.size].toInt(), glyph = if (index % 3 == 0) "😜" else "👎",
                        delay = random.nextFloat() * 0.38f,
                    )
                }
                EffectVisualKind.TOAST -> {
                    val angle = random.nextDouble(PI * 1.08, PI * 1.92)
                    Particle(
                        x = 0.5f, y = 0.53f,
                        vx = (cos(angle) * (0.12 + random.nextDouble() * 0.32)).toFloat(),
                        vy = (sin(angle) * (0.28 + random.nextDouble() * 0.38)).toFloat(),
                        size = 8f + random.nextFloat() * 18f, rotation = 0f, rotationSpeed = 0f,
                        color = spec.colors[index % spec.colors.size].toInt(), glyph = if (index < 2) "🍻" else null,
                        delay = if (index < 2) 0f else random.nextFloat() * 0.32f,
                    )
                }
            }
        }
}
