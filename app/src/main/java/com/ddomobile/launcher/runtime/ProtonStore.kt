package com.ddomobile.launcher.runtime

import android.content.Context
import com.ddomobile.launcher.core.Archives
import com.ddomobile.launcher.core.Http
import java.io.File
import java.io.IOException

/** ARM64 Protons (Wine with FEX for x86 code) unpacked under the runtime's /opt/proton. */
class ProtonStore(context: Context) {
    private val paths = Paths(context)
    private val prefs = context.getSharedPreferences("proton", Context.MODE_PRIVATE)

    class Source(val id: String, val label: String, val repo: String, val asset: Regex)

    fun installed(): List<String> = paths.proton.listFiles { f -> File(f, "proton").isFile }
        .orEmpty().map { it.name }.sortedWith(compareBy(::versionKey))

    fun selected(): String? = prefs.getString("selected", null)?.takeIf { it in installed() } ?: installed().lastOrNull()

    fun select(name: String) = prefs.edit().putString("selected", name).apply()

    fun guestPath(name: String) = "${Paths.GUEST_PROTON}/$name"

    fun install(source: Source, stage: (String, Float) -> Unit): String {
        stage("Looking up ${source.label}", -1f)
        val asset = Http.latestAsset(source.repo, source.asset) ?: throw IOException("No ${source.label} release found")
        val name = asset.tag
        if (name in installed()) return name.also(::select)
        val archive = File(paths.downloads, asset.name)
        val sums = runCatching { Http.text(asset.url.replace(Regex("\\.tar\\.(gz|xz|zst)$"), ".sha512sum")) }.getOrNull()
        stage("Downloading ${asset.tag}", 0f)
        Http.download(asset.url, archive, null) { stage("Downloading ${asset.tag}", it) }
        if (sums != null) {
            val want = sums.trim().split(Regex("\\s+")).firstOrNull()
            if (want != null && want.length == 128 && sha512(archive) != want.lowercase()) {
                archive.delete()
                throw IOException("${asset.name} did not match its checksum")
            }
        }
        val target = File(paths.proton, name)
        val staging = File(paths.proton, ".$name.partial")
        staging.deleteRecursively()
        stage("Unpacking ${asset.tag}", 0f)
        Archives.extractTar(archive, staging, stripComponents = 1) { stage("Unpacking ${asset.tag}", it) }
        if (!File(staging, "proton").isFile) throw IOException("${asset.name} is not a Proton build")
        target.deleteRecursively()
        if (!staging.renameTo(target)) throw IOException("Could not install $name")
        archive.delete()
        select(name)
        return name
    }

    fun remove(name: String) {
        File(paths.proton, name).deleteRecursively()
        if (prefs.getString("selected", null) == name) prefs.edit().remove("selected").apply()
    }

    private fun sha512(file: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-512")
        file.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        val DROIDDECK = Source("droiddeck", "DroidDeck-Proton", "Droid-Deck/DroidDeck-Proton", Regex("aarch64\\.tar\\.(gz|xz|zst)$"))
        val GE = Source("ge", "GE-Proton (ARM64)", "GloriousEggroll/proton-ge-custom", Regex("aarch64\\.tar\\.(gz|xz|zst)$"))
        val SOURCES = listOf(DROIDDECK, GE)

        private val NUMBER = Regex("\\d+")
        fun versionKey(name: String): String =
            NUMBER.replace(name) { it.value.padStart(8, '0') }
    }
}
