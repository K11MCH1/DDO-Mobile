package com.ddomobile.launcher.input

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

/**
 * DDO's touch layer. Buttons press keys or mouse buttons; the free screen is the mouse: a tap clicks, a drag
 * holds the left button (DDO turns the camera), a long press right-clicks (use / interact) and a pinch scrolls
 * (camera zoom). A floating stick under the left thumb walks with W/A/S/D.
 */
@SuppressLint("ViewConstructor")
class TouchControls(context: Context, private val sink: Sink) : View(context) {

    interface Sink {
        /** Absolute pointer position in the view's pixels. */
        fun pointer(x: Float, y: Float)
        fun button(code: Int, pressed: Boolean)
        fun key(code: Int, pressed: Boolean)
        fun scroll(steps: Float)
        fun keyboard()
        fun menu()
    }

    private class Button(val label: String, val x: Float, val y: Float, val sizeDp: Float, val code: Int, val mouse: Boolean = false) {
        var cx = 0f
        var cy = 0f
        var r = 0f
        var pointer = -1
    }

    private val density = resources.displayMetrics.density
    private val buttons = listOf(
        Button("Attack", 0.90f, 0.70f, 84f, Keys.BTN_RIGHT, mouse = true),
        Button("Jump", 0.78f, 0.86f, 58f, Keys.SPACE),
        Button("Target", 0.77f, 0.62f, 52f, Keys.TAB),
        Button("Run", 0.96f, 0.46f, 44f, Keys.R),
        Button("1", 0.66f, 0.92f, 42f, Keys.digit(1)),
        Button("2", 0.68f, 0.78f, 42f, Keys.digit(2)),
        Button("3", 0.69f, 0.64f, 42f, Keys.digit(3)),
        Button("4", 0.84f, 0.48f, 42f, Keys.digit(4)),
        Button("5", 0.90f, 0.36f, 42f, Keys.digit(5)),
        Button("6", 0.96f, 0.24f, 42f, Keys.digit(6)),
        Button("Menu", 0.40f, 0.05f, 30f, Keys.ESC),
        Button("Bag", 0.47f, 0.05f, 30f, Keys.I),
        Button("Map", 0.54f, 0.05f, 30f, Keys.M),
        Button("⌨", 0.61f, 0.05f, 30f, KEYBOARD),
        Button("☰", 0.97f, 0.05f, 30f, SETTINGS),
    )

    var controlsVisible = true
        set(value) { field = value; releaseAll(); invalidate() }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(70, 10, 12, 16) }
    private val pressedFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(140, 200, 160, 60) }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.5f * resources.displayMetrics.density; color = Color.argb(150, 230, 200, 120)
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 245, 235, 210); textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD
    }

    // Movement stick
    private var stickPointer = -1
    private var stickX = 0f
    private var stickY = 0f
    private var knobX = 0f
    private var knobY = 0f
    private val stickHeld = mutableSetOf<Int>()

    // Mouse from the free screen
    private var mousePointer = -1
    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L
    private var dragging = false
    private var longPressed = false
    private var pinchDistance = 0f
    private val longPress = Runnable {
        if (mousePointer >= 0 && !dragging) {
            longPressed = true
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            sink.button(Keys.BTN_RIGHT, true)
            sink.button(Keys.BTN_RIGHT, false)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        for (b in buttons) {
            b.r = b.sizeDp * density / 2f
            b.cx = (b.x * w).coerceIn(b.r, w - b.r)
            b.cy = (b.y * h).coerceIn(b.r, h - b.r)
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (!controlsVisible) {
            drawButton(canvas, buttons.last())
            return
        }
        for (b in buttons) drawButton(canvas, b)
        if (stickPointer >= 0) {
            val r = STICK_RADIUS_DP * density
            canvas.drawCircle(stickX, stickY, r, fill)
            canvas.drawCircle(stickX, stickY, r, ring)
            canvas.drawCircle(knobX, knobY, r * 0.42f, pressedFill)
        }
    }

    private fun drawButton(canvas: Canvas, b: Button) {
        canvas.drawCircle(b.cx, b.cy, b.r, if (b.pointer >= 0) pressedFill else fill)
        canvas.drawCircle(b.cx, b.cy, b.r, ring)
        text.textSize = min(b.r * 0.62f, 15f * density) * if (b.label.length > 3) 0.8f else 1f
        canvas.drawText(b.label, b.cx, b.cy - (text.descent() + text.ascent()) / 2f, text)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val i = e.actionIndex
        val id = e.getPointerId(i)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> down(id, e.getX(i), e.getY(i), e)
            MotionEvent.ACTION_MOVE -> for (p in 0 until e.pointerCount) move(e.getPointerId(p), e.getX(p), e.getY(p), e)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> up(id, e.getX(i), e.getY(i), cancelled = false)
            MotionEvent.ACTION_CANCEL -> releaseAll()
        }
        return true
    }

    private fun down(id: Int, x: Float, y: Float, e: MotionEvent) {
        val visible = if (controlsVisible) buttons else listOf(buttons.last())
        visible.firstOrNull { it.pointer < 0 && hypot(x - it.cx, y - it.cy) <= it.r * 1.15f }?.let { b ->
            b.pointer = id
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            when (b.code) {
                KEYBOARD -> sink.keyboard()
                SETTINGS -> sink.menu()
                else -> if (b.mouse) sink.button(b.code, true) else sink.key(b.code, true)
            }
            invalidate()
            return
        }
        if (controlsVisible && stickPointer < 0 && x < width * 0.38f && y > height * 0.35f) {
            stickPointer = id
            stickX = x; stickY = y; knobX = x; knobY = y
            invalidate()
            return
        }
        if (mousePointer < 0) {
            mousePointer = id
            downX = x; downY = y; downAt = SystemClock.uptimeMillis()
            dragging = false
            longPressed = false
            sink.pointer(x, y)
            postDelayed(longPress, LONG_PRESS_MS)
        } else if (e.pointerCount >= 2) {
            removeCallbacks(longPress)
            if (dragging) sink.button(Keys.BTN_LEFT, false)
            dragging = false
            longPressed = true
            pinchDistance = spread(e)
        }
    }

    private fun move(id: Int, x: Float, y: Float, e: MotionEvent) {
        if (id == stickPointer) {
            val r = STICK_RADIUS_DP * density
            val dx = x - stickX
            val dy = y - stickY
            val d = hypot(dx, dy)
            val k = if (d > r) r / d else 1f
            knobX = stickX + dx * k
            knobY = stickY + dy * k
            updateStick(dx, dy, d / r)
            invalidate()
            return
        }
        if (id != mousePointer) return
        if (e.pointerCount >= 2 && pinchDistance > 0f) {
            val now = spread(e)
            val step = PINCH_STEP_DP * density
            if (abs(now - pinchDistance) > step) {
                sink.scroll(if (now > pinchDistance) -1f else 1f)
                pinchDistance = now
            }
            return
        }
        if (longPressed) return
        if (!dragging && hypot(x - downX, y - downY) > DRAG_SLOP_DP * density) {
            removeCallbacks(longPress)
            dragging = true
            sink.pointer(downX, downY)
            sink.button(Keys.BTN_LEFT, true)
        }
        if (dragging) sink.pointer(x, y)
    }

    private fun up(id: Int, x: Float, y: Float, cancelled: Boolean) {
        buttons.firstOrNull { it.pointer == id }?.let { b ->
            b.pointer = -1
            if (b.code >= 0) { if (b.mouse) sink.button(b.code, false) else sink.key(b.code, false) }
            invalidate()
            return
        }
        if (id == stickPointer) {
            stickPointer = -1
            updateStick(0f, 0f, 0f)
            invalidate()
            return
        }
        if (id != mousePointer) return
        removeCallbacks(longPress)
        when {
            dragging -> sink.button(Keys.BTN_LEFT, false)
            !longPressed && !cancelled && SystemClock.uptimeMillis() - downAt < TAP_MS -> {
                sink.pointer(x, y)
                sink.button(Keys.BTN_LEFT, true)
                sink.button(Keys.BTN_LEFT, false)
            }
        }
        mousePointer = -1
        dragging = false
        pinchDistance = 0f
    }

    /** Eight sectors around the stick; a diagonal holds both of its keys. */
    private fun updateStick(dx: Float, dy: Float, amount: Float) {
        val want = mutableSetOf<Int>()
        if (amount > DEADZONE) {
            val angle = Math.toDegrees(atan2(-dy.toDouble(), dx.toDouble())).let { if (it < 0) it + 360 else it }
            val sector = ((angle + 22.5) / 45).toInt() % 8
            when (sector) {
                0 -> want += Keys.D
                1 -> { want += Keys.W; want += Keys.D }
                2 -> want += Keys.W
                3 -> { want += Keys.W; want += Keys.A }
                4 -> want += Keys.A
                5 -> { want += Keys.S; want += Keys.A }
                6 -> want += Keys.S
                7 -> { want += Keys.S; want += Keys.D }
            }
        }
        (stickHeld - want).forEach { sink.key(it, false) }
        (want - stickHeld).forEach { sink.key(it, true) }
        stickHeld.clear()
        stickHeld += want
    }

    private fun spread(e: MotionEvent): Float =
        if (e.pointerCount < 2) 0f else hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))

    fun releaseAll() {
        removeCallbacks(longPress)
        for (b in buttons) if (b.pointer >= 0) {
            b.pointer = -1
            if (b.code >= 0) { if (b.mouse) sink.button(b.code, false) else sink.key(b.code, false) }
        }
        if (stickPointer >= 0) { stickPointer = -1; updateStick(0f, 0f, 0f) }
        if (dragging) sink.button(Keys.BTN_LEFT, false)
        mousePointer = -1
        dragging = false
        invalidate()
    }

    companion object {
        private const val KEYBOARD = -1
        private const val SETTINGS = -2
        private const val STICK_RADIUS_DP = 64f
        private const val DEADZONE = 0.25f
        private const val DRAG_SLOP_DP = 10f
        private const val PINCH_STEP_DP = 24f
        private const val TAP_MS = 300L
        private const val LONG_PRESS_MS = 550L
    }
}
