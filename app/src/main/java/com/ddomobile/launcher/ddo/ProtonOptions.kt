package com.ddomobile.launcher.ddo

import android.content.Context

/** How Wine keeps the game's threads in step. ntsync and fsync are emulated by the runtime's preload (libddoshim). */
enum class WineSync(val id: String, val label: String) {
    NTSYNC("ntsync", "ntsync"),
    FSYNC("fsync", "fsync"),
    ESYNC("esync", "esync"),
    WINESERVER("wineserver", "wineserver");

    /** What ddo-run sets for the choice; DroidDeck-Proton has esync built in, so no esync pack is needed. */
    val env: Map<String, String> get() = when (this) {
        NTSYNC -> mapOf("BL_SYNC" to "1", "BL_FSYNC" to "0")
        FSYNC -> mapOf("BL_SYNC" to "0", "BL_FSYNC" to "1", "WINEFSYNC" to "1", "PROTON_NO_NTSYNC" to "1")
        ESYNC -> mapOf("BL_SYNC" to "0", "BL_FSYNC" to "0", "PROTON_NO_NTSYNC" to "1", "PROTON_NO_FSYNC" to "1")
        WINESERVER -> mapOf("BL_SYNC" to "0", "BL_FSYNC" to "0", "PROTON_NO_NTSYNC" to "1", "PROTON_NO_ESYNC" to "1",
            "PROTON_NO_FSYNC" to "1", "WINEESYNC" to "0", "WINEFSYNC" to "0")
    }

    companion object {
        fun of(id: String?) = entries.firstOrNull { it.id == id }
    }
}

/** FEX settings in DroidDeck's presets, key for key. Each sets only its own keys; the rest stay FEX's defaults. */
class FexPreset(val id: String, val label: String, val detail: String, val env: Map<String, String>) {
    companion object {
        private fun tso(main: Int, vector: Int, memcpy: Int, halfBarrier: Int) = listOf(
            "FEX_TSOENABLED" to "$main", "FEX_VECTORTSOENABLED" to "$vector", "FEX_MEMCPYSETTSOENABLED" to "$memcpy",
            "FEX_HALFBARRIERTSOENABLED" to "$halfBarrier",
        )
        private val EXTREME = listOf("FEX_X87REDUCEDPRECISION" to "1", "FEX_MULTIBLOCK" to "1", "FEX_SMCCHECKS" to "none",
            "FEX_DISABLEL2CACHE" to "1", "FEX_DYNAMICL1CACHE" to "1", "FEX_DYNAMICL1CACHEINCREASECOUNTHEURISTIC" to "250",
            "FEX_DYNAMICL1CACHEDECREASECOUNTHEURISTIC" to "50")
        private val FAST = listOf("FEX_X87REDUCEDPRECISION" to "1", "FEX_MULTIBLOCK" to "1")

        val ALL = listOf(
            FexPreset("", "FEX defaults", "Uses FEX defaults.", emptyMap()),
            FexPreset("STABILITY", "Stability", "Safest, slowest settings.",
                (tso(1, 1, 1, 1) + listOf("FEX_X87REDUCEDPRECISION" to "0", "FEX_MULTIBLOCK" to "0")).toMap()),
            FexPreset("COMPATIBILITY", "Compatibility", "Full TSO with multiblock.",
                (tso(1, 1, 1, 1) + listOf("FEX_X87REDUCEDPRECISION" to "0", "FEX_MULTIBLOCK" to "1")).toMap()),
            FexPreset("INTERMEDIATE", "Intermediate", "Balanced TSO with reduced x87 precision.", (tso(1, 0, 0, 1) + FAST).toMap()),
            FexPreset("PERFORMANCE", "Performance", "All TSO disabled; multiblock.", (tso(0, 0, 0, 0) + FAST).toMap()),
            FexPreset("PERFORMANCE_TSO", "Performance + TSO", "Performance settings with main TSO enabled.", (tso(1, 0, 0, 0) + FAST).toMap()),
            FexPreset("EXTREME", "Extreme", "Disables self-modifying-code checks. JIT games and DRM may break.", (tso(0, 0, 0, 0) + EXTREME).toMap()),
            FexPreset("EXTREME_TSO", "Extreme + TSO", "Extreme settings with main TSO enabled.", (tso(1, 0, 0, 0) + EXTREME).toMap()),
            FexPreset("EXTREME_GN", "Extreme-gn", "Performance with TSC and metadata hints. Try before Extreme.",
                (tso(0, 0, 0, 0) + FAST + listOf("FEX_SMALLTSCSCALE" to "1", "FEX_VOLATILEMETADATA" to "1")).toMap()),
            FexPreset("DENUVO", "Denuvo", "Performance with full code checks and the hypervisor bit hidden.",
                (tso(0, 0, 0, 0) + FAST + listOf("FEX_SMCCHECKS" to "full", "FEX_HIDEHYPERVISORBIT" to "1")).toMap()),
        )

        fun byId(id: String?): FexPreset = ALL.firstOrNull { it.id == id } ?: ALL[0]
    }
}

/** The Proton tab's settings for DDO. */
object ProtonOptions {
    private fun prefs(context: Context) = context.getSharedPreferences("proton-options", Context.MODE_PRIVATE)

    /** esync is what DDO Mobile ran before this choice existed. */
    fun wineSync(context: Context): WineSync = WineSync.of(prefs(context).getString("sync", null)) ?: WineSync.ESYNC
    fun setWineSync(context: Context, value: WineSync) = prefs(context).edit().putString("sync", value.id).apply()

    /**
     * Performance by default: DDO's executable has no volatile metadata, so with TSO FEX would order every memory
     * access. The old "Fast CPU mode" switch, when turned off, becomes FEX's own defaults.
     */
    fun fexPreset(context: Context): FexPreset {
        val stored = prefs(context).getString("fexPreset", null)
        if (stored != null) return FexPreset.byId(stored)
        val fastCpu = context.getSharedPreferences("ddo", Context.MODE_PRIVATE).getBoolean("fastCpu", true)
        return FexPreset.byId(if (fastCpu) "PERFORMANCE" else "")
    }
    fun setFexPreset(context: Context, value: FexPreset) = prefs(context).edit().putString("fexPreset", value.id).apply()

    fun forceSsbs(context: Context): Boolean = prefs(context).getBoolean("forceSsbs", false)
    fun setForceSsbs(context: Context, value: Boolean) = prefs(context).edit().putBoolean("forceSsbs", value).apply()
}
