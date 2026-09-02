package com.rubion.nexplaybe.discovery

import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 목록 정렬 점수를 다시 계산해 둔다.
 *
 * 예전에는 조회할 때마다 계산했다. 계산 결과가 저장돼 있지 않으니 인덱스가 탈
 * 수 없었고, 30건을 돌려주려고 11,305행을 전부 읽고 전부 정렬한 뒤 11,275행을
 * 버렸다. 캐시가 10분마다 비니 하루에 그 값을 백 번 넘게 치렀다.
 *
 * 점수를 이루는 것은 전부 하루에 한 번만 바뀐다 — 종류는 고정이고,
 * discovery_score 는 동기화 때, 요약과 홍보성 표시는 분류될 때, 날짜 감점은
 * 하루가 지나면서 바뀐다. 그래서 하루 한 번 여기서 새로 적어 두면 된다.
 *
 * [FeedEventSelector] 의 정렬식과 짝이다. 한쪽만 고치면 순서가 조용히 어긋난다.
 */
@Service
class FeedScoreService(private val jdbc: JdbcTemplate) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * @return 점수가 실제로 바뀐 행 수. MySQL 은 값이 같으면 쓰지 않으므로,
     *   1만 행을 훑어도 대개 어제와 달라진 몇백 행만 기록된다.
     */
    @Transactional
    fun recompute(): Int {
        val changed = jdbc.update(RECOMPUTE_SQL)
        log.info("목록 점수 갱신: {}행", changed)
        return changed
    }

    private companion object {
        /**
         * NULL 은 "목록에 내보내지 않는다" 는 뜻이다. archive_only 인 게임의 소식이
         * 여기 해당한다. 이렇게 두면 조회 쪽이 game 을 조인할 이유가 없어진다.
         */
        val RECOMPUTE_SQL = """
            UPDATE game_event e
            JOIN game g ON g.id = e.game_id
            LEFT JOIN game_event_extraction x ON x.event_id = e.id AND x.prompt_version = 1
            SET e.feed_score = CASE WHEN g.archive_only = 1 THEN NULL ELSE (
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
                + CASE WHEN x.summary_ko IS NOT NULL AND x.summary_ko <> '' THEN 18 ELSE 0 END
                - LEAST(GREATEST(DATEDIFF(CURRENT_DATE, e.event_date), 0), 60) / 2
                - CASE WHEN COALESCE(x.is_marketing_noise, 0) = 1 THEN 30 ELSE 0 END
            ) END
        """.trimIndent()
    }
}
