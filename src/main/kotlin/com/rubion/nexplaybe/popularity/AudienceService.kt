package com.rubion.nexplaybe.popularity

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** 카드에 붙일 수. 아직 적은 수는 null 이다 — 없는 것과 적은 것은 다르게 다룬다. */
data class AudienceCounts(val anticipations: Int?, val views: Int?)

/**
 * 사람이 남긴 수 — 기대와 조회.
 *
 * 목록을 그릴 때마다 게임 하나씩 세면 N+1 이 된다. 한 번에 받아 맵으로 돌려준다.
 * 수상 배지와 같은 방식이다.
 *
 * ### 적은 수를 감추는 이유
 *
 * "기대 3" 이나 "조회 7" 은 없느니만 못하다. 텅 빈 방처럼 보이고, 한 번 그렇게
 * 보이면 다음 사람도 안 누른다. 거짓말을 하는 것이 아니라 아직 말할 만한 수가
 * 아니라고 보는 것이다 — 재료가 없을 때 그럴듯한 숫자를 만들지 않는 원칙과 같다.
 */
@Service
class AudienceService(private val jdbc: JdbcTemplate) {

    @Transactional(readOnly = true)
    fun counts(): Map<Long, AudienceCounts> {
        val anticipations = jdbc.query(
            "SELECT game_id, COUNT(*) FROM game_anticipation GROUP BY game_id",
        ) { rs, _ -> rs.getLong(1) to rs.getInt(2) }.toMap()

        val views = jdbc.query(
            "SELECT game_id, SUM(views) FROM game_view_daily GROUP BY game_id",
        ) { rs, _ -> rs.getLong(1) to rs.getInt(2) }.toMap()

        return (anticipations.keys + views.keys).associateWith { gameId ->
            AudienceCounts(
                anticipations = anticipations[gameId]?.takeIf { it >= MIN_ANTICIPATIONS },
                views = views[gameId]?.takeIf { it >= MIN_VIEWS },
            )
        }
    }

    /**
     * 한 번 봤다고 기록한다.
     *
     * 같은 사람이 새로고침하는 것까지 세지는 않는다 — 그건 화면 쪽에서 한 번만
     * 보내도록 막고, 여기서는 온 것을 그대로 더한다. 서버에서 사람을 가르려면
     * 결국 방문자를 식별해야 하는데, 조회수 하나 때문에 그럴 일은 아니다.
     */
    @Transactional
    fun recordView(slug: String) {
        jdbc.update(
            """
            INSERT INTO game_view_daily (game_id, view_date, views)
            SELECT g.id, CURRENT_DATE, 1 FROM game g WHERE g.slug = ?
            ON DUPLICATE KEY UPDATE views = views + 1
            """.trimIndent(),
            slug,
        )
    }

    private companion object {
        const val MIN_ANTICIPATIONS = 5
        /** 조회는 기대보다 빨리 쌓이므로 문턱도 높다. */
        const val MIN_VIEWS = 20
    }
}
