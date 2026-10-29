package com.ddomobile.launcher.session

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import com.ddomobile.launcher.display.Compositor
import com.ddomobile.launcher.runtime.DriverStore
import com.ddomobile.launcher.runtime.Paths
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * One game session: the compositor, then proot running ddo-session (gamescope, Xwayland, Proton, the client).
 * Lives in the app process, independent of any activity, so rotating or leaving the game screen keeps it.
 */
object GameSession {
    private const val TAG = "GameSession"

    sealed interface State {
        data object Idle : State
        data class Running(val width: Int, val height: Int, val log: File) : State
        data class Ended(val exitCode: Int, val log: File) : State
    }

    private val mutableState = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = mutableState

    @Volatile private var process: Process? = null

    val running: Boolean get() = state.value is State.Running

    /** [argsFile] is the guest path of ddo-run's request; [extraEnv] reaches every guest process. */
    @Synchronized
    fun start(context: Context, width: Int, height: Int, argsFile: String, extraEnv: Map<String, String>) {
        if (running) return
        val app = context.applicationContext
        val paths = Paths(app)
        val proot = Proot(paths)
        val drivers = DriverStore(app)
        paths.logs.mkdirs()
        val log = File(paths.logs, "session-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".log")
        pruneLogs(paths.logs)

        proot.prepareAccount()
        writeNetworkFiles(app, paths)
        File(paths.run, Paths.WAYLAND_SOCKET).delete()
        File(paths.run, Paths.WAYLAND_SOCKET + ".lock").delete()

        val androidDriver = drivers.selectedAndroid()
        val started = Compositor.nativeStart(
            paths.run.path, Paths.WAYLAND_SOCKET, width, height,
            androidDriver?.let { drivers.androidDir(it).path }, androidDriver?.let(drivers::androidLibrary),
        )
        if (!started) {
            log.writeText("The compositor could not start\n")
            mutableState.value = State.Ended(-1, log)
            return
        }

        val env = linkedMapOf(
            "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/bin",
            "HOME" to "/root",
            "LANG" to "C.UTF-8",
            "TERM" to "dumb",
            "XDG_RUNTIME_DIR" to Paths.GUEST_RUN,
            "WAYLAND_DISPLAY" to Paths.WAYLAND_SOCKET,
            "DDO_WIDTH" to width.toString(),
            "DDO_HEIGHT" to height.toString(),
            "TMPDIR" to "/tmp",
        )
        drivers.selectedLinux()?.let { env["VK_ICD_FILENAMES"] = drivers.linuxIcd(it) }
        env.putAll(extraEnv)
        val guest = listOf("/usr/bin/env", "-i") + env.map { (k, v) -> "$k=$v" } +
            listOf("/bin/bash", "/usr/local/bin/ddo-session", argsFile)

        val builder = ProcessBuilder(proot.command(guest))
            .directory(paths.files)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(log))
        builder.environment().putAll(proot.hostEnvironment())
        val proc = try {
            builder.start()
        } catch (e: Exception) {
            Log.e(TAG, "proot did not start", e)
            log.appendText("proot did not start: ${e.message}\n")
            Compositor.nativeStop()
            mutableState.value = State.Ended(-1, log)
            return
        }
        process = proc
        mutableState.value = State.Running(width, height, log)
        Thread({
            val code = runCatching { proc.waitFor() }.getOrDefault(-1)
            Log.i(TAG, "session ended with $code")
            process = null
            Compositor.nativeStop()
            mutableState.value = State.Ended(code, log)
        }, "ddo-session-wait").start()
    }

    fun stop() {
        process?.destroy()
    }

    fun acknowledgeEnd() {
        if (state.value is State.Ended) mutableState.value = State.Idle
    }

    /** Android keeps DNS out of /etc; the guest's resolver reads these. */
    private fun writeNetworkFiles(context: Context, paths: Paths) {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val servers = runCatching { cm.getLinkProperties(cm.activeNetwork)?.dnsServers.orEmpty() }.getOrDefault(emptyList())
            .mapNotNull { it.hostAddress?.substringBefore('%') }
            .ifEmpty { listOf("1.1.1.1", "8.8.8.8") }
        val etc = File(paths.rootfs, "etc")
        File(etc, "resolv.conf").apply { delete() }.writeText(servers.joinToString("") { "nameserver $it\n" })
        File(etc, "hosts").writeText("127.0.0.1 localhost\n::1 localhost\n127.0.1.1 ddo-mobile\n")
        File(etc, "hostname").writeText("ddo-mobile\n")
    }

    private fun pruneLogs(dir: File) {
        dir.listFiles { f -> f.name.startsWith("session-") }?.sortedByDescending { it.name }?.drop(20)?.forEach { it.delete() }
    }
}
