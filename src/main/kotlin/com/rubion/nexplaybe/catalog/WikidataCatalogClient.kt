package com.rubion.nexplaybe.catalog

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
import java.time.LocalDate
import org.slf4j.LoggerFactory

data class CatalogCompanyRef(val wikidataId: String?, val name: String)
data class CatalogGameItem(
    val wikidataId: String,
    val title: String,
    val releaseDate: LocalDate,
    val officialUrl: String?,
    val steamAppId: Long?,
    val imageUrl: String?,
    val imageSource: String? = null,
    val developers: List<CatalogCompanyRef>,
    val publishers: List<CatalogCompanyRef>,
    val genres: Set<String> = emptySet(),
    val platforms: Set<String> = emptySet(),
    val gameModes: Set<String> = emptySet(),
)

data class CatalogClassification(val genres: Set<String>, val platforms: Set<String>, val gameModes: Set<String>)
data class CatalogRelation(val gameId: String, val relatedId: String, val relatedTitle: String, val type: String)

@Component
class WikidataCatalogClient(
    @Value("\${nexplay.catalog.wikidata.timeout-seconds:60}") timeoutSeconds: Long,
    @Value("\${nexplay.catalog.wikidata.max-rows:500}") private val maxRows: Int,
) {
    private val timeout = Duration.ofSeconds(timeoutSeconds)
    private val httpClient = HttpClient.newBuilder().connectTimeout(timeout).build()
    private val objectMapper = jacksonObjectMapper()
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 한 해를 달 단위로 나눠 받는다.
     *
     * 분기로 나눠 받고 있었는데 1분기가 상한(500행)에 걸려 3월 13일에서 잘렸다.
     * 그 뒤에 나오는 게임은 통째로 안 보인다 — 붉은사막(3월 19일)이 딱 그 선
     * 너머에 있었다.
     *
     * 달로 나누면 한 창이 작아져 상한에 닿지 않는다. 요청 수는 네 배가 되지만
     * Wikidata 는 무료이고, 못 가져온 게임은 어디서도 메울 수 없다.
     *
     * 상한에 닿으면 그 달은 잘린 것이므로 기록을 남긴다. 조용히 잘리는 것이
     * 이 일에서 가장 나쁘다.
     */
    /**
     * 몇 건을 받았는지만 남기면, 아무것도 안 들어온 날에 "Wikidata 에 없어서"인지
     * "우리가 못 받아서"인지 구분할 수 없다. 실패한 달을 같이 돌려준다.
     */
    data class YearFetch(val items: List<CatalogGameItem>, val failedMonths: List<String>)

    fun fetchReleaseYear(year: Int): YearFetch {
        val failed = mutableListOf<String>()
        val items = (1..12)
            .flatMap { month ->
                if (month > 1) runCatching { Thread.sleep(RANGE_REQUEST_INTERVAL_MS) }
                val start = LocalDate.of(year, month, 1)
                runCatching { fetchRange(start, start.plusMonths(1)) }
                    .onFailure {
                        failed += "%d-%02d: %s".format(year, month, it.message)
                        log.warn("{}년 {}월 카탈로그를 못 받았습니다: {}", year, month, it.message)
                    }
                    .getOrDefault(emptyList())
            }
            .distinctBy { it.wikidataId }
            .distinctBy { it.steamAppId?.let { id -> "steam:$id" } ?: "wikidata:${it.wikidataId}" }
        return YearFetch(items, failed)
    }

    fun fetchClassifications(wikidataIds: Collection<String>): Map<String, CatalogClassification> {
        val chunks = wikidataIds.filter { it.matches(Regex("Q\\d+")) }.distinct().chunked(40)
        return chunks.flatMapIndexed { index, chunk ->
            if (index > 0) Thread.sleep(CLASSIFICATION_REQUEST_INTERVAL_MS)
            // 장르·플랫폼은 있으면 좋은 것이지 없으면 안 되는 것이 아니다.
            // 한 덩어리가 실패했다고 그 해의 수집 전체를 버릴 이유는 없다.
            runCatching { fetchClassificationChunk(chunk) }
                .onFailure { log.warn("분류 {}건을 못 받았습니다: {}", chunk.size, it.message) }
                .getOrDefault(emptyList())
        }.associate { it.first to it.second }
    }

    fun fetchRelations(wikidataIds: Collection<String>): List<CatalogRelation> = wikidataIds
        .filter { it.matches(Regex("Q\\d+")) }.distinct().chunked(40).flatMapIndexed { index, ids ->
            if (index > 0) Thread.sleep(CLASSIFICATION_REQUEST_INTERVAL_MS)
            val values = ids.joinToString(" ") { "wd:$it" }
            val query = """
                SELECT ?game ?related ?relatedLabel ?type WHERE {
                  VALUES ?game { $values }
                  { ?game wdt:P155 ?related. BIND("PREQUEL" AS ?type) }
                  UNION
                  { ?game wdt:P156 ?related. BIND("SEQUEL" AS ?type) }
                  SERVICE wikibase:label { bd:serviceParam wikibase:language "ko,en,ja,mul". }
                }
            """.trimIndent()
            val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8)
            val request = HttpRequest.newBuilder(URI.create("https://query.wikidata.org/sparql?query=$encoded&format=json"))
                .timeout(timeout).header("Accept", "application/sparql-results+json")
                .header("User-Agent", "NEXPLAY/0.1 (game relation sync; https://github.com/rubi-on)").GET().build()
            val response = sendClassificationRequest(request)
            check(response.statusCode() in 200..299) { "Wikidata relation query failed: HTTP ${response.statusCode()}" }
            objectMapper.readTree(response.body()).path("results").path("bindings").mapNotNull { binding ->
                val gameId = binding.path("game").path("value").asText().substringAfterLast('/')
                val relatedId = binding.path("related").path("value").asText().substringAfterLast('/')
                val title = binding.path("relatedLabel").path("value").asText()
                val type = binding.path("type").path("value").asText()
                if (gameId.matches(Regex("Q\\d+")) && relatedId.matches(Regex("Q\\d+")) && title.isNotBlank() && !title.matches(Regex("Q\\d+"))) CatalogRelation(gameId, relatedId, title, type) else null
            }
        }

    private fun fetchClassificationChunk(ids: List<String>): List<Pair<String, CatalogClassification>> {
        if (ids.isEmpty()) return emptyList()
        val values = ids.joinToString(" ") { "wd:$it" }
        val query = """
            SELECT ?game ?genreLabel ?platformLabel ?modeLabel WHERE {
              VALUES ?game { $values }
              OPTIONAL { ?game wdt:P136 ?genre. }
              OPTIONAL { ?game wdt:P400 ?platform. }
              OPTIONAL { ?game wdt:P404 ?mode. }
              SERVICE wikibase:label { bd:serviceParam wikibase:language "ko,en,ja,mul". }
            }
        """.trimIndent()
        val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8)
        val request = HttpRequest.newBuilder(URI.create("https://query.wikidata.org/sparql?query=$encoded&format=json"))
            .timeout(timeout)
            .header("Accept", "application/sparql-results+json")
            .header("User-Agent", "NEXPLAY/0.1 (game classification sync; https://github.com/rubi-on)")
            .GET()
            .build()
        val response = sendClassificationRequest(request)
        check(response.statusCode() in 200..299) { "Wikidata classification query failed: HTTP ${response.statusCode()}" }
        data class Builder(val genres: MutableSet<String> = linkedSetOf(), val platforms: MutableSet<String> = linkedSetOf(), val modes: MutableSet<String> = linkedSetOf())
        val grouped = ids.associateWith { Builder() }.toMutableMap()
        objectMapper.readTree(response.body()).path("results").path("bindings").forEach { binding ->
            val id = binding.path("game").path("value").asText().substringAfterLast('/')
            val builder = grouped[id] ?: return@forEach
            binding.path("genreLabel").path("value").asText().takeIf(String::isNotBlank)?.let(::canonicalGenre)?.let(builder.genres::add)
            binding.path("platformLabel").path("value").asText().takeIf(String::isNotBlank)?.let(::canonicalPlatform)?.let(builder.platforms::add)
            binding.path("modeLabel").path("value").asText().takeIf(String::isNotBlank)?.let(::canonicalGameMode)?.let(builder.modes::add)
        }
        return grouped.map { (id, value) -> id to CatalogClassification(value.genres, value.platforms, value.modes) }
    }

    /**
     * 잠깐 막힌 것과 정말 안 되는 것을 가른다.
     *
     * Wikidata 는 몰아치면 502·503·429 를 돌려준다. 그건 "지금은 말고" 라는 뜻이지
     * "그런 데이터는 없다" 가 아니다. 한 번 쉬고 다시 물으면 대개 온다.
     */
    private fun sendWithRetry(request: HttpRequest): HttpResponse<String> {
        var last: HttpResponse<String>? = null
        repeat(RETRY_ATTEMPTS) { attempt ->
            if (attempt > 0) runCatching { Thread.sleep(RETRY_BACKOFF_MS * attempt) }
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() in 200..299) return response
            if (response.statusCode() !in RETRYABLE) return response
            log.warn("Wikidata 가 HTTP {} 를 돌려줬습니다. {}번째 시도", response.statusCode(), attempt + 1)
            last = response
        }
        return requireNotNull(last)
    }

    private fun fetchRange(start: LocalDate, endExclusive: LocalDate): List<CatalogGameItem> {
        val query = """
            SELECT ?game ?gameLabel ?date ?developer ?developerLabel ?publisher ?publisherLabel ?officialWebsite ?steamAppId ?image WHERE {
              { SELECT ?game (MIN(?published) AS ?date) WHERE {
                  ?game wdt:P31 wd:Q7889; wdt:P577 ?published.
                } GROUP BY ?game
                HAVING(MIN(?published) >= "${start}T00:00:00Z"^^xsd:dateTime &&
                       MIN(?published) < "${endExclusive}T00:00:00Z"^^xsd:dateTime)
              }
              OPTIONAL { ?game wdt:P178 ?developer. }
              OPTIONAL { ?game wdt:P123 ?publisher. }
              OPTIONAL { ?game wdt:P856 ?officialWebsite. }
              OPTIONAL { ?game wdt:P1733 ?steamAppId. }
              OPTIONAL { ?game wdt:P18 ?image. }
              FILTER(BOUND(?officialWebsite) || BOUND(?steamAppId))
              SERVICE wikibase:label { bd:serviceParam wikibase:language "ko,en,ja,mul". }
            } ORDER BY ?date LIMIT $maxRows
        """.trimIndent()
        val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8)
        val request = HttpRequest.newBuilder(URI.create("https://query.wikidata.org/sparql?query=$encoded&format=json"))
            .timeout(timeout)
            .header("Accept", "application/sparql-results+json")
            .header("User-Agent", "NEXPLAY/0.1 (2026 game catalog sync; https://github.com/rubi-on)")
            .GET()
            .build()
        val response = sendWithRetry(request)
        check(response.statusCode() in 200..299) { "Wikidata query failed: HTTP ${response.statusCode()}" }

        data class Builder(
            val id: String,
            var title: String = "",
            var date: LocalDate? = null,
            var officialUrl: String? = null,
            var steamAppId: Long? = null,
            var imageUrl: String? = null,
            val developers: LinkedHashMap<String, CatalogCompanyRef> = linkedMapOf(),
            val publishers: LinkedHashMap<String, CatalogCompanyRef> = linkedMapOf(),
        )

        val grouped = linkedMapOf<String, Builder>()
        objectMapper.readTree(response.body()).path("results").path("bindings").forEach { binding ->
            val gameUri = binding.path("game").path("value").asText()
            val gameId = gameUri.substringAfterLast('/')
            val title = binding.path("gameLabel").path("value").asText()
            val dateText = binding.path("date").path("value").asText()
            if (!gameId.matches(Regex("Q\\d+")) || title.isBlank() || title.matches(Regex("Q\\d+"))) return@forEach
            val date = runCatching { LocalDate.parse(dateText.take(10)) }.getOrNull() ?: return@forEach
            val builder = grouped.getOrPut(gameId) { Builder(gameId) }
            builder.title = title
            builder.date = date
            binding.path("officialWebsite").path("value").asText().takeIf { it.startsWith("http") }?.let { builder.officialUrl = it }
            binding.path("steamAppId").path("value").asText().toLongOrNull()?.let { builder.steamAppId = it }
            binding.path("image").path("value").asText().takeIf { it.startsWith("http") }?.let { builder.imageUrl = it.replaceFirst("http://", "https://") }
            addCompany(binding, "developer", "developerLabel", builder.developers)
            addCompany(binding, "publisher", "publisherLabel", builder.publishers)
        }
        return grouped.values.mapNotNull { value ->
            value.date?.let {
                CatalogGameItem(
                    wikidataId = value.id,
                    title = value.title,
                    releaseDate = it,
                    officialUrl = value.officialUrl,
                    steamAppId = value.steamAppId,
                    imageUrl = value.imageUrl,
                    imageSource = value.imageUrl?.let { "WIKIMEDIA_COMMONS" },
                    developers = value.developers.values.toList(),
                    publishers = value.publishers.values.toList(),
                )
            }
        }
            // 예전에는 여기서 steamAppId 를 필수로 요구했다. SPARQL 은 "공식 웹사이트 또는
            // 스팀"이면 통과시켜 놓고, 코틀린이 다시 스팀만 남긴 것이다. 그 결과 플레이스테이션·
            // 닌텐도 독점작이 통째로 빠졌다 — 2026~2027년만 61개. 유튜브에 도배되는 대작이
            // 정작 우리 목록에 없던 이유가 이것이다.
            //
            // 개발사도 배급사도 스팀 페이지도 없는 항목만 버린다.
            //
            // 예전에는 개발사·배급사를 무조건 요구했는데, Wikidata 는 갓 발표된 게임의
            // P178·P123 을 자주 비워둔다. 귀무자 Way of the Sword 가 그랬다 — 캡콤 신작이고
            // 스팀 페이지도 있는데 개발사 칸이 비었다는 이유로 버려졌다.
            // 스팀 ID 가 있으면 스토어가 개발사와 배급사를 알려주므로, 받아두고 나중에 채운다.
            .filter { it.steamAppId != null || it.developers.isNotEmpty() || it.publishers.isNotEmpty() }
    }

    private fun addCompany(
        binding: com.fasterxml.jackson.databind.JsonNode,
        idField: String,
        labelField: String,
        target: LinkedHashMap<String, CatalogCompanyRef>,
    ) {
        val uri = binding.path(idField).path("value").asText()
        val name = binding.path(labelField).path("value").asText()
        if (name.isBlank() || name.matches(Regex("Q\\d+"))) return
        val id = uri.substringAfterLast('/').takeIf { it.matches(Regex("Q\\d+")) }
        target.putIfAbsent(id ?: name.lowercase(), CatalogCompanyRef(id, name))
    }

    private fun canonicalGenre(value: String): String {
        val normalized = value.trim().lowercase()
        return when {
            "action-adventure" in normalized || "액션 어드벤처" in normalized -> "액션 어드벤처"
            "action" in normalized || "액션" in normalized -> "액션"
            "role-playing" in normalized || "롤플레잉" in normalized || normalized == "rpg" -> "RPG"
            "adventure" in normalized || "어드벤처" in normalized -> "어드벤처"
            "first-person shooter" in normalized || "1인칭 슈팅" in normalized -> "FPS"
            "third-person shooter" in normalized || "3인칭 슈팅" in normalized -> "TPS"
            "shooter" in normalized || "슈팅" in normalized -> "슈팅"
            "strategy" in normalized || "전략" in normalized -> "전략"
            "combat flight simulator" in normalized -> "비행 시뮬레이션"
            "simulation" in normalized || "시뮬레이션" in normalized -> "시뮬레이션"
            "survival horror" in normalized || "서바이벌 호러" in normalized -> "서바이벌 호러"
            "survival" in normalized || "생존" in normalized -> "생존"
            "horror" in normalized || "공포" in normalized -> "공포"
            "platform" in normalized || "플랫폼" in normalized -> "플랫포머"
            "puzzle" in normalized || "퍼즐" in normalized -> "퍼즐"
            "racing" in normalized || "레이싱" in normalized || "경주" in normalized -> "레이싱"
            "sports" in normalized || "스포츠" in normalized -> "스포츠"
            "fighting" in normalized || "대전 격투" in normalized -> "격투"
            "visual novel" in normalized || "비주얼 노벨" in normalized -> "비주얼 노벨"
            "roguelike" in normalized || "로그라이크" in normalized -> "로그라이크"
            "roguelite" in normalized || "로그라이트" in normalized -> "로그라이트"
            "metroidvania" in normalized || "메트로배니아" in normalized -> "메트로배니아"
            "science fiction" in normalized || "공상과학" in normalized -> "SF"
            "indie" in normalized || "인디" in normalized -> "인디"
            "deck-building" in normalized -> "덱빌딩"
            normalized == "mecha" -> "메카"
            normalized == "baseball" -> "야구"
            normalized == "basketball" -> "농구"
            normalized == "golf" -> "골프"
            normalized == "cycling" -> "사이클"
            normalized == "card" -> "카드"
            normalized == "comedy" -> "코미디"
            "side-scrolling beat 'em up" in normalized -> "횡스크롤 액션"
            "arena fighter" in normalized -> "아레나 격투"
            "football management" in normalized -> "축구 경영"
            normalized == "board" -> "보드게임"
            "dark fantasy" in normalized -> "다크 판타지"
            "high fantasy" in normalized -> "하이 판타지"
            normalized == "hunting" -> "사냥"
            "minigame collection" in normalized -> "미니게임 모음"
            normalized == "parkour" -> "파쿠르"
            "post-apocalyptic" in normalized -> "포스트 아포칼립스"
            "raising sim" in normalized -> "육성 시뮬레이션"
            "real-time tactics" in normalized -> "실시간 전술"
            "turn-based tactics" in normalized -> "턴제 전술"
            normalized == "skateboarding" -> "스케이트보드"
            normalized == "soulsvania" -> "소울라이크 메트로배니아"
            "typing game" in normalized -> "타이핑"
            normalized == "western" -> "서부극"
            "world war i" in normalized -> "제1차 세계대전"
            "vietnam war" in normalized -> "베트남 전쟁"
            else -> value.replace(Regex("(?i) video game$| 비디오 게임$| 게임$"), "").trim()
        }
    }

    private fun canonicalPlatform(value: String): String? {
        val normalized = value.trim().lowercase()
        return when {
            normalized.contains("windows") || normalized.contains("linux") || normalized.contains("macos") || normalized == "pc" -> "PC"
            normalized.contains("playstation 5") || normalized.contains("플레이스테이션 5") -> "PS5"
            normalized.contains("xbox series") || normalized.contains("엑스박스") -> "Xbox"
            normalized.contains("nintendo switch 2") || normalized.contains("닌텐도 스위치 2") -> "Switch 2"
            else -> null
        }
    }

    private fun canonicalGameMode(value: String): String? {
        val normalized = value.trim().lowercase()
        return when {
            "single-player" in normalized || "일인용" in normalized || "싱글 플레이" in normalized -> "싱글 플레이"
            "cooperative" in normalized || "협동" in normalized -> "협동"
            "massively multiplayer" in normalized || "대규모 다중" in normalized -> "MMO"
            "multiplayer" in normalized || "다인용" in normalized || "멀티플레이" in normalized -> "멀티플레이"
            else -> null
        }
    }

    private fun sendClassificationRequest(request: HttpRequest, attempt: Int = 1): HttpResponse<String> {
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if ((response.statusCode() == 429 || response.statusCode() >= 500) && attempt < CLASSIFICATION_MAX_ATTEMPTS) {
            Thread.sleep(CLASSIFICATION_RETRY_BASE_MS * attempt)
            return sendClassificationRequest(request, attempt + 1)
        }
        return response
    }

    private companion object {
        /** 한 해에 열두 번 부르므로 간격을 둔다. Wikidata 는 무료지만 예의는 지킨다. */
        const val RANGE_REQUEST_INTERVAL_MS = 1_200L
        const val RETRY_ATTEMPTS = 3
        const val RETRY_BACKOFF_MS = 2_000L
        /** 잠깐 막힌 것들. 이건 다시 물어볼 값이 있다. */
        val RETRYABLE = setOf(429, 500, 502, 503, 504)
        const val CLASSIFICATION_REQUEST_INTERVAL_MS = 1_000L
        const val CLASSIFICATION_RETRY_BASE_MS = 3_000L
        const val CLASSIFICATION_MAX_ATTEMPTS = 3
    }
}
