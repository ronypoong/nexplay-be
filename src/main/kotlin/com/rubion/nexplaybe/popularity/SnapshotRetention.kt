package com.rubion.nexplaybe.popularity

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * 오래된 인기 스냅숏을 지운다.
 *
 * 매일 게임 수만큼 한 줄씩 쌓인다 — 2026-10-06 기준 하루 2,446줄, 41일에 88,918줄,
 * 13.5MB다. 그대로 두면 한 해에 89만 줄, 140MB쯤 늘고, 두 해가 지나면 이 테이블
 * 하나가 InnoDB 버퍼 풀(384MB)을 통째로 밀어낸다. 그때부터는 읽을 때마다 디스크를
 * 친다.
 *
 * 정작 읽는 쪽은 그렇게 멀리 보지 않는다. 게임 상세의 추이는 90일, 추세 화면의
 * 급상승 비교는 7일이다. 그보다 오래된 줄은 아무도 읽지 않으면서 자리만 먹는다.
 *
 * 지금은 모아 둔 것이 41일치라 한 줄도 지워지지 않는다. 그래도 지금 넣는다 —
 * 버퍼 풀을 넘긴 뒤에 알아차리면 이미 느려진 다음이다.
 *
 * 한 번에 다 지우지 않는다. 큰 DELETE 는 되돌리기 로그를 길게 잡고 그동안
 * 다른 쿼리를 느리게 만든다. 하루 한 번 도는 일이니 나눠 지워도 충분하다.
 */
@Component
class SnapshotRetention(
    private val jdbc: JdbcTemplate,
    @param:Value("\${nexplay.popularity.retention-days:120}") private val retentionDays: Int,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun purgeOldSnapshots(): Int {
        var removed = 0
        // idx_popularity_date 가 있어서 날짜로 자르는 것은 싸다.
        while (removed < MAX_PER_RUN) {
            val deleted = jdbc.update(
                "DELETE FROM popularity_snapshot WHERE snapshot_date < CURRENT_DATE - INTERVAL ? DAY LIMIT ?",
                retentionDays, BATCH_SIZE,
            )
            removed += deleted
            if (deleted < BATCH_SIZE) break
        }
        if (removed > 0) log.info("오래된 인기 스냅숏 {}줄 정리 (보관 {}일)", removed, retentionDays)
        return removed
    }

    private companion object {
        const val BATCH_SIZE = 2_000
        /** 하루치 상한. 밀린 것이 많아도 한 번에 다 밀어내지 않는다. */
        const val MAX_PER_RUN = 50_000
    }
}
