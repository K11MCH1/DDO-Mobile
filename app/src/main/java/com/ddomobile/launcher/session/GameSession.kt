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
    @Volatile private var audio: AudioServer? = null

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
        paths.run.mkdirs()
        File(paths.run, Paths.WAYLAND_SOCKET).delete()
        File(paths.run, Paths.WAYLAND_SOCKET + ".lock").delete()
        File(paths.run, "compositor.log").delete()

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

        val audio = AudioServer(app).also { it.start(log) }
        this.audio = audio

        val env = linkedMapOf(
            "PULSE_SERVER" to audio.guestServer,
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
        val binds = proot.binds()
        val fastPath = if (File(paths.rootfs, "usr/local/lib/libddofastpath.so").isFile) proot.fastPathKey(binds) else null
        fastPath?.let { env.putAll(proot.fastPathGuestEnv(binds, it)) }
        proot.writePreload(fastPath != null)
        env.putAll(extraEnv)
        val guest = listOf("/usr/bin/env", "-i") + env.map { (k, v) -> "$k=$v" } +
            listOf("/bin/bash", "/usr/local/bin/ddo-session", argsFile)
        log.appendText("== proot fast path: ${if (fastPath != null) "on" else "off"}\n")

        val builder = ProcessBuilder(proot.command(binds, guest))
            .directory(paths.files)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(log))
        builder.environment().putAll(proot.hostEnvironment())
        fastPath?.let { builder.environment()["PROOT_FASTPATH"] = it }
        val proc = try {
            builder.start()
        } catch (e: Exception) {
            Log.e(TAG, "proot did not start", e)
            log.appendText("proot did not start: ${e.message}\n")
            audio.stop()
            Compositor.nativeStop()
            mutableState.value = State.Ended(-1, log)
            return
        }
        process = proc
        GameService.start(app)
        mutableState.value = State.Running(width, height, log)
        Thread({
            val code = runCatching { proc.waitFor() }.getOrDefault(-1)
            Log.i(TAG, "session ended with $code")
            process = null
            audio.stop()
            Compositor.nativeStop()
            GameService.stop(app)
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
        writeCertificates(etc)
        writeLink(cm, etc)
        // dbus and Wine expect a machine id; one per install, never shared between phones.
        File(etc, "machine-id").takeIf { !it.isFile || it.length() < 32 }
            ?.writeText(java.util.UUID.randomUUID().toString().replace("-", "") + "\n")
    }

    /**
     * Android refuses netlink to apps, so the guest can't list its network interfaces; the netif shim answers
     * getifaddrs, ioctls and Wine's adapter queries from this description of the active link instead.
     */
    private fun writeLink(cm: ConnectivityManager, etc: File) {
        val file = File(etc, "droiddeck-net")
        val props = runCatching { cm.getLinkProperties(cm.activeNetwork) }.getOrNull()
        val name = props?.interfaceName
        if (props == null || name == null) { file.delete(); return }
        val nif = runCatching { java.net.NetworkInterface.getByName(name) }.getOrNull()
        val index = nif?.index?.takeIf { it > 1 } ?: 2
        val mtu = props.mtu.takeIf { it > 0 } ?: nif?.mtu?.takeIf { it > 0 } ?: 1500
        // Apps can't read the hardware address; a stable, locally administered one stands in.
        val mac = (nif?.hardwareAddress?.takeIf { it.size == 6 && it.any { b -> b.toInt() != 0 } }
            ?: byteArrayOf(0x02, 0x44, 0x44, 0x4f, 0x4d, 0x42)).joinToString(":") { "%02x".format(it) }
        val text = buildString {
            append("if $name $index $mtu\n")
            append("mac $mac\n")
            props.linkAddresses.forEach { append("addr ${it.address.hostAddress?.substringBefore('%')} ${it.prefixLength}\n") }
            props.routes.filter { it.isDefaultRoute && it.gateway != null }.take(2)
                .forEach { append("gw ${it.gateway!!.hostAddress?.substringBefore('%')}\n") }
        }
        file.writeText(text)
    }

    /**
     * The runtime's TLS trust bundle, from Android's own (updatable) CA store, so DDO's ticket refresh and store pages
     * over HTTPS validate exactly as Android would. Arch's ca-certificates.crt links to the extracted bundle.
     */
    private fun writeCertificates(etc: File) {
        val store = listOf(File("/apex/com.android.conscrypt/cacerts"), File("/system/etc/security/cacerts"))
            .firstOrNull { it.list()?.isNotEmpty() == true } ?: return
        val pem = Regex("-----BEGIN CERTIFICATE-----[\\s\\S]+?-----END CERTIFICATE-----")
        val bundle = store.listFiles().orEmpty().sortedBy { it.name }.flatMap { f ->
            runCatching { pem.findAll(f.readText()).map { it.value }.toList() }.getOrDefault(emptyList())
        }
        if (bundle.isEmpty()) return
        val text = bundle.joinToString("\n", postfix = "\n")
        File(etc, "ca-certificates/extracted").mkdirs()
        File(etc, "ca-certificates/extracted/tls-ca-bundle.pem").writeText(text)
        val link = File(etc, "ssl/certs/ca-certificates.crt")
        if (!java.nio.file.Files.exists(link.toPath())) {
            link.parentFile?.mkdirs()
            java.nio.file.Files.deleteIfExists(link.toPath())
            link.writeText(text)
        }
    }

    private fun pruneLogs(dir: File) {
        dir.listFiles { f -> f.name.startsWith("session-") }?.sortedByDescending { it.name }?.drop(20)?.forEach { it.delete() }
    }
}
