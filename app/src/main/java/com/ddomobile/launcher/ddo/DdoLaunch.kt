package com.ddomobile.launcher.ddo

import android.content.Context
import com.ddomobile.launcher.runtime.Paths
import com.ddomobile.launcher.runtime.ProtonStore
import java.io.File
import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject

/** Turns a login into the request ddo-run reads to start dndclient64.exe under Proton. */
object DdoLaunch {
    private const val QUEUE_POLL_MS = 3_000L
    const val REQUEST = "${Paths.GUEST_DDO}/launch.json"
    private const val PREFS = "${Paths.GUEST_DDO}/UserPreferences.ini"

    private fun prefsFile(paths: Paths) = File(paths.ddoHome, "UserPreferences.ini")

    /**
     * Blocking: logs in, waits its turn in the world queue and writes the launch request. [status] gets one line
     * per stage for the UI; [cancelled] is checked between queue polls. Returns the request's guest path.
     */
    fun prepare(
        context: Context,
        username: String,
        password: String,
        worldName: String,
        status: (String) -> Unit,
        cancelled: () -> Boolean,
    ): String {
        val (paths, proton, install) = checkSetup(context)

        status("Contacting the DDO datacenter…")
        val datacenter = DdoGls.datacenter()
        val world = datacenter.worlds.firstOrNull { it.name == worldName }
            ?: throw IOException("$worldName is not in the world list any more")
        val config = DdoGls.launcherConfig(DdoGls.secure(datacenter.launcherConfigUrl))

        status("Signing in…")
        val login = DdoGls.login(datacenter.authServer, username, password)
        val subscription = login.ddoSubscriptions().firstOrNull() ?: throw IOException("This account has no DDO subscription")

        status("Checking ${world.name}…")
        val worldStatus = DdoGls.worldStatus(world)
        while (true) {
            if (cancelled()) throw InterruptedException()
            val turn = DdoGls.takeANumber(config, subscription.name, login.ticket, worldStatus.queueUrl)
            if (turn.queueNumber <= turn.nowServing) break
            status("In the ${world.name} login queue: ${turn.ahead} ahead of you")
            Thread.sleep(QUEUE_POLL_MS)
        }

        status("Starting the game…")
        val args = DdoGls.clientArguments(config, subscription.name, worldStatus.loginServer, login.ticket, world,
            highRes = DdoSettings.highRes(context))
        DdoSettings.setWorld(context, world.name)
        return write(context, paths, proton, install, args)
    }

    /** Starts the client with placeholder login values that only reach this device: it brings up its renderer and fails to connect. */
    fun prepareTest(context: Context): String {
        val (paths, proton, install) = checkSetup(context)
        val placeholder = listOf(
            "-a", "test", "-h", "127.0.0.1:9", "--glsticketdirect", "0", "--chatserver", "127.0.0.1:9",
            "--rodat", "on", "--language", "English", "--gametype", "DDO",
        )
        return write(context, paths, proton, install, placeholder)
    }

    private fun checkSetup(context: Context): Triple<Paths, String, String> {
        val paths = Paths(context)
        DdoSettings.installProblem(context)?.let { throw IOException(it) }
        val store = ProtonStore(context)
        val proton = store.selected() ?: throw IOException("Install a Proton first")
        val install = paths.guest(DdoSettings.installDir(context))
            ?: throw IOException("The game folder must be in the phone's storage")
        return Triple(paths, store.guestPath(proton), install)
    }

    private fun write(context: Context, paths: Paths, proton: String, install: String, clientArgs: List<String>): String {
        writePreferences(context, prefsFile(paths))
        DdoKeymap.apply(context, paths.ddoHome)
        // Proton starts xalia (controller accessibility for Steam Deck) by default; DDO has no use for it.
        val env = JSONObject().put("PROTON_USE_XALIA", "0")
        if (DdoSettings.showHud(context)) env.put("DXVK_HUD", "fps,frametimes,gpuload")
        if (DdoSettings.renderer(context) == DdoRenderer.OPENGL) env.put("MESA_LOADER_DRIVER_OVERRIDE", "zink")
        val request = JSONObject()
            .put("proton", proton)
            .put("prefix", Paths.GUEST_PREFIX)
            .put("dir", install)
            .put("exe", "$install/${DdoSettings.CLIENT_EXE}")
            .put("args", JSONArray(clientArgs + listOf("--prefs", "Z:" + PREFS.replace('/', '\\'))))
            .put("env", env)
            .put("prefs", PREFS)
        writePrivate(File(paths.ddoHome, "launch.json"), request.toString())
        return REQUEST
    }

    private fun writePrivate(file: File, text: String) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, ".${file.name}.tmp")
        tmp.delete()
        tmp.createNewFile()
        tmp.setReadable(false, false); tmp.setReadable(true, true)
        tmp.setWritable(false, false); tmp.setWritable(true, true)
        tmp.writeText(text)
        if (!tmp.renameTo(file)) throw IOException("Could not write ${file.name}")
    }

    /** Seeds the mobile defaults once, then only enforces what the launcher owns. */
    internal fun writePreferences(context: Context, file: File) {
        file.parentFile?.mkdirs()
        val ini = if (file.isFile) Ini.parse(file.readText()) else
            Ini.parse(context.assets.open("ddo/UserPreferences.mobile.ini").bufferedReader().use { it.readText() })
        ini["Render", "GraphicsCore"] = DdoSettings.renderer(context).core
        ini["Render", "D3DVersionPromptedForAtStartup"] = "11"
        ini["Render", "HavePromptedForD3D10AtStartup"] = "True"
        ini["Troubleshooting", "MaximumFrameRate"] = DdoSettings.frameLimit(context).toString()
        ini["Display", "ShowIntroMovie"] = "False"
        val quality = DdoSettings.quality(context)
        if (ini["Launcher", "DdoMobileQuality"] != quality.name) {
            QUALITY.getValue(quality).forEach { (k, v) -> ini["Render", k] = v }
            ini["Launcher", "DdoMobileQuality"] = quality.name
        }
        file.writeText(ini.toString())
    }

    private val QUALITY = mapOf(
        DdoQuality.LOW to mapOf(
            "TextureDetail" to "Low", "ModelDetail" to "Low", "MaterialDetail" to "Low",
            "LandscapeDrawDistance" to "Low", "ObjectDrawDistance" to "Low", "ShadowMapQuality" to "0",
            "StencilShadows" to "Off", "FrillDensity" to "0.00", "DynamicParticles" to "Few",
            "AnisotropicQuality" to "1", "SurfaceReflections" to "Off", "VideoPostEffects" to "False",
        ),
        DdoQuality.MEDIUM to mapOf(
            "TextureDetail" to "Medium", "ModelDetail" to "Medium", "MaterialDetail" to "Medium",
            "LandscapeDrawDistance" to "Medium", "ObjectDrawDistance" to "Medium", "ShadowMapQuality" to "0",
            "StencilShadows" to "Off", "FrillDensity" to "0.30", "DynamicParticles" to "Few",
            "AnisotropicQuality" to "2", "SurfaceReflections" to "Off", "VideoPostEffects" to "False",
        ),
        DdoQuality.HIGH to mapOf(
            "TextureDetail" to "High", "ModelDetail" to "High", "MaterialDetail" to "High",
            "LandscapeDrawDistance" to "High", "ObjectDrawDistance" to "High", "ShadowMapQuality" to "1",
            "StencilShadows" to "Medium", "FrillDensity" to "0.70", "DynamicParticles" to "All",
            "AnisotropicQuality" to "4", "SurfaceReflections" to "Medium", "VideoPostEffects" to "True",
        ),
    )

    /** Order-preserving INI editing, enough for UserPreferences.ini. */
    internal class Ini private constructor(private val sections: LinkedHashMap<String, LinkedHashMap<String, String>>) {
        operator fun get(section: String, key: String): String? = sections[section]?.get(key)

        operator fun set(section: String, key: String, value: String) {
            sections.getOrPut(section) { LinkedHashMap() }[key] = value
        }

        override fun toString(): String = buildString {
            sections.entries.forEachIndexed { i, (name, values) ->
                if (i > 0) append("\r\n")
                append('[').append(name).append("]\r\n")
                values.forEach { (k, v) -> append(k).append('=').append(v).append("\r\n") }
            }
        }

        companion object {
            fun parse(text: String): Ini {
                val sections = LinkedHashMap<String, LinkedHashMap<String, String>>()
                var current: LinkedHashMap<String, String>? = null
                text.lineSequence().map { it.trim() }.forEach { line ->
                    when {
                        line.startsWith("[") && line.endsWith("]") ->
                            current = sections.getOrPut(line.substring(1, line.length - 1)) { LinkedHashMap() }
                        '=' in line && current != null -> {
                            val at = line.indexOf('=')
                            current!![line.substring(0, at)] = line.substring(at + 1)
                        }
                    }
                }
                return Ini(sections)
            }
        }
    }
}
