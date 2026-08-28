package com.rubion.nexplaybe.awards

import com.rubion.nexplaybe.api.AwardBadge
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/**
 * 게임에 달 수상 배지를 찾아 준다.
 *
 * 한 게임이 여러 기록을 가질 수 있다(수상 + 후보 + 최고 기대작). 카드에는 하나만
 * 달아야 하므로 무게 순으로 고른다 — GOTY 수상 > GOTY 후보 > 최고 기대작.
 *
 * 목록을 그릴 때마다 게임 하나씩 조회하면 N+1 이 된다. 한 번에 받아 맵으로 돌려준다.
 */
@Component
class AwardBadgeLookup(private val jdbc: JdbcTemplate) {

    fun badges(): Map<Long, AwardBadge> {
        val rows = jdbc.query(
            """
            SELECT game_id, award_name, result, award_year
            FROM game_award
            WHERE game_id IS NOT NULL
            """.trimIndent(),
        ) { rs, _ ->
            Row(rs.getLong("game_id"), rs.getString("award_name"), rs.getString("result"), rs.getInt("award_year"))
        }
        return rows.groupBy { it.gameId }
            .mapValues { (_, list) -> list.minByOrNull(::weight)!!.toBadge() }
    }

    private fun weight(row: Row) = when {
        row.awardName == GOTY && row.result == "WINNER" -> 0
        row.awardName == GOTY -> 1
        row.result == "WINNER" -> 2
        else -> 3
    }

    private fun Row.toBadge(): AwardBadge = when {
        awardName == GOTY && result == "WINNER" -> AwardBadge("GOTY 수상", year, "GOTY_WINNER")
        awardName == GOTY -> AwardBadge("GOTY 후보", year, "GOTY_NOMINEE")
        result == "WINNER" -> AwardBadge("최고 기대작", year, "ANTICIPATED_WINNER")
        else -> AwardBadge("최고 기대작 후보", year, "ANTICIPATED_NOMINEE")
    }

    private data class Row(val gameId: Long, val awardName: String, val result: String, val year: Int)

    private companion object {
        const val GOTY = "The Game Awards Game of the Year"
    }
}
