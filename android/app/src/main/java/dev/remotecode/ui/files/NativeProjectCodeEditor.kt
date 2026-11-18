package dev.remotecode.ui.files

import dev.remotecode.R
import dev.remotecode.i18n.appString
import androidx.compose.ui.res.stringResource

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.text.LineBreaker
import android.os.Build
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.Layout
import android.text.Spanned
import android.text.TextWatcher
import android.text.method.ArrowKeyMovementMethod
import android.text.method.TextKeyListener
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewTreeObserver
import android.view.inputmethod.EditorInfo
import android.view.textclassifier.TextClassifier
import android.widget.EditText
import android.widget.LinearLayout
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import dev.remotecode.data.CodeLineIndex
import dev.remotecode.data.checkProjectEdit
import kotlin.math.ceil
import kotlin.math.max

/** 本文は Editable に保持し、選択・スクロールでは Compose を更新しない。 */
internal class NativeCodeEditorState(initialText: String = "") {
    var contentRevision by mutableLongStateOf(0L)
        private set
    var lineCount by mutableIntStateOf(1 + initialText.count { it == '\n' })
        private set
    private var attached: CodeEditorLayout? = null
    private var cached = initialText
    private var cachedRevision = 0L
    private var savedSelectionStart = 0
    private var savedSelectionEnd = 0
    private var savedScrollX = 0
    private var savedScrollY = 0

    val selectionStart: Int get() = attached?.editor?.selectionStart?.coerceAtLeast(0) ?: savedSelectionStart
    val selectionEnd: Int get() = attached?.editor?.selectionEnd?.coerceAtLeast(0) ?: savedSelectionEnd

    /** 内容が変わるたびに一度だけ文字列化する。 */
    fun snapshot(): String {
        if (cachedRevision != contentRevision) {
            attached?.editor?.text?.let { cached = it.toString(); cachedRevision = contentRevision }
        }
        return cached
    }

    /** 通常編集では呼ばず、読み込み・保存・再読み込みに使う。 */
    fun setText(value: String, resetSelection: Boolean = true) {
        val previousStart = selectionStart
        val previousEnd = selectionEnd
        cached = value
        contentRevision++
        cachedRevision = contentRevision
        lineCount = 1 + value.count { it == '\n' }
        savedSelectionStart = if (resetSelection) 0 else previousStart.coerceIn(0, value.length)
        savedSelectionEnd = if (resetSelection) 0 else previousEnd.coerceIn(0, value.length)
        if (resetSelection) { savedScrollX = 0; savedScrollY = 0 }
        attached?.replaceDocument(value, savedSelectionStart, savedSelectionEnd, resetSelection)
    }

    private fun changed(lines: Int) {
        lineCount = lines
        contentRevision++
    }

    private fun attach(view: CodeEditorLayout) {
        if (attached === view) return
        attached?.let { detach(it) }
        attached = view
        view.replaceDocument(cached, savedSelectionStart, savedSelectionEnd, resetScroll = false)
        view.editor.post { if (attached === view) view.editor.scrollTo(savedScrollX, savedScrollY) }
    }

    private fun detach(view: CodeEditorLayout) {
        if (attached !== view) return
        snapshot()
        savedSelectionStart = selectionStart
        savedSelectionEnd = selectionEnd
        savedScrollX = view.editor.scrollX
        savedScrollY = view.editor.scrollY
        attached = null
    }

    internal fun mount(view: View) = attach(view as CodeEditorLayout)
    internal fun release(view: View) = detach(view as CodeEditorLayout)
    internal fun documentChanged(lines: Int) = changed(lines)
}

@Composable
internal fun NativeProjectCodeEditor(
    state: NativeCodeEditorState,
    editable: Boolean,
    wrapLines: Boolean,
    modifier: Modifier = Modifier,
    textSizeSp: Float,
    lineHeightSp: Float,
    onChanged: () -> Unit,
    onRejected: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val foreground = colors.onSurface.toArgb()
    val gutter = colors.onSurfaceVariant.toArgb()
    val highlight = colors.primary.copy(alpha = 0.35f).toArgb()
    key(state) { AndroidView(
        factory = { context -> CodeEditorLayout(context, state).also(state::mount) },
        modifier = modifier.fillMaxWidth(),
        onReset = null,
        onRelease = { view -> state.release(view); view.onChanged = {}; view.onRejected = {} },
        update = { view ->
            view.onChanged = onChanged
            view.onRejected = onRejected
            view.configure(editable, wrapLines, textSizeSp, lineHeightSp, foreground, gutter, highlight)
        },
    ) }
}

private const val CODE_INPUT_TYPE = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
    InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_FLAG_IME_MULTI_LINE

private class CodeEditorLayout(context: Context, private val state: NativeCodeEditorState) : LinearLayout(context) {
    val editor = ScrollingCodeEditText(context)
    private val lines = CodeLineIndex()
    private val gutter = LineNumberView(context, editor, lines)
    private val originalKeyListener = TextKeyListener.getInstance(false, TextKeyListener.Capitalize.NONE)
    private var replacing = false
    private var editable: Boolean? = null
    private var wrapLines: Boolean? = null
    private var unwrappedScrollX = 0
    private var viewport: ViewportAnchor? = null
    private var viewportScheduled = false
    private var textSp = Float.NaN
    private var lineSp = Float.NaN
    private var foregroundColor = Int.MIN_VALUE
    private var gutterColor = Int.MIN_VALUE
    private var highlightColor = Int.MIN_VALUE
    private var gutterDigits = 0
    private var columns = 10_000
    var onChanged: () -> Unit = {}
    var onRejected: () -> Unit = {}

    init {
        orientation = HORIZONTAL
        isSaveEnabled = false
        importantForAutofill = IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) importantForContentCapture = IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS
        editor.apply {
            background = null
            gravity = Gravity.TOP or Gravity.START
            typeface = Typeface.MONOSPACE
            includeFontPadding = false
            setRawInputType(CODE_INPUT_TYPE)
            setHorizontallyScrolling(true)
            breakStrategy = LineBreaker.BREAK_STRATEGY_SIMPLE
            hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
            imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            setTextClassifier(TextClassifier.NO_OP)
            isSaveEnabled = false
            importantForAutofill = IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) importantForContentCapture = IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS
            isVerticalScrollBarEnabled = true
            isHorizontalScrollBarEnabled = true
            setPadding(dp(6), dp(8), dp(12), dp(8))
            contentDescription = appString(R.string.screen_native_project_code_editor_001)
            onScrolled = { gutter.invalidate() }
            onLaidOut = { restoreViewportAfterLayout(); gutter.invalidate() }
        }
        addView(gutter, LayoutParams(dp(40), LayoutParams.MATCH_PARENT))
        addView(editor, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        editor.filters = arrayOf(InputFilter { source, start, end, destination, dstart, dend ->
            if (replacing) null else {
                val tooLarge = destination.length.toLong() - (dend - dstart) + (end - start) > 2 * 1024 * 1024
                val normalized = if (!tooLarge && (start until end).any { source[it] == '\r' }) source.subSequence(start, end).toString().replace("\r\n", "\n").replace('\r', '\n') else null
                val check = if (tooLarge) null else checkProjectEdit(destination, dstart, dend, normalized ?: source, if (normalized != null) 0 else start, normalized?.length ?: end, state.lineCount, columns)
                if (check?.allowed == true) normalized else {
                    // InputFilter 内で Compose の状態を変更しない。
                    editor.post { onRejected() }
                    // 空文字を返すと選択範囲が削除される。
                    destination.subSequence(dstart, dend).toString()
                }
            }
        })
        editor.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) {
                if (replacing || text == null) return
                lines.replace(start, start + before, text, start, start + count)
                state.documentChanged(lines.lineCount)
                updateGutterWidth()
                gutter.invalidate()
                onChanged()
            }
            override fun afterTextChanged(text: Editable?) = Unit
        })
    }

    fun replaceDocument(text: String, start: Int, end: Int, resetScroll: Boolean) {
        viewport = null
        lines.reset(text)
        replacing = true
        try {
            editor.setText(text, android.widget.TextView.BufferType.EDITABLE)
            editor.setSelection(start.coerceIn(0, text.length), end.coerceIn(0, text.length))
            if (resetScroll) editor.scrollTo(0, 0)
        } finally { replacing = false }
        updateGutterWidth()
        gutter.invalidate()
    }

    fun configure(enabled: Boolean, wrap: Boolean, sizeSp: Float, heightSp: Float, foreground: Int, numberColor: Int, selectionColor: Int) {
        if (wrapLines != wrap) {
            editor.layout?.let { layout ->
                val top = editor.scrollY - editor.totalPaddingTop
                val row = layout.getLineForVertical(top.coerceAtLeast(0))
                viewport = ViewportAnchor(layout.getLineStart(row), top - layout.getLineTop(row), state.contentRevision)
            }
            if (wrap) unwrappedScrollX = editor.scrollX
            wrapLines = wrap
            editor.setHorizontallyScrolling(!wrap)
            editor.isHorizontalScrollBarEnabled = !wrap
            if (wrap) editor.scrollTo(0, editor.scrollY)
        }
        if (textSp != sizeSp || lineSp != heightSp) {
            textSp = sizeSp; lineSp = heightSp
            editor.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            val targetHeight = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, heightSp, resources.displayMetrics)
            editor.setLineSpacing(max(0f, targetHeight - editor.paint.fontSpacing), 1f)
            gutter.paint.textSize = editor.textSize
            // 読み込み時の行幅制限とそろえる。
            columns = (100_000f / max(1f, editor.textSize)).toInt().coerceAtLeast(1)
            gutterDigits = 0
            updateGutterWidth()
        }
        if (foregroundColor != foreground) { foregroundColor = foreground; editor.setTextColor(foreground) }
        if (gutterColor != numberColor) { gutterColor = numberColor; gutter.paint.color = numberColor; gutter.invalidate() }
        if (highlightColor != selectionColor) { highlightColor = selectionColor; editor.highlightColor = selectionColor }
        if (editable != enabled) {
            val start = editor.selectionStart.coerceAtLeast(0)
            val end = editor.selectionEnd.coerceAtLeast(0)
            replacing = true
            try {
                if (enabled) {
                    editor.setTextIsSelectable(false)
                    editor.keyListener = originalKeyListener
                    editor.movementMethod = ArrowKeyMovementMethod.getInstance()
                    editor.setRawInputType(CODE_INPUT_TYPE)
                } else {
                    editor.keyListener = null
                    editor.setTextIsSelectable(true)
                }
                editor.isFocusableInTouchMode = true
                editor.showSoftInputOnFocus = enabled
                editor.isCursorVisible = enabled
                editor.setSelection(start.coerceAtMost(editor.length()), end.coerceAtMost(editor.length()))
            } finally { replacing = false }
            editable = enabled
        }
    }

    private fun restoreViewportAfterLayout() {
        if (viewport == null || viewportScheduled) return
        viewportScheduled = true
        val observer = editor.viewTreeObserver
        observer.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (observer.isAlive) observer.removeOnPreDrawListener(this)
                viewportScheduled = false
                restoreViewport()
                return true
            }
        })
    }

    private fun restoreViewport() {
        val anchor = viewport ?: return
        viewport = null
        if (anchor.revision != state.contentRevision) return
        val layout = editor.layout ?: return
        val row = layout.getLineForOffset(anchor.offset.coerceIn(0, editor.length()))
        val maximum = (layout.height + editor.totalPaddingTop + editor.totalPaddingBottom - editor.height).coerceAtLeast(0)
        val top = (layout.getLineTop(row) + editor.totalPaddingTop + anchor.withinRow).coerceIn(0, maximum)
        editor.scrollTo(if (wrapLines == true) 0 else unwrappedScrollX, top)
    }

    private data class ViewportAnchor(val offset: Int, val withinRow: Int, val revision: Long)

    private fun updateGutterWidth() {
        val digits = state.lineCount.toString().length
        if (digits == gutterDigits) return
        gutterDigits = digits
        val width = ceil(gutter.paint.measureText("9".repeat(digits))).toInt() + dp(20)
        gutter.layoutParams = gutter.layoutParams.apply { this.width = width }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
}

private class ScrollingCodeEditText(context: Context) : EditText(context) {
    var onScrolled: (() -> Unit)? = null
    var onLaidOut: (() -> Unit)? = null
    override fun onScrollChanged(left: Int, top: Int, oldLeft: Int, oldTop: Int) {
        super.onScrollChanged(left, top, oldLeft, oldTop)
        onScrolled?.invoke()
    }
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        onLaidOut?.invoke()
    }
}

private class LineNumberView(context: Context, private val editor: EditText, private val lines: CodeLineIndex) : View(context) {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE; textAlign = Paint.Align.RIGHT }
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO; isSaveEnabled = false }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val layout = editor.layout ?: return
        if (layout.lineCount == 0) return
        val first = layout.getLineForVertical((editor.scrollY - editor.totalPaddingTop).coerceAtLeast(0))
        val last = layout.getLineForVertical((editor.scrollY + height - editor.totalPaddingTop).coerceAtLeast(0)).coerceAtMost(layout.lineCount - 1)
        val right = width - 8 * resources.displayMetrics.density
        for (line in first..last) {
            val offset = layout.getLineStart(line)
            val logical = lines.lineForOffset(offset)
            if (offset != lines.startOfLine(logical)) continue
            val baseline = editor.totalPaddingTop + layout.getLineBaseline(line) - editor.scrollY
            canvas.drawText((logical + 1).toString(), right, baseline.toFloat(), paint)
        }
    }
}
