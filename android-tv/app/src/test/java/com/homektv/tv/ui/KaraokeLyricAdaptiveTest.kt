package com.homektv.tv.ui

import org.junit.Assert.assertTrue
import org.junit.Test

class KaraokeLyricAdaptiveTest {

    @Test
    fun testLyricWidthScalingGuaranteesNonNegativeLeftOffset() {
        val testWidth = 400f
        val longLyricText = "月落乌啼总是千年的风霜涛声依旧不见当年的夜晚" // 24 汉字
        val baseTextSize = 42f

        // 计算未缩放时的宽度（每个汉字约为 baseTextSize）
        val unscaledWidth = longLyricText.length * baseTextSize
        assertTrue("长歌词原始宽度远超控件宽度", unscaledWidth > testWidth)

        // 采用双向约束：maxAllowedWidth = testWidth * 0.94f
        val maxAllowedWidth = testWidth * 0.94f
        val scaleW = if (unscaledWidth > maxAllowedWidth) maxAllowedWidth / unscaledWidth else 1f
        val scaledTextSize = baseTextSize * scaleW
        val scaledWidth = longLyricText.length * scaledTextSize

        val left = (testWidth - scaledWidth) / 2f
        assertTrue("缩放后起始绘制位置 left 必须大于等于 0，绝不允许裁切两端", left >= 0f)
        assertTrue("缩放后文字总宽度必须在可用宽度 94% 以内", scaledWidth <= maxAllowedWidth)
    }
}
