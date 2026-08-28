package com.rubion.nexplaybe.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

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
    private val jdbc: JdbcTemplate,
    @param:Value("\${nexplay.admin.token:}") private val configuredToken: String,
    @param:Value("\${nexplay.admin.max-requests-per-minute:60}") private val maxPerMinute: Int,
    @param:Value("\${nexplay.admin.max-failures-per-hour:10}") private val maxFailuresPerHour: Int,
) : OncePerRequestFilter() {

    private val log = LoggerFactory.getLogger(javaClass)

    /*
     * 토큰은 40자 238비트라 추측으로는 뚫리지 않는다. 그래도 횟수를 제한한다.
     *
     * 첫째, 틀린 토큰을 계속 던지는 것은 정상 사용이 아니다. 그런 주소는 잠근다.
     * 둘째, 토큰이 새더라도 무제한으로 쓰지 못하게 한다 — 수집 작업은 무거워서
     * 반복 호출만으로도 서비스를 눕힐 수 있다.
     *
     * 메모리에 둔다. 재시작하면 풀리지만 토큰 예산과 달리 이건 손해가 아니다.
     */
    private val recentRequests = ConcurrentHashMap<String, MutableList<Instant>>()
    private val recentFailures = ConcurrentHashMap<String, MutableList<Instant>>()

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
        val client = clientKey(request)

        if (tooMany(recentFailures, client, Duration.ofHours(1), maxFailuresPerHour)) {
            log.warn("관리 API: 인증 실패가 잦아 차단했습니다. ip={}", client)
            deny(response, HttpStatus.TOO_MANY_REQUESTS, "잠시 후 다시 시도해주세요.")
            return
        }
        if (tooMany(recentRequests, client, Duration.ofMinutes(1), maxPerMinute)) {
            deny(response, HttpStatus.TOO_MANY_REQUESTS, "요청이 너무 잦습니다.")
            return
        }
        record(recentRequests, client)

        if (configuredToken.isBlank()) {
            log.warn("관리 API 요청이 거부됐습니다. NEXPLAY_ADMIN_TOKEN 이 설정되지 않았습니다. path={}", request.requestURI)
            deny(response, HttpStatus.SERVICE_UNAVAILABLE, "관리 API 토큰이 설정되지 않아 비활성화된 상태입니다.")
            return
        }
        val provided = request.getHeader(TOKEN_HEADER)
        if (provided == null || !matches(provided)) {
            record(recentFailures, client)
            log.warn("관리 API: 잘못된 토큰. ip={} path={}", client, request.requestURI)
            audit(request, HttpStatus.UNAUTHORIZED.value(), client)
            deny(response, HttpStatus.UNAUTHORIZED, "관리 API 토큰이 필요합니다.")
            return
        }
        filterChain.doFilter(request, response)
        // 성공한 요청도 남긴다. 토큰이 새더라도 무엇이 일어났는지는 알 수 있어야 한다.
        if (!request.method.equals("GET", ignoreCase = true)) {
            audit(request, response.status, client)
        }
    }

    /**
     * 접속 주소를 그대로 남기지 않는다. 어느 대역에서 들어왔는지만 알면
     * 이상한 접근을 알아챌 수 있고, 그 이상은 필요 없다.
     */
    private fun clientKey(request: HttpServletRequest): String =
        (request.getHeader("CF-Connecting-IP")
            ?: request.getHeader("X-Forwarded-For")?.substringBefore(',')?.trim()
            ?: request.remoteAddr
            ?: "unknown")

    private fun maskedIp(ip: String): String = when {
        ip.contains(':') -> ip.split(':').take(3).joinToString(":") + "::"
        ip.count { it == '.' } == 3 -> ip.substringBeforeLast('.') + ".0"
        else -> "unknown"
    }

    private fun audit(request: HttpServletRequest, status: Int, client: String) {
        runCatching {
            jdbc.update(
                "INSERT INTO admin_audit (method, path, status, ip_prefix) VALUES (?,?,?,?)",
                request.method, request.requestURI.take(300), status, maskedIp(client),
            )
        }.onFailure { log.warn("관리 기록 저장 실패: {}", it.message) }
    }

    private fun tooMany(store: MutableMap<String, MutableList<Instant>>, key: String, window: Duration, max: Int): Boolean {
        val cutoff = Instant.now().minus(window)
        val list = store[key] ?: return false
        synchronized(list) {
            list.removeAll { it.isBefore(cutoff) }
            return list.size >= max
        }
    }

    private fun record(store: MutableMap<String, MutableList<Instant>>, key: String) {
        // 주소가 무한정 쌓이지 않게 한다. 오래된 항목이 없으면 통째로 비운다.
        if (store.size > MAX_TRACKED_CLIENTS) store.clear()
        val list = store.computeIfAbsent(key) { mutableListOf() }
        synchronized(list) { list.add(Instant.now()) }
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
        const val MAX_TRACKED_CLIENTS = 5_000
    }
}
