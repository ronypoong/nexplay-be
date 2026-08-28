package com.rubion.nexplaybe.collector

import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.util.concurrent.atomic.AtomicBoolean

data class BackfillSummary(
    val status: String,
    val feedsChecked: Int,
    val itemsSeen: Int,
    val filled: Int,
    val stillEmpty: Int,
    val errors: List<String>,
)

/**
 * 이미 저장된 소식의 본문을 되받아 채운다.
 *
 * 본문을 저장하는 코드를 나중에 넣는 바람에, 그 전에 들어온 362건은 제목과 링크만
 * 남고 본문이 비어 있다. 수집은 `(source_id, external_id)` 기준으로 멱등이라
 * 정상 수집으로는 절대 다시 채워지지 않는다.
 *
 * **시한이 있다.** Steam RSS 는 앱당 최근 10건만 준다. 그 게임이 새 글을 올리면
 * 가장 오래된 글이 밀려나고 그 본문은 영영 사라진다. 지금은 아직 겹쳐 있어서
 * 되찾을 수 있다.
 *
 * 하는 일은 UPDATE 하나뿐이다. 새 행을 만들지 않고, 이벤트도 건드리지 않는다.
 * 비어 있는 칸만 채운다 — 이미 본문이 있는 행은 덮어쓰지 않는다.
 */
@Service
class RawPayloadBackfill(
    private val jdbc: JdbcTemplate,
    private val subscriptionRepository: SourceSubscriptionRepository,
    private val client: SteamNewsRssClient,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val running = AtomicBoolean(false)

    fun run(limit: Int = DEFAULT_LIMIT): BackfillSummary {
        if (!running.compareAndSet(false, true)) {
            return BackfillSummary("SKIPPED_ALREADY_RUNNING", 0, 0, 0, 0, emptyList())
        }
        try {
            // 본문이 빈 행이 하나라도 있는 구독만 본다. 다 채워진 피드를 다시 받을 이유가 없고,
            // 이렇게 해야 여러 번 나눠 돌려도 매번 남은 것부터 집는다.
            val pending = jdbc.queryForList(
                """
                SELECT DISTINCT r.source_id, r.game_id
                FROM raw_item r
                WHERE r.raw_payload IS NULL OR r.raw_payload = ''
                """.trimIndent(),
            ).mapNotNull { row ->
                val sourceId = (row["source_id"] as? Number)?.toLong()
                val gameId = (row["game_id"] as? Number)?.toLong()
                if (sourceId == null || gameId == null) null else sourceId to gameId
            }.toSet()

            val targets = subscriptionRepository.findAllByActiveTrueOrderByIdAsc()
                .filter { (it.source.id to it.game.id) in pending }
                .take(limit.coerceIn(1, MAX_LIMIT))

            var seen = 0
            var filled = 0
            val errors = mutableListOf<String>()

            targets.forEach { subscription ->
                runCatching {
                    val items = client.fetch(subscription.feedUrl)
                    seen += items.size
                    items.forEach { item ->
                        if (item.body.isBlank()) return@forEach
                        filled += jdbc.update(
                            """
                            UPDATE raw_item SET raw_payload = ?
                            WHERE source_id = ? AND external_id = ?
                              AND (raw_payload IS NULL OR raw_payload = '')
                            """.trimIndent(),
                            item.body, subscription.source.id, item.externalId,
                        )
                    }
                }.onFailure {
                    errors += "${subscription.game.title}: ${it.message.orEmpty().take(200)}"
                }
                // 예의상 간격을 둔다. 되찾기는 급한 일이 아니고, 한 번에 몰아치면 막힌다.
                runCatching { Thread.sleep(PACE_MS) }
            }

            val stillEmpty = jdbc.queryForObject(
                "SELECT COUNT(*) FROM raw_item WHERE raw_payload IS NULL OR raw_payload = ''",
                Int::class.java,
            ) ?: 0

            log.info("원문 백필: 피드 {}개, 항목 {}건, 채움 {}건, 남은 빈 칸 {}건", targets.size, seen, filled, stillEmpty)
            return BackfillSummary(
                status = if (errors.isEmpty()) "SUCCESS" else "PARTIAL",
                feedsChecked = targets.size,
                itemsSeen = seen,
                filled = filled,
                stillEmpty = stillEmpty,
                errors = errors.take(10),
            )
        } finally {
            running.set(false)
        }
    }

    private companion object {
        /** Cloudflare 프록시가 100초에서 끊는다. 한 번에 다 돌리려다 524 를 받느니 나눠 돈다. */
        const val DEFAULT_LIMIT = 30
        const val MAX_LIMIT = 200
        const val PACE_MS = 300L
    }
}
