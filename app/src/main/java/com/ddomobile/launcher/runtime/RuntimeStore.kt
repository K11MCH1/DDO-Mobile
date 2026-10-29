package com.ddomobile.launcher.runtime

import android.content.Context
import android.os.Environment
import com.ddomobile.launcher.core.Archives
import com.ddomobile.launcher.core.Http
import java.io.File
import java.io.IOException

/** The DDO Mobile runtime: our Arch Linux ARM userspace (runtime/build.sh), unpacked into app storage. */
class RuntimeStore(context: Context) {
    private val paths = Paths(context)

    fun installedVersion(): String? =
        paths.runtimeVersion.takeIf { it.isFile && File(paths.rootfs, "usr/local/bin/ddo-session").isFile }
            ?.readText()?.trim()?.ifEmpty { null }

    /** A runtime archive the user or a developer placed in Download or DDO-Mobile, newest first. */
    fun localArchive(): File? = listOf(
        File(Environment.getExternalStorageDirectory(), "Download"),
        File(Environment.getExternalStorageDirectory(), "DDO-Mobile"),
    ).flatMap { dir -> dir.listFiles { f -> ARCHIVE.matches(f.name) }.orEmpty().toList() }
        .maxByOrNull { it.lastModified() }

    /** Installs [archive], replacing the previous runtime but keeping the game prefix, Proton and drivers. */
    fun install(archive: File, expectedSha256: String?, stage: (String, Float) -> Unit) {
        if (expectedSha256 != null) {
            stage("Checking the runtime", -1f)
            if (!Http.sha256(archive).equals(expectedSha256, true)) throw IOException("The runtime archive is damaged")
        }
        val version = ARCHIVE.matchEntire(archive.name)?.groupValues?.get(1) ?: "local"
        val staging = File(paths.rootfs.parentFile, "rootfs.new")
        staging.deleteRecursively()
        stage("Unpacking the runtime", 0f)
        Archives.extractTar(archive, staging) { stage("Unpacking the runtime", it) }
        val keep = listOf(Paths.GUEST_DDO, Paths.GUEST_PROTON, Paths.GUEST_DRIVERS)
        if (paths.rootfs.isDirectory) {
            for (guest in keep) {
                val from = File(paths.rootfs, guest.removePrefix("/"))
                if (!from.exists()) continue
                val to = File(staging, guest.removePrefix("/"))
                to.deleteRecursively()
                to.parentFile?.mkdirs()
                if (!from.renameTo(to)) throw IOException("Could not keep $guest")
            }
        }
        val old = File(paths.rootfs.parentFile, "rootfs.old")
        old.deleteRecursively()
        if (paths.rootfs.exists() && !paths.rootfs.renameTo(old)) throw IOException("Could not replace the runtime")
        if (!staging.renameTo(paths.rootfs)) throw IOException("Could not install the runtime")
        paths.runtimeVersion.writeText(version)
        stage("Cleaning up", -1f)
        old.deleteRecursively()
    }

    companion object {
        val ARCHIVE = Regex("ddo-runtime-([A-Za-z0-9._-]+)\\.tar\\.zst")
    }
}
