package com.rubion.nexplaybe.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * 관리 API 보호.
 *
 * `/api/v1/admin` 이하는 수집기를 돌리고 카탈로그와 주인장 픽을 고친다. 운영 DB 에 붙어 있고
 * 저장소가 공개라 엔드포인트 목록도 공개돼 있으므로, 인증 없이 열어두면 누구나 데이터를
 * 바꿀 수 있다.
 *
 * 토큰이 설정되지 않으면 **막는다.** 설정을 깜빡했을 때 열린 채로 뜨는 것보다
 * 닫힌 채로 뜨는 편이 안전하다.
 */
@Component
class AdminTokenFilter(
    @param:Value("\${nexplay.admin.token:}") private val configuredToken: String,
) : OncePerRequestFilter() {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
        if (!request.requestURI.startsWith(ADMIN_PREFIX)) {
            filterChain.doFilter(request, response)
            return
        }
        // CORS preflight 는 자격 증명을 싣지 않는다. 여기서 막으면 브라우저가 본 요청을 보내지 못한다.
        if (request.method.equals("OPTIONS", ignoreCase = true)) {
            filterChain.doFilter(request, response)
            return
        }
        if (configuredToken.isBlank()) {
            log.warn("관리 API 요청이 거부됐습니다. NEXPLAY_ADMIN_TOKEN 이 설정되지 않았습니다. path={}", request.requestURI)
            deny(response, HttpStatus.SERVICE_UNAVAILABLE, "관리 API 토큰이 설정되지 않아 비활성화된 상태입니다.")
            return
        }
        val provided = request.getHeader(TOKEN_HEADER)
        if (provided == null || !matches(provided)) {
            deny(response, HttpStatus.UNAUTHORIZED, "관리 API 토큰이 필요합니다.")
            return
        }
        filterChain.doFilter(request, response)
    }

    /** 길이·내용 비교 시간이 입력에 따라 달라지지 않게 한다. */
    private fun matches(provided: String) = MessageDigest.isEqual(
        configuredToken.toByteArray(StandardCharsets.UTF_8),
        provided.toByteArray(StandardCharsets.UTF_8),
    )

    private fun deny(response: HttpServletResponse, status: HttpStatus, message: String) {
        response.status = status.value()
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = "UTF-8"
        response.writer.write("""{"status":${status.value()},"error":"${status.reasonPhrase}","message":"$message"}""")
    }

    private companion object {
        const val ADMIN_PREFIX = "/api/v1/admin"
        const val TOKEN_HEADER = "X-NEXPLAY-Admin-Token"
    }
}
