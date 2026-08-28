package com.rubion.nexplaybe.awards

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

data class AwardRecord(
    val wikidataId: String,
    val title: String,
    val awardName: String,
    val result: String,
    val awardYear: Int,
    val steamAppId: Long?,
    val developer: String?,
    val publisher: String?,
    val releaseYear: Int?,
)

/**
 * The Game Awards 수상·후보 이력을 Wikidata 에서 가져온다.
 *
 * 시상 연도는 게임 출시일(P577)이 아니라 수상 진술의 시점 한정자(P585)에서 읽는다.
 * 출시일로 잡으면 재발매판 때문에 엘든 링이 2026년 수상작이 된다.
 */
@Component
class GameAwardClient(
    @param:Value("\${nexplay.awards.timeout-seconds:60}") timeoutSeconds: Long,
) {
    private val timeout = Duration.ofSeconds(timeoutSeconds)
    private val httpClient = HttpClient.newBuilder().connectTimeout(timeout).build()
    private val mapper = jacksonObjectMapper()

    fun fetchGameOfTheYear(): List<AwardRecord> = fetch(GOTY_ENTITY, "The Game Awards Game of the Year")
    fun fetchMostAnticipated(): List<AwardRecord> = fetch(ANTICIPATED_ENTITY, "The Game Awards Most Anticipated Game")

    private fun fetch(awardEntity: String, awardName: String): List<AwardRecord> {
        val query = """
            SELECT ?game ?gameLabel ?kind ?awardYear ?steam ?devLabel ?pubLabel ?releaseYear WHERE {
              { ?game p:P166 ?st . ?st ps:P166 wd:$awardEntity . BIND("WINNER" AS ?kind) }
              UNION
              { ?game p:P1411 ?st . ?st ps:P1411 wd:$awardEntity . BIND("NOMINEE" AS ?kind) }
              OPTIONAL { ?st pq:P585 ?t . BIND(YEAR(?t) AS ?awardYear) }
              OPTIONAL { ?game wdt:P1733 ?steam }
              OPTIONAL { ?game wdt:P178 ?dev }
              OPTIONAL { ?game wdt:P123 ?pub }
              OPTIONAL { ?game wdt:P577 ?rd . BIND(YEAR(?rd) AS ?releaseYear) }
              SERVICE wikibase:label { bd:serviceParam wikibase:language "ko,en". }
            }
        """.trimIndent()
        val request = HttpRequest.newBuilder(
            URI.create("https://query.wikidata.org/sparql?query=" + java.net.URLEncoder.encode(query, Charsets.UTF_8)),
        )
            .timeout(timeout)
            .header("Accept", "application/sparql-results+json")
            .header("User-Agent", "NEXPLAY/0.1 (game discovery; https://github.com/ronypoong/nexplay-be)")
            .GET().build()
        val response = runCatching { httpClient.send(request, HttpResponse.BodyHandlers.ofString()) }.getOrNull()
            ?: return emptyList()
        if (response.statusCode() !in 200..299) return emptyList()

        val seen = mutableSetOf<Triple<String, String, Int>>()
        val records = mutableListOf<AwardRecord>()
        mapper.readTree(response.body()).path("results").path("bindings").forEach { row ->
            val wikidataId = row.path("game").path("value").asText().substringAfterLast('/')
            val year = row.path("awardYear").path("value").asText().toIntOrNull() ?: return@forEach
            val result = row.path("kind").path("value").asText()
            if (!seen.add(Triple(wikidataId, result, year))) return@forEach
            val title = row.path("gameLabel").path("value").asText().takeIf(String::isNotBlank) ?: return@forEach
            // 라벨이 없으면 Wikidata 가 Q번호를 그대로 준다. 화면에 Q64826862 를 띄울 수는 없다.
            if (title.matches(Regex("Q\\d+"))) return@forEach
            records += AwardRecord(
                wikidataId, title, awardName, result, year,
                row.path("steam").path("value").asText().toLongOrNull(),
                row.path("devLabel").path("value").asText().takeIf { it.isNotBlank() && !it.matches(Regex("Q\\d+")) },
                row.path("pubLabel").path("value").asText().takeIf { it.isNotBlank() && !it.matches(Regex("Q\\d+")) },
                row.path("releaseYear").path("value").asText().toIntOrNull(),
            )
        }
        return records
    }

    private companion object {
        const val GOTY_ENTITY = "Q78762377"
        const val ANTICIPATED_ENTITY = "Q68094302"
    }
}
