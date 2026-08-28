package com.rubion.nexplaybe.cache

import org.slf4j.LoggerFactory
import org.springframework.cache.CacheManager
import org.springframework.stereotype.Component

/**
 * 쓰기 뒤에 읽기 캐시를 비운다.
 *
 * TTL 만 믿으면 관리 화면에서 고친 것이 최대 10분 동안 안 보인다. 고쳤는데 안 보이면
 * 다들 한 번 더 고치게 되고, 그게 더 비싸다.
 */
@Component
class ReadCacheEvictor(private val cacheManager: CacheManager) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun evictAll() {
        CacheConfig.NAMES.forEach { cacheManager.getCache(it)?.clear() }
        log.info("읽기 캐시를 비웠습니다")
    }

    /** 실패해도 본 작업을 되돌리지 않는다. 캐시를 못 비운 건 최대 10분 늦게 보이는 문제일 뿐이다. */
    fun evictQuietly() = runCatching { evictAll() }
        .onFailure { log.warn("읽기 캐시 비우기 실패: {}", it.message) }
        .let { }
}
