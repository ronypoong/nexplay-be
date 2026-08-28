package com.rubion.nexplaybe.cache

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor

/**
 * 관리 API 로 무언가를 바꿨으면 읽기 캐시를 비운다.
 *
 * 각 관리 메서드마다 비우는 코드를 넣으면, 나중에 추가되는 엔드포인트에서 빠뜨린다.
 * 빠뜨렸는지 알아채는 방법은 "고쳤는데 화면이 안 바뀐다" 뿐이라 원인을 찾기도 어렵다.
 * 여기 한 곳에서 처리하면 새 엔드포인트도 자동으로 포함된다.
 */
@Component
class AdminWriteCacheInterceptor(private val evictor: ReadCacheEvictor) : HandlerInterceptor {

    override fun afterCompletion(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
        ex: Exception?,
    ) {
        // 조회는 아무것도 바꾸지 않는다. 실패한 요청도 마찬가지다.
        if (request.method == "GET" || request.method == "OPTIONS") return
        if (ex != null || response.status >= 400) return
        evictor.evictQuietly()
    }
}
