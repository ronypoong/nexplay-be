package com.rubion.nexplaybe.discovery

import com.rubion.nexplaybe.api.GameCardResponse
import com.rubion.nexplaybe.api.GameEventResponse
import com.rubion.nexplaybe.api.GameResponse
import com.rubion.nexplaybe.cache.CacheConfig
import com.rubion.nexplaybe.intelligence.PromiseQueryService
import com.rubion.nexplaybe.intelligence.PromiseRow
import com.rubion.nexplaybe.metadata.ExtendedGameMetadataResponse
import com.rubion.nexplaybe.metadata.ExtendedGameMetadataService
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Service

data class GameDetailResponse(
    val game: GameResponse,
    val metadata: ExtendedGameMetadataResponse,
    val events: List<GameEventResponse>,
    val promises: List<PromiseRow>,
    val related: List<GameCardResponse>,
)

/**
 * 게임 상세 화면이 필요로 하는 것을 한 번에 준다.
 *
 * 예전에는 화면 하나를 그리려고 서버를 여섯 번 불렀다 — 게임, 소식, 부가 정보,
 * 약속, 관련 게임, 그리고 메타 태그용으로 게임을 한 번 더. 서울에서 원본까지
 * 왕복이 한 번에 0.2초쯤이라, 여섯 번이면 그것만으로 1초가 넘는다.
 *
 * 각 조각은 이미 따로 캐시돼 있으므로 여기서 다시 계산하지 않는다. 모아서 한 번에
 * 보내는 것이 전부다.
 */
@Service
class GameDetailBundle(
    private val discoveryService: DiscoveryService,
    private val metadataService: ExtendedGameMetadataService,
    private val promiseQueryService: PromiseQueryService,
) {
    @Cacheable(CacheConfig.GAME_DETAIL, key = "'bundle-' + #slug")
    fun of(slug: String): GameDetailResponse = GameDetailResponse(
        game = discoveryService.game(slug),
        metadata = metadataService.get(slug),
        events = discoveryService.events(slug),
        // 약속이나 관련 게임이 없다고 상세 화면이 죽으면 안 된다.
        promises = runCatching { promiseQueryService.forGame(slug) }.getOrDefault(emptyList()),
        related = runCatching { discoveryService.related(slug) }.getOrDefault(emptyList()),
    )
}
