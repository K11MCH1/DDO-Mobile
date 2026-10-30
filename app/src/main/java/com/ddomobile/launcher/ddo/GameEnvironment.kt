package com.ddomobile.launcher.ddo

import android.content.Context
import java.io.File
import java.io.IOException
import org.json.JSONObject

/**
 * Environment variables for DDO, edited on the Proton tab as DroidDeck's Game environment editor does. An edit is a
 * value, or null to unset a variable the launcher or the runtime would otherwise set.
 */
object GameEnvironment {
    enum class Type { TOGGLE, CHOICE, MULTIPLE, NUMBER, TEXT }

    /** [starter] is filled in when the variable is added; nothing applies until it is saved. */
    data class Option(val name: String, val starter: String, val detail: String, val type: Type, val choices: List<String> = emptyList())

    private val BOOL = listOf("0", "1")

    val OPTIONS = listOf(
        Option("MESA_SHADER_CACHE_DISABLE", "false", "Keep Mesa shader caching enabled to reduce repeated compilation. false enables caching.", Type.TOGGLE, listOf("false", "true")),
        Option("VKD3D_FEATURE_LEVEL", "12_2", "D3D12 feature-level override. Leave unset for automatic detection.", Type.CHOICE, listOf("12_2", "12_1", "12_0", "11_1", "11_0")),
        Option("VKD3D_SHADER_MODEL", "6_9", "D3D12 shader-model override. Leave unset for automatic detection.", Type.CHOICE, listOf("6_9", "6_8", "6_7", "6_6", "6_5", "6_4", "6_3", "6_2", "6_1", "6_0", "5_1")),
        Option("VKD3D_CONFIG", "nodxr", "VKD3D options for D3D12. nodxr disables ray tracing.", Type.MULTIPLE, listOf("nodxr", "single_queue", "no_upload_hvv")),
        Option("MESA_SHADER_CACHE_MAX_SIZE", "1G", "Maximum Mesa shader-cache size. Larger caches use more storage.", Type.CHOICE, listOf("512M", "1G", "2G", "4G")),
        Option("PROTON_LOG", "1", "Set to 1 for a Proton diagnostic log in the prefix's logs folder. Logging can reduce performance.", Type.TOGGLE, BOOL),
        Option("PROTON_USE_WINED3D", "1", "Set to 1 to use OpenGL-based WineD3D instead of DXVK, for compatibility testing.", Type.TOGGLE, BOOL),
        Option("PROTON_DXVK_D3D8", "1", "Set to 1 to run Direct3D 8 through DXVK instead of WineD3D.", Type.TOGGLE, BOOL),
        Option("WINE_D3D_CONFIG", "renderer=vulkan", "Renderer for WineD3D.", Type.CHOICE, listOf("renderer=vulkan", "renderer=gl")),
        Option("PROTON_USE_XALIA", "0", "Set to 0 to keep Proton's gamepad-navigation helper off.", Type.TOGGLE, BOOL),
        Option("PROTON_NO_NTSYNC", "1", "Set to 1 to keep DDO off ntsync, whatever Wine sync says.", Type.TOGGLE, BOOL),
        Option("PROTON_NO_ESYNC", "1", "Set to 1 to keep DDO off esync.", Type.TOGGLE, BOOL),
        Option("PROTON_NO_FSYNC", "1", "Set to 1 to keep DDO off fsync.", Type.TOGGLE, BOOL),
        Option("DXVK_HUD", "fps", "DXVK overlay. fps displays the frame rate; unset hides it.", Type.MULTIPLE, listOf("scale=0.5", "scale=0.7", "opacity=0.5", "opacity=0.7", "devinfo", "fps", "frametimes", "submissions", "drawcalls", "pipelines", "descriptors", "memory", "gpuload", "version", "api", "cs", "compiler", "samplers")),
        Option("DXVK_CONFIG", "dxvk.maxFrameRate = 60", "DXVK settings separated by semicolons; dxvk.maxFrameRate limits the frame rate.", Type.TEXT),
        Option("VKD3D_FRAME_RATE", "60", "D3D12 frame-rate limit. 0 disables the limit.", Type.NUMBER),
        Option("mesa_glthread", "true", "Mesa OpenGL threading, for the OpenGL renderer.", Type.TOGGLE, listOf("false", "true")),
        Option("ZINK_DESCRIPTORS", "auto", "", Type.CHOICE, listOf("auto", "lazy", "cached", "notemplates")),
        Option("ZINK_DEBUG", "nir", "", Type.MULTIPLE, listOf("nir", "spirv", "tgsi", "validation", "sync", "compact", "noreorder")),
        Option("SteamDeck", "0", "", Type.TOGGLE, BOOL),
        Option("FD_DEV_FEATURES", "enable_tp_ubwc_flag_hint=1", "", Type.MULTIPLE, listOf("enable_tp_ubwc_flag_hint=1", "storage_8bit=1")),
        Option("TU_DEBUG", "forcecb", "Turnip debug flags for the game. sysmem is DDO Mobile's default.", Type.MULTIPLE, listOf("forcecb", "nocb", "startup", "deck_emu", "nir", "nobin", "sysmem", "gmem", "forcebin", "layout", "noubwc", "nomultipos", "nolrz", "nolrzfc", "perf", "perfc", "flushall", "syncdraw", "push_consts_per_stage", "rast_order", "unaligned_store", "log_skip_gmem_ops", "dynamic", "bos", "3d_load", "fdm", "noconform", "rd")),
        Option("IR3_SHADER_DEBUG", "nouboopt", "", Type.MULTIPLE, listOf("nouboopt", "nopreamble", "noearlypreamble")),
        Option("MESA_EXTENSION_MAX_YEAR", "", "", Type.TEXT),
        Option("MESA_GL_VERSION_OVERRIDE", "", "", Type.TEXT),
        Option("PULSE_LATENCY_MSEC", "", "Audio buffer length in milliseconds. Longer means fewer wake-ups.", Type.NUMBER),
        Option("DDO_GAME_CORES", "", "CPU cores for DDO, such as 0-4 or 0,2,4, in place of the cores DDO Mobile picks. Fewer cores run cooler.", Type.TEXT),
        Option("WINE_LARGE_ADDRESS_AWARE", "0", "", Type.TOGGLE, BOOL),
        Option("WINEDLLOVERRIDES", "", "", Type.TEXT),
        Option("GALLIUM_HUD", "simple", "", Type.MULTIPLE, listOf("simple", "fps", "frametime")),
    )

    val NAME = Regex("^[A-Za-z_][A-Za-z0-9_]*$")
    const val MAX_VALUE = 8192

    private val UNSUPPORTED = setOf("WINENTSYNC", "WINE_FAST_YIELD", "WINE_DO_NOT_CREATE_DXGI_DEVICE_MANAGER", "WINE_NEW_MEDIASOURCE",
        "DXVK_ASYNC", "DXVK_GPLASYNCCACHE")
    private val UNSUPPORTED_PREFIXES = listOf("WRAPPER_", "ALSA_", "BOX64_", "BOX86_")

    fun find(name: String): Option? = OPTIONS.firstOrNull { it.name == name }

    /** Variables for other emulators or other builds of Wine, which DDO Mobile's do not read. */
    fun unsupported(name: String) = name in UNSUPPORTED || UNSUPPORTED_PREFIXES.any { name.startsWith(it) }

    fun valid(name: String, value: String) = NAME.matches(name) && value.length <= MAX_VALUE && '\u0000' !in value

    /** A multiple-choice value with [choice] switched on or off. */
    fun toggle(value: String, choice: String): String {
        val selected = value.split(',').filter { it.isNotEmpty() }.toMutableList()
        if (!selected.remove(choice)) selected.add(choice)
        return selected.joinToString(",")
    }

    class Entry(val name: String, val value: String?, val default: String?, val edited: Boolean)

    /** The editor's list: what the launcher sets, with the edits over it, by name. */
    fun entries(defaults: Map<String, String>, edits: Map<String, String?>): List<Entry> =
        (defaults.keys + edits.keys).toSortedSet(String.CASE_INSENSITIVE_ORDER).map { name ->
            val edited = name in edits
            Entry(name, if (edited) edits[name] else defaults[name], defaults[name], edited)
        }

    /** What the game gets: [defaults], then [edits]; null unsets. */
    fun merge(defaults: Map<String, String>, edits: Map<String, String?>): Map<String, String?> = LinkedHashMap<String, String?>(defaults).apply { putAll(edits) }
}

/** The edits, in files/game-environment.json as {"version": 1, "vars": {name: value or null}}. */
class GameEnvironmentStore(context: Context) {
    private val file = File(context.filesDir, "game-environment.json")

    fun edits(): Map<String, String?> = runCatching {
        val vars = JSONObject(file.readText()).getJSONObject("vars")
        vars.keys().asSequence().associateWith { if (vars.isNull(it)) null else vars.getString(it) }
    }.getOrDefault(emptyMap())

    fun save(edits: Map<String, String?>) {
        val vars = JSONObject()
        edits.forEach { (k, v) -> vars.put(k, v ?: JSONObject.NULL) }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(JSONObject().put("version", 1).put("vars", vars).toString())
        if (!tmp.renameTo(file)) throw IOException("Could not save the game environment")
    }

    fun set(name: String, value: String?) = save(edits() + (name to value))
    fun forget(name: String) = save(edits() - name)
    fun clear() = save(emptyMap())
}
