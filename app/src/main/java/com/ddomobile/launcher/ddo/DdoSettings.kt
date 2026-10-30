package com.ddomobile.launcher.ddo

import android.content.Context
import android.os.Environment
import com.ddomobile.launcher.core.Secrets
import java.io.File

enum class DdoRenderer(val core: String, val label: String) {
    D3D11("D3D11", "DirectX 11 (DXVK → Vulkan)"),
    D3D10("D3D10", "DirectX 10 (DXVK → Vulkan)"),
    D3D9("D3D9", "DirectX 9 (DXVK → Vulkan)"),
    OPENGL("OpenGL", "OpenGL (Zink → Vulkan)"),
}

enum class DdoQuality(val label: String) { LOW("Low"), MEDIUM("Medium"), HIGH("High") }

object DdoSettings {
    private const val PREFS = "ddo"
    const val CLIENT_EXE = "x64/dndclient64.exe"

    /** Share of the screen's pixels the game renders at; gamescope scales the result to the full panel. */
    val RENDER_SCALES = listOf(50, 67, 75, 100)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun defaultInstall(): File = File(Environment.getExternalStorageDirectory(), "DDO")

    fun installDir(context: Context): File = prefs(context).getString("install", null)?.let(::File) ?: defaultInstall()
    fun setInstallDir(context: Context, dir: File) = prefs(context).edit().putString("install", dir.absolutePath).apply()
    fun clientExe(context: Context): File = File(installDir(context), CLIENT_EXE)

    /** Null when the folder holds a usable install, else what is missing. */
    fun installProblem(context: Context): String? {
        val dir = installDir(context)
        return when {
            !dir.isDirectory -> "No folder at ${dir.absolutePath}"
            !File(dir, "client_general.dat").isFile -> "client_general.dat is missing in ${dir.absolutePath}"
            !clientExe(context).isFile -> "$CLIENT_EXE is missing in ${dir.absolutePath}"
            else -> null
        }
    }

    fun username(context: Context): String = prefs(context).getString("username", "").orEmpty()
    fun setUsername(context: Context, value: String) = prefs(context).edit().putString("username", value).apply()

    fun rememberPassword(context: Context): Boolean = prefs(context).getBoolean("remember", false)

    fun savedPassword(context: Context): String? =
        if (!rememberPassword(context)) null else prefs(context).getString("password", null)?.let(Secrets::open)

    fun storePassword(context: Context, remember: Boolean, password: String) {
        val edit = prefs(context).edit().putBoolean("remember", remember)
        val sealed = if (remember) runCatching { Secrets.seal(password) }.getOrNull() else null
        if (sealed != null) edit.putString("password", sealed) else edit.remove("password")
        edit.apply()
    }

    fun world(context: Context): String? = prefs(context).getString("world", null)
    fun setWorld(context: Context, name: String) = prefs(context).edit().putString("world", name).apply()

    fun renderer(context: Context): DdoRenderer =
        runCatching { DdoRenderer.valueOf(prefs(context).getString("renderer", null)!!) }.getOrDefault(DdoRenderer.D3D11)
    fun setRenderer(context: Context, value: DdoRenderer) = prefs(context).edit().putString("renderer", value.name).apply()

    fun quality(context: Context): DdoQuality =
        runCatching { DdoQuality.valueOf(prefs(context).getString("quality", null)!!) }.getOrDefault(DdoQuality.MEDIUM)
    fun setQuality(context: Context, value: DdoQuality) = prefs(context).edit().putString("quality", value.name).apply()

    fun renderScale(context: Context): Int = prefs(context).getInt("scale", 50).takeIf { it in RENDER_SCALES } ?: 50
    fun setRenderScale(context: Context, value: Int) = prefs(context).edit().putInt("scale", value).apply()

    /** DDO's MaximumFrameRate one past its 120 cap, which the client runs without a limit (133 fps idle on the RedMagic). */
    const val FRAME_LIMIT_OFF = 121
    val FRAME_LIMITS = listOf(30, 45, 60, 120, FRAME_LIMIT_OFF)

    fun frameLimit(context: Context): Int = prefs(context).getInt("fps", 60)
    fun setFrameLimit(context: Context, value: Int) = prefs(context).edit().putInt("fps", value).apply()

    fun highRes(context: Context): Boolean = prefs(context).getBoolean("highres", true)
    fun setHighRes(context: Context, value: Boolean) = prefs(context).edit().putBoolean("highres", value).apply()

    /** Our FEX build's counters (runtime/fex: invalidations, cache loads, compiles, clears), logged every 5 s. */
    fun diagnostics(context: Context): Boolean = prefs(context).getBoolean("diagnostics", false)
    fun setDiagnostics(context: Context, value: Boolean) = prefs(context).edit().putBoolean("diagnostics", value).apply()

    fun showHud(context: Context): Boolean = prefs(context).getBoolean("hud", false)
    fun setShowHud(context: Context, value: Boolean) = prefs(context).edit().putBoolean("hud", value).apply()
}
