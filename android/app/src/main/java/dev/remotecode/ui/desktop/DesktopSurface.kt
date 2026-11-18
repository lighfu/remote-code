package dev.remotecode.ui.desktop

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import dev.remotecode.data.DesktopCursor
import dev.remotecode.data.DesktopDisplay
import dev.remotecode.data.DesktopImage
import dev.remotecode.data.DesktopInput
import dev.remotecode.data.DesktopPoint
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.hypot
import kotlin.math.min

/** The same rectangle is used for rendering and hit testing, including the letterbox. */
internal data class DesktopViewport(val left: Float, val top: Float, val width: Float, val height: Float) {
    fun filterBitmap(pixelated: Boolean, sourceWidth: Int, sourceHeight: Int): Boolean =
        !pixelated || (width < sourceWidth && height < sourceHeight)

    fun point(x: Float, y: Float): DesktopPoint? {
        if (width <= 0f || height <= 0f || x < left || y < top || x > left + width || y > top + height) return null
        return DesktopPoint(((x - left) / width).toDouble(), ((y - top) / height).toDouble())
    }

    fun transform(from: FingerPair, to: FingerPair, factor: Float, cursor: DesktopPoint?,
                  viewWidth: Int, viewHeight: Int): DesktopViewport {
        val resized = copy(width = width * factor, height = height * factor)
        if (cursor != null) return resized.centeredOn(cursor, viewWidth, viewHeight)
        return resized.copy(left = to.centerX - (from.centerX - left) * factor,
            top = to.centerY - (from.centerY - top) * factor)
    }

    fun centeredOn(point: DesktopPoint, viewWidth: Int, viewHeight: Int): DesktopViewport {
        fun origin(view: Int, content: Float, coordinate: Double): Float {
            val spare = view - content
            if (spare >= 0f) return spare / 2f
            return (view / 2f - coordinate.toFloat() * content).coerceIn(spare, 0f)
        }
        return copy(left = origin(viewWidth, width, point.x), top = origin(viewHeight, height, point.y))
    }

    companion object {
        fun fit(viewWidth: Int, viewHeight: Int, bitmapWidth: Int, bitmapHeight: Int): DesktopViewport {
            val scale = min(viewWidth.toFloat() / bitmapWidth, viewHeight.toFloat() / bitmapHeight)
            val width = scale * bitmapWidth
            val height = scale * bitmapHeight
            return DesktopViewport((viewWidth - width) / 2, (viewHeight - height) / 2, width, height)
        }
    }
}

internal data class FingerPair(val centerX: Float, val centerY: Float, val span: Float) {
    companion object {
        fun of(x1: Float, y1: Float, x2: Float, y2: Float) =
            FingerPair((x1 + x2) / 2, (y1 + y2) / 2, hypot(x2 - x1, y2 - y1))
    }
}

internal data class DesktopFinger(val id: Int, val x: Float, val y: Float) {
    fun distance(other: DesktopFinger) = hypot(x - other.x, y - other.y)
}

/** Touches are classified before emitting PC input. A two-finger gesture never holds a Windows mouse button. */
internal class DesktopTouchGestures(
    private val slop: Float,
    private val doubleSlop: Float,
    private val doubleTimeout: Long,
    private val longTimeout: Long,
    private val viewport: () -> DesktopViewport,
    private val emit: (DesktopInput) -> Unit,
    private val transform: (from: FingerPair, to: FingerPair, scale: Float?) -> Unit,
    private val previewDrag: (DesktopPoint?) -> Unit = {},
) {
    var touchpad = true
        set(value) {
            if (field != value) cancel()
            field = value
        }
    var pointerSpeed = 1f
    private data class Contact(val start: DesktopFinger, val at: Long, var last: DesktopFinger = start,
                               var moved: Boolean = false, var held: Boolean = false)
    private data class Tap(val finger: DesktopFinger, val at: Long)
    private enum class PairMotion { Pending, Pan, Pinch, Wheel }
    private var first: Contact? = null
    private var second: Contact? = null
    private var previousPair: FingerPair? = null
    private var startingPair: FingerPair? = null
    private var pairFirstY = 0f
    private var pairMotion = PairMotion.Pending
    private var wheelRemainder = 0f
    private var pending: Tap? = null
    private var doubleTap: Tap? = null
    private var twoFingerTap = false

    // The view uses the platform's gesture timeouts and uptime clock for this deadline.
    fun nextDeadline(): Long? {
        pending?.let { return it.at + doubleTimeout }
        val contact = first ?: return null
        if (touchpad || second != null || contact.held || contact.moved || doubleTap != null) return null
        return contact.at + longTimeout
    }

    fun advance(now: Long) {
        pending?.takeIf { now >= it.at + doubleTimeout }?.let {
            pending = null
            click("click", "cursorClick", it.finger)
        }
        val contact = first ?: return
        if (!touchpad && second == null && doubleTap == null && !contact.held && !contact.moved && now >= contact.at + longTimeout) {
            contact.held = true
            click("rightClick", "cursorRightClick", contact.start)
        }
    }

    fun down(finger: DesktopFinger, time: Long) {
        advance(time)
        val prior = pending
        pending = null
        cancel()
        if (prior != null && finger.distance(prior.finger) <= doubleSlop && time - prior.at < doubleTimeout) {
            doubleTap = prior
        } else if (prior != null) {
            click("click", "cursorClick", prior.finger)
        }
        first = Contact(finger, time)
    }

    fun secondDown(firstNow: DesktopFinger, next: DesktopFinger, time: Long) {
        val one = first ?: return
        if (firstNow.id != one.start.id || second != null) {
            cancel()
            return
        }
        // An already completed single tap still counts when the next gesture uses two fingers.
        previewDrag(null)
        doubleTap?.let { click("click", "cursorClick", it.finger) }
        doubleTap = null
        twoFingerTap = !one.moved && !one.held && firstNow.distance(one.start) <= slop && time - one.at < doubleTimeout
        one.last = firstNow
        second = Contact(next, time)
        val pair = FingerPair.of(firstNow.x, firstNow.y, next.x, next.y)
        previousPair = pair
        startingPair = pair
        pairFirstY = firstNow.y
        pairMotion = PairMotion.Pending
        wheelRemainder = 0f
    }

    fun move(finger: DesktopFinger, other: DesktopFinger? = null) {
        val one = first ?: return
        val two = second
        if (two != null) {
            fun update(f: DesktopFinger) {
                val contact = if (f.id == one.start.id) one else two
                contact.last = f
                if (f.distance(contact.start) > slop) twoFingerTap = false
            }
            update(finger)
            if (other != null) {
                update(other)
                handlePairMove(one, two)
            }
            return
        }
        if (finger.id != one.start.id) return
        if (!one.moved && finger.distance(one.start) <= slop) return
        one.moved = true
        if (touchpad && !one.held) {
            if (doubleTap != null) previewDrag(relativeDelta(one.start, finger))
            else relativeDelta(one.last, finger)?.let { emit(DesktopInput("moveBy", it)) }
        }
        one.last = finger
    }

    private fun handlePairMove(one: Contact, two: Contact) {
        val start = startingPair ?: return
        val previous = previousPair ?: return
        val next = FingerPair.of(one.last.x, one.last.y, two.last.x, two.last.y)
        if (twoFingerTap || previous == next) return
        val oldMotion = pairMotion
        if (oldMotion == PairMotion.Pending || oldMotion == PairMotion.Pan) {
            val dx = abs(next.centerX - start.centerX)
            val dy = abs(next.centerY - start.centerY)
            val spanChange = abs(next.span - start.span)
            val parallelY = (one.last.y - pairFirstY) * (two.last.y - two.start.y) > 0f
            pairMotion = when {
                spanChange > slop && (!touchpad || spanChange >= max(dx, dy)) -> PairMotion.Pinch
                oldMotion == PairMotion.Pan -> PairMotion.Pan
                touchpad && parallelY && dy > slop && dy > dx -> PairMotion.Wheel
                dx > slop && dx >= dy || !touchpad && dy > slop -> PairMotion.Pan
                else -> return
            }
        }
        twoFingerTap = false
        when (pairMotion) {
            PairMotion.Wheel -> {
                val pixelsPerTick = (slop * 3f).coerceAtLeast(1f)
                wheelRemainder += next.centerY - previous.centerY
                var ticks = (wheelRemainder / pixelsPerTick).toInt()
                wheelRemainder -= ticks * pixelsPerTick
                while (ticks != 0) {
                    val batch = ticks.coerceIn(-MaxDesktopWheelTicks, MaxDesktopWheelTicks)
                    emit(DesktopInput("cursorScroll", delta = batch))
                    ticks -= batch
                }
            }
            PairMotion.Pinch -> {
                val reference = if (oldMotion == PairMotion.Pinch) previous.span else start.span
                if (reference >= 1f && next.span >= 1f) transform(previous, next, next.span / reference)
            }
            PairMotion.Pan -> transform(previous, next, null)
            PairMotion.Pending -> return
        }
        previousPair = next
    }

    fun up(finger: DesktopFinger, time: Long) {
        val one = first ?: return
        move(finger)
        when {
            second != null -> if (twoFingerTap && time - one.at <= doubleTimeout) {
                click("rightClick", "cursorRightClick", one.start)
            }
            one.held -> Unit
            one.moved && touchpad && doubleTap != null -> {
                previewDrag(null)
                relativeDelta(one.start, finger)?.let { emit(DesktopInput("cursorDragBy", it)) }
            }
            one.moved && !touchpad -> {
                val box = viewport()
                val start = box.point(one.start.x, one.start.y)
                val end = box.point(finger.x, finger.y)
                if (start != null && end != null) emit(DesktopInput("drag", point = start, end = end))
            }
            one.moved -> Unit
            doubleTap != null -> click("doubleClick", "cursorDoubleClick", finger)
            touchpad && time - one.at >= longTimeout -> Unit
            else -> pending = Tap(finger, time)
        }
        first = null
        second = null
        doubleTap = null
        previousPair = null
        startingPair = null
        twoFingerTap = false
    }

    private fun relativeDelta(from: DesktopFinger, to: DesktopFinger): DesktopPoint? {
        val box = viewport()
        if (box.width <= 0 || box.height <= 0) return null
        val dx = ((to.x - from.x) * pointerSpeed / box.width).toDouble().coerceIn(-1.0, 1.0)
        val dy = ((to.y - from.y) * pointerSpeed / box.height).toDouble().coerceIn(-1.0, 1.0)
        return if (dx != 0.0 || dy != 0.0) DesktopPoint(dx, dy) else null
    }

    private fun click(direct: String, cursor: String, finger: DesktopFinger) {
        if (touchpad) emit(DesktopInput(cursor))
        else viewport().point(finger.x, finger.y)?.let { emit(DesktopInput(direct, it)) }
    }

    fun cancel() {
        previewDrag(null)
        first = null
        second = null
        pending = null
        doubleTap = null
        previousPair = null
        startingPair = null
        twoFingerTap = false
    }
}

internal const val MaxDesktopZoom = 32f

internal class DesktopSurface(context: Context) : View(context) {
    var send: (DesktopInput) -> Boolean = { false }
    var onZoomChanged: (Float) -> Unit = {}
    var onFrameDrawn: (DesktopImage) -> Unit = {}
    private var image: DesktopImage? = null
    private var reportFrame = false
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    var pixelated: Boolean = false
        set(value) {
            if (value == field) return
            field = value
            postInvalidateOnAnimation()
        }
    private val destination = RectF()
    private val pointer = DesktopPointerPreview()
    private val pointerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeJoin = Paint.Join.ROUND }
    private val arrow = Path().apply {
        moveTo(0f, 0f)
        lineTo(0f, 17f)
        lineTo(4.5f, 13f)
        lineTo(8f, 20f)
        lineTo(11f, 18.5f)
        lineTo(7.5f, 11.5f)
        lineTo(14.5f, 11.5f)
        close()
    }
    private var followingPointer = true
    private var dragPosition: DesktopPoint? = null
    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f
    private val configuration = ViewConfiguration.get(context)
    private val gestures = DesktopTouchGestures(
        configuration.scaledTouchSlop.toFloat(), configuration.scaledDoubleTapSlop.toFloat(),
        ViewConfiguration.getDoubleTapTimeout().toLong(), ViewConfiguration.getLongPressTimeout().toLong(),
        ::viewport, ::sendGesture, ::transformView, ::previewDrag,
    )
    var touchpad: Boolean
        get() = gestures.touchpad
        set(value) {
            if (value != gestures.touchpad) {
                gestures.touchpad = value
                followingPointer = value
                if (centerOnPointer()) invalidate()
            }
        }
    var pointerSpeed: Float
        get() = gestures.pointerSpeed
        set(value) { gestures.pointerSpeed = value.coerceIn(0.25f, 4f) }

    private val tick = Runnable {
        scheduled = null
        gestures.advance(SystemClock.uptimeMillis())
        scheduleTick()
    }
    private var scheduled: Long? = null
    private var touchClick: DesktopInput? = null

    init { isClickable = true }

    fun update(image: DesktopImage, cursor: DesktopCursor) {
        val previous = pointer.position
        val embedded = pointer.embedded
        pointer.sample(cursor)
        if (pointer.location == null) dragPosition = null
        val newImage = this.image !== image
        if (newImage) {
            if (this.image?.width != image.width || this.image?.height != image.height) fit()
            this.image = image
            reportFrame = true
        }
        val panned = centerOnPointer()
        if (newImage || panned || previous != pointer.position || embedded != pointer.embedded) postInvalidateOnAnimation()
    }

    fun setDisplays(display: String, displays: List<DesktopDisplay>) {
        pointer.setDisplays(display, displays)
    }

    fun settleCursor(revision: Long?, moreQueued: Boolean) {
        val previous = pointer.position
        pointer.settle(revision, moreQueued)
        if (centerOnPointer() || previous != pointer.position) postInvalidateOnAnimation()
    }

    fun resetCursorPrediction() {
        dragPosition = null
        pointer.reset()
        postInvalidateOnAnimation()
    }

    fun fit() {
        followingPointer = touchpad
        zoom = 1f
        panX = 0f
        panY = 0f
        onZoomChanged(zoom)
        invalidate()
    }

    fun zoomBy(factor: Float) {
        if (!factor.isFinite() || factor <= 0f) return
        val center = FingerPair(width / 2f, height / 2f, 1f)
        transformView(center, center, factor)
    }

    private fun transformView(from: FingerPair, to: FingerPair, scale: Float?) {
        val next = (zoom * (scale ?: 1f)).coerceIn(1f, MaxDesktopZoom)
        val box = viewport()
        val cursor = if (scale != null) dragPosition ?: pointer.location else null
        followingPointer = touchpad && scale != null
        val result = box.transform(from, to, next / zoom, cursor, width, height)
        panX = result.left - (width - result.width) / 2
        panY = result.top - (height - result.height) / 2
        if (next != zoom) {
            zoom = next
            onZoomChanged(next)
        }
        invalidate()
    }

    private fun viewport(): DesktopViewport {
        val frame = image ?: return DesktopViewport(0f, 0f, 0f, 0f)
        val fitted = DesktopViewport.fit(width, height, frame.width, frame.height)
        val w = fitted.width * zoom
        val h = fitted.height * zoom
        val maxX = ((w - width) / 2).coerceAtLeast(0f)
        val maxY = ((h - height) / 2).coerceAtLeast(0f)
        panX = panX.coerceIn(-maxX, maxX)
        panY = panY.coerceIn(-maxY, maxY)
        return DesktopViewport((width - w) / 2 + panX, (height - h) / 2 + panY, w, h)
    }

    // Two fingers can look elsewhere. The next one-finger pointer movement resumes tracking.
    private fun centerOnPointer(): Boolean {
        if (!touchpad || !followingPointer || zoom <= 1f || width <= 0 || height <= 0) return false
        val point = dragPosition ?: pointer.location ?: return false
        val box = viewport()
        val next = box.centeredOn(point, width, height)
        if (box.left == next.left && box.top == next.top) return false
        panX = next.left - (width - box.width) / 2f
        panY = next.top - (height - box.height) / 2f
        return true
    }

    private fun previewDrag(delta: DesktopPoint?) {
        val point = delta?.let(pointer::relativePosition)
        if (point == dragPosition) return
        dragPosition = point
        if (point != null) followingPointer = true
        centerOnPointer()
        postInvalidateOnAnimation()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        centerOnPointer()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        val frame = image ?: return
        val box = viewport()
        destination.set(box.left, box.top, box.left + box.width, box.top + box.height)
        paint.isFilterBitmap = box.filterBitmap(pixelated, frame.width, frame.height)
        frame.draw(canvas, destination, paint)
        (dragPosition ?: pointer.position)?.let { drawPointer(canvas, box, it) }
        if (reportFrame) {
            reportFrame = false
            onFrameDrawn(frame)
        }
    }

    private fun drawPointer(canvas: Canvas, box: DesktopViewport, point: DesktopPoint) {
        val save = canvas.save()
        canvas.clipRect(destination)
        canvas.translate(box.left + point.x.toFloat() * box.width, box.top + point.y.toFloat() * box.height)
        val scale = resources.displayMetrics.density
        canvas.scale(scale, scale)
        pointerPaint.style = Paint.Style.STROKE
        pointerPaint.color = Color.BLACK
        if (pointer.embedded) {
            // Custom Windows cursors are part of the image. Mark pending motion with a ring.
            pointerPaint.strokeWidth = 3f
            canvas.drawCircle(0f, 0f, 6f, pointerPaint)
            pointerPaint.strokeWidth = 1.4f
            pointerPaint.color = Color.WHITE
            canvas.drawCircle(0f, 0f, 6f, pointerPaint)
        } else {
            pointerPaint.strokeWidth = 2f
            canvas.drawPath(arrow, pointerPaint)
            pointerPaint.style = Paint.Style.FILL
            pointerPaint.color = Color.WHITE
            canvas.drawPath(arrow, pointerPaint)
        }
        canvas.restoreToCount(save)
    }

    private fun scheduleTick() {
        val deadline = gestures.nextDeadline()
        if (deadline == scheduled) return
        removeCallbacks(tick)
        scheduled = deadline
        if (deadline != null) postDelayed(tick, max(0L, deadline - SystemClock.uptimeMillis()))
    }

    private fun sendGesture(input: DesktopInput) {
        if (input.type == "click" || input.type == "cursorClick") {
            touchClick = input
            performClick()
            touchClick = null
        } else dispatch(input)
    }

    private fun dispatch(input: DesktopInput) {
        if (!send(input)) return
        val previous = pointer.position
        pointer.queued(input)
        if (touchpad && (input.type == "moveBy" || input.type == "cursorDragBy")) followingPointer = true
        if (centerOnPointer() || previous != pointer.position) postInvalidateOnAnimation()
    }

    override fun performClick(): Boolean {
        super.performClick()
        val action = touchClick ?: if (touchpad) DesktopInput("cursorClick")
            else viewport().point(width / 2f, height / 2f)?.let { DesktopInput("click", it) }
        if (action != null) dispatch(action)
        return true
    }

    private fun MotionEvent.finger(index: Int) = DesktopFinger(getPointerId(index), getX(index), getY(index))

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val time = event.eventTime
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> gestures.down(event.finger(0), time)
            MotionEvent.ACTION_POINTER_DOWN -> if (event.pointerCount == 2) {
                gestures.secondDown(event.finger(1 - event.actionIndex), event.finger(event.actionIndex), time)
            } else gestures.cancel()
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_POINTER_UP -> if (event.pointerCount <= 2) {
                gestures.move(event.finger(0), if (event.pointerCount == 2) event.finger(1) else null)
            }
            MotionEvent.ACTION_UP -> gestures.up(event.finger(0), time)
            MotionEvent.ACTION_CANCEL -> gestures.cancel()
        }
        scheduleTick()
        return true
    }

    override fun onDetachedFromWindow() {
        gestures.cancel()
        removeCallbacks(tick)
        scheduled = null
        send = { false }
        onZoomChanged = {}
        onFrameDrawn = {}
        image = null
        pointer.reset()
        super.onDetachedFromWindow()
    }
}
