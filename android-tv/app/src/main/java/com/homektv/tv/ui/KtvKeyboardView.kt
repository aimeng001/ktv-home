package com.homektv.tv.ui

import android.content.Context
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.view.View
import com.homektv.tv.R
import com.homektv.tv.databinding.ViewKtvKeyboardBinding
import com.homektv.tv.ui.kiosk.BopomofoPinyinMapper
import com.homektv.tv.ui.kiosk.KeyboardLayoutMode
import com.homektv.tv.ui.kiosk.KtvKeyboardInputSession
import com.homektv.tv.ui.kiosk.T9PinyinSearchPolicy

/**
 * Home KTV 大屏软键盘。手写区域保存笔迹并提供显式常用字选择，不伪造 OCR；
 * 注音只把明确的声母映射为拼音首字母，其余符号保留为本地组合态。
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
    var onLayoutModeChanged: ((KeyboardLayoutMode) -> Unit)? = null

    private var currentInputMode = Mode.ABC
    private var contentFocusTargetId = R.id.browserResultsRecyclerView
    private var gridButtons: List<Button> = emptyList()
    private var alternateModePanel: View? = null
    private var alternateModeAction: View? = null
    private var handwritingCanvas: KtvHandwritingCanvasView? = null
    private var lastDispatchedSearchKeyword = T9PinyinSearchPolicy.normalizeSearchKeyword(session.currentKeyword)
    private var wireModeContentFocus: (() -> Unit)? = null

    enum class Mode {
        ABC, NUMBER, HANDWRITING, ZHUYIN, SYSTEM
    }

    private companion object {
        val COMMON_SONG_TITLE_CHARACTERS = listOf(
            "爱", "你", "我", "的", "中", "国", "风", "雨", "梦", "星", "月", "光",
            "心", "情", "歌", "曲", "一", "生", "再", "见", "海", "天", "夜", "笑",
        )
    }

    init {
        updateModeSelection(Mode.ABC)
        renderKeys()
        setupActions()
        binding.keyboardGridScroll.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            when (currentInputMode) {
                Mode.ABC, Mode.SYSTEM -> resizeGridRows(rowCount = 6)
                Mode.NUMBER -> resizeGridRows(rowCount = 4)
                Mode.HANDWRITING, Mode.ZHUYIN -> resizeAlternatePanel()
            }
        }
    }

    private fun updateModeSelection(mode: Mode) {
        currentInputMode = mode
        binding.btnToggleT9.isSelected = (mode == Mode.ABC)
        binding.btnMode123.isSelected = (mode == Mode.NUMBER)
        binding.btnModeHandwrite.isSelected = (mode == Mode.HANDWRITING)
        binding.btnModeZhuyin.isSelected = (mode == Mode.ZHUYIN)
        binding.btnSwitchIme.isSelected = (mode == Mode.SYSTEM)
    }

    private fun renderKeys() {
        val grid = binding.keyboardGrid
        grid.removeAllViews()
        gridButtons = emptyList()
        handwritingCanvas?.clearInk()
        handwritingCanvas = null
        alternateModePanel = null
        alternateModeAction = null
        wireModeContentFocus = null
        grid.layoutParams = grid.layoutParams.apply { height = LayoutParams.WRAP_CONTENT }

        when (currentInputMode) {
            Mode.ABC -> renderQwertyKeys(grid)
            Mode.NUMBER -> renderNumberKeys(grid)
            Mode.SYSTEM -> renderQwertyKeys(grid)
            Mode.HANDWRITING -> renderHandwritingPanel(grid)
            Mode.ZHUYIN -> renderZhuyinKeys(grid)
        }
        wireModeRailFocus()
        renderPendingBopomofo()
    }

    private fun renderQwertyKeys(grid: GridLayout) {
        grid.columnCount = 5
        grid.rowCount = 6
        val marginPx = dpToPx(2f)
        val heightPx = dpToPx(48f)

        val keys = mutableListOf<String>()
        ('A'..'Z').forEach { keys.add(it.toString()) }
        keys.add("SPACE")

        gridButtons = keys.map { letter ->
            val btn = Button(context).apply {
                id = View.generateViewId()
                text = letter
                textSize = if (letter == "SPACE") 13f else 18f
                isFocusable = true
                setBackgroundResource(R.drawable.bg_ktv_transparent_key)
                setPadding(0, 0, 0, 0)
                setTextColor(0xFFFFFFFF.toInt())

                val isSpace = (letter == "SPACE")
                val params = GridLayout.LayoutParams().apply {
                    width = 0
                    height = heightPx
                    columnSpec = if (isSpace) GridLayout.spec(GridLayout.UNDEFINED, 4, 4f)
                                 else GridLayout.spec(GridLayout.UNDEFINED, 1, 1f)
                    setMargins(marginPx, marginPx, marginPx, marginPx)
                }
                layoutParams = params

                setOnClickListener {
                    if (isSpace) {
                        session.onLetterPressed(' ')
                    } else if (letter.length == 1) {
                        session.onLetterPressed(letter[0])
                    }
                    updateDisplay()
                }
            }
            grid.addView(btn)
            btn
        }
        wireModeContentFocus = ::wireQwertyFocus
        wireQwertyFocus()
        resizeGridRows(rowCount = 6)
    }

    private fun renderNumberKeys(grid: GridLayout) {
        grid.columnCount = 3
        grid.rowCount = 4
        val marginPx = dpToPx(3f)
        val heightPx = dpToPx(50f)
        val keys = listOf(
            "1" to (0 to 0), "2" to (0 to 1), "3" to (0 to 2),
            "4" to (1 to 0), "5" to (1 to 1), "6" to (1 to 2),
            "7" to (2 to 0), "8" to (2 to 1), "9" to (2 to 2),
            "0" to (3 to 1),
        )
        gridButtons = keys.map { (label, position) ->
            val btn = Button(context).apply {
                id = View.generateViewId()
                text = label
                textSize = 20f
                isFocusable = true
                setBackgroundResource(R.drawable.bg_ktv_transparent_key)
                setPadding(0, 0, 0, 0)
                setTextColor(0xFFFFFFFF.toInt())

                val params = GridLayout.LayoutParams().apply {
                    width = 0
                    height = heightPx
                    rowSpec = GridLayout.spec(position.first, 1)
                    columnSpec = GridLayout.spec(position.second, 1, 1f)
                    setMargins(marginPx, marginPx, marginPx, marginPx)
                }
                layoutParams = params

                setOnClickListener {
                    session.onNumericKeyPressed(label[0])
                    updateDisplay()
                }
            }
            grid.addView(btn)
            btn
        }
        wireModeContentFocus = { wireNumberFocus(keys.map { it.second }) }
        wireNumberFocus(keys.map { it.second })
        resizeGridRows(rowCount = 4)
    }

    private fun resizeGridRows(rowCount: Int) {
        val viewportHeight = binding.keyboardGridScroll.height
        if (viewportHeight <= 0 || gridButtons.isEmpty()) return
        val verticalMarginPerRow = if (currentInputMode == Mode.NUMBER) dpToPx(6f) else dpToPx(4f)
        val verticalMargins = verticalMarginPerRow * rowCount
        val rowHeight = ((viewportHeight - verticalMargins) / rowCount)
            .coerceIn(dpToPx(17f), dpToPx(48f))
        gridButtons.forEach { button ->
            button.layoutParams = button.layoutParams.apply { height = rowHeight }
            button.textSize = when {
                currentInputMode == Mode.NUMBER -> 20f
                rowHeight < dpToPx(28f) -> 11f
                rowHeight < dpToPx(36f) -> 14f
                button.text == "SPACE" -> 13f
                currentInputMode == Mode.ZHUYIN -> 19f
                else -> 18f
            }
        }
    }

    private fun renderHandwritingPanel(grid: GridLayout) {
        grid.columnCount = 1
        grid.rowCount = 1
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(6f), dpToPx(4f), dpToPx(6f), dpToPx(4f))
        }

        val candidatesScroll = HorizontalScrollView(context).apply {
            isFocusable = false
            isFocusableInTouchMode = false
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            background = handwritingBorder()
            contentDescription = "常用歌名汉字候选"
        }
        val candidateRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpToPx(4f), 0, dpToPx(4f), 0)
        }
        candidateRow.addView(TextView(context).apply {
            text = "常用字"
            gravity = Gravity.CENTER
            setTextColor(0xFFD5DCE8.toInt())
            textSize = 11f
            isFocusable = false
        }, LinearLayout.LayoutParams(dpToPx(44f), LayoutParams.MATCH_PARENT))

        val candidates = COMMON_SONG_TITLE_CHARACTERS.map { character ->
            Button(context).apply {
                id = View.generateViewId()
                text = character
                textSize = 16f
                minWidth = 0
                minHeight = 0
                isFocusable = true
                setPadding(0, 0, 0, 0)
                setBackgroundResource(R.drawable.bg_ktv_transparent_key)
                setTextColor(0xFFFFFFFF.toInt())
                setOnClickListener {
                    session.onCandidateCharacterPressed(character)
                    updateDisplay()
                }
            }.also { candidateRow.addView(it, LinearLayout.LayoutParams(dpToPx(36f), LayoutParams.MATCH_PARENT)) }
        }
        candidatesScroll.addView(candidateRow, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        panel.addView(candidatesScroll, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dpToPx(36f)))

        val canvas = KtvHandwritingCanvasView(context).apply {
            id = View.generateViewId()
            nextFocusLeftId = contentFocusTargetId
            nextFocusRightId = activeModeButtonId()
            nextFocusUpId = candidates.first().id
            nextFocusDownId = contentFocusTargetId
        }
        panel.addView(canvas, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply {
            topMargin = dpToPx(5f)
        })
        panel.layoutParams = GridLayout.LayoutParams().apply {
            width = LayoutParams.MATCH_PARENT
            height = maxOf(binding.keyboardGridScroll.height, dpToPx(124f))
        }
        grid.addView(panel)
        alternateModePanel = panel
        alternateModeAction = canvas
        handwritingCanvas = canvas
        gridButtons = candidates
        wireModeContentFocus = { wireHandwritingFocus(gridButtons, canvas) }
        wireHandwritingFocus(candidates, canvas)
    }

    private fun renderZhuyinKeys(grid: GridLayout) {
        val columns = 5
        val rows = (BopomofoPinyinMapper.keys.size + columns - 1) / columns
        grid.columnCount = columns
        grid.rowCount = rows
        val marginPx = dpToPx(2f)
        val heightPx = dpToPx(44f)
        val positions = BopomofoPinyinMapper.keys.mapIndexed { index, symbol ->
            val row = index / columns
            val column = if (index == BopomofoPinyinMapper.keys.lastIndex) 2 else index % columns
            symbol to (row to column)
        }
        gridButtons = positions.map { (symbol, position) ->
            Button(context).apply {
                id = View.generateViewId()
                text = symbol.toString()
                textSize = 19f
                minWidth = 0
                minHeight = 0
                isFocusable = true
                setPadding(0, 0, 0, 0)
                setBackgroundResource(R.drawable.bg_ktv_transparent_key)
                setTextColor(0xFFFFFFFF.toInt())
                contentDescription = if (BopomofoPinyinMapper.toPinyinInitial(symbol) != null) {
                    "$symbol，拼音声母"
                } else {
                    "$symbol，组合符号"
                }
                layoutParams = GridLayout.LayoutParams().apply {
                    width = 0
                    height = heightPx
                    rowSpec = GridLayout.spec(position.first, 1)
                    columnSpec = GridLayout.spec(position.second, 1, 1f)
                    setMargins(marginPx, marginPx, marginPx, marginPx)
                }
                setOnClickListener {
                    session.onBopomofoSymbolPressed(symbol)
                    updateDisplay()
                }
            }.also(grid::addView)
        }
        wireModeContentFocus = { wirePositionedGridFocus(positions.map { it.second }) }
        wirePositionedGridFocus(positions.map { it.second })
        resizeGridRows(rowCount = rows)
    }

    private fun wireHandwritingFocus(candidates: List<Button>, canvas: KtvHandwritingCanvasView) {
        candidates.forEachIndexed { index, button ->
            button.nextFocusLeftId = if (index == 0) contentFocusTargetId else candidates[index - 1].id
            button.nextFocusRightId = if (index == candidates.lastIndex) activeModeButtonId() else candidates[index + 1].id
            button.nextFocusUpId = binding.btnBackspace.id
            button.nextFocusDownId = canvas.id
        }
    }

    private fun wireQwertyFocus() {
        gridButtons.forEachIndexed { index, button ->
            if (button.text == "SPACE") {
                button.nextFocusLeftId = gridButtons[25].id
                button.nextFocusRightId = activeModeButtonId()
                button.nextFocusUpId = gridButtons[24].id
                button.nextFocusDownId = contentFocusTargetId
            } else {
                val row = index / 5
                val column = index % 5
                button.nextFocusLeftId = if (column == 0) contentFocusTargetId else gridButtons[index - 1].id
                button.nextFocusRightId = when {
                    index == 25 -> gridButtons.last().id
                    column == 4 -> activeModeButtonId()
                    else -> gridButtons[index + 1].id
                }
                button.nextFocusUpId = if (row == 0) binding.btnBackspace.id else gridButtons[index - 5].id
                button.nextFocusDownId = when {
                    row == 5 -> contentFocusTargetId
                    row == 4 && column == 0 -> gridButtons[25].id
                    row == 4 -> gridButtons.last().id
                    else -> gridButtons[index + 5].id
                }
            }
        }
    }

    private fun wireNumberFocus(positions: List<Pair<Int, Int>>) {
        val byPosition = positions.mapIndexed { index, position -> position to gridButtons[index] }.toMap()
        gridButtons.forEachIndexed { index, button ->
            val (row, column) = positions[index]
            val label = button.text.toString()
            button.nextFocusLeftId = when {
                label == "0" -> byPosition[2 to 0]?.id ?: contentFocusTargetId
                else -> byPosition[row to (column - 1)]?.id ?: contentFocusTargetId
            }
            button.nextFocusRightId = when {
                label == "0" -> byPosition[2 to 2]?.id ?: activeModeButtonId()
                else -> byPosition[row to (column + 1)]?.id ?: activeModeButtonId()
            }
            button.nextFocusUpId = byPosition[(row - 1) to column]?.id ?: binding.btnBackspace.id
            button.nextFocusDownId = when {
                label == "7" || label == "9" -> byPosition[3 to 1]?.id ?: contentFocusTargetId
                else -> byPosition[(row + 1) to column]?.id ?: contentFocusTargetId
            }
        }
    }

    private fun wirePositionedGridFocus(positions: List<Pair<Int, Int>>) {
        val byRow = gridButtons.indices.groupBy { positions[it].first }
        gridButtons.forEachIndexed { index, button ->
            val (row, column) = positions[index]
            val rowCells = byRow[row].orEmpty().sortedBy { positions[it].second }
            val left = rowCells.lastOrNull { positions[it].second < column }
            val right = rowCells.firstOrNull { positions[it].second > column }
            val above = byRow[row - 1].orEmpty().minByOrNull { kotlin.math.abs(positions[it].second - column) }
            val below = byRow[row + 1].orEmpty().minByOrNull { kotlin.math.abs(positions[it].second - column) }
            button.nextFocusLeftId = left?.let { gridButtons[it].id } ?: contentFocusTargetId
            button.nextFocusRightId = right?.let { gridButtons[it].id } ?: activeModeButtonId()
            button.nextFocusUpId = above?.let { gridButtons[it].id } ?: binding.btnBackspace.id
            button.nextFocusDownId = below?.let { gridButtons[it].id } ?: contentFocusTargetId
        }
    }

    private fun resizeAlternatePanel() {
        val panel = alternateModePanel ?: return
        val viewportHeight = binding.keyboardGridScroll.height
        if (viewportHeight <= 0) return
        panel.layoutParams = panel.layoutParams.apply { height = maxOf(viewportHeight, dpToPx(124f)) }
    }

    private fun handwritingBorder(): android.graphics.drawable.Drawable =
        android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = dpToPx(5f).toFloat()
            setColor(0x08000000)
            setStroke(dpToPx(1f), 0x45B8B8B8)
        }

    private fun setupActions() {
        binding.btnBackspace.setOnClickListener {
            session.backspace()
            updateDisplay()
        }
        binding.btnClear.setOnClickListener {
            session.clear()
            handwritingCanvas?.clearInk()
            updateDisplay()
        }

        binding.btnToggleT9.setOnClickListener {
            switchMode(Mode.ABC)
            onLayoutModeChanged?.invoke(KeyboardLayoutMode.QWERTY)
        }

        binding.btnMode123.setOnClickListener {
            switchMode(Mode.NUMBER)
            onLayoutModeChanged?.invoke(KeyboardLayoutMode.T9)
        }

        binding.btnModeHandwrite.setOnClickListener {
            switchMode(Mode.HANDWRITING)
        }

        binding.btnModeZhuyin.setOnClickListener {
            switchMode(Mode.ZHUYIN)
        }

        binding.btnSwitchIme.setOnClickListener {
            requestSystemInputMode()
        }
    }

    private fun switchMode(mode: Mode) {
        handwritingCanvas?.clearInk()
        session.clearPendingBopomofo()
        updateModeSelection(mode)
        renderKeys()
    }

    /** Focus entry from the song results into the currently visible keyboard mode. */
    fun focusTargetFromResults(): Int = gridButtons.firstOrNull()?.id
        ?: alternateModeAction?.id
        ?: activeModeButtonId()

    /** Points every keyboard mode back to the currently visible browser content. */
    fun setContentFocusTarget(viewId: Int) {
        if (viewId == View.NO_ID || contentFocusTargetId == viewId) return
        contentFocusTargetId = viewId
        wireModeRailFocus()
        wireModeContentFocus?.invoke()
    }

    private fun activeModeButtonId(): Int = when (currentInputMode) {
        Mode.ABC -> binding.btnToggleT9.id
        Mode.NUMBER -> binding.btnMode123.id
        Mode.HANDWRITING -> binding.btnModeHandwrite.id
        Mode.ZHUYIN -> binding.btnModeZhuyin.id
        Mode.SYSTEM -> binding.btnSwitchIme.id
    }

    private fun wireModeRailFocus() {
        val modeButtons = listOf(
            binding.btnToggleT9,
            binding.btnMode123,
            binding.btnModeHandwrite,
            binding.btnModeZhuyin,
            binding.btnSwitchIme,
        )
        val keyboardEntryId = focusTargetFromResults()
        modeButtons.forEachIndexed { index, button ->
            button.nextFocusLeftId = keyboardEntryId
            button.nextFocusUpId = if (index == 0) binding.btnClear.id else modeButtons[index - 1].id
            button.nextFocusDownId = if (index == modeButtons.lastIndex) {
                contentFocusTargetId
            } else {
                modeButtons[index + 1].id
            }
        }

        alternateModeAction?.let { action ->
            action.nextFocusLeftId = contentFocusTargetId
            action.nextFocusRightId = activeModeButtonId()
            action.nextFocusUpId = if (currentInputMode == Mode.HANDWRITING) {
                gridButtons.firstOrNull()?.id ?: binding.btnBackspace.id
            } else {
                binding.btnClear.id
            }
            action.nextFocusDownId = contentFocusTargetId
        }

        binding.btnBackspace.nextFocusDownId = if (gridButtons.isNotEmpty()) {
            gridButtons.first().id
        } else {
            alternateModeAction?.id ?: activeModeButtonId()
        }
        binding.btnClear.nextFocusDownId = activeModeButtonId()
    }

    private fun switchMode(mode: Mode, requestIme: Boolean) {
        switchMode(mode)
        if (requestIme) onImeRequest?.invoke()
    }

    private fun requestSystemInputMode() {
        switchMode(Mode.SYSTEM, requestIme = true)
    }

    fun measureNaturalContentHeightPx(availableWidthPx: Int): Int {
        if (availableWidthPx <= 0 || binding.keyboardContentRoot.measuredWidth != availableWidthPx) return 0
        return binding.keyboardContentRoot.measuredHeight
    }

    private fun updateDisplay() {
        binding.txtKeywordDisplay.text = session.currentKeyword
        renderPendingBopomofo()
        val keyword = session.currentKeyword
        val searchKeyword = T9PinyinSearchPolicy.normalizeSearchKeyword(keyword)
        if (searchKeyword != lastDispatchedSearchKeyword) {
            lastDispatchedSearchKeyword = searchKeyword
            onKeywordChanged?.invoke(keyword)
        }
    }

    private fun renderPendingBopomofo() {
        val pending = session.pendingBopomofo
        binding.txtPendingZhuyin.text = pending
        binding.txtPendingZhuyin.visibility = if (pending.isEmpty()) View.GONE else View.VISIBLE
    }

    fun consumeBackForActiveDrawing(action: Int): Boolean {
        val canvas = handwritingCanvas ?: return false
        if (!canvas.isRemoteDrawing) return false
        if (action == android.view.KeyEvent.ACTION_UP) canvas.finishRemoteDrawing()
        return action == android.view.KeyEvent.ACTION_DOWN || action == android.view.KeyEvent.ACTION_UP
    }

    private fun clearTransientInputState() {
        handwritingCanvas?.clearInk()
        session.clearPendingBopomofo()
        renderPendingBopomofo()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility != View.VISIBLE || !isShown) clearTransientInputState()
    }

    fun setKeyword(keyword: String) {
        session.setKeyword(keyword)
        updateDisplay()
    }

    fun onRemoteDigitPressed(digit: Char) {
        if (digit !in '0'..'9') return
        session.onDigitPressed(digit)
        updateDisplay()
    }

    fun clear() {
        session.clear()
        handwritingCanvas?.clearInk()
        updateDisplay()
    }

    private fun dpToPx(dp: Float): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, dp, resources.displayMetrics
        ).toInt()
    }
}
