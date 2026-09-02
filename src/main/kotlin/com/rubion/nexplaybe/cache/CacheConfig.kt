package com.rubion.nexplaybe.cache

import com.github.benmanes.caffeine.cache.Caffeine
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.cache.CacheManager
import org.springframework.cache.annotation.EnableCaching
import org.springframework.cache.caffeine.CaffeineCacheManager
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.cache.RedisCacheConfiguration
import org.springframework.data.redis.cache.RedisCacheManager
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer
import tools.jackson.databind.jsontype.BasicPolymorphicTypeValidator
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair
import org.springframework.data.redis.serializer.StringRedisSerializer
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
 *
 * 저장소는 둘 중 하나다. `REDIS_URL` 이 채워져 있으면 Redis 를, 비어 있으면
 * Caffeine 을 쓴다. 두 조건이 서로 배타적이라 순서에 기대지 않는다.
 *
 * 기본값을 Caffeine 으로 둔 이유는 인스턴스가 하나일 때는 그쪽이 실제로 빠르기
 * 때문이다. Caffeine 은 같은 프로세스 안의 맵이고 Redis 는 네트워크 왕복이다.
 * Redis 가 이기는 자리는 (1) 인스턴스가 둘 이상이라 캐시가 갈리면 안 될 때,
 * (2) 배포가 잦고 트래픽이 있어 콜드 캐시 비용이 클 때다. 그때 값만 채우면 된다.
 */
@Configuration
@EnableCaching
class CacheConfig {

    /**
     * `REDIS_URL` 이 채워져 있을 때만 뜬다.
     *
     * 키에 `nexplay:` 를 붙인다. Redis 한 대를 다른 서비스와 나눠 쓰는 경우가
     * 흔한데, 접두어가 없으면 `sections::feed` 같은 흔한 이름이 그대로 부딪힌다.
     *
     * null 캐싱을 막지 않는다. 없는 슬러그를 묻는 경로가 있어서, 막아 두면
     * "값이 없음" 을 돌려줄 때 캐시가 예외를 던진다.
     */
    @Bean
    @ConditionalOnExpression("!'\${spring.data.redis.url:}'.isBlank()")
    fun redisCacheManager(factory: RedisConnectionFactory): CacheManager {
        val config = RedisCacheConfiguration.defaultCacheConfig()
            .entryTtl(Duration.ofMinutes(TTL_MINUTES))
            .prefixCacheNameWith("nexplay:")
            .serializeKeysWith(SerializationPair.fromSerializer(StringRedisSerializer()))
            .serializeValuesWith(SerializationPair.fromSerializer(redisValueSerializer()))
        return RedisCacheManager.builder(factory)
            .cacheDefaults(config)
            .initialCacheNames(NAMES.toSet())
            .build()
    }

    /**
     * 캐시에 담기는 값은 응답 DTO 다. 되읽을 때 어떤 클래스였는지 알아야 하므로
     * 타입 정보를 함께 적는다. 다만 아무 클래스나 되살리게 두면 역직렬화가
     * 공격 경로가 되므로, 우리 패키지 것만 허용한다.
     *
     * `enableSpringCacheNullValueSupport` 는 "값이 없음" 을 나타내는 표식을
     * 다룰 수 있게 한다. 없는 슬러그를 묻는 경로가 있어서 이게 없으면 그 응답을
     * 캐시에 넣다가 깨진다.
     */
    private fun redisValueSerializer(): GenericJacksonJsonRedisSerializer =
        GenericJacksonJsonRedisSerializer.builder()
            .enableSpringCacheNullValueSupport()
            .enableDefaultTyping(
                BasicPolymorphicTypeValidator.builder()
                    .allowIfBaseType(Any::class.java)
                    .allowIfSubType("com.rubion.nexplaybe.")
                    .allowIfSubType("java.util.")
                    .allowIfSubType("java.time.")
                    .allowIfSubType("java.lang.")
                    .build(),
            )
            .build()

    /** `REDIS_URL` 이 비어 있을 때. 지금까지의 동작이 그대로 유지된다. */
    @Bean
    @ConditionalOnExpression("'\${spring.data.redis.url:}'.isBlank()")
    fun caffeineCacheManager(): CacheManager {
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
