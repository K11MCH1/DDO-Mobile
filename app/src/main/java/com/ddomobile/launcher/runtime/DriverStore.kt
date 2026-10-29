package com.ddomobile.launcher.runtime

import android.content.Context
import com.ddomobile.launcher.core.Archives
import com.ddomobile.launcher.core.Http
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile
import org.json.JSONObject

/**
 * Turnip drivers. A driver can have two halves: an Android (bionic) build the app's compositor loads through
 * adrenotools, and a Linux (glibc) build that gamescope, DXVK and the game use inside the runtime.
 * Imports DD-Turnip bundles (manifest.json with both), plain adrenotools zips (meta.json, Android only) and
 * Linux-only zips (meta.json with "platform": "linux").
 */
class DriverStore(context: Context) {
    private val paths = Paths(context)
    private val prefs = context.getSharedPreferences("drivers", Context.MODE_PRIVATE)

    class Driver(val id: String, val name: String, val android: Boolean, val linux: Boolean)

    fun installed(): List<Driver> {
        val ids = (paths.androidDrivers.list().orEmpty().toList() + paths.linuxDrivers.list().orEmpty().toList())
            .filterNot { it.startsWith(".") }.toSortedSet()
        return ids.mapNotNull { id ->
            val android = File(paths.androidDrivers, "$id/meta.json").isFile
            val linux = File(paths.linuxDrivers, "$id/icd.json").isFile
            if (!android && !linux) return@mapNotNull null
            val meta = listOf(File(paths.linuxDrivers, "$id/meta.json"), File(paths.androidDrivers, "$id/meta.json"))
                .firstOrNull { it.isFile }?.let { runCatching { JSONObject(it.readText()) }.getOrNull() }
            Driver(id, meta?.optString("name")?.ifEmpty { null } ?: id, android, linux)
        }
    }

    /** The Linux driver for the session, or null for the runtime's own Mesa. */
    fun selectedLinux(): String? = prefs.getString("linux", null)?.takeIf { File(paths.linuxDrivers, "$it/icd.json").isFile }
        ?: installed().lastOrNull { it.linux }?.id

    /**
     * The Android driver for the compositor, or null for the phone's system Vulkan driver. Qualcomm's driver can't
     * import gamescope's dma-buf frames, so an installed Turnip is preferred unless the user picked otherwise.
     */
    fun selectedAndroid(): String? = prefs.getString("android", null)?.takeIf { File(paths.androidDrivers, "$it/meta.json").isFile }
        ?: installed().lastOrNull { it.android }?.id

    fun selectLinux(id: String?) = prefs.edit().putString("linux", id).apply()
    fun selectAndroid(id: String?) = prefs.edit().putString("android", id).apply()

    fun linuxIcd(id: String) = "${Paths.GUEST_DRIVERS}/$id/icd.json"
    fun androidDir(id: String): File = File(paths.androidDrivers, id)
    fun androidLibrary(id: String): String? =
        runCatching { JSONObject(File(androidDir(id), "meta.json").readText()).getString("libraryName") }.getOrNull()

    fun downloadLatest(stage: (String, Float) -> Unit): Driver {
        stage("Looking up DD-Turnip", -1f)
        val asset = Http.latestAsset("Droid-Deck/Drivers-CI", Regex("^DD-Turnip-v[0-9.]+\\.zip$"))
            ?: throw IOException("No DD-Turnip release found")
        val zip = File(paths.downloads, asset.name)
        stage("Downloading ${asset.tag}", 0f)
        Http.download(asset.url, zip, null) { stage("Downloading ${asset.tag}", it) }
        return import(zip).also { zip.delete() }
    }

    fun import(zip: File): Driver {
        val entries = ZipFile(zip).use { z -> z.entries().toList().associate { it.name to z.getInputStream(it).use { s -> if (it.size < 65536) s.readBytes() else ByteArray(0) } } }
        fun json(name: String) = entries[name]?.let { JSONObject(String(it)) }
        val manifest = json("manifest.json")
        val id: String
        var android: String? = null
        var linux: String? = null
        if (manifest != null && manifest.has("drivers")) {
            id = manifest.optString("tag").ifEmpty { manifest.optString("package") + "-" + manifest.optString("version") }
            val drivers = manifest.getJSONObject("drivers")
            android = drivers.optJSONObject("android")?.optString("path")
            linux = drivers.optJSONObject("linux")?.optString("path")
        } else {
            val metaName = entries.keys.firstOrNull { it == "meta.json" || it.endsWith("/meta.json") }
                ?: throw IOException("${zip.name} is not a driver package (no meta.json)")
            val meta = json(metaName)!!
            id = meta.optString("driverVersion").ifEmpty { meta.optString("name") }.ifEmpty { zip.nameWithoutExtension }
            val dir = metaName.substringBeforeLast("meta.json").trimEnd('/')
            if (meta.optString("platform") == "linux" || meta.optString("libc") == "glibc") linux = dir else android = dir
        }
        val safeId = id.replace(Regex("[^A-Za-z0-9._-]"), "_")
        if (android != null) installHalf(zip, android, File(paths.androidDrivers, safeId))
        if (linux != null) {
            val dir = File(paths.linuxDrivers, safeId)
            installHalf(zip, linux, dir)
            val library = runCatching { JSONObject(File(dir, "meta.json").readText()).optString("libraryName") }.getOrNull()
                ?.ifEmpty { null } ?: "libvulkan_freedreno.so"
            if (!File(dir, library).isFile) throw IOException("${zip.name}: $library is missing")
            File(dir, library).setExecutable(true, false)
            File(dir, "icd.json").writeText(
                """{"file_format_version":"1.0.1","ICD":{"library_path":"${Paths.GUEST_DRIVERS}/$safeId/$library","api_version":"1.4.0"}}""" + "\n"
            )
            selectLinux(safeId)
        }
        return installed().first { it.id == safeId }
    }

    private fun installHalf(zip: File, prefix: String, target: File) {
        val staging = File(target.parentFile, ".${target.name}.partial")
        staging.deleteRecursively()
        val lead = if (prefix.isEmpty()) "" else "$prefix/"
        Archives.extractZip(zip, staging) { it.startsWith(lead) && !it.endsWith("/") }
        val inner = if (lead.isEmpty()) staging else File(staging, prefix)
        target.deleteRecursively()
        target.parentFile?.mkdirs()
        if (!inner.renameTo(target)) throw IOException("Could not install ${target.name}")
        staging.deleteRecursively()
    }

    fun remove(id: String) {
        File(paths.androidDrivers, id).deleteRecursively()
        File(paths.linuxDrivers, id).deleteRecursively()
    }
}
