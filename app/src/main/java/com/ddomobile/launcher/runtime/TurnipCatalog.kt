package com.ddomobile.launcher.runtime

import android.content.Context
import com.ddomobile.launcher.core.Http
import java.io.File
import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject

/**
 * Turnip builds on the release pages DroidDeck offers: The412Banner's Banners-Turnip, WinNative's drivers and
 * DroidDeck's own DD-Turnip bundles. Android builds serve the compositor (the display driver), Linux builds the game
 * (the runtime driver), and a DD-Turnip bundle has both. Kept in files/drivers/catalog.json between refreshes.
 */
class TurnipCatalog(context: Context) {
    private val file = File(context.filesDir, "drivers/catalog.json")

    enum class Half { ANDROID, LINUX, BOTH }

    data class Build(val name: String, val repo: String, val tag: String, val url: String, val size: Long, val sha256: String, val half: Half) {
        /** The driver id an install of this build gets: its file name. */
        val id: String get() = name.removeSuffix(".zip").replace(Regex("[^A-Za-z0-9._-]"), "_")
    }

    class Snapshot(val builds: List<Build>, val fetchedAt: Long)

    fun cached(): Snapshot = runCatching {
        val json = JSONObject(file.readText())
        val list = json.getJSONArray("builds")
        Snapshot((0 until list.length()).map { i ->
            val o = list.getJSONObject(i)
            Build(o.getString("name"), o.getString("repo"), o.getString("tag"), o.getString("url"), o.optLong("size"),
                o.getString("sha256"), Half.valueOf(o.getString("half")))
        }, json.optLong("fetchedAt"))
    }.getOrDefault(Snapshot(emptyList(), 0))

    fun refresh(): Snapshot {
        val found = ArrayList<Build>()
        var reached = 0
        for (repo in REPOS) {
            val releases = runCatching { JSONArray(Http.text("https://api.github.com/repos/$repo/releases?per_page=15")) }.getOrNull() ?: continue
            reached++
            for (i in 0 until releases.length()) {
                val release = releases.getJSONObject(i)
                if (release.optBoolean("draft") || release.optBoolean("prerelease")) continue
                val assets = release.optJSONArray("assets") ?: continue
                for (j in 0 until assets.length()) {
                    val a = assets.getJSONObject(j)
                    val name = a.optString("name")
                    val digest = a.optString("digest")
                    if (!DIGEST.matches(digest)) continue
                    val half = half(repo, name) ?: continue
                    found += Build(name, repo, release.optString("tag_name"), a.getString("browser_download_url"), a.optLong("size"),
                        digest.removePrefix("sha256:"), half)
                }
            }
        }
        if (reached == 0) throw IOException("The driver pages could not be reached")
        val snapshot = Snapshot(found, System.currentTimeMillis() / 1000)
        val list = JSONArray()
        found.forEach {
            list.put(JSONObject().put("name", it.name).put("repo", it.repo).put("tag", it.tag).put("url", it.url).put("size", it.size)
                .put("sha256", it.sha256).put("half", it.half.name))
        }
        file.parentFile?.mkdirs()
        file.writeText(JSONObject().put("builds", list).put("fetchedAt", snapshot.fetchedAt).toString())
        return snapshot
    }

    companion object {
        val REPOS = listOf("Droid-Deck/Drivers-CI", "The412Banner/Banners-Turnip", "WinNative-Emu/Drivers")
        private val DIGEST = Regex("^sha256:[0-9a-f]{64}$")

        /** Which half a release file holds, or null for files that are not drivers DDO Mobile can use. */
        fun half(repo: String, name: String): Half? = when (repo) {
            "Droid-Deck/Drivers-CI" -> if (Regex("^DD-Turnip-v[0-9.]+\\.zip$").matches(name)) Half.BOTH else null
            "The412Banner/Banners-Turnip" -> when {
                !name.startsWith("Turnip-") || !name.endsWith(".zip") || name.endsWith("-Wayland.zip") -> null
                name.endsWith("-Linux.zip") -> Half.LINUX
                else -> Half.ANDROID
            }
            "WinNative-Emu/Drivers" -> when {
                name.startsWith("WN-Linux-Turnip-") && name.endsWith(".zip") -> Half.LINUX
                name.startsWith("WN-Turnip-") && name.endsWith(".zip") -> Half.ANDROID
                else -> null
            }
            else -> null
        }
    }
}
