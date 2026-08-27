package com.rubion.nexplaybe.collector

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.xml.parsers.DocumentBuilderFactory

data class SteamNewsItem(val externalId: String, val title: String, val url: String, val publishedAt: Instant, val summary: String = "")

@Component
class SteamNewsRssClient(
    @Value("\${nexplay.collector.steam.timeout-seconds:15}") timeoutSeconds: Long,
) {
    private val timeout = Duration.ofSeconds(timeoutSeconds)
    private val httpClient = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NORMAL).build()

    fun fetch(feedUrl: String): List<SteamNewsItem> {
        require(feedUrl.startsWith("https://store.steampowered.com/feeds/news/app/")) { "Feed URL is outside the Steam allowlist" }
        val localizedFeedUrl = feedUrl + if (feedUrl.contains('?')) "&l=koreana" else "?l=koreana"
        val request = HttpRequest.newBuilder(URI.create(localizedFeedUrl))
            .timeout(timeout)
            .header("Accept", "application/rss+xml, application/xml;q=0.9")
            .header("User-Agent", "Mozilla/5.0 (compatible; NEXPLAY/0.1; +https://github.com/rubi-on)")
            .GET()
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray())
        check(response.statusCode() in 200..299) { "Steam RSS returned HTTP ${response.statusCode()}" }
        return parse(response.body())
    }

    internal fun parse(xml: ByteArray): List<SteamNewsItem> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            isExpandEntityReferences = false
            isXIncludeAware = false
        }
        val document = factory.newDocumentBuilder().parse(ByteArrayInputStream(xml))
        return (0 until document.getElementsByTagName("item").length).mapNotNull { index ->
            val element = document.getElementsByTagName("item").item(index) as? Element ?: return@mapNotNull null
            val title = element.text("title").trim()
            val url = element.text("link").trim()
            val guid = element.text("guid").trim().ifBlank { url }
            val summary = element.text("description").toPlainText().take(600)
            if (title.isBlank() || url.isBlank()) return@mapNotNull null
            val publishedAt = runCatching {
                ZonedDateTime.parse(element.text("pubDate").trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
            }.getOrElse { Instant.now() }
            SteamNewsItem(guid, title.take(500), url, publishedAt, summary)
        }.take(20)
    }

    private fun Element.text(tag: String): String = getElementsByTagName(tag).item(0)?.textContent.orEmpty()
    private fun String.toPlainText(): String = replace(Regex("<[^>]+>"), " ")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&quot;", "\"")
        .replace("&#39;", "'").replace(Regex("\\s+"), " ").trim()
}
