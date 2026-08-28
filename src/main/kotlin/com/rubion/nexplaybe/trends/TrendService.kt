package com.rubion.nexplaybe.trends

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import com.rubion.nexplaybe.cache.CacheConfig
import org.springframework.cache.annotation.Cacheable

data class MomentumEntry(
    val slug: String,
    val title: String,
    val releaseLabel: String,
    val current: Int,
    val previous: Int,
    val delta: Int,
)

data class DelayEntry(
    val slug: String,
    val title: String,
    val platform: String,
    val previousDate: String,
    val newDate: String,
    val changeType: String,
    val shiftDays: Int,
    val announcedAt: String,
)

data class StudioReliability(
    val studio: String,
    val trackedGames: Int,
    val delays: Int,
    val averageShiftDays: Int,
)

data class DataMaturity(val days: Int, val readyAt: Int, val ready: Boolean)

data class TrendsResponse(
    val momentumMaturity: DataMaturity,
    val delayMaturity: DataMaturity,
    val risingGames: List<MomentumEntry>,
    val recentChanges: List<DelayEntry>,
    val studios: List<StudioReliability>,
)

/**
 * 시간이 지나야 말할 수 있는 것들.
 *
 * 기대 지수 급상승은 popularity_snapshot 이 며칠 쌓여야 하고, 연기 예보는
 * release_revision 에 실제 날짜 변경이 들어와야 한다. 둘 다 오늘부터 쌓인다.
 * 재료가 없을 때 그럴듯한 숫자를 지어내지 않고, 며칠째인지를 그대로 알려준다.
 */
@Service
@Transactional(readOnly = true)
class TrendService(private val jdbc: JdbcTemplate) {

    @Cacheable(CacheConfig.SECTIONS, key = "'trends'")
    fun trends(): TrendsResponse {
        val snapshotDays = jdbc.queryForObject(
            "SELECT COUNT(DISTINCT snapshot_date) FROM popularity_snapshot", Int::class.java,
        ) ?: 0
        val changeCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM release_revision WHERE change_type <> 'INITIAL_CONFIRMATION'", Int::class.java,
        ) ?: 0

        val rising = if (snapshotDays < MIN_SNAPSHOT_DAYS) emptyList() else jdbc.query(
            """
            SELECT g.slug, g.title, g.release_label, latest.anticipation_score AS cur, oldest.anticipation_score AS prev
            FROM game g
            JOIN popularity_snapshot latest ON latest.game_id = g.id
              AND latest.snapshot_date = (SELECT MAX(snapshot_date) FROM popularity_snapshot)
            JOIN popularity_snapshot oldest ON oldest.game_id = g.id
              AND oldest.snapshot_date = (SELECT MIN(snapshot_date) FROM popularity_snapshot)
            WHERE latest.anticipation_score > oldest.anticipation_score
            ORDER BY (latest.anticipation_score - oldest.anticipation_score) DESC, g.discovery_score DESC
            LIMIT 12
            """.trimIndent(),
        ) { rs, _ ->
            val cur = rs.getBigDecimal("cur").toInt()
            val prev = rs.getBigDecimal("prev").toInt()
            MomentumEntry(rs.getString("slug"), rs.getString("title"), rs.getString("release_label"), cur, prev, cur - prev)
        }

        val changes = jdbc.query(
            """
            SELECT g.slug, g.title, r.platform, r.previous_date, r.new_date, r.change_type, r.announced_at
            FROM release_revision r JOIN game g ON g.id = r.game_id
            WHERE r.change_type <> 'INITIAL_CONFIRMATION' AND r.previous_date IS NOT NULL AND r.new_date IS NOT NULL
            ORDER BY r.announced_at DESC, r.id DESC
            LIMIT 20
            """.trimIndent(),
        ) { rs, _ ->
            val prev = rs.getDate("previous_date").toLocalDate()
            val next = rs.getDate("new_date").toLocalDate()
            DelayEntry(
                rs.getString("slug"), rs.getString("title"), rs.getString("platform"),
                prev.toString(), next.toString(), rs.getString("change_type"),
                java.time.temporal.ChronoUnit.DAYS.between(prev, next).toInt(),
                rs.getDate("announced_at").toLocalDate().toString(),
            )
        }

        val studios = jdbc.query(
            """
            SELECT c.name AS studio,
                   COUNT(DISTINCT r.game_id) AS tracked,
                   SUM(r.change_type = 'DELAY') AS delays,
                   AVG(DATEDIFF(r.new_date, r.previous_date)) AS avg_shift
            FROM release_revision r
            JOIN game g ON g.id = r.game_id
            JOIN company c ON c.id = g.developer_id
            WHERE r.change_type <> 'INITIAL_CONFIRMATION' AND r.previous_date IS NOT NULL AND r.new_date IS NOT NULL
            GROUP BY c.name
            HAVING COUNT(DISTINCT r.game_id) >= 1
            ORDER BY delays DESC, tracked DESC
            LIMIT 12
            """.trimIndent(),
            RowMapper { rs, _ ->
                StudioReliability(
                    rs.getString("studio"), rs.getInt("tracked"), rs.getInt("delays"),
                    rs.getBigDecimal("avg_shift")?.toInt() ?: 0,
                )
            },
        )

        return TrendsResponse(
            DataMaturity(snapshotDays, MIN_SNAPSHOT_DAYS, snapshotDays >= MIN_SNAPSHOT_DAYS),
            DataMaturity(changeCount, MIN_CHANGES, changeCount >= MIN_CHANGES),
            rising, changes, studios,
        )
    }

    private companion object {
        // 이틀치로 "급상승" 을 말하면 노이즈다. 일주일은 있어야 추세라 부를 수 있다.
        const val MIN_SNAPSHOT_DAYS = 7
        const val MIN_CHANGES = 1
    }
}
