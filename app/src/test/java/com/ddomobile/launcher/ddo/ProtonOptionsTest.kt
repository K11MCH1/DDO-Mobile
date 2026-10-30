package com.ddomobile.launcher.ddo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtonOptionsTest {
    @Test
    fun presetsMatchDroidDeck() {
        assertEquals(10, FexPreset.ALL.size)
        assertTrue(FexPreset.byId("").env.isEmpty())
        assertEquals(mapOf("FEX_TSOENABLED" to "1", "FEX_VECTORTSOENABLED" to "0", "FEX_MEMCPYSETTSOENABLED" to "0",
            "FEX_HALFBARRIERTSOENABLED" to "0", "FEX_X87REDUCEDPRECISION" to "1", "FEX_MULTIBLOCK" to "1"), FexPreset.byId("PERFORMANCE_TSO").env)
        val extreme = FexPreset.byId("EXTREME").env
        assertEquals("none", extreme["FEX_SMCCHECKS"])
        assertEquals("250", extreme["FEX_DYNAMICL1CACHEINCREASECOUNTHEURISTIC"])
        assertEquals("50", extreme["FEX_DYNAMICL1CACHEDECREASECOUNTHEURISTIC"])
        assertEquals("full", FexPreset.byId("DENUVO").env["FEX_SMCCHECKS"])
        assertEquals("1", FexPreset.byId("EXTREME_GN").env["FEX_VOLATILEMETADATA"])
        assertEquals("0", FexPreset.byId("STABILITY").env["FEX_MULTIBLOCK"])
        // An unknown id falls back to FEX's defaults.
        assertEquals("", FexPreset.byId("NOPE").id)
    }

    @Test
    fun wineSyncSwitchesTheEmulation() {
        assertEquals("1", WineSync.NTSYNC.env["BL_SYNC"])
        assertEquals("1", WineSync.FSYNC.env["BL_FSYNC"])
        assertEquals("1", WineSync.ESYNC.env["PROTON_NO_NTSYNC"])
        assertEquals("0", WineSync.WINESERVER.env["WINEESYNC"])
    }

    @Test
    fun editsGoOverDefaultsAndNullUnsets() {
        val merged = GameEnvironment.merge(mapOf("TU_DEBUG" to "sysmem", "vblank_mode" to "0"), mapOf("TU_DEBUG" to null, "DXVK_HUD" to "fps"))
        assertTrue("TU_DEBUG" in merged)
        assertNull(merged["TU_DEBUG"])
        assertEquals("0", merged["vblank_mode"])
        assertEquals("fps", merged["DXVK_HUD"])
    }

    @Test
    fun entriesShowDefaultsAndEdits() {
        val entries = GameEnvironment.entries(mapOf("vblank_mode" to "0"), mapOf("vblank_mode" to "1", "DXVK_HUD" to "fps"))
        assertEquals(listOf("DXVK_HUD", "vblank_mode"), entries.map { it.name })
        val v = entries.single { it.name == "vblank_mode" }
        assertTrue(v.edited)
        assertEquals("0", v.default)
        assertEquals("1", v.value)
    }

    @Test
    fun namesAndChoices() {
        assertTrue(GameEnvironment.valid("DXVK_HUD", "fps"))
        assertFalse(GameEnvironment.valid("1BAD", "x"))
        assertFalse(GameEnvironment.valid("A", "x\u0000"))
        assertEquals("fps,gpuload", GameEnvironment.toggle("fps", "gpuload"))
        assertEquals("gpuload", GameEnvironment.toggle("fps,gpuload", "fps"))
        assertTrue(GameEnvironment.unsupported("BOX64_DYNAREC"))
        assertFalse(GameEnvironment.unsupported("DXVK_HUD"))
    }
}
