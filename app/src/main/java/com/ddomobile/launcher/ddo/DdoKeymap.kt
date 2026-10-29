package com.ddomobile.launcher.ddo

import android.content.Context
import java.io.File

/**
 * DDO's keymap, adjusted for touch. With the defaults a left click in the world is AttackInteract, so every tap
 * swung a weapon, and A/D turn rather than strafe. Here a left click only selects (and works the UI), Attack has a
 * key of its own for the on-screen button, and A/D strafe; the camera is steered with the right button as before.
 */
object DdoKeymap {
    /** The key the Attack button presses (K is unbound in DDO's defaults). */
    const val ATTACK_KEY = "DIK_K"
    const val FILE = "ddo.keymap"

    /** Seeds the settings folder with the adjusted defaults, or adjusts the keymap the client already wrote. */
    fun apply(context: Context, dir: File) {
        val file = File(dir, FILE)
        val text = if (file.isFile) file.readText() else
            context.assets.open("ddo/$FILE").bufferedReader().use { it.readText() }
        val patched = patch(text)
        if (!file.isFile || patched != text) file.writeText(patched)
    }

    internal fun patch(text: String): String {
        var t = text
        t = t.replace("MovementTurnOrStrafeLeft [ Old [ 0 DIK_A ] ]", "MovementStrafeLeft [ User [ 0 DIK_A ] ]")
        t = t.replace("MovementTurnOrStrafeRight [ Old [ 0 DIK_D ] ]", "MovementStrafeRight [ User [ 0 DIK_D ] ]")
        t = t.replace(Regex("""\r?\n[ \t]*AttackInteract \[ Old \[ 1 DIMOFS_BUTTON0 \] 0x00000000 MouseDblClick \]"""), "")
        t = t.replace("AttackInteract [ Old [ 1 DIMOFS_BUTTON0 ] ]", "Attack [ User [ 0 $ATTACK_KEY ] ]")
        return t
    }
}
