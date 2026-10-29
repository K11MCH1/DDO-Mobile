package com.ddomobile.launcher.input

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

/**
 * DDO's touch layer, for the keymap DdoKeymap sets up. The game's cursor is hidden: a finger is the cursor.
 *
 * - A tap clicks where it lands. In the world DdoKeymap makes that select the target and interact with it (talk,
 *   open, pick up); on the game's windows it is an ordinary click. It never attacks.
 * - Dragging turns the camera: the right button is held (DDO's steer) and the finger's movement is sent as relative
 *   mouse motion.
 * - A long press is a right click; holding and then dragging drags with the left button, for moving items in the UI.
 * - A two-finger tap is a right click; two fingers spreading or pinching zoom the camera.
 * - The lower left walks: once a finger there moves, a floating stick appears under it (W/S forward and back, A/D
 *   strafe). A finger that lifts there without moving is a tap like anywhere else.
 * - Buttons press keys: Attack (bound to K), Jump, Target, Run, menus.
 * - Cursor, at the left edge, shows the game's cursor and turns the free screen into a trackpad: a drag moves the
 *   cursor by the finger's movement and taps click where the cursor is. It is filled while on.
 */
@SuppressLint("ViewConstructor")
class TouchControls(context: Context, private val sink: Sink) : View(context) {

    interface Sink {
        /** Absolute pointer position in the view's pixels. */
        fun pointer(x: Float, y: Float)
        /** Relative pointer motion in the view's pixels, for steering while the game holds the pointer. */
        fun relative(dx: Float, dy: Float)
        fun button(code: Int, pressed: Boolean)
        fun key(code: Int, pressed: Boolean)
        /** Positive zooms in (wheel up). */
        fun scroll(steps: Float)
        /** Shows or hides the game's cursor. */
        fun cursorShown(shown: Boolean)
        fun keyboard()
        fun menu()
    }

    private class Button(
        val label: String, val x: Float, val y: Float, val sizeDp: Float, val code: Int, val toggle: Boolean = false,
    ) {
        var cx = 0f
        var cy = 0f
        var r = 0f
        var pointer = -1
        var on = false
    }

    private val density = resources.displayMetrics.density
    private val buttons = listOf(
        Button("Attack", 0.90f, 0.72f, 84f, Keys.K),
        Button("Jump", 0.78f, 0.88f, 56f, Keys.SPACE),
        Button("Target", 0.96f, 0.47f, 44f, Keys.TAB),
        Button("Cursor", 0.00f, 0.50f, 48f, CURSOR, toggle = true),
        Button("Run", 0.84f, 0.50f, 40f, Keys.R),
        Button("Menu", 0.40f, 0.05f, 30f, Keys.ESC),
        Button("Bag", 0.47f, 0.05f, 30f, Keys.I),
        Button("Map", 0.54f, 0.05f, 30f, Keys.M),
        Button("⌨", 0.61f, 0.05f, 30f, KEYBOARD),
        Button("☰", 0.97f, 0.05f, 30f, SETTINGS),
    )

    private val cursor = buttons.first { it.toggle }
    /** Trackpad mode: the game's cursor is shown and a drag moves it. */
    private val trackpad get() = cursor.on
    /** Where the game's cursor is, in the view's pixels; negative until the first click. */
    private var cursorX = -1f
    private var cursorY = -1f

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

    // Movement stick: a finger in the lower left becomes the stick once it moves.
    private var stickPointer = -1
    private var stickActive = false
    private var stickX = 0f
    private var stickY = 0f
    private var knobX = 0f
    private var knobY = 0f
    private val stickHeld = mutableSetOf<Int>()

    // The free screen: one finger is the mouse, a second one makes a two-finger tap or a pinch.
    private enum class Gesture { PENDING, CAMERA, TRACK, LONG, DRAG, PINCH }
    private var mousePointer = -1
    private var gesture = Gesture.PENDING
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var pinchPointer = -1
    private var pinchDistance = 0f
    // Both fingers where they were when the second one landed; a two-finger tap is when neither moves from there.
    private var pinchStart = 0L
    private var pinchMoved = false
    private var pinchAX = 0f
    private var pinchAY = 0f
    private var pinchBX = 0f
    private var pinchBY = 0f
    private val longPress = Runnable {
        if (mousePointer >= 0 && gesture == Gesture.PENDING) {
            gesture = Gesture.LONG
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
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
        if (stickActive) {
            val r = STICK_RADIUS_DP * density
            canvas.drawCircle(stickX, stickY, r, fill)
            canvas.drawCircle(stickX, stickY, r, ring)
            canvas.drawCircle(knobX, knobY, r * 0.42f, pressedFill)
        }
    }

    private fun drawButton(canvas: Canvas, b: Button) {
        when {
            b.pointer >= 0 -> canvas.drawCircle(b.cx, b.cy, b.r, pressedFill)
            !b.toggle -> canvas.drawCircle(b.cx, b.cy, b.r, fill)
            b.on -> canvas.drawCircle(b.cx, b.cy, b.r, pressedFill)
        }
        canvas.drawCircle(b.cx, b.cy, b.r, ring)
        text.textSize = min(b.r * 0.62f, 15f * density) * if (b.label.length > 3) 0.8f else 1f
        canvas.drawText(b.label, b.cx, b.cy - (text.descent() + text.ascent()) / 2f, text)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val i = e.actionIndex
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> down(e.getPointerId(i), e.getX(i), e.getY(i), e)
            MotionEvent.ACTION_MOVE -> for (p in 0 until e.pointerCount) move(e.getPointerId(p), e.getX(p), e.getY(p), e)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> up(e.getPointerId(i), e.getX(i), e.getY(i), e)
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
                CURSOR -> setTrackpad(!b.on)
                else -> sink.key(b.code, true)
            }
            invalidate()
            return
        }
        if (controlsVisible && stickPointer < 0 && x < width * 0.38f && y > height * 0.35f) {
            stickPointer = id
            stickActive = false
            stickX = x; stickY = y; knobX = x; knobY = y
            return
        }
        if (mousePointer < 0) {
            mousePointer = id
            gesture = Gesture.PENDING
            downX = x; downY = y; lastX = x; lastY = y
            postDelayed(longPress, LONG_PRESS_MS)
            return
        }
        if (pinchPointer < 0 && gesture != Gesture.DRAG) {
            // A second finger on the free screen: a two-finger tap if neither moves, else a pinch.
            pinchMoved = gesture != Gesture.PENDING
            endMouseGesture()
            pinchPointer = id
            gesture = Gesture.PINCH
            pinchDistance = spread(e, mousePointer, pinchPointer)
            pinchStart = e.eventTime
            val a = e.findPointerIndex(mousePointer)
            pinchAX = if (a >= 0) e.getX(a) else downX
            pinchAY = if (a >= 0) e.getY(a) else downY
            pinchBX = x; pinchBY = y
        }
    }

    private fun move(id: Int, x: Float, y: Float, e: MotionEvent) {
        if (id == stickPointer) {
            val r = STICK_RADIUS_DP * density
            val dx = x - stickX
            val dy = y - stickY
            val d = hypot(dx, dy)
            if (!stickActive) {
                if (d <= DRAG_SLOP_DP * density) return
                stickActive = true
            }
            val k = if (d > r) r / d else 1f
            knobX = stickX + dx * k
            knobY = stickY + dy * k
            updateStick(dx, dy, d / r)
            invalidate()
            return
        }
        if (id != mousePointer && id != pinchPointer) return
        if (gesture == Gesture.PINCH) {
            if (!pinchMoved) {
                val ox = if (id == mousePointer) pinchAX else pinchBX
                val oy = if (id == mousePointer) pinchAY else pinchBY
                if (hypot(x - ox, y - oy) > DRAG_SLOP_DP * density) pinchMoved = true
            }
            if (id != mousePointer) return
            val now = spread(e, mousePointer, pinchPointer)
            if (now > 0f && abs(now - pinchDistance) > PINCH_STEP_DP * density) {
                sink.scroll(if (now > pinchDistance) 1f else -1f)
                pinchDistance = now
            }
            return
        }
        if (id != mousePointer) return
        val moved = hypot(x - downX, y - downY) > DRAG_SLOP_DP * density
        when (gesture) {
            Gesture.PENDING -> if (moved) {
                removeCallbacks(longPress)
                if (trackpad) {
                    gesture = Gesture.TRACK
                } else {
                    gesture = Gesture.CAMERA
                    sink.button(Keys.BTN_RIGHT, true)
                }
                lastX = x; lastY = y
            }
            Gesture.CAMERA -> {
                sink.relative((x - lastX) * CAMERA_SPEED, (y - lastY) * CAMERA_SPEED)
                lastX = x; lastY = y
            }
            Gesture.TRACK -> {
                nudge(x - lastX, y - lastY)
                lastX = x; lastY = y
            }
            Gesture.LONG -> if (moved) {
                gesture = Gesture.DRAG
                if (!trackpad) moveTo(downX, downY)
                sink.button(Keys.BTN_LEFT, true)
                if (trackpad) nudge(x - downX, y - downY) else moveTo(x, y)
                lastX = x; lastY = y
            }
            Gesture.DRAG -> {
                if (trackpad) nudge(x - lastX, y - lastY) else moveTo(x, y)
                lastX = x; lastY = y
            }
            Gesture.PINCH -> {}
        }
    }

    private fun up(id: Int, x: Float, y: Float, e: MotionEvent) {
        buttons.firstOrNull { it.pointer == id }?.let { b ->
            b.pointer = -1
            if (b.code >= 0) sink.key(b.code, false)
            invalidate()
            return
        }
        if (id == stickPointer) {
            stickPointer = -1
            if (stickActive) {
                stickActive = false
                updateStick(0f, 0f, 0f)
                invalidate()
            } else {
                click(Keys.BTN_LEFT, stickX, stickY)
            }
            return
        }
        if (gesture == Gesture.PINCH && (id == mousePointer || id == pinchPointer)) {
            // The first finger up decides a two-finger tap; the second only ends the gesture.
            if (!pinchMoved && e.eventTime - pinchStart <= TWO_FINGER_TAP_MS) click(Keys.BTN_RIGHT, pinchAX, pinchAY)
            pinchMoved = true
        }
        if (id == pinchPointer) {
            pinchPointer = -1
            return
        }
        if (id != mousePointer) return
        removeCallbacks(longPress)
        when (gesture) {
            Gesture.PENDING -> click(Keys.BTN_LEFT, x, y)
            Gesture.LONG -> click(Keys.BTN_RIGHT, x, y)
            else -> endMouseGesture()
        }
        mousePointer = -1
        pinchPointer = -1
        gesture = Gesture.PENDING
    }

    /** A click where the finger is, or where the cursor is in trackpad mode. */
    private fun click(button: Int, x: Float, y: Float) {
        if (!trackpad) moveTo(x, y)
        sink.button(button, true)
        sink.button(button, false)
    }

    private fun moveTo(x: Float, y: Float) {
        cursorX = x
        cursorY = y
        sink.pointer(x, y)
    }

    /** Moves the cursor by a finger's movement, as on a trackpad. */
    private fun nudge(dx: Float, dy: Float) {
        moveTo((cursorX + dx * TRACKPAD_SPEED).coerceIn(0f, width - 1f), (cursorY + dy * TRACKPAD_SPEED).coerceIn(0f, height - 1f))
    }

    private fun setTrackpad(on: Boolean) {
        cursor.on = on
        if (on) {
            if (cursorX < 0f) moveTo(width / 2f, height / 2f) else sink.pointer(cursorX, cursorY)
        }
        sink.cursorShown(on)
    }

    private fun endMouseGesture() {
        removeCallbacks(longPress)
        when (gesture) {
            Gesture.CAMERA -> sink.button(Keys.BTN_RIGHT, false)
            Gesture.DRAG -> sink.button(Keys.BTN_LEFT, false)
            else -> {}
        }
    }

    /** Eight sectors around the stick; a diagonal holds both of its keys. */
    private fun updateStick(dx: Float, dy: Float, amount: Float) {
        val want = mutableSetOf<Int>()
        if (amount > DEADZONE) {
            val angle = Math.toDegrees(atan2(-dy.toDouble(), dx.toDouble())).let { if (it < 0) it + 360 else it }
            when (((angle + 22.5) / 45).toInt() % 8) {
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

    private fun spread(e: MotionEvent, a: Int, b: Int): Float {
        val ia = e.findPointerIndex(a)
        val ib = e.findPointerIndex(b)
        return if (ia < 0 || ib < 0) 0f else hypot(e.getX(ia) - e.getX(ib), e.getY(ia) - e.getY(ib))
    }

    fun releaseAll() {
        for (b in buttons) if (b.pointer >= 0) {
            b.pointer = -1
            if (b.code >= 0) sink.key(b.code, false)
        }
        if (stickPointer >= 0) {
            stickPointer = -1
            if (stickActive) { stickActive = false; updateStick(0f, 0f, 0f) }
        }
        if (mousePointer >= 0) endMouseGesture()
        mousePointer = -1
        pinchPointer = -1
        gesture = Gesture.PENDING
        invalidate()
    }

    companion object {
        private const val KEYBOARD = -1
        private const val SETTINGS = -2
        private const val CURSOR = -3
        private const val STICK_RADIUS_DP = 64f
        private const val DEADZONE = 0.25f
        private const val DRAG_SLOP_DP = 10f
        private const val PINCH_STEP_DP = 24f
        private const val LONG_PRESS_MS = 450L
        private const val TWO_FINGER_TAP_MS = 300L
        private const val CAMERA_SPEED = 1.0f
        private const val TRACKPAD_SPEED = 1.0f
    }
}
