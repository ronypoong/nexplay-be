package com.rubion.nexplaybe.discovery

import com.rubion.nexplaybe.api.GameCardResponse
import com.rubion.nexplaybe.awards.AwardBadgeLookup
import com.rubion.nexplaybe.api.toCardResponse
import com.rubion.nexplaybe.cache.CacheConfig
import com.rubion.nexplaybe.api.ReleaseResponse
import com.rubion.nexplaybe.api.toResponse
import com.rubion.nexplaybe.game.GameRepository
import com.rubion.nexplaybe.release.ReleaseRepository
import com.rubion.nexplaybe.game.GameStatus
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

/**
 * 카드 하나와, 목록을 거르고 정렬할 때 필요한 값들.
 *
 * 카드 안에도 같은 값이 문자열로 들어 있지만("Upcoming", "2026-03-01"), 요청마다
 * 다시 파싱하지 않으려고 타입 그대로 함께 들고 있는다.
 */
data class CatalogEntry(
    val card: GameCardResponse,
    val id: Long,
    val slug: String,
    val status: GameStatus,
    val releaseDate: LocalDate?,
    val featured: Boolean,
    val discoveryScore: Int,
    val anticipationScore: Int,
    val genres: Set<String>,
    val platforms: Set<String>,
    val searchText: String,
)

/**
 * 게임 목록을 한 번만 만든다.
 *
 * 예전에는 `/feed`, `/games`, `/games/{slug}/related` 가 각자 게임 458건을 JPA 엔티티로
 * 통째로 불러왔다. 그 조회는 컬렉션 셋(장르·플랫폼·모드)을 한꺼번에 fetch join 해서
 * 3,885행을 받아 458건으로 줄이는 일을 매 요청 반복했다.
 *
 * 카탈로그는 하루 한 번 바뀐다. 요청 시점에 다시 만들 이유가 없다.
 *
 * `DiscoveryService` 안에 두지 않고 별도 컴포넌트로 뺀 이유는 자기호출 때문이다.
 * 같은 클래스 안에서 부르면 프록시를 타지 않아 `@Cacheable` 이 조용히 무시된다.
 */
@Component
class CatalogSnapshot(
    private val gameRepository: GameRepository,
    private val releaseRepository: ReleaseRepository,
    private val awardBadgeLookup: AwardBadgeLookup,
) {
    @Cacheable(CacheConfig.CATALOG)
    @Transactional(readOnly = true)
    fun entries(): List<CatalogEntry> {
        val badges = awardBadgeLookup.badges()
        return gameRepository.findAllForDiscovery().map { game ->
            CatalogEntry(
                card = game.toCardResponse(badges[game.id]),
                id = game.id,
                slug = game.slug,
                status = game.status,
                releaseDate = game.releaseDate,
                featured = game.featured,
                discoveryScore = game.discoveryScore.toInt(),
                anticipationScore = game.anticipationScore.toInt(),
                genres = game.genres.toSet(),
                platforms = game.platforms.toSet(),
                // 검색이 매번 제목·회사·장르·플랫폼을 이어 붙이던 것을 한 번만 해 둔다.
                searchText = (listOf(game.title, game.developer.name, game.publisher.name) + game.genres + game.platforms)
                    .joinToString(" ")
                    .lowercase(),
            )
        }
    }

    /**
     * 출시 캘린더. 이쪽도 게임 전체를 fetch join 으로 끌고 오므로 달마다 다시 만들 이유가 없다.
     * 달·플랫폼으로 거르는 건 목록을 받아 놓고 하면 된다.
     */
    @Cacheable(CacheConfig.RELEASES)
    @Transactional(readOnly = true)
    fun releases(): List<ReleaseResponse> = releaseRepository.findAllForCalendar().map { it.toResponse() }
}
