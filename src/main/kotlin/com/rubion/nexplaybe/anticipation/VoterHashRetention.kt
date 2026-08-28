package com.rubion.nexplaybe.anticipation

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.security.SecureRandom

/**
 * 오래된 투표자 해시를 되돌릴 수 없게 바꾼다.
 *
 * 해시는 같은 사람이 두 번 누르는 것만 막으려고 둔 것이다. 그 목적은 며칠이면
 * 끝나는데 기록은 영원히 남는다. 소금을 우리가 갖고 있으므로 후보 주소를 넣어
 * 맞춰 볼 수 있고, 그러면 익명이 아니다.
 *
 * 지우지 않고 바꾼다. 행을 지우면 기대 수가 줄어드는데, 그건 사람들이 남긴
 * 사실을 없애는 것이다. 해시만 무작위로 갈면 수는 그대로고 누구였는지만 사라진다.
 *
 * 처리방침에 적은 기간을 코드가 실제로 지키게 하려고 둔다. 지키지 않을 것을
 * 적으면 안 적느니만 못하다.
 */
@Component
class VoterHashRetention(
    private val jdbc: JdbcTemplate,
    @param:Value("\${nexplay.anticipation.hash-retention-days:180}") private val retentionDays: Int,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val random = SecureRandom()

    @Transactional
    fun anonymizeOldHashes(): Int {
        val stale = jdbc.queryForList(
            """
            SELECT id FROM game_anticipation
            WHERE created_at < DATE_SUB(NOW(), INTERVAL ? DAY) AND voter_hash NOT LIKE 'anon-%'
            LIMIT 5000
            """.trimIndent(),
            Long::class.java, retentionDays,
        ).filterNotNull()
        if (stale.isEmpty()) return 0

        // 한 줄씩 다른 값으로 바꾼다. 같은 값으로 몰면 (game_id, voter_hash) 유일 제약에 걸린다.
        stale.forEach { id ->
            val bytes = ByteArray(24).also(random::nextBytes)
            val token = "anon-" + bytes.joinToString("") { "%02x".format(it) }
            jdbc.update("UPDATE game_anticipation SET voter_hash = ? WHERE id = ?", token, id)
        }
        log.info("투표자 해시 {}건을 익명으로 바꿨습니다({}일 경과)", stale.size, retentionDays)
        return stale.size
    }
}
