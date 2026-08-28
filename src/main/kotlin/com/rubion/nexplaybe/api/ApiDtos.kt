package com.rubion.nexplaybe.api

import com.rubion.nexplaybe.event.GameEvent
import com.rubion.nexplaybe.game.Game
import com.rubion.nexplaybe.game.GameStatus
import com.rubion.nexplaybe.release.Release
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId

/** 카드에 다는 수상 배지. 가장 무게 있는 기록 하나만 고른다. */
data class AwardBadge(val label: String, val year: Int, val kind: String)

data class GameResponse(
    val id: String,
    val slug: String,
    val title: String,
    val tagline: String,
    val description: String,
    val developer: String,
    val publisher: String,
    val genres: List<String>,
    val platforms: List<String>,
    val gameModes: List<String>,
    val koreanTextSupported: Boolean?,
    val koreanAudioSupported: Boolean?,
    val releaseDate: String,
    val releaseLabel: String,
    val officialUrl: String?,
    val steamAppId: Long?,
    val wikidataId: String?,
    val catalogSource: String,
    val coverImageUrl: String?,
    val imageSource: String?,
    val status: String,
    val score: Int,
    val anticipationScore: Int,
    val followers: String,
    val accent: String,
    val accent2: String,
    val symbol: String,
    val featured: Boolean,
    val awardBadge: AwardBadge? = null,
    /** 기대를 누른 사람 수. 아직 적으면 null 이라 화면에서 감춘다. */
    val anticipations: Int? = null,
    /** 누적 조회수. 아직 적으면 null 이다. */
    val views: Int? = null,
)

data class GameEventResponse(
    val id: String,
    val gameSlug: String,
    val type: String,
    val title: String,
    val summary: String,
    val date: String,
    val dateLabel: String,
    val source: String,
    val official: Boolean,
    val sourceCount: Int,
    /**
     * 원문 링크. 모든 이벤트에 원출처를 남긴다는 정책이 있는데 화면까지 오지
     * 않고 있었다. 우리가 쓴 글이 아니라 남의 발표라는 것이 링크로 드러나야 한다.
     */
    val sourceUrl: String? = null,
    /**
     * 소식이 가리키는 게임의 표시 정보.
     *
     * 예전에는 화면이 게임 목록에서 찾아 썼는데, 피드가 주는 목록은 40개고
     * 소식은 405개 게임에서 온다. 못 찾으면 첫 게임으로 대체돼서 소식 30건 중
     * 24건이 남의 사진과 남의 링크를 달고 있었다.
     */
    val game: EventGameRef? = null,
    /** 모델이 원문에서 뽑은 한국어 한 줄. 원문이 영어·일본어라 이게 없으면 제목만 남는다. */
    val summaryKo: String? = null,
    /** 체험판 배포를 알리는 글인가. 카드에 표시해 준다. */
    val hasDemo: Boolean = false,
    /** 원문에 명시된 할인율. 없으면 null */
    val discountPercent: Int? = null,
    /** 게임 내용과 무관한 마케팅·커뮤니티 잡음인가. 홈에서는 빼고 게임 이력에는 남긴다. */
    val marketingNoise: Boolean = false,
)

/** 카드 하나를 그리는 데 필요한 최소한. 전체 카드를 실으면 소식마다 무거워진다. */
data class EventGameRef(
    val slug: String,
    val title: String,
    val developer: String,
    val coverImageUrl: String?,
    val accent: String,
    val accent2: String,
    val symbol: String,
)

data class ReleaseResponse(
    val id: String,
    val game: GameCardResponse,
    val platform: String,
    val releaseDate: String,
    val status: String,
    val region: String,
)

/**
 * 목록용 게임. description 이 빠져 있다.
 *
 * Steam 소개문을 붙이면서 게임당 500~950자가 됐다. /feed 가 458개를 실어 나르면
 * 그 필드 하나로만 300KB 가 넘는데, 카드와 캐러셀 어디에서도 쓰지 않는다.
 * 소개는 상세 화면에서 한 건씩 받는다.
 */
data class GameCardResponse(
    val id: String,
    val slug: String,
    val title: String,
    val tagline: String,
    val developer: String,
    val publisher: String,
    val genres: List<String>,
    val platforms: List<String>,
    val gameModes: List<String>,
    val koreanTextSupported: Boolean?,
    val koreanAudioSupported: Boolean?,
    val releaseDate: String,
    val releaseLabel: String,
    val steamAppId: Long?,
    val coverImageUrl: String?,
    val status: String,
    val score: Int,
    val anticipationScore: Int,
    val followers: String,
    val accent: String,
    val accent2: String,
    val symbol: String,
    val featured: Boolean,
    val awardBadge: AwardBadge? = null,
    /** 기대를 누른 사람 수. 아직 적으면 null 이라 화면에서 감춘다. */
    val anticipations: Int? = null,
    /** 누적 조회수. 아직 적으면 null 이다. */
    val views: Int? = null,
)

/** 홈이 배열 길이로 세던 값들. 목록을 자르면 그 수가 틀려지므로 서버가 진짜 총계를 준다. */
data class FeedStats(
    val totalGames: Int,
    val currentYearGames: Int,
    val totalEvents: Int,
    val upcomingGames: Int,
    val updateEvents: Int,
    val expansionEvents: Int,
)

data class FeedResponse(
    val games: List<GameCardResponse>,
    val upcoming: List<GameCardResponse>,
    val hiddenGems: List<GameCardResponse>,
    val events: List<GameEventResponse>,
    val stats: FeedStats,
)

fun Game.toResponse(awardBadge: AwardBadge? = null) = GameResponse(
    id = id.toString(), slug = slug, title = title, tagline = tagline, description = description,
    developer = developer.name, publisher = publisher.name, genres = genres.sorted(), platforms = platforms.sortedBy(::platformOrder),
    gameModes = gameModes.sorted(), koreanTextSupported = koreanTextSupported, koreanAudioSupported = koreanAudioSupported,
    releaseDate = releaseDate?.toString() ?: "TBA", releaseLabel = releaseLabel,
    officialUrl = officialUrl, steamAppId = steamAppId, wikidataId = wikidataId, catalogSource = catalogSource,
    coverImageUrl = coverImageUrl, imageSource = imageSource,
    status = when (status) { GameStatus.AVAILABLE -> "Available"; GameStatus.UPCOMING -> "Upcoming"; GameStatus.TBA -> "TBA" },
    score = discoveryScore.toInt(), anticipationScore = anticipationScore.toInt(), followers = formatFollowers(followerCount), accent = accent,
    accent2 = accentSecondary, symbol = symbol, featured = featured, awardBadge = awardBadge,
)

fun GameEvent.toResponse(
    clock: Clock,
    insight: com.rubion.nexplaybe.intelligence.EventInsight? = null,
): GameEventResponse {
    val orderedSources = sources.sortedWith(compareByDescending<com.rubion.nexplaybe.event.GameEventSource> { it.source.official }.thenBy { it.id })
    val primary = orderedSources.firstOrNull()
    return GameEventResponse(
        id = id.toString(), gameSlug = game.slug,
        type = insight?.trustworthyType ?: type.name,
        title = title, summary = summary,
        date = eventDate.toString(), dateLabel = relativeDateLabel(publishedAt, eventDate, clock),
        source = primary?.source?.name ?: "NEXPLAY", official = orderedSources.any { it.source.official },
        sourceCount = orderedSources.size,
        sourceUrl = primary?.sourceUrl,
        game = EventGameRef(
            game.slug, game.title, game.developer.name, game.coverImageUrl,
            game.accent, game.accentSecondary, game.symbol,
        ),
        summaryKo = insight?.summaryKo,
        hasDemo = insight?.hasDemo ?: false,
        discountPercent = insight?.discountPercent,
        marketingNoise = insight?.marketingNoise ?: false,
    )
}

fun Game.toCardResponse(
    awardBadge: AwardBadge? = null,
    audience: com.rubion.nexplaybe.popularity.AudienceCounts? = null,
) = GameCardResponse(
    id = id.toString(), slug = slug, title = title, tagline = tagline,
    developer = developer.name, publisher = publisher.name,
    genres = genres.sorted(), platforms = platforms.sortedBy(::platformOrder), gameModes = gameModes.sorted(),
    koreanTextSupported = koreanTextSupported, koreanAudioSupported = koreanAudioSupported,
    releaseDate = releaseDate?.toString() ?: "TBA", releaseLabel = releaseLabel,
    steamAppId = steamAppId, coverImageUrl = coverImageUrl,
    status = when (status) { GameStatus.AVAILABLE -> "Available"; GameStatus.UPCOMING -> "Upcoming"; GameStatus.TBA -> "TBA" },
    score = discoveryScore.toInt(), anticipationScore = anticipationScore.toInt(),
    followers = formatFollowers(followerCount), accent = accent, accent2 = accentSecondary,
    symbol = symbol, featured = featured, awardBadge = awardBadge,
    anticipations = audience?.anticipations, views = audience?.views,
)

fun Release.toResponse() = ReleaseResponse(id.toString(), game.toCardResponse(), platform, releaseDate.toString(), status.name, region)

private fun platformOrder(value: String) = listOf("PC", "PS5", "Xbox", "Switch 2", "미정").indexOf(value).let { if (it < 0) 99 else it }
private fun formatFollowers(value: Long) = if (value >= 1_000) "%.1fK".format(value / 1_000.0) else value.toString()
private fun relativeDateLabel(publishedAt: java.time.Instant, eventDate: LocalDate, clock: Clock): String {
    val hours = Duration.between(publishedAt, clock.instant()).toHours()
    val today = LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")))
    return when {
        hours in 0..23 -> if (hours == 0L) "방금 전" else "${hours}시간 전"
        eventDate == today.minusDays(1) -> "어제"
        eventDate.year == today.year -> "${eventDate.monthValue}월 ${eventDate.dayOfMonth}일"
        else -> eventDate.toString()
    }
}
