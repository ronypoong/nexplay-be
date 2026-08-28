package com.rubion.nexplaybe.intelligence

import com.rubion.nexplaybe.cache.CacheConfig
import com.rubion.nexplaybe.discovery.ResourceNotFoundException
import org.springframework.cache.annotation.Cacheable
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** 모델이 원문에서 뽑아 둔 사실들. 없으면 아직 읽지 않은 소식이다. */
data class ExtractedFacts(
    val confidence: String,
    val mentionedReleaseDate: String?,
    val discountPercent: Int?,
    val hasDemo: Boolean,
    val marketingNoise: Boolean,
    /** 그렇게 분류한 근거를 원문에서 인용한 것. */
    val reason: String?,
    val model: String,
)

data class EventSourceRef(val name: String, val url: String, val official: Boolean)

data class EventDetail(
    val id: String,
    val type: String,
    val title: String,
    val summary: String,
    val summaryKo: String?,
    val eventDate: String,
    val publishedAt: String,
    val gameSlug: String,
    val gameTitle: String,
    val sources: List<EventSourceRef>,
    val facts: ExtractedFacts?,
    /** 이 발표에서 뽑은 약속. 대조표가 개별 소식과 이어지는 자리다. */
    val promises: List<PromiseRow>,
)

/**
 * 소식 하나를 자세히 본다.
 *
 * **원문을 옮기지 않는다.** 제목과 우리가 뽑은 사실만 보여 주고 본문은 링크로
 * 보낸다 — 기사 본문을 그대로 재게시하지 않는다는 것이 이 서비스의 첫 번째
 * 금지선이다. 여기서 보여 줄 것은 남의 글이 아니라 그 글에서 우리가 확인한 것이다.
 */
@Service
@Transactional(readOnly = true)
class EventDetailService(private val jdbc: JdbcTemplate) {

    @Cacheable(CacheConfig.GAME_EVENTS, key = "'detail-' + #id")
    fun detail(id: Long): EventDetail {
        val base = jdbc.query(
            """
            SELECT e.id, e.type, e.title, e.summary, e.event_date, e.published_at,
                   g.slug AS game_slug, g.title AS game_title
            FROM game_event e JOIN game g ON g.id = e.game_id
            WHERE e.id = ?
            """.trimIndent(),
            { rs, _ ->
                Base(
                    rs.getLong("id"), rs.getString("type"), rs.getString("title"), rs.getString("summary"),
                    rs.getDate("event_date").toLocalDate().toString(),
                    rs.getTimestamp("published_at").toInstant().toString(),
                    rs.getString("game_slug"), rs.getString("game_title"),
                )
            },
            id,
        ).firstOrNull() ?: throw ResourceNotFoundException("Event not found: $id")

        val sources = jdbc.query(
            """
            SELECT s.name, es.source_url, es.is_official
            FROM game_event_source es JOIN source s ON s.id = es.source_id
            WHERE es.game_event_id = ?
            ORDER BY es.is_official DESC, es.id
            """.trimIndent(),
            { rs, _ -> EventSourceRef(rs.getString(1), rs.getString(2), rs.getBoolean(3)) },
            id,
        )

        val facts = jdbc.query(
            """
            SELECT confidence, summary_ko, mentioned_release_date, discount_percent,
                   has_demo, is_marketing_noise, reason, model
            FROM game_event_extraction WHERE event_id = ? ORDER BY prompt_version DESC LIMIT 1
            """.trimIndent(),
            { rs, _ ->
                rs.getString("summary_ko") to ExtractedFacts(
                    rs.getString("confidence"),
                    rs.getDate("mentioned_release_date")?.toLocalDate()?.toString(),
                    rs.getInt("discount_percent").takeUnless { rs.wasNull() },
                    rs.getBoolean("has_demo"), rs.getBoolean("is_marketing_noise"),
                    rs.getString("reason"), rs.getString("model"),
                )
            },
            id,
        ).firstOrNull()

        val promises = jdbc.query(
            """
            SELECT g.slug, g.title, p.claim_type, p.claimed_value, p.announced_at,
                   COALESCE(r.status, 'PENDING') AS status, r.slip_days, p.source_quote, r.evidence
            FROM game_promise p
            JOIN game g ON g.id = p.game_id
            LEFT JOIN game_promise_resolution r ON r.promise_id = p.id
            WHERE p.event_id = ?
            ORDER BY p.claim_type
            """.trimIndent(),
            { rs, _ ->
                PromiseRow(
                    rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                    rs.getDate(5).toLocalDate(), rs.getString(6),
                    rs.getInt(7).takeUnless { rs.wasNull() }, rs.getString(8), rs.getString(9),
                )
            },
            id,
        )

        return EventDetail(
            base.id.toString(), base.type, base.title, base.summary, facts?.first,
            base.eventDate, base.publishedAt, base.gameSlug, base.gameTitle,
            sources, facts?.second, promises,
        )
    }

    private data class Base(
        val id: Long, val type: String, val title: String, val summary: String,
        val eventDate: String, val publishedAt: String, val gameSlug: String, val gameTitle: String,
    )
}
