package com.rubion.nexplaybe.anticipation

import com.rubion.nexplaybe.discovery.ResourceNotFoundException
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest

data class AnticipationState(
    val slug: String,
    /** 눌린 수. 아직 적을 때는 null 이다 — 아래 주석 참고. */
    val count: Int?,
    val mine: Boolean,
)

data class AnticipatedGame(val slug: String, val title: String, val releaseLabel: String, val count: Int)

/**
 * "기대돼요".
 *
 * 원본 IP 는 어디에도 저장하지 않는다. 소금을 섞은 해시만 남기고, 그 해시로 같은
 * 사람이 두 번 세는 것만 막는다. foresee-be 가 커뮤니티에서 쓰는 방식과 같다.
 *
 * ### 수를 언제 보여 주는가
 *
 * 방문자가 적을 때 "3명이 기대합니다" 는 없느니만 못하다. 텅 빈 방처럼 보이고,
 * 한 번 그렇게 보이면 다음 사람도 안 누른다. 그래서 일정 수를 넘기 전까지는
 * 수를 감추고 자기가 눌렀는지만 보여 준다. 거짓말을 하는 것이 아니라 아직 말할
 * 만한 수가 아니라고 보는 것이다 — 재료가 없을 때 그럴듯한 숫자를 만들지 않는
 * 원칙을 여기에도 적용한다.
 */
@Service
class AnticipationService(
    private val jdbc: JdbcTemplate,
    @param:Value("\${nexplay.anticipation.salt:}") private val salt: String,
    @param:Value("\${nexplay.anticipation.daily-limit:40}") private val dailyLimit: Int,
) {

    @Transactional
    fun toggle(slug: String, clientIp: String): AnticipationState {
        val gameId = gameIdOf(slug)
        val hash = voterHash(clientIp)

        val removed = jdbc.update(
            "DELETE FROM game_anticipation WHERE game_id = ? AND voter_hash = ?", gameId, hash,
        )
        if (removed == 0) {
            // 한 사람이 카탈로그 전체를 눌러 순위를 뒤집는 것을 막는다.
            val today = jdbc.queryForObject(
                "SELECT COUNT(*) FROM game_anticipation WHERE voter_hash = ? AND created_at >= CURRENT_DATE",
                Int::class.java, hash,
            ) ?: 0
            if (today >= dailyLimit) throw TooManyVotesException()
            jdbc.update(
                "INSERT IGNORE INTO game_anticipation (game_id, voter_hash) VALUES (?,?)", gameId, hash,
            )
        }
        return state(slug, clientIp)
    }

    @Transactional(readOnly = true)
    fun state(slug: String, clientIp: String): AnticipationState {
        val gameId = gameIdOf(slug)
        val hash = voterHash(clientIp)
        val count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM game_anticipation WHERE game_id = ?", Int::class.java, gameId,
        ) ?: 0
        val mine = (
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM game_anticipation WHERE game_id = ? AND voter_hash = ?",
                Int::class.java, gameId, hash,
            ) ?: 0
            ) > 0
        return AnticipationState(slug, count.takeIf { it >= MIN_COUNT_TO_SHOW }, mine)
    }

    /** 순위는 그 자체로 참여를 부른다. 다만 표본이 얇으면 순위도 거짓말이라 문턱을 둔다. */
    @Transactional(readOnly = true)
    fun ranking(limit: Int = 10): List<AnticipatedGame> = jdbc.query(
        """
        SELECT g.slug, g.title, g.release_label, COUNT(*) AS c
        FROM game_anticipation a
        JOIN game g ON g.id = a.game_id
        WHERE g.archive_only = 0
        GROUP BY g.id, g.slug, g.title, g.release_label
        HAVING c >= ?
        ORDER BY c DESC, g.discovery_score DESC
        LIMIT ?
        """.trimIndent(),
        { rs, _ -> AnticipatedGame(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4)) },
        MIN_COUNT_TO_SHOW, limit.coerceIn(1, 50),
    )

    private fun gameIdOf(slug: String): Long = jdbc.query(
        "SELECT id FROM game WHERE slug = ?", { rs, _ -> rs.getLong(1) }, slug,
    ).firstOrNull() ?: throw ResourceNotFoundException("Game not found: $slug")

    private fun voterHash(clientIp: String): String {
        // 소금이 비어 있으면 해시가 그대로 IP 의 별칭이 된다. 그건 익명이 아니다.
        require(salt.isNotBlank()) { "NEXPLAY_ANTICIPATION_SALT 가 설정되지 않았습니다" }
        return MessageDigest.getInstance("SHA-256")
            .digest("$salt|ip|$clientIp".toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    private companion object {
        /** 이 수를 넘기 전에는 수를 보여 주지 않는다. */
        const val MIN_COUNT_TO_SHOW = 5
    }
}

class TooManyVotesException : RuntimeException("오늘 누를 수 있는 만큼 다 눌렀습니다")
