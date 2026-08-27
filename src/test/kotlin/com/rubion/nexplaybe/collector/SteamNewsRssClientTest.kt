package com.rubion.nexplaybe.collector

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class SteamNewsRssClientTest {
    @Test
    fun `parses only RSS metadata needed for ingestion`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0"><channel><title>730 RSS Feed</title><item>
              <title><![CDATA[Counter-Strike 2 Update]]></title>
              <link>https://store.steampowered.com/news/app/730/view/123</link>
              <guid>https://store.steampowered.com/news/app/730/view/123</guid>
              <pubDate>Wed, 26 Aug 2026 05:00:00 +0000</pubDate>
              <description><![CDATA[This body must not be persisted by the parser.]]></description>
            </item></channel></rss>
        """.trimIndent().toByteArray()

        val item = SteamNewsRssClient(5).parse(xml).single()

        assertEquals("Counter-Strike 2 Update", item.title)
        assertEquals("https://store.steampowered.com/news/app/730/view/123", item.externalId)
        assertEquals(Instant.parse("2026-08-26T05:00:00Z"), item.publishedAt)
    }
}
