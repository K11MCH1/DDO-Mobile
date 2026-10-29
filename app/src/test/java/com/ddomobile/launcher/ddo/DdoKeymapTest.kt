package com.ddomobile.launcher.ddo

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DdoKeymapTest {
    private val defaults = File("src/main/assets/ddo/ddo.keymap").readText()

    @Test
    fun leftClickNoLongerAttacks() {
        val patched = DdoKeymap.patch(defaults)
        assertFalse(patched.contains("AttackInteract [ Old [ 1 DIMOFS_BUTTON0 ]"))
        assertTrue(patched.contains("Attack [ User [ 0 DIK_K ] ]"))
        // Right-button steering and its tap-to-interact stay as DDO has them.
        assertTrue(patched.contains("ClassicInteractSteer [ Old [ 1 DIMOFS_BUTTON1 ] ]"))
        assertTrue(patched.contains("ClassicInteractTap [ Old [ 1 DIMOFS_BUTTON1 ] 0x00000000 Tap ]"))
    }

    @Test
    fun aAndDStrafe() {
        val patched = DdoKeymap.patch(defaults)
        assertTrue(patched.contains("MovementStrafeLeft [ User [ 0 DIK_A ] ]"))
        assertTrue(patched.contains("MovementStrafeRight [ User [ 0 DIK_D ] ]"))
        assertFalse(patched.contains("MovementTurnOrStrafeLeft [ Old [ 0 DIK_A ] ]"))
        // The arrow keys still turn.
        assertTrue(patched.contains("MovementTurnOrStrafeLeft [ Old [ 0 DIK_LEFTARROW ] ]"))
    }

    @Test
    fun aTapInteracts() {
        val patched = DdoKeymap.patch(defaults)
        assertTrue(patched.contains("Interact [ User [ 1 DIMOFS_BUTTON0 ] 0x00000000 Tap ]"))
        assertFalse(patched.contains("Invalid [ Old [ 1 DIMOFS_BUTTON0 ] 0x00000000 Tap ]"))
        // The press itself still selects.
        assertTrue(patched.contains("SelectLeft [ Old [ 1 DIMOFS_BUTTON0 ] ]"))
        // As the game saves it for the classic preset, the left tap is the right button's interact.
        val saved = defaults.replace("Invalid [ Old [ 1 DIMOFS_BUTTON0 ] 0x00000000 Tap ]",
            "ClassicInteractTap [ Old [ 1 DIMOFS_BUTTON0 ] 0x00000000 Tap ]")
        val resaved = DdoKeymap.patch(saved)
        assertTrue(resaved.contains("Interact [ User [ 1 DIMOFS_BUTTON0 ] 0x00000000 Tap ]"))
        assertFalse(resaved.contains("ClassicInteractTap [ Old [ 1 DIMOFS_BUTTON0 ]"))
        // The right button's own tap keeps it.
        assertTrue(resaved.contains("ClassicInteractTap [ Old [ 1 DIMOFS_BUTTON1 ] 0x00000000 Tap ]"))
    }

    @Test
    fun patchingTwiceChangesNothing() {
        val once = DdoKeymap.patch(defaults)
        assertEquals(once, DdoKeymap.patch(once))
    }

    @Test
    fun lineEndingsKept() {
        val crlf = defaults.replace("\r\n", "\n").replace("\n", "\r\n")
        val patched = DdoKeymap.patch(crlf)
        assertFalse(patched.replace("\r\n", "").contains("\n"))
    }
}
