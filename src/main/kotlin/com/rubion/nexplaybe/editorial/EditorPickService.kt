package com.rubion.nexplaybe.editorial

import com.rubion.nexplaybe.api.GameResponse
import com.rubion.nexplaybe.api.toResponse
import com.rubion.nexplaybe.discovery.ResourceNotFoundException
import com.rubion.nexplaybe.game.GameRepository
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import com.rubion.nexplaybe.cache.CacheConfig
import org.springframework.cache.annotation.Cacheable

data class EditorPickResponse(
    val game: GameResponse,
    val note: String,
    val headline: String?,
    val pickedAt: String,
)

data class EditorPickRequest(
    val slug: String,
    val note: String,
    val headline: String? = null,
    val sortOrder: Int? = null,
)

/**
 * 주인장이 직접 고르는 목록. 나머지 화면은 전부 점수와 날짜로 자동 정렬되지만
 * 여기만 사람이 고르고, 왜 기다리는지 한 줄을 남긴다.
 */
@Service
@Transactional(readOnly = true)
class EditorPickService(
    private val gameRepository: GameRepository,
    private val jdbc: JdbcTemplate,
) {
    @Cacheable(CacheConfig.SECTIONS, key = "'editor-picks'")
    fun list(): List<EditorPickResponse> {
        val rows = jdbc.query(
            """
            SELECT g.slug, p.note, p.headline, p.picked_at
            FROM editor_pick p JOIN game g ON g.id = p.game_id
            WHERE p.active = b'1'
            ORDER BY p.sort_order ASC, p.picked_at DESC, p.id ASC
            """.trimIndent(),
        ) { rs, _ ->
            Row(rs.getString(1), rs.getString(2), rs.getString(3), rs.getDate(4).toLocalDate())
        }
        // 게임 본문은 한 번에 모아 온다. 픽 하나마다 조회하면 N+1 이 된다.
        val games = rows.mapNotNull { gameRepository.findBySlug(it.slug) }.associateBy { it.slug }
        return rows.mapNotNull { row ->
            val game = games[row.slug] ?: return@mapNotNull null
            EditorPickResponse(game.toResponse(), row.note, row.headline, row.pickedAt.toString())
        }
    }

    @Transactional
    fun upsert(request: EditorPickRequest): EditorPickResponse {
        val note = request.note.trim()
        require(note.isNotBlank()) { "픽에는 왜 기다리는지 한 줄이 필요합니다." }
        val game = gameRepository.findBySlug(request.slug)
            ?: throw ResourceNotFoundException("Game not found: ${request.slug}")
        val order = request.sortOrder ?: nextSortOrder()
        jdbc.update(
            """
            INSERT INTO editor_pick (game_id, note, headline, sort_order, active, picked_at)
            VALUES (?,?,?,?,b'1',?)
            ON DUPLICATE KEY UPDATE note=VALUES(note), headline=VALUES(headline),
                                    sort_order=VALUES(sort_order), active=b'1'
            """.trimIndent(),
            game.id, note.take(300), request.headline?.trim()?.take(80), order, java.sql.Date.valueOf(LocalDate.now()),
        )
        return list().first { it.game.slug == request.slug }
    }

    @Transactional
    fun remove(slug: String): Boolean {
        val game = gameRepository.findBySlug(slug) ?: throw ResourceNotFoundException("Game not found: $slug")
        return jdbc.update("DELETE FROM editor_pick WHERE game_id = ?", game.id) > 0
    }

    private fun nextSortOrder(): Int =
        jdbc.queryForObject("SELECT COALESCE(MAX(sort_order), 0) + 10 FROM editor_pick", Int::class.java) ?: 10

    private data class Row(val slug: String, val note: String, val headline: String?, val pickedAt: LocalDate)
}
