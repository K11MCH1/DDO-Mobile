package com.ddomobile.launcher.ddo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DdoNewsTest {
    // Two items as the launcher feed serves them: the description is HTML, escaped inside the XML.
    private val feed = """<?xml version="1.0" encoding="utf-8" ?>
        <rss version="2.0" xmlns:dc="http://purl.org/dc/elements/1.1/">
          <channel>
            <title>Launcher Feed</title>
            <item>
              <title>DDO Bonus Days!</title>
              <link>https://www.ddo.com/news/ddo-bonus-102926?locale=en_US</link>
              <description>&lt;p&gt;Get +5% XP for every member in your party!&lt;/p&gt;</description>
              <pubDate>Thu, 29 Oct 2026 10:00:00 -0700</pubDate>
              <dc:creator>Standing Stone Games</dc:creator>
            </item>
            <item>
              <title>Sales: October 29th - November 5th</title>
              <link>https://www.ddo.com/news/ddo-sales-102926?locale=en_US</link>
              <description>&lt;p&gt;&lt;strong&gt;A coffer&lt;/strong&gt;&lt;br /&gt;&lt;a href=&quot;https://www.ddo.com/news/x&quot;&gt;Learn more&lt;/a&gt;&lt;/p&gt;</description>
              <pubDate>Thu, 29 Oct 2026 08:00:00 -0700</pubDate>
            </item>
          </channel>
        </rss>"""

    @Test
    fun itemsComeInFeedOrder() {
        val items = DdoNews.parse(feed)
        assertEquals(listOf("DDO Bonus Days!", "Sales: October 29th - November 5th"), items.map { it.title })
        assertEquals("Thu, 29 Oct 2026 10:00:00 -0700", items[0].date)
        assertEquals("https://www.ddo.com/news/ddo-bonus-102926?locale=en_US", items[0].link)
    }

    @Test
    fun descriptionsStayHtml() {
        val html = DdoNews.parse(feed)[1].html
        assertTrue(html.startsWith("<p><strong>A coffer</strong>"))
        assertTrue(html.contains("""<a href="https://www.ddo.com/news/x">Learn more</a>"""))
    }

    @Test(expected = java.io.IOException::class)
    fun garbageIsAnError() {
        DdoNews.parse("<html><body>maintenance")
    }
}
