package com.ddomobile.launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.ddomobile.launcher.display.Compositor
import com.ddomobile.launcher.input.Keys
import com.ddomobile.launcher.input.TouchControls
import com.ddomobile.launcher.session.GameSession

/** Highest panel refresh the game asks for: past this the rate only costs battery. */
private const val MAX_REFRESH_HZ = 120f

/** The game on screen: the compositor's surface, DDO's touch layer, and keyboard / mouse pass-through. */
class GameActivity : Activity(), SurfaceHolder.Callback, TouchControls.Sink {
    private lateinit var surface: SurfaceView
    private lateinit var controls: TouchControls
    private lateinit var typing: EditText
    private lateinit var status: TextView
    private lateinit var fps: TextView
    private var outWidth = 1
    private var outHeight = 1
    private var watching = true

    /**
     * The panel's fastest mode up to 120 Hz at the current resolution. Left alone, Android drops the panel to 60 Hz
     * whenever it judges the content slow, and gamescope then paces the game to that.
     */
    private fun fastestDisplayMode(): Int? {
        @Suppress("DEPRECATION")
        val display = windowManager.defaultDisplay ?: return null
        val current = display.mode
        return display.supportedModes
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
            .filter { it.refreshRate <= MAX_REFRESH_HZ + 1 }
            .maxByOrNull { it.refreshRate }
            ?.modeId
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            fastestDisplayMode()?.let { preferredDisplayModeId = it }
        }

        val root = FrameLayout(this)
        surface = SurfaceView(this).also { it.holder.addCallback(this) }
        controls = TouchControls(this, this)
        typing = EditText(this).apply {
            alpha = 0f
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
            addTextChangedListener(TypedText())
            setOnEditorActionListener { _, _, _ -> tapKey(Keys.ENTER); true }
            setOnKeyListener { _, code, event ->
                if (code == KeyEvent.KEYCODE_DEL && event.action == KeyEvent.ACTION_DOWN) { tapKey(Keys.BACKSPACE); true } else false
            }
        }
        status = TextView(this).apply {
            setTextColor(0xFFE8DCC0.toInt())
            textSize = 16f
            gravity = Gravity.CENTER
            setBackgroundColor(0xCC0B0D10.toInt())
            visibility = View.GONE
        }
        root.addView(surface, FrameLayout.LayoutParams(-1, -1))
        root.addView(typing, FrameLayout.LayoutParams(1, 1))
        root.addView(controls, FrameLayout.LayoutParams(-1, -1))
        fps = TextView(this).apply {
            setTextColor(0xFFF1E6D2.toInt())
            textSize = 12f
            setShadowLayer(3f, 0f, 0f, 0xFF000000.toInt())
            setPadding(24, 12, 24, 12)
            visibility = if (prefs().getBoolean("fps", true)) View.VISIBLE else View.GONE
        }
        root.addView(fps, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START))
        root.addView(status, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        hideSystemBars()
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        watchSession()
    }

    override fun onPause() {
        super.onPause()
        controls.releaseAll()
    }

    override fun onDestroy() {
        watching = false
        super.onDestroy()
    }

    private fun prefs() = getSharedPreferences("game", Context.MODE_PRIVATE)

    private fun watchSession() {
        if (fps.visibility == View.VISIBLE) fps.text = "%.0f fps".format(Compositor.nativeFramesPerSecond())
        when (val s = GameSession.state.value) {
            is GameSession.State.Running -> {
                if (outWidth != s.width || outHeight != s.height) {
                    outWidth = s.width; outHeight = s.height
                    // The screen's own scaler upscales the game for free; the compositor draws at render size.
                    surface.holder.setFixedSize(outWidth, outHeight)
                }
                status.visibility = View.GONE
            }
            is GameSession.State.Ended -> showEnded(s)
            GameSession.State.Idle -> finish()
        }
        if (watching) surface.postDelayed({ if (!isFinishing) watchSession() }, 500)
    }

    private fun showEnded(s: GameSession.State.Ended) {
        watching = false
        controls.releaseAll()
        controls.visibility = View.GONE
        status.visibility = View.VISIBLE
        status.text = if (s.exitCode == 0) "The game closed.\n\nTap to return to the launcher."
        else "The game stopped (exit ${s.exitCode}).\nLog: ${s.log.name}\n\nTap to return to the launcher."
        status.setOnClickListener { GameSession.acknowledgeEnd(); finish() }
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) = Compositor.nativeSetSurface(holder.surface)
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Compositor.nativeSetSurface(holder.surface)
    override fun surfaceDestroyed(holder: SurfaceHolder) = Compositor.nativeSetSurface(null)

    // TouchControls.Sink: view pixels to the session's output pixels.
    override fun pointer(x: Float, y: Float) =
        Compositor.nativePointerMotion(x * outWidth / controls.width.coerceAtLeast(1), y * outHeight / controls.height.coerceAtLeast(1))

    override fun relative(dx: Float, dy: Float) =
        Compositor.nativePointerRelative(dx * outWidth / controls.width.coerceAtLeast(1), dy * outHeight / controls.height.coerceAtLeast(1))

    override fun button(code: Int, pressed: Boolean) = Compositor.nativePointerButton(code, pressed)
    override fun key(code: Int, pressed: Boolean) = Compositor.nativeKey(code, pressed)
    override fun scroll(steps: Float) = Compositor.nativePointerAxis(steps, 0f)

    override fun keyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        typing.requestFocus()
        imm.showSoftInput(typing, InputMethodManager.SHOW_FORCED)
    }

    override fun menu() {
        controls.releaseAll()
        val items = arrayOf(
            if (controls.controlsVisible) "Hide touch controls" else "Show touch controls",
            if (fps.visibility == View.VISIBLE) "Hide FPS" else "Show FPS",
            "Keyboard", "Quit the game",
        )
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> controls.controlsVisible = !controls.controlsVisible
                    1 -> {
                        val show = fps.visibility != View.VISIBLE
                        fps.visibility = if (show) View.VISIBLE else View.GONE
                        prefs().edit().putBoolean("fps", show).apply()
                    }
                    2 -> keyboard()
                    3 -> confirmQuit()
                }
            }
            .show()
    }

    private fun confirmQuit() {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setMessage("Quit Dungeons & Dragons Online? Log out in the game first to keep your character safe.")
            .setPositiveButton("Quit") { _, _ -> GameSession.stop() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun tapKey(code: Int, shift: Boolean = false) {
        if (shift) Compositor.nativeKey(Keys.LEFTSHIFT, true)
        Compositor.nativeKey(code, true)
        Compositor.nativeKey(code, false)
        if (shift) Compositor.nativeKey(Keys.LEFTSHIFT, false)
    }

    /** The soft keyboard types into a hidden field; each new character becomes a key press for the game. */
    private inner class TypedText : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
            if (s == null || count <= 0) return
            for (c in s.subSequence(start, start + count)) Keys.forChar(c)?.let { (code, shift) -> tapKey(code, shift) }
        }
        override fun afterTextChanged(s: Editable?) {
            if (s != null && s.length > 64) s.clear()
        }
    }

    // Physical keyboards, mice and the back gesture.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.source and InputDevice.SOURCE_MOUSE == 0) {
            if (event.action == KeyEvent.ACTION_UP) menu()
            return true
        }
        // The soft keyboard's own events go to the hidden field, which types them; real keyboards go straight through.
        if (event.deviceId == KeyCharacterMap.VIRTUAL_KEYBOARD) return super.dispatchKeyEvent(event)
        val code = Keys.fromAndroid(event.keyCode)
        if (code == 0) return super.dispatchKeyEvent(event)
        when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) Compositor.nativeKey(code, true)
            KeyEvent.ACTION_UP -> Compositor.nativeKey(code, false)
        }
        return true
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.isFromSource(InputDevice.SOURCE_MOUSE)) {
            when (event.actionMasked) {
                MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_MOVE -> pointer(event.x, event.y)
                MotionEvent.ACTION_SCROLL -> scroll(event.getAxisValue(MotionEvent.AXIS_VSCROLL))
                MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.ACTION_BUTTON_RELEASE -> {
                    val pressed = event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS
                    when (event.actionButton) {
                        MotionEvent.BUTTON_PRIMARY -> button(Keys.BTN_LEFT, pressed)
                        MotionEvent.BUTTON_SECONDARY -> button(Keys.BTN_RIGHT, pressed)
                        MotionEvent.BUTTON_TERTIARY -> button(Keys.BTN_MIDDLE, pressed)
                    }
                }
            }
            return true
        }
        return super.dispatchGenericMotionEvent(event)
    }
}
