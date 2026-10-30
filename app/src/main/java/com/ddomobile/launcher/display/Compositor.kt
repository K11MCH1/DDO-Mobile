package com.ddomobile.launcher.display

import android.view.Surface

/**
 * The in-app Wayland compositor gamescope draws into. It serves one display socket, shows the
 * toplevel surface on an Android [Surface] through Vulkan, and forwards input as a Wayland seat.
 * Coordinates are in the session's output pixels; key and button codes are Linux evdev codes.
 */
object Compositor {
    init { System.loadLibrary("ddocompositor") }

    const val TOUCH_DOWN = 0
    const val TOUCH_MOVE = 1
    const val TOUCH_UP = 2
    const val TOUCH_CANCEL = 3

    /** Listens on [runtimeDir]/[socket]; [driverDir] holds an adrenotools driver for presenting, or null for the system one. */
    external fun nativeStart(runtimeDir: String, socket: String, width: Int, height: Int, driverDir: String?, driverName: String?): Boolean
    external fun nativeStop()
    external fun nativeSetSurface(surface: Surface?)
    external fun nativeResize(width: Int, height: Int)

    external fun nativePointerMotion(x: Float, y: Float)
    external fun nativePointerRelative(dx: Float, dy: Float)
    external fun nativePointerButton(button: Int, pressed: Boolean)
    external fun nativePointerAxis(vertical: Float, horizontal: Float)
    external fun nativeKey(code: Int, pressed: Boolean)
    external fun nativeTouch(id: Int, action: Int, x: Float, y: Float)
    /** Draws the client's cursor; hidden by default, since a touch points by itself. */
    external fun nativeSetCursorShown(shown: Boolean)
    /** The panel's fastest display mode: the compositor tells the client no faster refresh than this. */
    external fun nativeSetMaxRefresh(hz: Int)

    /** Frames shown in the last second, for the overlay. */
    external fun nativeFramesPerSecond(): Float
    /** True while a client has a mapped toplevel. */
    external fun nativeHasClientSurface(): Boolean
}
