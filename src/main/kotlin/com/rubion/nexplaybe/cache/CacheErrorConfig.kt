package com.rubion.nexplaybe.cache

import org.slf4j.LoggerFactory
import org.springframework.cache.Cache
import org.springframework.cache.annotation.CachingConfigurer
import org.springframework.cache.interceptor.CacheErrorHandler
import org.springframework.context.annotation.Configuration

/**
 * 캐시가 죽어도 요청은 산다.
 *
 * 기본 동작은 캐시에서 난 예외를 그대로 올려보내는 것이다. Caffeine 은 같은
 * 프로세스 안에 있어서 그럴 일이 거의 없지만, Redis 로 바꾸면 얘기가 달라진다.
 * 네트워크 너머에 있으니 재시작·장애·타임아웃이 실제로 일어나고, 그때마다
 * 캐시를 읽으려던 요청이 통째로 500 이 된다.
 *
 * 캐시는 빨리 가려고 둔 것이지 정답을 갖고 있는 곳이 아니다. 못 읽으면 DB 로
 * 가면 된다. 그래서 여기서는 남기기만 하고 삼킨다 — 느려지되 서비스는 산다.
 *
 * 같은 이유로 `management.health.redis.enabled` 를 꺼 뒀다. 캐시가 없다고
 * 헬스체크가 DOWN 이 되면 PaaS 가 멀쩡한 인스턴스를 재시작한다.
 */
@Configuration
class CacheErrorConfig : CachingConfigurer {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun errorHandler(): CacheErrorHandler = object : CacheErrorHandler {
        override fun handleCacheGetError(exception: RuntimeException, cache: Cache, key: Any) =
            log.warn("캐시 읽기 실패 — DB 로 간다. cache={} key={}: {}", cache.name, key, exception.message)

        override fun handleCachePutError(exception: RuntimeException, cache: Cache, key: Any, value: Any?) =
            log.warn("캐시 저장 실패 — 다음 요청도 DB 로 간다. cache={} key={}: {}", cache.name, key, exception.message)

        override fun handleCacheEvictError(exception: RuntimeException, cache: Cache, key: Any) =
            log.warn("캐시 무효화 실패 — 오래된 값이 TTL 까지 남는다. cache={} key={}: {}", cache.name, key, exception.message)

        // 이건 삼키지 않는다. 전체 비우기가 조용히 실패하면 관리 화면에서 고친 것이
        // 반영되지 않는데, 그 사실을 아무도 모르는 채로 지나간다.
        override fun handleCacheClearError(exception: RuntimeException, cache: Cache) {
            log.error("캐시 전체 비우기 실패. cache={}", cache.name, exception)
            throw exception
        }
    }
}
