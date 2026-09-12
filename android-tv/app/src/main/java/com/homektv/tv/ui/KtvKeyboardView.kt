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

/**
 * 专为电视遥控器和触屏优化的 26 字母拼音快捷软键盘组件。
 *
 * KTV virtual 26-letter keyboard for fast acronym search on TV.
 */
class KtvKeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    val state = KtvKeyboardState()
    val binding = ViewKtvKeyboardBinding.inflate(LayoutInflater.from(context), this, true)

    var onKeywordChanged: ((String) -> Unit)? = null
    var onImeRequest: (() -> Unit)? = null

    init {
        setupKeys()
        setupActions()
    }

    private fun setupKeys() {
        val grid = binding.keyboardGrid
        val marginPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 3f, resources.displayMetrics
        ).toInt()
        val heightPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 46f, resources.displayMetrics
        ).toInt()

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
                    state.input(letter.toString())
                    updateDisplay()
                }
            }
            grid.addView(btn)
        }
    }

    private fun setupActions() {
        binding.btnBackspace.setOnClickListener {
            state.backspace()
            updateDisplay()
        }
        binding.btnClear.setOnClickListener {
            state.clear()
            updateDisplay()
        }
        binding.btnSwitchIme.setOnClickListener {
            onImeRequest?.invoke()
        }
    }

    private fun updateDisplay() {
        binding.txtKeywordDisplay.text = state.currentKeyword
        onKeywordChanged?.invoke(state.currentKeyword)
    }

    fun setKeyword(keyword: String) {
        state.clear()
        keyword.forEach { state.input(it.toString()) }
        updateDisplay()
    }

    fun clear() {
        state.clear()
        updateDisplay()
    }
}
