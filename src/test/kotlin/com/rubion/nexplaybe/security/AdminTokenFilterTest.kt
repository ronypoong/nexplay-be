package com.rubion.nexplaybe.security

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.mockito.Mockito.mock
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

class AdminTokenFilterTest {
    /**
     * 기록은 DB 에 남지만 여기서 확인할 것은 통과 여부다. 시험마다 새 대역을 쓰고
     * 실패 한도를 넉넉히 둔다 — 한도 자체는 별도로 확인한다.
     */
    private fun filterWith(token: String, maxFailures: Int = 100) =
        AdminTokenFilter(mock(JdbcTemplate::class.java), token, 1000, maxFailures)

    private fun run(filter: AdminTokenFilter, uri: String, token: String? = null, method: String = "POST"): Pair<MockHttpServletResponse, Boolean> {
        val request = MockHttpServletRequest(method, uri).apply {
            requestURI = uri
            // 시험끼리 같은 주소를 쓰면 앞 시험의 실패가 뒤 시험을 잠근다.
            addHeader("CF-Connecting-IP", "10.${(0..250).random()}.${(0..250).random()}.1")
            token?.let { addHeader("X-NEXPLAY-Admin-Token", it) }
        }
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()
        filter.doFilter(request, response, chain)
        return response to (chain.request != null)
    }

    @Test
    fun `공개 API 는 토큰 없이 통과한다`() {
        val (response, passed) = run(filterWith("secret"), "/api/v1/feed", method = "GET")
        assertTrue(passed)
        assertEquals(200, response.status)
    }

    @Test
    fun `토큰이 설정되지 않으면 관리 API 를 막는다`() {
        val (response, passed) = run(filterWith(""), "/api/v1/admin/collectors/steam/run")
        assertEquals(503, response.status, "설정을 잊었을 때 열린 채로 두면 안 된다")
        assertTrue(!passed)
    }

    @Test
    fun `토큰이 없거나 틀리면 401 이다`() {
        val filter = filterWith("secret")
        assertEquals(401, run(filter, "/api/v1/admin/editor-picks").first.status)
        assertEquals(401, run(filter, "/api/v1/admin/editor-picks", "wrong").first.status)
        assertEquals(401, run(filter, "/api/v1/admin/editor-picks", "secre").first.status)
    }

    @Test
    fun `올바른 토큰이면 통과한다`() {
        val (response, passed) = run(filterWith("secret"), "/api/v1/admin/editor-picks", "secret")
        assertTrue(passed)
        assertEquals(200, response.status)
    }

    @Test
    fun `CORS preflight 는 통과시킨다`() {
        val (_, passed) = run(filterWith("secret"), "/api/v1/admin/editor-picks", method = "OPTIONS")
        assertTrue(passed, "preflight 를 막으면 브라우저가 본 요청을 보내지 못한다")
    }

    @Test
    fun `틀린 토큰을 반복하면 잠근다`() {
        val filter = filterWith("secret", maxFailures = 3)
        val ip = "203.0.113.7"
        fun attempt(token: String): Int {
            val request = MockHttpServletRequest("POST", "/api/v1/admin/editor-picks").apply {
                requestURI = "/api/v1/admin/editor-picks"
                addHeader("CF-Connecting-IP", ip)
                addHeader("X-NEXPLAY-Admin-Token", token)
            }
            val response = MockHttpServletResponse()
            filter.doFilter(request, response, MockFilterChain())
            return response.status
        }
        assertEquals(401, attempt("wrong"))
        assertEquals(401, attempt("wrong"))
        assertEquals(401, attempt("wrong"))
        assertEquals(429, attempt("wrong"), "한도를 넘으면 잠근다")
        // 잠긴 주소는 올바른 토큰도 막힌다. 토큰을 아는 쪽이면 애초에 틀릴 일이 없다.
        assertEquals(429, attempt("secret"))
    }
}
