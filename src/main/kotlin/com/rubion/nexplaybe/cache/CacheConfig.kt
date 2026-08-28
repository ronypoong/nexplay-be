package com.rubion.nexplaybe.cache

import com.github.benmanes.caffeine.cache.Caffeine
import org.springframework.cache.annotation.EnableCaching
import org.springframework.cache.caffeine.CaffeineCacheManager
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration

/**
 * 읽기 경로 캐시.
 *
 * 카탈로그는 하루 한 번(06:00 동기화) 바뀌는데 요청마다 다시 계산하고 있었다.
 * 게임 458건을 JPA 엔티티로 전부 적재하느라 /feed 와 /games 만 다른 엔드포인트보다
 * 0.5~0.8초를 더 썼다.
 *
 * 크기를 반드시 묶는다. 검색어처럼 사용자가 값을 정하는 키가 섞이면 무제한 맵은
 * 그대로 메모리 누수가 된다. 메모리를 줄이려고 넣은 캐시가 메모리를 먹으면 안 된다.
 */
@Configuration
@EnableCaching
class CacheConfig {

    @Bean
    fun cacheManager(): CaffeineCacheManager {
        val manager = CaffeineCacheManager()
        manager.setCaffeine(
            Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofMinutes(TTL_MINUTES))
                .maximumSize(DEFAULT_MAX_SIZE),
        )
        // 이름을 미리 박아 둔다. 오타로 만들어진 캐시가 조용히 동작하지 않는 것보다,
        // 목록에 없는 이름을 쓰면 바로 드러나는 편이 낫다.
        manager.setCacheNames(NAMES)
        return manager
    }

    companion object {
        /** 하루 한 번 바뀌는 데이터다. 10분이면 관리 화면에서 고친 것도 곧 보인다. */
        const val TTL_MINUTES = 10L
        const val DEFAULT_MAX_SIZE = 600L

        /** 게임 목록 스냅샷. 항목 하나. */
        const val CATALOG = "catalog"
        /** 슬러그별 상세·소식·약속. 게임 수만큼. */
        const val GAME_DETAIL = "gameDetail"
        const val GAME_EVENTS = "gameEvents"
        const val GAME_METADATA = "gameMetadata"
        const val GAME_PROMISES = "gamePromises"
        /** 통째로 만드는 섹션들. 각각 항목 하나. */
        const val SECTIONS = "sections"
        const val RELEASES = "releases"

        val NAMES = listOf(CATALOG, GAME_DETAIL, GAME_EVENTS, GAME_METADATA, GAME_PROMISES, SECTIONS, RELEASES)
    }
}
