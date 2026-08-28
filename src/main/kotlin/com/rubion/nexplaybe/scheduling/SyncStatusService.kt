package com.rubion.nexplaybe.scheduling

import com.rubion.nexplaybe.cache.CacheConfig
import org.springframework.cache.annotation.Cacheable
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant

data class StepStatus(
    val step: String,
    val status: String,
    val lastRunAt: Instant,
    val detail: String?,
)

data class SyncStatus(
    /** 마지막으로 한 단계라도 성공한 시각. 이게 오래됐으면 수집이 멈춘 것이다. */
    val lastSuccessAt: Instant?,
    val hoursSinceSuccess: Long?,
    /** 하루 한 번 도는데 36시간 넘게 성공이 없으면 한 번은 통째로 건너뛴 것이다. */
    val stale: Boolean,
    val failedSteps: List<String>,
    val steps: List<StepStatus>,
    val totals: Map<String, Long>,
    /** 오늘 모델에 쓴 토큰. 얼마나 썼는지 밖에서 볼 수 없으면 통제할 수도 없다. */
    val llmToday: LlmUsageToday,
)

data class LlmUsageToday(val calls: Long, val totalTokens: Long, val budget: Long)

/**
 * 수집이 살아 있는지 밖에서 볼 수 있게 한다.
 *
 * 이 서비스의 값은 하루도 빠뜨리지 않고 쌓이는 데서 나온다. 그런데 멈춰도 아무도
 * 모르면, 알아챌 때는 이미 되찾을 수 없는 날들이 지나 있다.
 */
@Service
class SyncStatusService(
    private val jdbc: JdbcTemplate,
    @param:org.springframework.beans.factory.annotation.Value("\${nexplay.intelligence.daily-token-budget:200000}")
    private val llmBudget: Long,
) {

    @Cacheable(CacheConfig.SECTIONS, key = "'sync-status'")
    fun status(): SyncStatus {
        val steps = jdbc.query(
            """
            SELECT s.step, s.status, s.started_at, s.detail
            FROM sync_run s
            -- 시각으로 고른다. id 로 고르면 넣은 순서와 실제 실행 순서가 다를 때
            -- 옛 실패가 최신인 것처럼 올라온다.
            WHERE s.id = (
                SELECT s2.id FROM sync_run s2
                WHERE s2.step = s.step
                ORDER BY s2.started_at DESC, s2.id DESC LIMIT 1
            )
            ORDER BY s.step
            """.trimIndent(),
        ) { rs, _ ->
            StepStatus(
                rs.getString("step"), rs.getString("status"),
                rs.getTimestamp("started_at").toInstant(), rs.getString("detail"),
            )
        }

        val lastSuccess = jdbc.query(
            "SELECT MAX(finished_at) AS at FROM sync_run WHERE status = 'SUCCESS'",
        ) { rs, _ -> rs.getTimestamp("at")?.toInstant() }.firstOrNull()

        val hours = lastSuccess?.let { Duration.between(it, Instant.now()).toHours() }

        val totals = mapOf(
            "games" to countOf("SELECT COUNT(*) FROM game WHERE archive_only = 0"),
            "events" to countOf("SELECT COUNT(*) FROM game_event"),
            "archivedBodies" to countOf("SELECT COUNT(*) FROM raw_item WHERE raw_payload IS NOT NULL AND raw_payload <> ''"),
            "promises" to countOf("SELECT COUNT(*) FROM game_promise"),
        )

        val llm = jdbc.query(
            """
            SELECT COALESCE(SUM(calls),0) AS calls, COALESCE(SUM(total_tokens),0) AS tokens
            FROM llm_usage WHERE usage_date = CURRENT_DATE
            """.trimIndent(),
        ) { rs, _ -> LlmUsageToday(rs.getLong("calls"), rs.getLong("tokens"), llmBudget) }
            .firstOrNull() ?: LlmUsageToday(0, 0, llmBudget)

        return SyncStatus(
            lastSuccessAt = lastSuccess,
            hoursSinceSuccess = hours,
            // 하루 한 번 도는 일이라 36시간이면 한 번은 통째로 건너뛴 것이다.
            stale = hours == null || hours > STALE_AFTER_HOURS,
            failedSteps = steps.filter { it.status != "SUCCESS" }.map { it.step },
            steps = steps,
            totals = totals,
            llmToday = llm,
        )
    }

    private fun countOf(sql: String): Long = jdbc.queryForObject(sql, Long::class.java) ?: 0

    private companion object {
        const val STALE_AFTER_HOURS = 36L
    }
}
