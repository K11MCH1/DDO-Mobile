package com.ddomobile.launcher.runtime

import android.content.Context
import java.io.File

/** Where the runtime and its parts live in app storage, and the same places as the guest sees them. */
class Paths(context: Context) {
    val files: File = context.filesDir
    val rootfs = File(files, "runtime/rootfs")
    val runtimeVersion = File(files, "runtime/version")
    val downloads = File(context.cacheDir, "downloads")
    /** Shared with the guest as XDG_RUNTIME_DIR: the compositor's socket and session files. */
    val run = File(files, "run")
    val prootTmp = File(files, "tmp")
    val logs = File(files, "logs")
    val androidDrivers = File(files, "drivers/android")
    val nativeLibs: File = File(context.applicationInfo.nativeLibraryDir)

    val proton = File(rootfs, GUEST_PROTON.removePrefix("/"))
    val linuxDrivers = File(rootfs, GUEST_DRIVERS.removePrefix("/"))
    val ddoHome = File(rootfs, GUEST_DDO.removePrefix("/"))

    fun guest(host: File): String? {
        val path = host.absolutePath
        val root = rootfs.absolutePath
        if (path.startsWith("$root/")) return "/" + path.removePrefix("$root/")
        val storage = android.os.Environment.getExternalStorageDirectory().absolutePath
        if (path == storage) return GUEST_STORAGE
        if (path.startsWith("$storage/")) return "$GUEST_STORAGE/" + path.removePrefix("$storage/")
        return null
    }

    companion object {
        const val GUEST_RUN = "/run/ddo"
        const val GUEST_STORAGE = "/storage"
        const val GUEST_PROTON = "/opt/proton"
        const val GUEST_DRIVERS = "/opt/drivers"
        const val GUEST_DDO = "/root/.local/share/ddo-mobile"
        const val GUEST_PREFIX = "/root/.local/share/ddo-mobile/prefix"
        const val WAYLAND_SOCKET = "wayland-ddo"
    }
}
