package com.rubion.nexplaybe.security

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

class AdminTokenFilterTest {
    private fun run(filter: AdminTokenFilter, uri: String, token: String? = null, method: String = "POST"): Pair<MockHttpServletResponse, Boolean> {
        val request = MockHttpServletRequest(method, uri).apply {
            requestURI = uri
            token?.let { addHeader("X-NEXPLAY-Admin-Token", it) }
        }
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()
        filter.doFilter(request, response, chain)
        return response to (chain.request != null)
    }

    @Test
    fun `공개 API 는 토큰 없이 통과한다`() {
        val (response, passed) = run(AdminTokenFilter("secret"), "/api/v1/feed", method = "GET")
        assertTrue(passed)
        assertEquals(200, response.status)
    }

    @Test
    fun `토큰이 설정되지 않으면 관리 API 를 막는다`() {
        val (response, passed) = run(AdminTokenFilter(""), "/api/v1/admin/collectors/steam/run")
        assertEquals(503, response.status, "설정을 잊었을 때 열린 채로 두면 안 된다")
        assertTrue(!passed)
    }

    @Test
    fun `토큰이 없거나 틀리면 401 이다`() {
        val filter = AdminTokenFilter("secret")
        assertEquals(401, run(filter, "/api/v1/admin/editor-picks").first.status)
        assertEquals(401, run(filter, "/api/v1/admin/editor-picks", "wrong").first.status)
        assertEquals(401, run(filter, "/api/v1/admin/editor-picks", "secre").first.status)
    }

    @Test
    fun `올바른 토큰이면 통과한다`() {
        val (response, passed) = run(AdminTokenFilter("secret"), "/api/v1/admin/editor-picks", "secret")
        assertTrue(passed)
        assertEquals(200, response.status)
    }

    @Test
    fun `CORS preflight 는 통과시킨다`() {
        val (_, passed) = run(AdminTokenFilter("secret"), "/api/v1/admin/editor-picks", method = "OPTIONS")
        assertTrue(passed, "preflight 를 막으면 브라우저가 본 요청을 보내지 못한다")
    }
}
