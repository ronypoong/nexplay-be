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

data class SteamNewsItem(
    val externalId: String,
    val title: String,
    val url: String,
    val publishedAt: Instant,
    /** 화면용 짧은 요약. */
    val summary: String = "",
    /**
     * 원문 본문. Steam RSS 는 과거 글을 무한정 주지 않는다 — 오늘 안 받아두면
     * 다시 받을 수 없다. 나중에 분류를 다시 하거나 다른 것을 뽑아내려면
     * 원문이 남아 있어야 한다.
     */
    val body: String = "",
)

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
            val body = element.text("description").toPlainText().take(MAX_BODY_LENGTH)
            val summary = body.take(600)
            if (title.isBlank() || url.isBlank()) return@mapNotNull null
            // 예전에는 파싱 실패 시 Instant.now() 를 넣어 몇 년 전 뉴스가 "방금 전" 으로 뜨고
            // 이벤트 병합 기준일(±1일)까지 어긋났다. 날짜를 지어내느니 건너뛰고 다음 수집에서 다시 시도한다.
            val publishedAt = parsePublishedAt(element.text("pubDate").trim()) ?: return@mapNotNull null
            SteamNewsItem(guid, title.take(500), url, publishedAt, summary, body)
        }.take(20)
    }

    private fun parsePublishedAt(raw: String): Instant? {
        if (raw.isBlank()) return null
        return DATE_FORMATS.firstNotNullOfOrNull { format ->
            runCatching { ZonedDateTime.parse(raw, format).toInstant() }.getOrNull()
        }
    }

    private fun Element.text(tag: String): String = getElementsByTagName(tag).item(0)?.textContent.orEmpty()
    private fun String.toPlainText(): String = replace(Regex("<[^>]+>"), " ")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&quot;", "\"")
        .replace("&#39;", "'").replace(Regex("\\s+"), " ").trim()

    private companion object {
        // 원문 보관용이라 넉넉히 잡는다. raw_payload 는 LONGTEXT 다.
        const val MAX_BODY_LENGTH = 200_000
        val DATE_FORMATS = listOf(
            DateTimeFormatter.RFC_1123_DATE_TIME,
            DateTimeFormatter.ISO_ZONED_DATE_TIME,
            DateTimeFormatter.ISO_OFFSET_DATE_TIME,
        )
    }
}
