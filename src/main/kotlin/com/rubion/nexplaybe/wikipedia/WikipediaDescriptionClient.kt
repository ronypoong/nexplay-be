package com.rubion.nexplaybe.wikipedia

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

data class WikipediaArticle(val wikidataId: String, val language: String, val title: String, val extract: String) {
    /** CC BY-SA 라 출처를 남길 수 있어야 한다. */
    val url: String get() = "https://$language.wikipedia.org/wiki/" + URLEncoder.encode(title.replace(' ', '_'), StandardCharsets.UTF_8)
}

/**
 * Steam 스토어 페이지가 없는 게임의 소개를 채우는 2차 출처.
 *
 * 제목을 추측하지 않고 Wikidata 의 sitelinks 를 따라간다 — "그랜드 테프트 오토 6" 처럼
 * 번역 제목을 찍어 맞히려 들면 빈 결과가 나온다. 한국어 문서를 우선하고 없으면 영어로 간다.
 */
@Component
class WikipediaDescriptionClient(
    @param:Value("\${nexplay.wikipedia.timeout-seconds:20}") timeoutSeconds: Long,
) {
    private val timeout = Duration.ofSeconds(timeoutSeconds)
    private val httpClient = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NORMAL).build()
    private val mapper = jacksonObjectMapper()

    fun fetch(wikidataIds: Collection<String>): List<WikipediaArticle> {
        val ids = wikidataIds.filter { it.matches(Regex("Q\\d+")) }.distinct()
        if (ids.isEmpty()) return emptyList()
        val sitelinks = ids.chunked(SITELINK_BATCH).flatMap { fetchSitelinks(it) }
        // 한국어 문서가 있으면 그것만 쓴다. 같은 게임을 두 언어로 두 번 받을 이유가 없다.
        val byLanguage = sitelinks.groupBy({ it.language }, { it })
        return byLanguage.flatMap { (language, entries) ->
            entries.chunked(EXTRACT_BATCH).flatMap { chunk -> fetchExtracts(language, chunk) }
        }
    }

    private data class Sitelink(val wikidataId: String, val language: String, val title: String)

    private fun fetchSitelinks(ids: List<String>): List<Sitelink> {
        val url = "https://www.wikidata.org/w/api.php?action=wbgetentities&format=json&props=sitelinks" +
            "&sitefilter=kowiki%7Cenwiki&ids=" + ids.joinToString("%7C")
        val body = get(url) ?: return emptyList()
        val entities = mapper.readTree(body).path("entities")
        return ids.mapNotNull { id ->
            val links = entities.path(id).path("sitelinks")
            val ko = links.path("kowiki").path("title").asText().takeIf(String::isNotBlank)
            val en = links.path("enwiki").path("title").asText().takeIf(String::isNotBlank)
            when {
                ko != null -> Sitelink(id, "ko", ko)
                en != null -> Sitelink(id, "en", en)
                else -> null
            }
        }
    }

    private fun fetchExtracts(language: String, entries: List<Sitelink>): List<WikipediaArticle> {
        val titles = entries.joinToString("%7C") { encode(it.title) }
        val url = "https://$language.wikipedia.org/w/api.php?action=query&format=json&formatversion=2" +
            "&prop=extracts&exintro=1&explaintext=1&redirects=1&titles=$titles"
        val body = get(url) ?: return emptyList()
        val byTitle = mutableMapOf<String, String>()
        mapper.readTree(body).path("query").path("pages").forEach { page ->
            val extract = page.path("extract").asText().trim()
            if (extract.isNotBlank()) byTitle[page.path("title").asText()] = extract.take(MAX_LENGTH)
        }
        // redirects 때문에 요청 제목과 응답 제목이 다를 수 있어 정규화해 맞춘다.
        val normalized = byTitle.mapKeys { it.key.normalizeTitle() }
        return entries.mapNotNull { entry ->
            val extract = byTitle[entry.title] ?: normalized[entry.title.normalizeTitle()] ?: return@mapNotNull null
            WikipediaArticle(entry.wikidataId, language, entry.title, extract)
        }
    }

    private fun String.normalizeTitle() = lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), "")

    private fun get(url: String): String? {
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(timeout)
            .header("Accept", "application/json")
            .header("User-Agent", "NEXPLAY/0.1 (game discovery; https://github.com/ronypoong/nexplay-be)")
            .GET().build()
        val response = runCatching { httpClient.send(request, HttpResponse.BodyHandlers.ofString()) }.getOrNull() ?: return null
        return if (response.statusCode() in 200..299) response.body() else null
    }

    private fun encode(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")

    private companion object {
        const val SITELINK_BATCH = 50
        const val EXTRACT_BATCH = 20
        const val MAX_LENGTH = 2_000
    }
}
