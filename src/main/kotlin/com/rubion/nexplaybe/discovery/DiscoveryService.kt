package com.rubion.nexplaybe.discovery

import com.rubion.nexplaybe.api.AwardBadge
import com.rubion.nexplaybe.awards.AwardBadgeLookup
import com.rubion.nexplaybe.api.FeedResponse
import com.rubion.nexplaybe.api.FeedStats
import com.rubion.nexplaybe.api.GameCardResponse
import com.rubion.nexplaybe.api.GameEventResponse
import com.rubion.nexplaybe.api.GameResponse
import com.rubion.nexplaybe.api.ReleaseResponse
import com.rubion.nexplaybe.api.toResponse
import com.rubion.nexplaybe.event.GameEventRepository
import com.rubion.nexplaybe.game.GameRepository
import com.rubion.nexplaybe.release.ReleaseRepository
import com.rubion.nexplaybe.cache.CacheConfig
import com.rubion.nexplaybe.intelligence.EventInsightLookup
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import com.rubion.nexplaybe.game.GameStatus
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

@Service
@Transactional(readOnly = true)
class DiscoveryService(
    private val catalogSnapshot: CatalogSnapshot,
    private val gameRepository: GameRepository,
    private val eventRepository: GameEventRepository,
    private val releaseRepository: ReleaseRepository,
    private val awardBadgeLookup: AwardBadgeLookup,
    private val eventInsightLookup: EventInsightLookup,
    private val feedEventSelector: FeedEventSelector,
) {
    private val clock: Clock = Clock.systemUTC()

    /**
     * 홈이 쓰는 것만 골라 보낸다.
     *
     * 예전에는 게임 458개와 이벤트 363개를 통째로 보내고(772KB) 클라이언트가 잘라 썼다.
     * 홈이 실제로 그리는 건 캐러셀 몇 개와 출시 예정 10개, 소식 5개다.
     * 배열 길이로 세던 통계는 stats 로 따로 준다 — 목록을 자르면 그 수가 틀려지기 때문이다.
     */
    @Cacheable(CacheConfig.SECTIONS, key = "'feed'")
    fun feed(): FeedResponse {
        val games = catalogSnapshot.entries()
        val insights = eventInsightLookup.insights()
        // 중요도로 고른 것만 읽는다. 예전에는 3,276건을 전부 엔티티로 불러와
        // 앞의 30건만 썼다 — 콜드 요청이 1.2초였다.
        val ranked = feedEventSelector.topEventIds(FEED_EVENT_LIMIT)
        val byId = eventRepository.findFeedEventsByIds(ranked).associateBy { it.id }
        val events = ranked.mapNotNull(byId::get)
        val eventStats = feedEventSelector.stats()
        val today = LocalDate.now(ZoneId.of(SEOUL))
        val currentYear = today.year

        val upcomingAll = games
            .filter { it.status == GameStatus.UPCOMING && it.releaseDate?.isBefore(today) == false }
            .sortedBy { it.releaseDate }
        // 대형사도 featured 도 아닌, 점수가 낮은 출시 예정작 — 정렬 상위를 그대로 쓰면 "숨은" 이 아니다.
        val hiddenGems = games
            .filter { !it.featured && it.status == GameStatus.UPCOMING }
            .sortedWith(compareBy({ it.discoveryScore }, { -it.anticipationScore }))
            .take(HIDDEN_GEM_COUNT)

        return FeedResponse(
            games = games.take(FEED_GAME_LIMIT).map { it.card },
            upcoming = upcomingAll.take(UPCOMING_LIMIT).map { it.card },
            hiddenGems = hiddenGems.map { it.card },
            events = events.map { it.toResponse(clock, insights[it.id]) },
            stats = FeedStats(
                totalGames = games.size,
                currentYearGames = games.count { it.releaseDate?.year == currentYear },
                totalEvents = eventStats.total,
                upcomingGames = upcomingAll.size,
                updateEvents = eventStats.updateEvents,
                expansionEvents = eventStats.expansionEvents,
            ),
        )
    }

    /**
     * 소식 목록.
     *
     * 홈은 30건만 보여 주는데 "전체 소식 보기" 가 게임 목록으로 가고 있었다.
     * 소식을 모으는 서비스에 소식 목록이 없었던 셈이다.
     */
    @Cacheable(CacheConfig.SECTIONS, key = "'events-' + #page")
    fun eventFeed(page: Int = 0): List<GameEventResponse> {
        val insights = eventInsightLookup.insights()
        val ranked = feedEventSelector.topEventIds(NEWS_PAGE_SIZE, page.coerceAtLeast(0) * NEWS_PAGE_SIZE)
        val byId = eventRepository.findFeedEventsByIds(ranked).associateBy { it.id }
        return ranked.mapNotNull(byId::get).map { it.toResponse(clock, insights[it.id]) }
    }

    /** 같은 장르를 공유하는 게임. 상세 화면이 전체 카탈로그를 받아 3개만 쓰던 것을 대신한다. */
    fun related(slug: String, limit: Int = 3): List<GameCardResponse> {
        val games = catalogSnapshot.entries()
        val target = games.firstOrNull { it.slug == slug }
        // 스냅샷은 아카이브 전용 게임을 제외한다. 그런 게임의 상세 화면도 열리므로,
        // 목록에 없다고 404 를 주면 예전에 빈 배열을 받던 자리에서 없던 오류가 난다.
            ?: return if (gameRepository.findBySlug(slug) == null) {
                throw ResourceNotFoundException("Game not found: $slug")
            } else {
                emptyList()
            }
        if (target.genres.isEmpty()) return emptyList()
        return games.asSequence()
            .filter { it.slug != slug }
            .map { it to it.genres.count { genre -> genre in target.genres } }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<CatalogEntry, Int>> { it.second }.thenByDescending { it.first.discoveryScore })
            .take(limit.coerceIn(1, 12))
            .map { it.first.card }
            .toList()
    }

    fun games(platform: String?, genre: String?, query: String?): List<GameCardResponse> {
        val needle = query?.takeIf(String::isNotBlank)?.lowercase()
        return catalogSnapshot.entries().asSequence()
            .filter { platform.isNullOrBlank() || it.platforms.any { value -> value.equals(platform, true) } }
            .filter { genre.isNullOrBlank() || it.genres.any { value -> value.equals(genre, true) } }
            .filter { needle == null || it.searchText.contains(needle) }
            .map { it.card }
            .toList()
    }

    @Cacheable(CacheConfig.GAME_DETAIL)
    fun game(slug: String): GameResponse {
        val game = gameRepository.findBySlug(slug) ?: throw ResourceNotFoundException("Game not found: $slug")
        return game.toResponse(awardBadgeLookup.badges()[game.id])
    }

    @Cacheable(CacheConfig.GAME_EVENTS)
    fun events(slug: String): List<GameEventResponse> {
        if (gameRepository.findBySlug(slug) == null) throw ResourceNotFoundException("Game not found: $slug")
        // 게임 한 대의 이력에서는 잡음도 빼지 않는다. 무엇이 있었는지가 기록이고,
        // 잡음이라는 판단 자체도 화면에서 볼 수 있어야 뒤집을 수 있다.
        val insights = eventInsightLookup.insights()
        return eventRepository.findByGameSlug(slug).map { it.toResponse(clock, insights[it.id]) }
    }

    fun releases(from: LocalDate?, to: LocalDate?, platform: String?): List<ReleaseResponse> =
        catalogSnapshot.releases().asSequence()
            .filter { from == null || !LocalDate.parse(it.releaseDate).isBefore(from) }
            .filter { to == null || !LocalDate.parse(it.releaseDate).isAfter(to) }
            .filter { platform.isNullOrBlank() || it.platform.equals(platform, true) }
            .toList()

    private companion object {
        const val SEOUL = "Asia/Seoul"
        const val FEED_GAME_LIMIT = 40
        const val FEED_EVENT_LIMIT = 30
        const val UPCOMING_LIMIT = 10
        const val HIDDEN_GEM_COUNT = 2
        const val NEWS_PAGE_SIZE = 40
    }
}

class ResourceNotFoundException(message: String) : RuntimeException(message)
