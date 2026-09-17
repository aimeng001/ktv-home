package com.homektv.tv.ui

import android.content.Context
import android.util.AttributeSet
import android.util.TypedValue
import android.view.LayoutInflater
import android.widget.Button
import android.widget.FrameLayout
import android.widget.GridLayout
import androidx.core.content.ContextCompat
import com.homektv.tv.R
import com.homektv.tv.databinding.ViewKtvKeyboardBinding
import com.homektv.tv.ui.kiosk.KeyboardLayoutMode
import com.homektv.tv.ui.kiosk.KtvKeyboardInputSession

/**
 * 专为电视遥控器和触屏优化的 9键（T9 九宫格）/ 26键（全键盘）双模软键盘组件。
 *
 * KTV virtual keyboard supporting both T9 multi-tap 9-key and QWERTY 26-key layouts.
 */
class KtvKeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    val session = KtvKeyboardInputSession()
    val state: KtvKeyboardState get() = session.state
    val binding = ViewKtvKeyboardBinding.inflate(LayoutInflater.from(context), this, true)

    var onKeywordChanged: ((String) -> Unit)? = null
    var onImeRequest: (() -> Unit)? = null

    init {
        renderKeys()
        setupActions()
    }

    private fun renderKeys() {
        val grid = binding.keyboardGrid
        grid.removeAllViews()

        if (session.mode == KeyboardLayoutMode.T9) {
            binding.btnToggleT9.text = "切换全键盘(26键)"
            renderT9Keys(grid)
        } else {
            binding.btnToggleT9.text = "切换九宫格(9键)"
            renderQwertyKeys(grid)
        }
    }

    private fun renderT9Keys(grid: GridLayout) {
        grid.columnCount = 3
        val marginPx = dpToPx(3f)
        val heightPx = dpToPx(52f)

        data class T9Key(val digit: Char?, val label: String, val isClear: Boolean = false, val isBackspace: Boolean = false)

        val keys = listOf(
            T9Key('1', "1"),
            T9Key('2', "2 ABC"),
            T9Key('3', "3 DEF"),
            T9Key('4', "4 GHI"),
            T9Key('5', "5 JKL"),
            T9Key('6', "6 MNO"),
            T9Key('7', "7 PQRS"),
            T9Key('8', "8 TUV"),
            T9Key('9', "9 WXYZ"),
            T9Key(null, "清空", isClear = true),
            T9Key('0', "0"),
            T9Key(null, "退格", isBackspace = true),
        )

        keys.forEach { item ->
            val btn = Button(context).apply {
                text = item.label
                textSize = if (item.label.length > 3) 14f else 18f
                isFocusable = true
                setBackgroundResource(R.drawable.btn_keyboard_key)
                setTextColor(ContextCompat.getColorStateList(context, R.color.color_keyboard_key_text))

                val params = GridLayout.LayoutParams().apply {
                    width = 0
                    height = heightPx
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    setMargins(marginPx, marginPx, marginPx, marginPx)
                }
                layoutParams = params

                setOnClickListener {
                    when {
                        item.isClear -> session.clear()
                        item.isBackspace -> session.backspace()
                        item.digit != null -> session.onDigitPressed(item.digit)
                    }
                    updateDisplay()
                }
            }
            grid.addView(btn)
        }
    }

    private fun renderQwertyKeys(grid: GridLayout) {
        grid.columnCount = 6
        val marginPx = dpToPx(3f)
        val heightPx = dpToPx(46f)

        ('A'..'Z').forEach { letter ->
            val btn = Button(context).apply {
                text = letter.toString()
                textSize = 18f
                isFocusable = true
                setBackgroundResource(R.drawable.btn_keyboard_key)
                setTextColor(ContextCompat.getColorStateList(context, R.color.color_keyboard_key_text))

                val params = GridLayout.LayoutParams().apply {
                    width = 0
                    height = heightPx
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    setMargins(marginPx, marginPx, marginPx, marginPx)
                }
                layoutParams = params

                setOnClickListener {
                    session.onLetterPressed(letter)
                    updateDisplay()
                }
            }
            grid.addView(btn)
        }
    }

    private fun setupActions() {
        binding.btnBackspace.setOnClickListener {
            session.backspace()
            updateDisplay()
        }
        binding.btnClear.setOnClickListener {
            session.clear()
            updateDisplay()
        }
        binding.btnSwitchIme.setOnClickListener {
            onImeRequest?.invoke()
        }
        binding.btnToggleT9.setOnClickListener {
            session.toggleMode()
            renderKeys()
            updateDisplay()
        }
    }

    private fun updateDisplay() {
        binding.txtKeywordDisplay.text = session.currentKeyword
        onKeywordChanged?.invoke(session.currentKeyword)
    }

    fun setKeyword(keyword: String) {
        session.setKeyword(keyword)
        updateDisplay()
    }

    fun clear() {
        session.clear()
        updateDisplay()
    }

    private fun dpToPx(dp: Float): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, dp, resources.displayMetrics
        ).toInt()
    }
}
