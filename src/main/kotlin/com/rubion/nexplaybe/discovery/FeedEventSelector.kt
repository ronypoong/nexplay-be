package com.rubion.nexplaybe.discovery

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

data class FeedEventStats(
    val total: Int,
    val updateEvents: Int,
    val expansionEvents: Int,
)

/**
 * 홈에 올릴 소식을 고른다.
 *
 * 예전에는 최신순으로 전부 불러와 앞의 30건을 썼다. 지켜보는 게임이 46개에서
 * 405개로 늘자 홈이 영어 패치노트 벽이 됐다 — "Patch 1.3 | Full Changelog",
 * "PC Crash Report Thread", "Crash Fixes". 30건 중 한국어 요약이 붙은 것은 0건이었다.
 *
 * 모은 양이 늘었다고 홈이 나빠지면 늘린 의미가 없다. 적은 카드로 더 많은 사건을
 * 정확히 전달하는 것이 목표지, 최근 것을 다 보여주는 것이 목표가 아니다.
 *
 * 그래서 세 가지를 함께 본다.
 *   - 무슨 종류의 사건인가 (출시일·연기 > 트레일러 > 대규모 업데이트 > 패치)
 *   - 얼마나 눈여겨볼 게임인가
 *   - 우리가 한국어로 설명할 수 있는가 (요약이 붙은 것)
 *
 * 3,276건을 엔티티로 불러오던 것도 여기서 멈춘다. id 만 골라 필요한 만큼만 읽는다.
 */
@Component
class FeedEventSelector(private val jdbc: JdbcTemplate) {

    fun topEventIds(limit: Int, offset: Int = 0): List<Long> = jdbc.queryForList(
        """
        SELECT e.id
        FROM game_event e
        JOIN game g ON g.id = e.game_id
        LEFT JOIN game_event_extraction x ON x.event_id = e.id AND x.prompt_version = 1
        WHERE g.archive_only = 0
          AND COALESCE(x.is_marketing_noise, 0) = 0
        ORDER BY (
            CASE COALESCE(NULLIF(x.event_type, ''), e.type)
                WHEN 'RELEASE_DATE'  THEN 100
                WHEN 'DELAY'         THEN 100
                WHEN 'RELEASE'       THEN  95
                WHEN 'EXPANSION'     THEN  80
                WHEN 'DLC'           THEN  75
                WHEN 'DEMO'          THEN  72
                WHEN 'TRAILER'       THEN  70
                WHEN 'GAMEPLAY'      THEN  70
                WHEN 'BETA'          THEN  64
                WHEN 'PREORDER'      THEN  60
                WHEN 'DISCOUNT'      THEN  56
                WHEN 'MAJOR_UPDATE'  THEN  52
                WHEN 'ANNOUNCEMENT'  THEN  44
                WHEN 'PATCH'         THEN  16
                ELSE 40
            END
            + g.discovery_score / 5
            -- 한국어로 설명할 수 있는 소식을 앞세운다. 영어 제목만 남는 카드는
            -- 한국 사용자에게 아무것도 전달하지 못한다.
            + CASE WHEN x.summary_ko IS NOT NULL AND x.summary_ko <> '' THEN 18 ELSE 0 END
            -- 오래될수록 내린다. 60일이 지나면 더 내리지 않는다 — 그 아래로는
            -- 종류와 중요도가 날짜보다 낫다.
            - LEAST(GREATEST(DATEDIFF(CURRENT_DATE, e.event_date), 0), 60) / 2
        ) DESC, e.published_at DESC
        LIMIT ? OFFSET ?
        """.trimIndent(),
        Long::class.java,
        limit, offset,
    ).filterNotNull()

    /** 통계는 보여 주는 목록이 아니라 아카이브 전체를 센다. 자른 목록으로 세면 총계가 틀린다. */
    fun stats(): FeedEventStats = jdbc.query(
        """
        SELECT COUNT(*) AS total,
               SUM(e.type IN ('MAJOR_UPDATE','PATCH')) AS updates,
               SUM(e.type IN ('EXPANSION','DLC')) AS expansions
        FROM game_event e JOIN game g ON g.id = e.game_id
        WHERE g.archive_only = 0
        """.trimIndent(),
    ) { rs, _ -> FeedEventStats(rs.getInt("total"), rs.getInt("updates"), rs.getInt("expansions")) }
        .firstOrNull() ?: FeedEventStats(0, 0, 0)
}
