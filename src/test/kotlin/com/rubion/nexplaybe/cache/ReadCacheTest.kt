package com.rubion.nexplaybe.cache

import com.rubion.nexplaybe.awards.AwardBadgeLookup
import com.rubion.nexplaybe.popularity.AudienceService
import com.rubion.nexplaybe.discovery.CatalogSnapshot
import com.rubion.nexplaybe.game.GameRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.cache.CacheManager

/**
 * 캐시가 실제로 걸리는지 확인한다.
 *
 * `@Cacheable` 은 프록시를 타야만 동작한다. 같은 클래스 안에서 부르거나 빈이 아닌
 * 객체로 만들면 애노테이션만 붙은 채 아무 일도 하지 않고, 그래도 테스트는 통과한다.
 * 조용히 안 걸리는 게 이 기능의 유일한 실패 방식이라 따로 못을 박아 둔다.
 */
@SpringBootTest
class ReadCacheTest {

    @Autowired private lateinit var catalogSnapshot: CatalogSnapshot
    @Autowired private lateinit var cacheManager: CacheManager
    @Autowired private lateinit var evictor: ReadCacheEvictor

    @MockitoBean private lateinit var gameRepository: GameRepository

    // game_award, game_anticipation, game_view_daily 는 Flyway 가 만드는 테이블이고
    // 테스트는 엔티티로만 스키마를 만든다.
    @MockitoBean private lateinit var awardBadgeLookup: AwardBadgeLookup
    @MockitoBean private lateinit var audienceService: AudienceService

    @BeforeEach
    fun reset() {
        evictor.evictAll()
        `when`(gameRepository.findAllForDiscovery()).thenReturn(emptyList())
        `when`(awardBadgeLookup.badges()).thenReturn(emptyMap())
        `when`(audienceService.counts()).thenReturn(emptyMap())
    }

    @Test
    fun `두 번 불러도 DB 는 한 번만 본다`() {
        catalogSnapshot.entries()
        catalogSnapshot.entries()
        catalogSnapshot.entries()

        verify(gameRepository, times(1)).findAllForDiscovery()
    }

    @Test
    fun `캐시를 비우면 다시 조회한다`() {
        catalogSnapshot.entries()
        evictor.evictAll()
        catalogSnapshot.entries()

        verify(gameRepository, times(2)).findAllForDiscovery()
    }

    @Test
    fun `설정한 캐시 이름이 모두 만들어져 있다`() {
        assertThat(cacheManager.cacheNames).containsExactlyInAnyOrderElementsOf(CacheConfig.NAMES)
    }
}
