package com.ddomobile.launcher.core

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/** Progress is 0..1, or -1 while the size is unknown. */
typealias Progress = (Float) -> Unit

object Http {
    private const val TIMEOUT_MS = 30_000

    private fun open(url: String, range: Long = 0): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = TIMEOUT_MS
        conn.readTimeout = TIMEOUT_MS
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "DDO-Mobile")
        if (range > 0) conn.setRequestProperty("Range", "bytes=$range-")
        return conn
    }

    fun text(url: String): String {
        val conn = open(url)
        try {
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode} from ${URL(url).host}")
            return conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            conn.disconnect()
        }
    }

    /** Downloads to [target], resuming a partial file, and checks [sha256] when given. */
    fun download(url: String, target: File, sha256: String?, progress: Progress) {
        target.parentFile?.mkdirs()
        val partial = File(target.parentFile, target.name + ".part")
        val have = if (partial.isFile) partial.length() else 0L
        val conn = open(url, have)
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code from ${URL(url).host}")
            val resumed = code == 206
            val total = conn.contentLengthLong.let { if (it < 0) -1L else it + if (resumed) have else 0L }
            var done = if (resumed) have else 0L
            conn.inputStream.use { input ->
                java.io.FileOutputStream(partial, resumed).use { out ->
                    val buf = ByteArray(1 shl 16)
                    var last = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (done - last > (1 shl 20)) {
                            last = done
                            progress(if (total > 0) done.toFloat() / total else -1f)
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        if (sha256 != null && !sha256(partial).equals(sha256, ignoreCase = true)) {
            partial.delete()
            throw IOException("${target.name} did not match its checksum")
        }
        target.delete()
        if (!partial.renameTo(target)) throw IOException("Could not save ${target.name}")
    }

    fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
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

    class Asset(val tag: String, val name: String, val url: String, val size: Long)

    /** Newest non-draft release of [repo] with an asset matching [pattern]. */
    fun latestAsset(repo: String, pattern: Regex, includePrereleases: Boolean = false): Asset? {
        val releases = JSONArray(text("https://api.github.com/repos/$repo/releases?per_page=10"))
        for (i in 0 until releases.length()) {
            val release = releases.getJSONObject(i)
            if (release.optBoolean("draft") || (!includePrereleases && release.optBoolean("prerelease"))) continue
            val assets = release.optJSONArray("assets") ?: continue
            for (j in 0 until assets.length()) {
                val a: JSONObject = assets.getJSONObject(j)
                if (pattern.containsMatchIn(a.getString("name"))) {
                    return Asset(release.getString("tag_name"), a.getString("name"), a.getString("browser_download_url"), a.optLong("size"))
                }
            }
        }
        return null
    }
}
