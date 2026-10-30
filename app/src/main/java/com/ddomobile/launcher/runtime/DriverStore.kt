package com.ddomobile.launcher.runtime

import android.content.Context
import com.ddomobile.launcher.core.Archives
import com.ddomobile.launcher.core.Http
import com.ddomobile.launcher.core.Progress
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile
import org.json.JSONObject

/**
 * Turnip drivers. A driver can have two halves: an Android (bionic) build the app's compositor loads through
 * adrenotools, and a Linux (glibc) build that gamescope, DXVK and the game use inside the runtime.
 * Imports DD-Turnip bundles (manifest.json with both), adrenotools zips (meta.json) and Linux zips (a glibc
 * libvulkan_freedreno, with or without meta.json).
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

    /** The Linux driver for the session, or null for the runtime's own Mesa. An empty choice is that, picked on purpose. */
    fun selectedLinux(): String? {
        val pick = prefs.getString("linux", null)
        if (pick == "") return null
        return pick?.takeIf { File(paths.linuxDrivers, "$it/icd.json").isFile } ?: installed().lastOrNull { it.linux }?.id
    }

    /**
     * The Android driver for the compositor, or null for the phone's system Vulkan driver. Qualcomm's driver can't
     * import gamescope's dma-buf frames, so an installed Turnip is preferred unless the user picked otherwise.
     */
    fun selectedAndroid(): String? {
        val pick = prefs.getString("android", null)
        if (pick == "") return null
        return pick?.takeIf { File(paths.androidDrivers, "$it/meta.json").isFile } ?: installed().lastOrNull { it.android }?.id
    }

    /** null picks the runtime's own Mesa. */
    fun selectLinux(id: String?) = prefs.edit().putString("linux", id ?: "").apply()

    /** null picks the phone's system driver. */
    fun selectAndroid(id: String?) = prefs.edit().putString("android", id ?: "").apply()

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

    /** A build from [TurnipCatalog], checked against its published SHA-256. */
    fun download(build: TurnipCatalog.Build, progress: Progress): Driver {
        val zip = File(paths.downloads, build.name)
        Http.download(build.url, zip, build.sha256, progress)
        return try { import(zip, build.id) } finally { zip.delete() }
    }

    /** Installs the halves [zip] has and makes them the ones in use. [id] names the driver, else its own metadata does. */
    fun import(zip: File, id: String? = null): Driver {
        var name: String
        var android: String? = null
        var linux: String? = null
        ZipFile(zip).use { z ->
            val names = z.entries().toList().filterNot { it.isDirectory }.map { it.name }
            fun bytes(entry: String): ByteArray? = z.getEntry(entry)?.let { e -> z.getInputStream(e).use { it.readBytes() } }
            fun json(entry: String) = bytes(entry)?.let { runCatching { JSONObject(String(it)) }.getOrNull() }
            val manifest = json("manifest.json")
            if (manifest != null && manifest.has("drivers")) {
                name = manifest.optString("tag").ifEmpty { manifest.optString("package") + "-" + manifest.optString("version") }
                val drivers = manifest.getJSONObject("drivers")
                android = drivers.optJSONObject("android")?.optString("path")
                linux = drivers.optJSONObject("linux")?.optString("path")
            } else {
                val metaName = names.firstOrNull { it == "meta.json" || it.endsWith("/meta.json") }
                val meta = metaName?.let(::json)
                val dir = metaName?.substringBeforeLast("meta.json")?.trimEnd('/')
                    ?: names.firstOrNull { isTurnipLibrary(it) }?.substringBeforeLast('/', "")
                    ?: throw IOException("${zip.name} is not a driver package (no meta.json or libvulkan_freedreno)")
                val library = meta?.optString("libraryName")?.ifEmpty { null }?.let { if (dir.isEmpty()) it else "$dir/$it" }
                    ?.takeIf { it in names } ?: names.firstOrNull { it.startsWith(if (dir.isEmpty()) "" else "$dir/") && isTurnipLibrary(it) }
                    ?: names.firstOrNull { it.startsWith(if (dir.isEmpty()) "" else "$dir/") && it.endsWith(".so") }
                    ?: throw IOException("${zip.name} has no driver library")
                val glibc = meta?.optString("platform") == "linux" || meta?.optString("libc") == "glibc" || linksGlibc(bytes(library))
                name = meta?.optString("driverVersion")?.ifEmpty { null } ?: meta?.optString("name")?.ifEmpty { null } ?: zip.nameWithoutExtension
                if (glibc) linux = dir
                else if (meta != null) android = dir
                else throw IOException("${zip.name} is an Android driver without meta.json, which adrenotools needs")
            }
        }
        val safeId = (id ?: name).replace(Regex("[^A-Za-z0-9._-]"), "_")
        android?.let {
            installHalf(zip, it, File(paths.androidDrivers, safeId))
            selectAndroid(safeId)
        }
        linux?.let {
            val dir = File(paths.linuxDrivers, safeId)
            installHalf(zip, it, dir)
            val metaFile = File(dir, "meta.json")
            val library = runCatching { JSONObject(metaFile.readText()).optString("libraryName") }.getOrNull()?.ifEmpty { null }
                ?.takeIf { lib -> File(dir, lib).isFile }
                ?: dir.list()?.firstOrNull { f -> isTurnipLibrary(f) }
                ?: throw IOException("${zip.name}: no libvulkan_freedreno in its Linux driver")
            if (!metaFile.isFile) metaFile.writeText(JSONObject().put("name", name).put("libraryName", library).put("platform", "linux").toString())
            File(dir, library).setExecutable(true, false)
            File(dir, "icd.json").writeText(
                """{"file_format_version":"1.0.1","ICD":{"library_path":"${Paths.GUEST_DRIVERS}/$safeId/$library","api_version":"1.4.0"}}""" + "\n"
            )
            selectLinux(safeId)
        }
        return installed().first { it.id == safeId }
    }

    private fun isTurnipLibrary(path: String) = path.substringAfterLast('/').let { it.startsWith("libvulkan_freedreno") && it.contains(".so") }

    /** A Linux (glibc) build needs libc.so.6; Android's bionic libraries link libc.so. */
    private fun linksGlibc(elf: ByteArray?): Boolean {
        if (elf == null || elf.size < 20 || elf[0] != 0x7f.toByte() || elf[1] != 'E'.code.toByte()) return false
        return String(elf, Charsets.ISO_8859_1).contains("libc.so.6")
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
        if (prefs.getString("linux", null) == id) prefs.edit().remove("linux").apply()
        if (prefs.getString("android", null) == id) prefs.edit().remove("android").apply()
    }
}
