package com.ddomobile.launcher.input

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * DDO's touch layer, for the keymap DdoKeymap sets up. The game's cursor is hidden: a finger is the cursor.
 *
 * - A tap clicks where it lands. In the world DdoKeymap makes that select the target and interact with it (talk,
 *   open, pick up); on the game's windows it is an ordinary click. It never attacks.
 * - Dragging turns the camera: the right button is held (DDO's steer) and the finger's movement is sent as relative
 *   mouse motion.
 * - A long press is a right click; holding and then dragging drags with the left button, for moving items in the UI.
 * - A two-finger tap is a right click; two fingers spreading or pinching zoom the camera, and two fingers sliding
 *   together turn the mouse wheel over what is under them, scrolling a list or the chat as the fingers go.
 * - The lower left walks: once a finger there moves, a floating stick appears under it (W/S forward and back, A/D
 *   strafe). A finger that lifts there without moving is a tap like anywhere else.
 * - Buttons press keys: Attack (bound to K), Jump, Target, Run, menus.
 * - Sliding a thumb on Attack turns the camera as a drag on the free screen does, while the attack stays held:
 *   aiming as it swings.
 * - Sliding a thumb on Eye, above Run, swings the camera around the character without turning the character: DDO's
 *   drag with the left button on the world.
 * - Cursor, at the left edge, shows the game's cursor and turns the free screen into a trackpad: a drag moves the
 *   cursor by the finger's movement and taps click where the cursor is. It is filled while on.
 * - The arrows beside ☰ rearrange the buttons: they turn green and follow a finger, and nothing reaches the game.
 *   Tapping the arrows again keeps the new places, for every game after.
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

    /** [x] and [y] place the button as fractions of the view; a saved layout replaces them. */
    private class Button(
        val label: String, var x: Float, var y: Float, val sizeDp: Float, val code: Int, val toggle: Boolean = false,
        val steers: Boolean = false,
    ) {
        var cx = 0f
        var cy = 0f
        var r = 0f
        var pointer = -1
        var on = false
        /** Where a finger moving the button holds it, from its centre. */
        var grabX = 0f
        var grabY = 0f
    }

    private val density = resources.displayMetrics.density
    private val buttons = listOf(
        Button("Attack", 0.90f, 0.72f, 84f, Keys.K, steers = true),
        Button("Jump", 0.78f, 0.88f, 56f, Keys.SPACE),
        Button("Target", 0.96f, 0.47f, 44f, Keys.TAB),
        Button("Cursor", 0.00f, 0.50f, 48f, CURSOR, toggle = true),
        Button("Run", 0.84f, 0.50f, 40f, Keys.R),
        Button("Eye", 0.84f, 0.37f, 40f, ORBIT),
        Button("Menu", 0.40f, 0.05f, 30f, Keys.ESC),
        Button("Bag", 0.47f, 0.05f, 30f, Keys.I),
        Button("Map", 0.54f, 0.05f, 30f, Keys.M),
        Button("⌨", 0.61f, 0.05f, 30f, KEYBOARD),
        Button("Layout", 0.90f, 0.05f, 30f, LAYOUT, toggle = true),
        // Last: the one button left when the controls are hidden.
        Button("☰", 0.97f, 0.05f, 30f, SETTINGS),
    )

    private val cursor = buttons.first { it.code == CURSOR }
    private val layoutButton = buttons.first { it.code == LAYOUT }
    private val layout = context.getSharedPreferences("touch-layout", Context.MODE_PRIVATE)
    /** Rearranging the buttons: they are green, follow a finger, and send nothing to the game. */
    private var editing = false

    init {
        for (b in buttons) {
            b.x = layout.getFloat("${b.label}.x", b.x)
            b.y = layout.getFloat("${b.label}.y", b.y)
        }
    }
    /** Trackpad mode: the game's cursor is shown and a drag moves it. */
    private val trackpad get() = cursor.on
    /** Where the game's cursor is, in the view's pixels; negative until the first click. */
    private var cursorX = -1f
    private var cursorY = -1f

    var controlsVisible = true
        set(value) {
            if (editing) setEditing(false)
            field = value
            releaseAll()
            invalidate()
        }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(70, 10, 12, 16) }
    private val pressedFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(140, 200, 160, 60) }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.5f * resources.displayMetrics.density; color = Color.argb(150, 230, 200, 120)
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 245, 235, 210); textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD
    }
    private val outline = Paint(text).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f * resources.displayMetrics.density; strokeCap = Paint.Cap.ROUND
    }
    private val editFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(110, 40, 170, 70) }
    private val editHeldFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(170, 60, 200, 90) }
    private val editRing = Paint(ring).apply { color = Color.argb(230, 90, 230, 120) }
    private val icon = Path()

    // Movement stick: a finger in the lower left becomes the stick once it moves.
    private var stickPointer = -1
    private var stickActive = false
    private var stickX = 0f
    private var stickY = 0f
    private var knobX = 0f
    private var knobY = 0f
    private val stickHeld = mutableSetOf<Int>()

    // The finger on Attack, steering the camera once it slides.
    private var aimPointer = -1
    private var aimActive = false
    private var aimDownX = 0f
    private var aimDownY = 0f
    private var aimLastX = 0f
    private var aimLastY = 0f
    /** Fingers holding the right button to steer: a drag and a thumb on Attack can do it at once. */
    private var steerHolds = 0

    // The finger on Eye, orbiting the camera once it slides.
    private var orbitPointer = -1
    private var orbitActive = false
    /** The left button is down in the game for the orbit. */
    private var orbitHeld = false
    private var orbitDownX = 0f
    private var orbitDownY = 0f
    private var orbitLastX = 0f
    private var orbitLastY = 0f
    /** Where the trackpad's cursor was before the orbit took it. */
    private var orbitFromX = -1f
    private var orbitFromY = -1f
    private val orbitMove = Runnable { moveTo(width * ORBIT_X, height * ORBIT_Y) }
    private val orbitPress = Runnable { orbitHeld = true; sink.button(Keys.BTN_LEFT, true) }

    // The free screen: one finger is the mouse, a second one makes a two-finger tap or a pinch.
    private enum class Gesture { PENDING, CAMERA, TRACK, LONG, DRAG, PINCH }
    /** What two fingers on the free screen turn out to be once they move: spreading apart or sliding together. */
    private enum class TwoFinger { UNDECIDED, ZOOM, SCROLL }
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
    private var twoFinger = TwoFinger.UNDECIDED
    /** The midpoint of two scrolling fingers where the wheel last turned, and when the wheel may first turn. */
    private var scrollY = 0f
    private var scrollFrom = 0L
    private val longPress = Runnable {
        if (mousePointer >= 0 && gesture == Gesture.PENDING) {
            gesture = Gesture.LONG
            // The cursor goes under the finger now, so the game knows what is there before a drag presses on it.
            if (!trackpad) moveTo(downX, downY)
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
    }

    // Clicks are queued: the cursor moves, then after a beat the button goes down and up.
    private val clicks = Handler(Looper.getMainLooper())
    /** When the last queued click is done; the next starts no earlier. */
    private var clicksFreeAt = 0L
    /** A button a queued click has pressed and not yet released. */
    private var clickHeld = -1

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
        val moving = editing && b !== layoutButton
        when {
            moving -> canvas.drawCircle(b.cx, b.cy, b.r, if (b.pointer >= 0) editHeldFill else editFill)
            b.pointer >= 0 -> canvas.drawCircle(b.cx, b.cy, b.r, pressedFill)
            !b.toggle -> canvas.drawCircle(b.cx, b.cy, b.r, fill)
            b.on -> canvas.drawCircle(b.cx, b.cy, b.r, pressedFill)
        }
        canvas.drawCircle(b.cx, b.cy, b.r, if (moving) editRing else ring)
        when (b.code) {
            ORBIT -> drawEye(canvas, b)
            LAYOUT -> drawArrows(canvas, b)
            else -> {
                text.textSize = min(b.r * 0.62f, 15f * density) * if (b.label.length > 3) 0.8f else 1f
                canvas.drawText(b.label, b.cx, b.cy - (text.descent() + text.ascent()) / 2f, text)
            }
        }
    }

    /** An eye: an almond outline around a pupil. */
    private fun drawEye(canvas: Canvas, b: Button) {
        val w = b.r * 0.6f
        val h = b.r * 0.62f
        icon.reset()
        icon.moveTo(b.cx - w, b.cy)
        icon.quadTo(b.cx, b.cy - h, b.cx + w, b.cy)
        icon.quadTo(b.cx, b.cy + h, b.cx - w, b.cy)
        icon.close()
        canvas.drawPath(icon, outline)
        canvas.drawCircle(b.cx, b.cy, b.r * 0.17f, text)
    }

    /** Arrows out to all four sides: move. */
    private fun drawArrows(canvas: Canvas, b: Button) {
        val arm = b.r * 0.55f
        val head = b.r * 0.2f
        icon.reset()
        for ((dx, dy) in listOf(1f to 0f, -1f to 0f, 0f to 1f, 0f to -1f)) {
            val tipX = b.cx + dx * arm
            val tipY = b.cy + dy * arm
            icon.moveTo(b.cx, b.cy)
            icon.lineTo(tipX, tipY)
            icon.moveTo(tipX - dx * head - dy * head, tipY - dy * head + dx * head)
            icon.lineTo(tipX, tipY)
            icon.lineTo(tipX - dx * head + dy * head, tipY - dy * head - dx * head)
        }
        canvas.drawPath(icon, outline)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val i = e.actionIndex
        if (editing) {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> editDown(e.getPointerId(i), e.getX(i), e.getY(i))
                MotionEvent.ACTION_MOVE -> for (p in 0 until e.pointerCount) editMove(e.getPointerId(p), e.getX(p), e.getY(p))
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> editUp(e.getPointerId(i))
                MotionEvent.ACTION_CANCEL -> { for (b in buttons) b.pointer = -1; invalidate() }
            }
            return true
        }
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
                LAYOUT -> setEditing(true)
                ORBIT -> {
                    orbitPointer = id
                    orbitActive = false
                    orbitDownX = x; orbitDownY = y; orbitLastX = x; orbitLastY = y
                }
                else -> sink.key(b.code, true)
            }
            if (b.steers) {
                aimPointer = id
                aimActive = false
                aimDownX = x; aimDownY = y; aimLastX = x; aimLastY = y
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
            twoFinger = TwoFinger.UNDECIDED
        }
    }

    private fun move(id: Int, x: Float, y: Float, e: MotionEvent) {
        if (id == aimPointer) {
            if (!aimActive) {
                if (hypot(x - aimDownX, y - aimDownY) <= DRAG_SLOP_DP * density) return
                aimActive = true
                steer(true)
            } else {
                sink.relative((x - aimLastX) * CAMERA_SPEED, (y - aimLastY) * CAMERA_SPEED)
            }
            aimLastX = x; aimLastY = y
            return
        }
        if (id == orbitPointer) {
            if (!orbitActive) {
                if (hypot(x - orbitDownX, y - orbitDownY) <= DRAG_SLOP_DP * density) return
                orbitActive = true
                startOrbit()
            } else if (orbitHeld) {
                sink.relative((x - orbitLastX) * CAMERA_SPEED, (y - orbitLastY) * CAMERA_SPEED)
            }
            orbitLastX = x; orbitLastY = y
            return
        }
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
            val a = e.findPointerIndex(mousePointer)
            val b = e.findPointerIndex(pinchPointer)
            if (a < 0 || b < 0) return
            val now = hypot(e.getX(a) - e.getX(b), e.getY(a) - e.getY(b))
            val midY = (e.getY(a) + e.getY(b)) / 2f
            if (twoFinger == TwoFinger.UNDECIDED) {
                val startX = (pinchAX + pinchBX) / 2f
                val startY = (pinchAY + pinchBY) / 2f
                val apart = abs(now - pinchDistance)
                val slide = hypot((e.getX(a) + e.getX(b)) / 2f - startX, midY - startY)
                if (max(apart, slide) <= DRAG_SLOP_DP * density) return
                if (apart > slide) {
                    twoFinger = TwoFinger.ZOOM
                } else {
                    twoFinger = TwoFinger.SCROLL
                    // The wheel turns what is under the fingers, once the game has seen the cursor there.
                    if (!trackpad) moveTo(startX, startY)
                    scrollY = startY
                    scrollFrom = SystemClock.uptimeMillis() + CLICK_HOVER_MS
                }
            }
            if (twoFinger == TwoFinger.ZOOM && abs(now - pinchDistance) > PINCH_STEP_DP * density) {
                sink.scroll(if (now > pinchDistance) 1f else -1f)
                pinchDistance = now
            } else if (twoFinger == TwoFinger.SCROLL && SystemClock.uptimeMillis() >= scrollFrom) {
                // Content follows the fingers: sliding down turns the wheel up, toward the top of a list.
                val step = SCROLL_STEP_DP * density
                val steps = ((midY - scrollY) / step).toInt()
                if (steps != 0) {
                    sink.scroll(steps.toFloat())
                    scrollY += steps * step
                }
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
                    steer(true)
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
            if (id == aimPointer) endAim()
            if (id == orbitPointer) endOrbit()
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

    /**
     * A click where the finger is, or where the cursor is in trackpad mode. DDO finds the button or slot under the
     * cursor when the cursor moves, a frame or so later: a press that came with the move landed on whatever was
     * under the cursor before, so it took a second tap. The press now waits for the game to see the move, and the
     * button stays down long enough for a frame to catch it. Clicks run one after another, in order.
     */
    private fun click(button: Int, x: Float, y: Float) {
        val start = maxOf(SystemClock.uptimeMillis(), clicksFreeAt)
        val moves = !trackpad && (x != cursorX || y != cursorY)
        if (moves) {
            cursorX = x
            cursorY = y
            clicks.postAtTime({ sink.pointer(x, y) }, start)
        }
        val pressAt = start + if (moves) CLICK_HOVER_MS else 0L
        clicks.postAtTime({ clickHeld = button; sink.button(button, true) }, pressAt)
        clicks.postAtTime({ clickHeld = -1; sink.button(button, false) }, pressAt + CLICK_HOLD_MS)
        clicksFreeAt = pressAt + CLICK_HOLD_MS
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

    /** Starts rearranging the buttons, or ends it and keeps where they are. */
    private fun setEditing(on: Boolean) {
        releaseAll()
        editing = on
        layoutButton.on = on
        if (!on) saveLayout()
        invalidate()
    }

    private fun editDown(id: Int, x: Float, y: Float) {
        val b = buttons.firstOrNull { it.pointer < 0 && hypot(x - it.cx, y - it.cy) <= it.r * 1.15f } ?: return
        if (b === layoutButton) {
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            setEditing(false)
            return
        }
        b.pointer = id
        b.grabX = x - b.cx
        b.grabY = y - b.cy
        invalidate()
    }

    private fun editMove(id: Int, x: Float, y: Float) {
        val b = buttons.firstOrNull { it.pointer == id } ?: return
        b.cx = (x - b.grabX).coerceIn(b.r, width - b.r)
        b.cy = (y - b.grabY).coerceIn(b.r, height - b.r)
        invalidate()
    }

    private fun editUp(id: Int) {
        buttons.firstOrNull { it.pointer == id }?.pointer = -1
        invalidate()
    }

    /** Each button's place as fractions of the view, so the layout fits the screen it comes back on. */
    private fun saveLayout() {
        if (width == 0 || height == 0) return
        val edit = layout.edit()
        for (b in buttons) {
            b.x = b.cx / width
            b.y = b.cy / height
            edit.putFloat("${b.label}.x", b.x).putFloat("${b.label}.y", b.y)
        }
        edit.apply()
    }

    private fun endMouseGesture() {
        removeCallbacks(longPress)
        when (gesture) {
            Gesture.CAMERA -> steer(false)
            Gesture.DRAG -> sink.button(Keys.BTN_LEFT, false)
            else -> {}
        }
    }

    private fun steer(on: Boolean) {
        if (on) {
            if (steerHolds++ == 0) sink.button(Keys.BTN_RIGHT, true)
        } else if (steerHolds > 0 && --steerHolds == 0) {
            sink.button(Keys.BTN_RIGHT, false)
        }
    }

    private fun endAim() {
        if (aimActive) {
            aimActive = false
            steer(false)
        }
        aimPointer = -1
    }

    /**
     * DDO swings the camera around the character while the left button is held on the world. The cursor goes above
     * the character, where the world nearly always is, and the press waits for the game to see it there.
     */
    private fun startOrbit() {
        val start = maxOf(SystemClock.uptimeMillis(), clicksFreeAt)
        orbitFromX = cursorX
        orbitFromY = cursorY
        clicks.postAtTime(orbitMove, start)
        clicks.postAtTime(orbitPress, start + CLICK_HOVER_MS)
        clicksFreeAt = start + CLICK_HOVER_MS
    }

    private fun endOrbit() {
        if (orbitActive) {
            orbitActive = false
            // A press still to come is dropped: pressed and released at once, it would be a click on the world.
            clicks.removeCallbacks(orbitMove)
            clicks.removeCallbacks(orbitPress)
            if (orbitHeld) {
                orbitHeld = false
                sink.button(Keys.BTN_LEFT, false)
            }
            if (trackpad && orbitFromX >= 0f) moveTo(orbitFromX, orbitFromY)
        }
        orbitPointer = -1
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
        clicks.removeCallbacksAndMessages(null)
        clicksFreeAt = 0L
        if (clickHeld >= 0) {
            sink.button(clickHeld, false)
            clickHeld = -1
        }
        for (b in buttons) if (b.pointer >= 0) {
            b.pointer = -1
            // A finger moving a button pressed nothing.
            if (b.code >= 0 && !editing) sink.key(b.code, false)
        }
        endAim()
        endOrbit()
        if (stickPointer >= 0) {
            stickPointer = -1
            if (stickActive) { stickActive = false; updateStick(0f, 0f, 0f) }
        }
        if (mousePointer >= 0) endMouseGesture()
        if (steerHolds > 0) {
            steerHolds = 0
            sink.button(Keys.BTN_RIGHT, false)
        }
        mousePointer = -1
        pinchPointer = -1
        gesture = Gesture.PENDING
        invalidate()
    }

    companion object {
        private const val KEYBOARD = -1
        private const val SETTINGS = -2
        private const val CURSOR = -3
        private const val ORBIT = -4
        private const val LAYOUT = -5
        /** Where an orbit holds the button: above the character, below the target's name. */
        private const val ORBIT_X = 0.5f
        private const val ORBIT_Y = 0.32f
        private const val STICK_RADIUS_DP = 64f
        private const val DEADZONE = 0.25f
        private const val DRAG_SLOP_DP = 10f
        private const val PINCH_STEP_DP = 24f
        /** How far two sliding fingers go for one turn of the wheel. */
        private const val SCROLL_STEP_DP = 20f
        private const val LONG_PRESS_MS = 450L
        private const val TWO_FINGER_TAP_MS = 300L
        private const val CAMERA_SPEED = 1.0f
        private const val TRACKPAD_SPEED = 1.0f
        /** From a click's cursor move to its press: two frames at 60 fps, and some. */
        private const val CLICK_HOVER_MS = 50L
        private const val CLICK_HOLD_MS = 50L
    }
}
