package com.rubion.nexplaybe.catalog

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

data class SteamStoreMetadata(
    val name: String,
    val headerImageUrl: String,
    /** 한 문단 요약. 카드와 태그라인에 쓴다. */
    val shortDescription: String? = null,
    /** 본문 소개. detailed_description 은 에디션·DLC 판매 문구가 섞여 있어 쓰지 않는다. */
    val aboutTheGame: String? = null,
    val genres: Set<String>,
    val platforms: Set<String>,
    val gameModes: Set<String> = emptySet(),
    val languages: List<SteamLanguageSupport> = emptyList(),
    val media: List<SteamMediaItem> = emptyList(),
    val minimumRequirements: String? = null,
    val recommendedRequirements: String? = null,
    val price: SteamPrice? = null,
    val ageRatings: List<SteamAgeRating> = emptyList(),
    val accessibilityFeatures: Set<String> = emptySet(),
    val dlcAppIds: Set<Long> = emptySet(),
)

data class SteamLanguageSupport(val code: String, val name: String, val text: Boolean, val audio: Boolean)
data class SteamMediaItem(val type: String, val externalId: String, val title: String?, val url: String, val thumbnailUrl: String?)
data class SteamPrice(val currency: String, val initial: Long, val final: Long, val discountPercent: Int)
data class SteamAgeRating(val system: String, val rating: String, val descriptors: String?)

@Component
class SteamStoreClient(
    @Value("\${nexplay.catalog.steam-store.timeout-seconds:12}") timeoutSeconds: Long,
) {
    private val timeout = Duration.ofSeconds(timeoutSeconds)
    private val httpClient = HttpClient.newBuilder().connectTimeout(timeout).build()
    private val objectMapper = jacksonObjectMapper()

    fun fetchDetails(appId: Long): SteamStoreMetadata? {
        val request = HttpRequest.newBuilder(
            URI.create("https://store.steampowered.com/api/appdetails?appids=$appId&l=korean&cc=kr"),
        )
            .timeout(timeout)
            .header("Accept", "application/json")
            .header("User-Agent", "NEXPLAY/0.1 (Steam catalog verification; https://github.com/rubi-on)")
            .GET()
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) return null
        val result = objectMapper.readTree(response.body()).path(appId.toString())
        if (!result.path("success").asBoolean(false)) return null
        val data = result.path("data")
        val name = data.path("name").asText().trim()
        val image = data.path("header_image").asText().trim()
        val shortDescription = data.path("short_description").asText().toReadableText().takeIf(String::isNotBlank)
        val aboutTheGame = data.path("about_the_game").asText().toReadableText().takeIf(String::isNotBlank)
        if (name.isBlank() || !image.startsWith("https://")) return null
        val genres = data.path("genres")
            .mapNotNull { it.path("description").asText().trim().takeIf(String::isNotBlank) }
            .map(::canonicalGenre)
            .filterNot { it in NON_GENRE_LABELS }
            .toCollection(linkedSetOf())
        val platformNode = data.path("platforms")
        val platforms = linkedSetOf<String>()
        if (platformNode.path("windows").asBoolean(false) || platformNode.path("mac").asBoolean(false) || platformNode.path("linux").asBoolean(false)) {
            platforms += "PC"
        }
        val categories = data.path("categories").toList()
        val modes = categories.mapNotNull { canonicalMode(it.path("description").asText()) }.toCollection(linkedSetOf())
        val languages = parseLanguages(data.path("supported_languages").asText())
        val screenshots = data.path("screenshots").mapNotNull { node ->
            val full = node.path("path_full").asText()
            if (!full.startsWith("https://")) null else SteamMediaItem("SCREENSHOT", node.path("id").asText(full.hashCode().toString()), null, full, node.path("path_thumbnail").asText().takeIf { it.startsWith("https://") })
        }
        val movies = data.path("movies").mapNotNull { node ->
            val movieUrl = node.path("mp4").path("max").asText().ifBlank { node.path("webm").path("max").asText() }
            if (!movieUrl.startsWith("https://")) null else SteamMediaItem("GAMEPLAY", node.path("id").asText(movieUrl.hashCode().toString()), node.path("name").asText().takeIf(String::isNotBlank), movieUrl, node.path("thumbnail").asText().takeIf { it.startsWith("https://") })
        }
        val requirements = data.path("pc_requirements")
        val priceNode = data.path("price_overview")
        val price = priceNode.takeIf { it.isObject && it.path("currency").asText().isNotBlank() }?.let {
            SteamPrice(it.path("currency").asText(), it.path("initial").asLong(), it.path("final").asLong(), it.path("discount_percent").asInt())
        }
        val ratings = data.path("ratings").fields().asSequence().mapNotNull { (system, node) ->
            node.path("rating").asText().takeIf(String::isNotBlank)?.let { SteamAgeRating(system.uppercase(), it, node.path("descriptors").asText().takeIf(String::isNotBlank)) }
        }.toList()
        val accessibility = categories.map { it.path("description").asText().trim() }
            .filter { label -> ACCESSIBILITY_KEYWORDS.any { keyword -> label.contains(keyword, true) } }
            .toCollection(linkedSetOf())
        val dlcIds = data.path("dlc").mapNotNull { it.asLong().takeIf { value -> value > 0 } }.toSet()
        return SteamStoreMetadata(
            name, image, shortDescription, aboutTheGame, genres, platforms, modes, languages, screenshots + movies,
            requirements.path("minimum").asText().takeIf(String::isNotBlank),
            requirements.path("recommended").asText().takeIf(String::isNotBlank),
            price, ratings, accessibility, dlcIds,
        )
    }

    /**
     * Steam 소개문은 HTML 이다. 태그를 지우되 문단 구분은 남긴다 —
     * 전부 한 줄로 뭉치면 900자짜리 글이 읽을 수 없는 덩어리가 된다.
     */
    private fun String.toReadableText(): String = this
        .replace(Regex("(?i)<br\\s*/?>"), "\n")
        .replace(Regex("(?i)</(p|h[1-6]|li|div)>"), "\n")
        .replace(Regex("(?i)<li[^>]*>"), "· ")
        .replace(Regex("<[^>]+>"), " ")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'")
        .lines().joinToString("\n") { it.replace(Regex("[ \t]+"), " ").trim() }
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()
        .take(MAX_DESCRIPTION_LENGTH)

    private fun canonicalGenre(value: String): String = when (value.trim().lowercase()) {
        "action", "액션" -> "액션"
        "adventure", "어드벤처" -> "어드벤처"
        "role-playing", "role playing", "rpg", "롤플레잉" -> "RPG"
        "strategy", "전략" -> "전략"
        "simulation", "시뮬레이션" -> "시뮬레이션"
        "sports", "스포츠" -> "스포츠"
        "racing", "레이싱", "경주" -> "레이싱"
        "indie", "인디" -> "인디"
        "casual", "캐주얼" -> "캐주얼"
        "massively multiplayer", "대규모 멀티플레이어" -> "대규모 멀티플레이어"
        else -> value.trim()
    }

    private fun canonicalMode(value: String): String? {
        val normalized = value.trim().lowercase()
        return when {
            "single-player" in normalized || "싱글 플레이" in normalized -> "싱글 플레이"
            "cross-platform" in normalized || "크로스 플랫폼" in normalized -> "크로스플레이"
            "co-op" in normalized || "협동" in normalized -> "협동"
            normalized.contains("pvp") -> "PvP"
            "multi-player" in normalized || "multiplayer" in normalized || "멀티플레이" in normalized -> "멀티플레이"
            else -> null
        }
    }

    // supported_languages 는 목록 뒤에 "<br><strong>*</strong>음성이 지원되는 언어" 각주가 붙어 온다.
    // 각주를 떼지 않으면 쉼표 분리 시 마지막 언어에 각주가 들러붙어 이름과 음성 지원 여부가 모두 망가진다.
    internal fun parseLanguages(raw: String): List<SteamLanguageSupport> = raw
        .split(FOOTNOTE_SEPARATOR, limit = 2).first()
        .split(',')
        .mapNotNull { segment ->
            val audio = segment.contains("*") || segment.contains("<strong>", ignoreCase = true)
            val name = segment.replace(Regex("<[^>]+>"), "").replace("*", "").trim()
            if (name.isBlank()) null else SteamLanguageSupport(languageCode(name), name, true, audio)
        }.distinctBy { it.code }

    private fun languageCode(name: String): String = when (name.lowercase()) {
        "한국어", "korean" -> "ko"
        "영어", "english" -> "en"
        "일본어", "japanese" -> "ja"
        "중국어 간체", "simplified chinese" -> "zh-CN"
        "중국어 번체", "traditional chinese" -> "zh-TW"
        "프랑스어", "french" -> "fr"
        "독일어", "german" -> "de"
        "스페인어", "스페인어 - 스페인", "spanish - spain" -> "es"
        "스페인어 - 중남미", "spanish - latin america" -> "es-419"
        "포르투갈어 - 브라질", "portuguese - brazil" -> "pt-BR"
        "포르투갈어", "portuguese", "포르투갈어 - 포르투갈", "portuguese - portugal" -> "pt"
        "러시아어", "russian" -> "ru"
        "폴란드어", "polish" -> "pl"
        "이탈리아어", "italian" -> "it"
        "태국어", "thai" -> "th"
        "아랍어", "arabic" -> "ar"
        "체코어", "czech" -> "cs"
        "터키어", "turkish" -> "tr"
        "네덜란드어", "dutch" -> "nl"
        "우크라이나어", "ukrainian" -> "uk"
        "베트남어", "vietnamese" -> "vi"
        "헝가리어", "hungarian" -> "hu"
        "덴마크어", "danish" -> "da"
        "핀란드어", "finnish" -> "fi"
        "노르웨이어", "norwegian" -> "no"
        "스웨덴어", "swedish" -> "sv"
        "그리스어", "greek" -> "el"
        "루마니아어", "romanian" -> "ro"
        "불가리아어", "bulgarian" -> "bg"
        "인도네시아어", "indonesian" -> "id"
        else -> name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "lang-${name.hashCode().toUInt()}" }
    }

    private companion object {
        const val MAX_DESCRIPTION_LENGTH = 2_000
        val FOOTNOTE_SEPARATOR = Regex("(?i)<br\\s*/?>")
        val NON_GENRE_LABELS = setOf("무료 플레이", "Free to Play", "앞서 해보기", "Early Access")
        val ACCESSIBILITY_KEYWORDS = setOf("자막", "색상", "색맹", "음량", "볼륨", "카메라", "텍스트", "난이도", "subtitles", "color", "volume", "camera", "text size", "difficulty")
    }
}
