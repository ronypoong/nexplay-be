package com.rubion.nexplaybe.intelligence

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/**
 * 모델이 소식에서 뽑아 둔 것.
 *
 * `game_event_extraction` 에 쌓고만 있었다. 뽑는 데 돈을 쓰고 아무 데도 안 썼으니,
 * 그 동안은 값이 0 이었던 셈이다.
 *
 * 목록을 그릴 때마다 하나씩 조회하면 N+1 이 된다. 한 번에 받아 맵으로 돌려준다.
 */
@Component
class EventInsightLookup(private val jdbc: JdbcTemplate) {

    fun insights(): Map<Long, EventInsight> = jdbc.query(
        """
        SELECT event_id, event_type, confidence, summary_ko, discount_percent,
               has_demo, is_marketing_noise
        FROM game_event_extraction
        WHERE prompt_version = ?
        """.trimIndent(),
        { rs, _ ->
            rs.getLong("event_id") to EventInsight(
                type = rs.getString("event_type"),
                confidence = rs.getString("confidence"),
                summaryKo = rs.getString("summary_ko")?.takeIf(String::isNotBlank),
                discountPercent = rs.getInt("discount_percent").takeUnless { rs.wasNull() },
                hasDemo = rs.getBoolean("has_demo"),
                marketingNoise = rs.getBoolean("is_marketing_noise"),
            )
        },
        PROMPT_VERSION,
    ).toMap()

    private companion object {
        const val PROMPT_VERSION = 1
    }
}

data class EventInsight(
    val type: String,
    val confidence: String,
    val summaryKo: String?,
    val discountPercent: Int?,
    val hasDemo: Boolean,
    val marketingNoise: Boolean,
) {
    /**
     * 규칙 분류를 대신할 만한가.
     *
     * 규칙은 제목 한 줄만 보고 363건 중 203건을 ANNOUNCEMENT 로 뭉갰다. 모델은 본문을
     * 읽지만, 확신이 낮은 판단까지 덮어쓰면 틀린 분류를 더 그럴듯하게 만들 뿐이다.
     */
    val trustworthyType: String? get() = type.takeIf { confidence == "HIGH" }
}
