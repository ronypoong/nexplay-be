package com.rubion.nexplaybe.cache

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * 공개 조회 응답에 캐시 수명을 붙인다.
 *
 * 헤더가 없어서 Cloudflare 가 `cf-cache-status: DYNAMIC` 으로 흘려보내고, 프론트도
 * `no-store` 라 방문 한 번이 그대로 원본 호출이 됐다. 서울에서 원본까지 왕복이
 * 0.7초쯤 되는데, 이건 서버를 아무리 빠르게 해도 줄지 않는 몫이다.
 *
 * 헤더는 반드시 **응답을 쓰기 전에** 넣는다. 톰캣 응답 버퍼는 8KB 라 그보다 큰 응답은
 * 컨트롤러가 쓰는 도중 이미 커밋된다. 커밋된 뒤의 `setHeader` 는 예외도 없이 그냥
 * 무시되므로, 나중에 넣으면 정작 가장 큰 `/games`(439KB) 에만 헤더가 빠진다.
 *
 * 오류 응답은 캐시되면 안 된다. 오류 본문은 작아서 아직 커밋 전이므로 그때 되돌린다.
 */
@Component
class HttpCacheHeaders : OncePerRequestFilter() {

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        // HEAD 는 톰캣이 같은 핸들러로 처리하고 본문만 버린다. CDN 이 HEAD 로 물어볼 때
        // 헤더가 다르면 캐시 판단이 갈린다.
        val cacheable = (request.method == "GET" || request.method == "HEAD") &&
            request.requestURI.startsWith("/api/v1/") &&
            // 관리 API 는 토큰이 있어야 보이는 응답이다. 어디에도 캐시되면 안 된다.
            !request.requestURI.startsWith("/api/v1/admin")

        if (cacheable) {
            response.setHeader(CACHE_CONTROL, "public, max-age=$MAX_AGE, stale-while-revalidate=$STALE")
        }

        chain.doFilter(request, response)

        if (cacheable && response.status >= 400 && !response.isCommitted) {
            response.setHeader(CACHE_CONTROL, "no-store")
        }
    }

    private companion object {
        const val CACHE_CONTROL = "Cache-Control"
        /** 서버 캐시 수명과 맞춘다. 더 길게 잡으면 캐시를 비워도 바깥이 안 따라온다. */
        const val MAX_AGE = 600
        const val STALE = 3600
    }
}
