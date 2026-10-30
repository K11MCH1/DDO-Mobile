package com.ddomobile.launcher.session

import android.content.Context
import android.util.Log
import com.ddomobile.launcher.core.Archives
import com.ddomobile.launcher.runtime.Paths
import java.io.File

/**
 * An Android-side PulseAudio (the daemon and libraries ship as the app's native libraries, its modules in
 * assets/pulseaudio.tzst) that plays through AAudio. The guest's PulseAudio clients reach it over a socket in
 * the shared run directory.
 */
class AudioServer(private val context: Context) {
    private val paths = Paths(context)
    private val dir = File(paths.files, "pulse")
    private var process: Process? = null

    val guestServer = "unix:${Paths.GUEST_RUN}/$SOCKET"

    fun start(log: File) {
        stop()
        unpack()
        val socket = File(paths.run, SOCKET).apply { delete() }
        File(dir, "default.pa").writeText(
            listOf(
                "load-module module-native-protocol-unix auth-anonymous=1 auth-cookie-enabled=0 socket=\"${socket.path}\"",
                // Without a volume the sink starts silent, and every stream starts at the sink's level.
                "load-module module-aaudio-classic-sink sink_name=AAudio volume=1.0",
                "set-default-sink AAudio",
            ).joinToString("\n", postfix = "\n")
        )
        val builder = ProcessBuilder(
            File(paths.nativeLibs, "libpulseaudio.so").path,
            "--system=false", "--disable-shm=true", "--fail=false", "-n", "--file=default.pa",
            "--daemonize=false", "--use-pid-file=false", "--exit-idle-time=-1", "--log-level=notice",
        ).directory(dir).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(log))
        builder.environment().apply {
            put("LD_LIBRARY_PATH", "/system/lib64:${paths.nativeLibs.path}:${File(dir, "modules/arm64").path}")
            put("HOME", dir.path)
            put("TMPDIR", dir.path)
        }
        process = runCatching { builder.start() }.onFailure { Log.w(TAG, "PulseAudio did not start", it) }.getOrNull()
    }

    fun stop() {
        process?.destroy()
        process = null
    }

    private fun unpack() {
        val stamp = File(dir, ".bundle")
        val want = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime.toString()
        if (stamp.isFile && stamp.readText() == want && File(dir, "modules/arm64").isDirectory) return
        dir.deleteRecursively()
        dir.mkdirs()
        val archive = File(context.cacheDir, "pulseaudio.tar.zst")
        context.assets.open("pulseaudio.tzst").use { input -> archive.outputStream().use { input.copyTo(it) } }
        Archives.extractTar(archive, dir)
        archive.delete()
        stamp.writeText(want)
    }

    companion object {
        private const val TAG = "AudioServer"
        private const val SOCKET = "pulse-native"
    }
}
