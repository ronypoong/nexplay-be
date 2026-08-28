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

/**
 * 꾸준히 소식을 내던 미출시작이 조용해진 것.
 *
 * 연기 발표는 사후에 나오지만 침묵은 그 전에 나온다. 다만 이것을 "연기될 것" 이라고
 * 말하지는 않는다 — 개발을 접은 팀도, 그냥 조용한 팀도 같은 모습이다. 관측된 사실만
 * 적고 판단은 보는 사람에게 맡긴다.
 */
data class SilenceEntry(
    val slug: String,
    val title: String,
    val releaseLabel: String,
    /** 평소 간격을 낼 때 쓴 소식 수. 적을수록 흔들리므로 화면에 함께 보여 준다. */
    val newsCount: Int,
    val typicalGapDays: Int,
    val silentDays: Int,
    val lastNewsAt: String,
)

data class DataMaturity(val days: Int, val readyAt: Int, val ready: Boolean)

data class TrendsResponse(
    val momentumMaturity: DataMaturity,
    val delayMaturity: DataMaturity,
    val risingGames: List<MomentumEntry>,
    val recentChanges: List<DelayEntry>,
    val studios: List<StudioReliability>,
    val silentGames: List<SilenceEntry>,
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
        /*
         * 연기 기록은 두 곳에서 나온다.
         *
         * release_revision 은 Wikidata 의 날짜가 바뀔 때 쌓이는데, 670건이 전부
         * INITIAL_CONFIRMATION 이고 실제 변경은 0건이다. 위키데이터는 출시일이
         * 확정된 뒤에나 갱신되기 때문에 "밀렸다" 는 사실이 거기 남지 않는다.
         *
         * 진짜 연기는 게임사가 스스로 말한다 — "2025 에 낸다" 고 했다가 나중에
         * "2026 가을" 이라고 하는 식이다. 그게 약속 대조표에 그대로 있다.
         */
        val changeCount = jdbc.queryForObject(
            """
            SELECT (SELECT COUNT(*) FROM release_revision WHERE change_type <> 'INITIAL_CONFIRMATION')
                 + (SELECT COUNT(*) FROM game_promise p JOIN game_promise_resolution r ON r.promise_id = p.id
                    WHERE p.claim_type = 'RELEASE_DATE' AND p.provenance = 'LIVE'
                      AND r.status = 'SUPERSEDED' AND r.slip_days > 0)
            """.trimIndent(), Int::class.java,
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
            SELECT g.slug, g.title, p.claimed_value AS prev_label, r.actual_value AS next_label,
                   r.slip_days, p.announced_at
            FROM game_promise p
            JOIN game_promise_resolution r ON r.promise_id = p.id
            JOIN game g ON g.id = p.game_id
            WHERE p.claim_type = 'RELEASE_DATE' AND p.provenance = 'LIVE'
              AND r.status = 'SUPERSEDED' AND r.actual_value IS NOT NULL
              -- 하루 이틀 차이는 연기가 아니라 표현 차이다. "August 20" 과
              -- "in just two days" 를 견주면 +1일이 나오는데, 그건 같은 말이다.
              AND r.slip_days >= ?
              -- 같은 게임의 같은 연기가 발표마다 한 줄씩 쌓인다. 게임당 가장 크게
              -- 밀린 것 하나만 싣는다 — 목록이 한 게임으로 도배되면 못 읽는다.
              AND r.slip_days = (
                  SELECT MAX(r2.slip_days) FROM game_promise p2
                  JOIN game_promise_resolution r2 ON r2.promise_id = p2.id
                  WHERE p2.game_id = p.game_id AND p2.claim_type = 'RELEASE_DATE'
                    AND p2.provenance = 'LIVE' AND r2.status = 'SUPERSEDED'
              )
            GROUP BY g.slug, g.title, p.claimed_value, r.actual_value, r.slip_days, p.announced_at
            ORDER BY r.slip_days DESC, p.announced_at DESC
            LIMIT 20
            """.trimIndent(),
            RowMapper { rs, _ ->
                DelayEntry(
                    rs.getString("slug"), rs.getString("title"),
                    // 근거가 무엇인지 화면에 그대로 적는다. 추정한 날짜가 아니라
                    // 게임사가 공식 발표에서 한 말이다.
                    "공식 발표",
                    rs.getString("prev_label"), rs.getString("next_label"),
                    "DELAY", rs.getInt("slip_days"),
                    rs.getDate("announced_at").toLocalDate().toString(),
                )
            },
            MIN_SHIFT_DAYS,
        )

        val studios = jdbc.query(
            """
            SELECT c.name AS studio,
                   COUNT(DISTINCT p.game_id) AS tracked,
                   SUM(r.status = 'SUPERSEDED') AS delays,
                   AVG(CASE WHEN r.slip_days > 0 THEN r.slip_days END) AS avg_shift
            FROM game_promise p
            JOIN game_promise_resolution r ON r.promise_id = p.id
            JOIN game g ON g.id = p.game_id
            JOIN company c ON c.id = g.publisher_id
            WHERE p.claim_type = 'RELEASE_DATE' AND p.provenance = 'LIVE'
              -- 퍼블리셔를 못 찾은 게임의 자리표시자다. 이름이 아니라 빈칸이라
              -- 신뢰도를 매길 대상이 아니다.
              AND c.slug <> 'independent-unknown'
            GROUP BY c.name
            -- 약속 한두 건으로 스튜디오를 평가하면 그 표가 거짓말이 된다.
            HAVING COUNT(*) >= 3 AND delays > 0
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

        /*
         * 침묵은 "평소보다 얼마나 오래 조용한가" 로 잰다. 절대 일수로 재면 원래
         * 분기에 한 번 말하는 팀이 매번 걸린다.
         *
         * 소식이 적으면 평소 간격 자체가 못 미덥다. 그래서 최소 건수를 두고,
         * 그 건수를 화면에도 함께 내보내 보는 사람이 얼마나 믿을지 정하게 한다.
         */
        val silent = jdbc.query(
            """
            SELECT g.slug, g.title, g.release_label,
                   COUNT(*) AS news_count,
                   DATEDIFF(MAX(e.event_date), MIN(e.event_date)) / (COUNT(*) - 1) AS gap,
                   DATEDIFF(CURRENT_DATE, MAX(e.event_date)) AS silent_days,
                   MAX(e.event_date) AS last_news
            FROM game_event e
            JOIN game g ON g.id = e.game_id
            -- 출시된 게임이 조용한 것은 이상한 일이 아니다. 아직 안 나온 게임만 본다.
            WHERE g.status = 'UPCOMING' AND g.archive_only = 0
            GROUP BY g.id, g.slug, g.title, g.release_label
            HAVING news_count >= ?
               AND gap > 0
               AND silent_days > ? * gap
               AND silent_days > ?
            ORDER BY silent_days / gap DESC
            LIMIT 12
            """.trimIndent(),
            RowMapper { rs, _ ->
                SilenceEntry(
                    rs.getString("slug"), rs.getString("title"), rs.getString("release_label"),
                    rs.getInt("news_count"), rs.getBigDecimal("gap").toInt(),
                    rs.getInt("silent_days"), rs.getDate("last_news").toLocalDate().toString(),
                )
            },
            MIN_NEWS_FOR_RHYTHM, SILENCE_MULTIPLE, MIN_SILENT_DAYS,
        )

        return TrendsResponse(
            DataMaturity(snapshotDays, MIN_SNAPSHOT_DAYS, snapshotDays >= MIN_SNAPSHOT_DAYS),
            DataMaturity(changeCount, MIN_CHANGES, changeCount >= MIN_CHANGES),
            rising, changes, studios, silent,
        )
    }

    private companion object {
        // 이틀치로 "급상승" 을 말하면 노이즈다. 일주일은 있어야 추세라 부를 수 있다.
        const val MIN_SNAPSHOT_DAYS = 7
        const val MIN_CHANGES = 1
        /** 하루 이틀 차이는 연기가 아니라 같은 날짜를 다르게 말한 것이다. */
        const val MIN_SHIFT_DAYS = 7

        /** 소식 서너 건으로 "평소 간격" 을 말하면 흔들린다. */
        const val MIN_NEWS_FOR_RHYTHM = 4
        /** 평소의 세 배는 조용해야 눈에 띄는 침묵이다. */
        const val SILENCE_MULTIPLE = 3
        /** 원래 뜸한 팀이 매번 걸리지 않도록 절대 하한도 둔다. */
        const val MIN_SILENT_DAYS = 60
    }
}
