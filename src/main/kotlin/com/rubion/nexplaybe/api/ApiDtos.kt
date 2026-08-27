package com.rubion.nexplaybe.api

import com.rubion.nexplaybe.event.GameEvent
import com.rubion.nexplaybe.game.Game
import com.rubion.nexplaybe.game.GameStatus
import com.rubion.nexplaybe.release.Release
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId

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
)

data class ReleaseResponse(
    val id: String,
    val game: GameResponse,
    val platform: String,
    val releaseDate: String,
    val status: String,
    val region: String,
)

data class FeedResponse(val games: List<GameResponse>, val events: List<GameEventResponse>)

fun Game.toResponse() = GameResponse(
    id = id.toString(), slug = slug, title = title, tagline = tagline, description = description,
    developer = developer.name, publisher = publisher.name, genres = genres.sorted(), platforms = platforms.sortedBy(::platformOrder),
    gameModes = gameModes.sorted(), koreanTextSupported = koreanTextSupported, koreanAudioSupported = koreanAudioSupported,
    releaseDate = releaseDate?.toString() ?: "TBA", releaseLabel = releaseLabel,
    officialUrl = officialUrl, steamAppId = steamAppId, wikidataId = wikidataId, catalogSource = catalogSource,
    coverImageUrl = coverImageUrl, imageSource = imageSource,
    status = when (status) { GameStatus.AVAILABLE -> "Available"; GameStatus.UPCOMING -> "Upcoming"; GameStatus.TBA -> "TBA" },
    score = discoveryScore.toInt(), anticipationScore = anticipationScore.toInt(), followers = formatFollowers(followerCount), accent = accent,
    accent2 = accentSecondary, symbol = symbol, featured = featured,
)

fun GameEvent.toResponse(clock: Clock): GameEventResponse {
    val orderedSources = sources.sortedWith(compareByDescending<com.rubion.nexplaybe.event.GameEventSource> { it.source.official }.thenBy { it.id })
    val primary = orderedSources.firstOrNull()
    return GameEventResponse(
        id = id.toString(), gameSlug = game.slug, type = type.name, title = title, summary = summary,
        date = eventDate.toString(), dateLabel = relativeDateLabel(publishedAt, eventDate, clock),
        source = primary?.source?.name ?: "NEXPLAY", official = orderedSources.any { it.source.official },
        sourceCount = orderedSources.size,
    )
}

fun Release.toResponse() = ReleaseResponse(id.toString(), game.toResponse(), platform, releaseDate.toString(), status.name, region)

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
