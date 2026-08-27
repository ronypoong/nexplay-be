package com.rubion.nexplaybe.catalog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SteamStoreClientTest {
    private val client = SteamStoreClient(timeoutSeconds = 1)

    // 실제 appdetails?l=korean 응답 형태. 목록 끝에 "음성이 지원되는 언어" 각주가 붙어 온다.
    @Test
    fun `strips the full-audio footnote from the last language`() {
        val languages = client.parseLanguages(
            "영어<strong>*</strong>, 프랑스어, 한국어, 아랍어<br><strong>*</strong>음성이 지원되는 언어",
        )

        assertEquals(listOf("en", "fr", "ko", "ar"), languages.map { it.code })
        assertEquals("아랍어", languages.last().name)
        assertFalse(languages.last().audio, "각주 때문에 마지막 언어가 음성 지원으로 오탐되면 안 된다")
    }

    @Test
    fun `detects korean even when it is listed last`() {
        val languages = client.parseLanguages(
            "영어<strong>*</strong>, 한국어<br><strong>*</strong>음성이 지원되는 언어",
        )

        val korean = languages.find { it.code == "ko" }
        assertNotNull(korean, "한국어가 마지막에 오면 예전에는 통째로 놓쳤다")
        assertFalse(korean.audio)
    }

    @Test
    fun `keeps the asterisk as the full-audio marker`() {
        val languages = client.parseLanguages(
            "English<strong>*</strong>, Korean<strong>*</strong>, French<br><strong>*</strong>languages with full audio support",
        )

        assertTrue(languages.first { it.code == "en" }.audio)
        assertTrue(languages.first { it.code == "ko" }.audio)
        assertFalse(languages.first { it.code == "fr" }.audio)
    }

    @Test
    fun `returns nothing when steam sends no language list`() {
        assertEquals(emptyList(), client.parseLanguages(""))
        assertNull(client.parseLanguages("").firstOrNull())
    }
}
