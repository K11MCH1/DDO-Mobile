package com.ddomobile.launcher.ddo

import com.ddomobile.launcher.core.Http
import java.io.File
import java.io.IOException
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * The news the official launcher shows beside its login: the RSS feed its server configuration names as
 * URL.NewsFeed. Each item's description is a little HTML (paragraphs, bold, line breaks, links).
 */
object DdoNews {
    const val DEFAULT_FEED = "https://www.ddo.com/en/launcher-feed.xml"

    data class Item(val title: String, val link: String, val date: String, val html: String)

    /** The feed the launcher configuration names, or its known address when that can't be read. */
    fun feedUrl(): String = runCatching {
        DdoGls.launcherConfig(DdoGls.secure(DdoGls.datacenter().launcherConfigUrl)).values["URL.NewsFeed"]
    }.getOrNull()?.takeIf { it.startsWith("https://") || it.startsWith("http://") }?.let(DdoGls::secure) ?: DEFAULT_FEED

    /** Reads the feed and keeps it in [cache] for the next start. */
    fun fetch(cache: File): List<Item> {
        val xml = Http.text(feedUrl())
        val items = parse(xml)
        cache.parentFile?.mkdirs()
        val tmp = File(cache.path + ".tmp")
        tmp.writeText(xml)
        tmp.renameTo(cache)
        return items
    }

    fun cached(cache: File): List<Item> = runCatching { parse(cache.readText()) }.getOrDefault(emptyList())

    internal fun parse(xml: String): List<Item> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            // Android's parser never resolves external entities and rejects these feature names.
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        }
        val doc = try {
            factory.newDocumentBuilder().parse(xml.byteInputStream(Charsets.UTF_8))
        } catch (e: Exception) {
            throw IOException("The news feed is unreadable", e)
        }
        val items = doc.getElementsByTagName("item")
        return (0 until items.length).map { i ->
            val item = items.item(i) as Element
            fun child(name: String) = item.getElementsByTagName(name).item(0)?.textContent?.trim().orEmpty()
            Item(child("title"), child("link"), child("pubDate"), child("description"))
        }.filter { it.title.isNotEmpty() }
    }
}
