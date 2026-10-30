package com.ddomobile.launcher.components

import android.content.Context
import com.ddomobile.launcher.core.Http
import java.io.File
import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject

/** The packages on the component release pages, kept in files/components/catalog.json between refreshes. */
class ComponentCatalog(context: Context) {
    private val file = File(context.filesDir, "components/catalog.json")

    class Snapshot(val items: List<CatalogItem>, val fetchedAt: Long)

    fun cached(): Snapshot = runCatching {
        val json = JSONObject(file.readText())
        val list = json.getJSONArray("items")
        Snapshot((0 until list.length()).mapNotNull { item(list.getJSONObject(it)) }, json.optLong("fetchedAt"))
    }.getOrDefault(Snapshot(emptyList(), 0))

    /** Reads every source; one that fails is skipped, and when all fail the cached list stays. */
    fun refresh(): Snapshot {
        val found = ArrayList<CatalogItem>()
        var reached = 0
        for (source in Catalog.SOURCES) {
            val release = runCatching { JSONObject(Http.text("https://api.github.com/repos/${source.repo}/releases/tags/${source.tag}")) }
                .getOrNull() ?: continue
            reached++
            val assets = release.optJSONArray("assets") ?: JSONArray()
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                val name = a.optString("name")
                val digest = a.optString("digest")
                if (!name.endsWith(".wcp") || !DIGEST.matches(digest)) continue
                val kind = source.kind ?: Catalog.nightlyKind(name) ?: continue
                found += CatalogItem(name, kind, source.tag, a.getString("browser_download_url"), a.optLong("size"),
                    digest.removePrefix("sha256:"), source.nightly)
            }
        }
        if (reached == 0) throw IOException("The component pages could not be reached")
        val snapshot = Snapshot(found, System.currentTimeMillis() / 1000)
        file.parentFile?.mkdirs()
        val items = JSONArray()
        found.forEach { items.put(json(it)) }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(JSONObject().put("items", items).put("fetchedAt", snapshot.fetchedAt).toString())
        if (!tmp.renameTo(file)) throw IOException("Could not save the component list")
        return snapshot
    }

    private fun json(i: CatalogItem) = JSONObject().put("file", i.file).put("comp", i.kind.id).put("release", i.release)
        .put("url", i.url).put("size", i.size).put("digest", i.sha256).put("nightly", i.nightly)

    private fun item(o: JSONObject): CatalogItem? {
        val kind = ComponentKind.ofId(o.optString("comp")) ?: return null
        return CatalogItem(o.getString("file"), kind, o.optString("release"), o.getString("url"), o.optLong("size"),
            o.getString("digest"), o.optBoolean("nightly"))
    }

    private companion object {
        val DIGEST = Regex("^sha256:[0-9a-f]{64}$")
    }
}
