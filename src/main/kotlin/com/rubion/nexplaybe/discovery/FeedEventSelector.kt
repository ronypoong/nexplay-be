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

    /**
     * 점수는 [FeedScoreService] 가 하루 한 번 적어 둔다. 여기서는 읽기만 한다.
     *
     * 예전에는 이 자리에서 계산했다. 계산 결과가 저장돼 있지 않으니 인덱스가 탈
     * 수 없었고, 30건을 돌려주려고 11,305행을 전부 읽어 점수를 매기고 전부
     * 정렬한 뒤 11,275행을 버렸다. 캐시가 10분마다 비니 하루에 백 번 넘게
     * 그 값을 치렀고, 소식이 하루 60건씩 쌓이므로 계속 비싸질 참이었다.
     *
     * 점수의 재료가 모두 하루 한 번만 바뀌어서 미리 적어 둘 수 있었다.
     * 종류는 고정이고, discovery_score 는 동기화 때, 요약과 홍보성 표시는
     * 분류될 때, 날짜 감점은 하루가 지나면서 바뀐다.
     *
     * feed_score 가 NULL 이면 목록에 내보내지 않는다는 뜻이다(archive_only 인
     * 게임의 소식). 그 판단까지 점수에 담았으므로 game 을 조인할 필요가 없다.
     * 인덱스가 정렬 순서 그대로 만들어져 있어 앞에서 필요한 만큼만 읽고 멈춘다.
     */
    fun topEventIds(limit: Int, offset: Int = 0): List<Long> = jdbc.queryForList(
        """
        SELECT e.id
        FROM game_event e
        WHERE e.feed_score IS NOT NULL
        ORDER BY e.feed_score DESC, e.published_at DESC
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
