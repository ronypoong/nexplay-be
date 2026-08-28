package com.rubion.nexplaybe.intelligence

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import com.rubion.nexplaybe.cache.CacheConfig
import org.springframework.cache.annotation.Cacheable

data class PromiseRow(
    val gameSlug: String,
    val gameTitle: String,
    val claimType: String,
    val claimedValue: String,
    val announcedAt: LocalDate,
    val status: String,
    val slipDays: Int?,
    val sourceQuote: String?,
    val evidence: String?,
)

data class PublisherScorecard(
    val companyId: Long,
    val name: String,
    val promises: Int,
    val kept: Int,
    val broken: Int,
    val superseded: Int,
    val pending: Int,
    /** 판정 난 약속 중 지킨 비율. 아직 결과를 모르는 약속은 분모에서 뺀다. */
    val keptRate: Double?,
    /** 밀린 약속들의 중앙값. 평균은 한 건에 휘둘려서 쓰지 않는다. */
    val medianSlipDays: Int?,
)

data class PromiseLedger(
    val totals: Map<String, Int>,
    val scorecards: List<PublisherScorecard>,
    val recentSlips: List<PromiseRow>,
    val note: String,
)

/**
 * 대조표 읽기. 점수는 저장하지 않고 매번 계산한다 — 판정이 바뀌면 점수도 따라 바뀌어야 한다.
 * BACKTEST 로 표시된 약속은 근거가 제목뿐이라 점수에서 제외한다.
 */
@Service
@Transactional(readOnly = true)
class PromiseQueryService(private val jdbc: JdbcTemplate) {

    @Cacheable(CacheConfig.SECTIONS, key = "'promises'")
    fun ledger(): PromiseLedger {
        val totals = jdbc.query(
            "SELECT r.status, COUNT(*) FROM game_promise p JOIN game_promise_resolution r ON r.promise_id = p.id " +
                "WHERE p.provenance = 'LIVE' GROUP BY r.status",
        ) { rs, _ -> rs.getString(1) to rs.getInt(2) }.toMap()

        val scorecards = jdbc.query(
            """
            SELECT c.id, c.name,
                   COUNT(*) AS promises,
                   SUM(r.status = 'KEPT') AS kept,
                   SUM(r.status = 'BROKEN') AS broken,
                   SUM(r.status = 'SUPERSEDED') AS superseded,
                   SUM(r.status = 'PENDING') AS pending
            FROM game_promise p
            JOIN game_promise_resolution r ON r.promise_id = p.id
            JOIN game g ON g.id = p.game_id
            JOIN company c ON c.id = g.publisher_id
            WHERE p.provenance = 'LIVE'
            GROUP BY c.id, c.name
            HAVING promises >= ?
            ORDER BY promises DESC
            """.trimIndent(),
            { rs, _ ->
                val kept = rs.getInt("kept")
                val broken = rs.getInt("broken")
                val superseded = rs.getInt("superseded")
                val judged = kept + broken + superseded
                PublisherScorecard(
                    companyId = rs.getLong("id"),
                    name = rs.getString("name"),
                    promises = rs.getInt("promises"),
                    kept = kept,
                    broken = broken,
                    superseded = superseded,
                    pending = rs.getInt("pending"),
                    keptRate = if (judged >= MIN_JUDGED) kept.toDouble() / judged else null,
                    medianSlipDays = null,
                )
            },
            MIN_PROMISES,
        )

        // 밀린 일수는 따로 가져와 코드에서 중앙값을 낸다. GROUP_CONCAT 은 1024자에서 말없이
        // 잘리기 때문에, 약속이 쌓일수록 중앙값이 조용히 틀어진다.
        val slipsByCompany = jdbc.query(
            """
            SELECT g.publisher_id, r.slip_days
            FROM game_promise p
            JOIN game_promise_resolution r ON r.promise_id = p.id
            JOIN game g ON g.id = p.game_id
            WHERE p.provenance = 'LIVE' AND r.slip_days > 0 AND g.publisher_id IS NOT NULL
            """.trimIndent(),
        ) { rs, _ -> rs.getLong(1) to rs.getInt(2) }
            .groupBy({ it.first }, { it.second })

        val recentSlips = jdbc.query(
            """
            SELECT g.slug, g.title, p.claim_type, p.claimed_value, p.announced_at,
                   r.status, r.slip_days, p.source_quote, r.evidence
            FROM game_promise p
            JOIN game_promise_resolution r ON r.promise_id = p.id
            JOIN game g ON g.id = p.game_id
            WHERE p.provenance = 'LIVE' AND r.status IN ('SUPERSEDED','BROKEN') AND r.slip_days > 0
            ORDER BY r.slip_days DESC, p.announced_at DESC
            LIMIT 30
            """.trimIndent(),
        ) { rs, _ ->
            PromiseRow(
                rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getDate(5).toLocalDate(), rs.getString(6),
                rs.getInt(7).takeUnless { rs.wasNull() }, rs.getString(8), rs.getString(9),
            )
        }

        return PromiseLedger(
            totals = totals,
            scorecards = scorecards.map { it.copy(medianSlipDays = median(slipsByCompany[it.companyId])) },
            recentSlips = recentSlips,
            note = "공식 발표 원문에서 뽑은 약속만 셉니다. 판정은 모델이 아니라 실제 출시일·언어 이력과 대조해 정합니다.",
        )
    }

    /** 한 게임의 약속 이력. 발표 순서대로 읽으면 그 게임이 걸어온 길이 그대로 나온다. */
    @Cacheable(CacheConfig.GAME_PROMISES)
    fun forGame(slug: String): List<PromiseRow> = jdbc.query(
        """
        SELECT g.slug, g.title, p.claim_type, p.claimed_value, p.announced_at,
               COALESCE(r.status,'PENDING'), r.slip_days, p.source_quote, r.evidence
        FROM game_promise p
        JOIN game g ON g.id = p.game_id
        LEFT JOIN game_promise_resolution r ON r.promise_id = p.id
        WHERE g.slug = ?
        ORDER BY p.announced_at
        """.trimIndent(),
        { rs, _ ->
            PromiseRow(
                rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getDate(5).toLocalDate(), rs.getString(6),
                rs.getInt(7).takeUnless { rs.wasNull() }, rs.getString(8), rs.getString(9),
            )
        },
        slug,
    )

    private fun median(raw: List<Int>?): Int? {
        val values = raw?.sorted().orEmpty()
        if (values.isEmpty()) return null
        val mid = values.size / 2
        return if (values.size % 2 == 1) values[mid] else (values[mid - 1] + values[mid]) / 2
    }

    private companion object {
        /** 약속 한두 건으로 퍼블리셔를 평가하면 그 표는 거짓말이 된다. */
        const val MIN_PROMISES = 3
        const val MIN_JUDGED = 3
    }
}
