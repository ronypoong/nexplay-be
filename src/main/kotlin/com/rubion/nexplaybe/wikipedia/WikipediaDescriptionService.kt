package com.rubion.nexplaybe.wikipedia

import com.rubion.nexplaybe.game.GameRepository
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.Instant

data class WikipediaSyncSummary(val status: String, val candidates: Int, val filled: Int, val missing: Int)

/**
 * Steam 이 못 채운 소개를 위키백과로 메운다.
 *
 * Steam 스토어 페이지가 없거나(미발표 콘솔 독점작 등) 스토어 소개가 없는 게임이 남는다.
 * 위키백과 본문은 CC BY-SA 이므로 출처와 링크를 provenance 에 반드시 남긴다.
 */
@Service
class WikipediaDescriptionService(
    private val client: WikipediaDescriptionClient,
    private val gameRepository: GameRepository,
    private val jdbc: JdbcTemplate,
    private val transactions: TransactionTemplate,
) {
    fun enrichDescriptions(limit: Int = DEFAULT_LIMIT): WikipediaSyncSummary {
        val candidates = jdbc.query(
            """
            SELECT g.id, g.wikidata_id, CHAR_LENGTH(g.description) AS len
            FROM game g
            WHERE g.wikidata_id IS NOT NULL
              AND NOT EXISTS (
                SELECT 1 FROM game_data_provenance p
                WHERE p.game_id = g.id AND p.field_name = 'description' AND p.source_name = 'Wikipedia'
              )
              AND (${BOILERPLATE_PREDICATE})
            ORDER BY g.featured DESC, g.discovery_score DESC
            LIMIT ${limit.coerceIn(1, MAX_LIMIT)}
            """.trimIndent(),
        ) { rs, _ -> Candidate(rs.getLong("id"), rs.getString("wikidata_id"), rs.getInt("len")) }
        if (candidates.isEmpty()) return WikipediaSyncSummary("SUCCESS", 0, 0, 0)

        val articles = client.fetch(candidates.map { it.wikidataId }).associateBy { it.wikidataId }
        var filled = 0
        candidates.forEach { candidate ->
            val article = articles[candidate.wikidataId] ?: return@forEach
            // 이미 더 긴 소개가 있으면 굳이 바꾸지 않는다.
            if (article.extract.length <= candidate.descriptionLength) return@forEach
            transactions.execute {
                jdbc.update("UPDATE game SET description = ? WHERE id = ?", article.extract, candidate.id)
                jdbc.update(
                    """
                    INSERT INTO game_data_provenance (game_id,field_name,source_name,source_url,confidence,verified_at)
                    VALUES (?, 'description', 'Wikipedia', ?, 'MEDIUM', ?)
                    ON DUPLICATE KEY UPDATE source_url=VALUES(source_url), verified_at=VALUES(verified_at)
                    """.trimIndent(),
                    candidate.id, article.url, Timestamp.from(Instant.now()),
                )
            }
            filled++
        }
        return WikipediaSyncSummary("SUCCESS", candidates.size, filled, candidates.size - filled)
    }

    private data class Candidate(val id: Long, val wikidataId: String, val descriptionLength: Int)

    private companion object {
        const val DEFAULT_LIMIT = 200
        const val MAX_LIMIT = 500
        val BOILERPLATE_PREDICATE = """
            g.description LIKE '%Wikidata CC0 구조화 데이터에서 확인한%'
            OR g.description LIKE '%에서 확인한 정보입니다%'
            OR CHAR_LENGTH(g.description) < 150
        """.trimIndent()
    }
}
