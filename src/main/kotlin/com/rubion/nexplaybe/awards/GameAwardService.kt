package com.rubion.nexplaybe.awards

import com.rubion.nexplaybe.catalog.CatalogSyncService
import com.rubion.nexplaybe.catalog.ManualGameRequest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate

data class AwardSyncSummary(val status: String, val fetched: Int, val stored: Int, val gamesAdded: Int)

data class AwardedGame(
    val slug: String?,
    val title: String,
    val awardYear: Int,
    val result: String,
    val coverImageUrl: String?,
    val sourceUrl: String?,
)

data class WatchlistEntry(
    val slug: String,
    val title: String,
    val releaseLabel: String,
    val coverImageUrl: String?,
    val reason: String,
)

data class GotyResponse(
    val winners: List<AwardedGame>,
    val nominees: List<AwardedGame>,
    val watchlist: List<WatchlistEntry>,
)

/**
 * GOTY 아카이브와 "관측 대상".
 *
 * 관측 대상은 예측이 아니다. 임의의 가중치로 점수를 지어내면 근거 없는 숫자가 된다.
 * 대신 확인 가능한 사실만 쓴다 — 과거 GOTY 수상·후보 이력이 있는 개발사/퍼블리셔의
 * 올해 출시작, 그리고 Wikidata 에 실제로 올라온 Most Anticipated 노미네이트.
 * 이유(reason)를 함께 실어 왜 목록에 있는지 화면에서 밝힌다.
 */
@Service
class GameAwardService(
    private val client: GameAwardClient,
    private val catalogSyncService: CatalogSyncService,
    private val jdbc: JdbcTemplate,
    private val transactions: TransactionTemplate,
) {
    /**
     * 클래스에 readOnly 트랜잭션을 걸면 안 된다. 이 메서드는 게임을 추가하고 수상 기록을
     * 쓰는데, 읽기 전용 커넥션에서는 그대로 실패한다. 게다가 중간에 SPARQL 호출이 있어
     * 하나의 긴 트랜잭션으로 묶을 것도 아니다 — 기록마다 transactions.execute 로 짧게 연다.
     */
    fun sync(): AwardSyncSummary {
        val records = client.fetchGameOfTheYear() + client.fetchMostAnticipated()
        if (records.isEmpty()) return AwardSyncSummary("SKIPPED_SOURCE_UNAVAILABLE", 0, 0, 0)

        // 카탈로그에 없는 수상작은 아카이브 전용으로 넣는다. 신작 화면에는 나오지 않는다.
        var added = 0
        records.map { it.wikidataId to it }.distinctBy { it.first }.forEach { (wikidataId, record) ->
            val developer = record.developer ?: record.publisher ?: "Unknown"
            val publisher = record.publisher ?: record.developer ?: "Unknown"
            val exists = jdbc.queryForObject(
                "SELECT COUNT(*) FROM game WHERE wikidata_id = ?", Int::class.java, wikidataId,
            ) ?: 0
            if (exists > 0) {
                record.releaseYear?.let { backfillReleaseDate(wikidataId, it) }
                // 앞선 동기화가 개발사를 Unknown 으로 넣어둔 것이 있다. 이력 매칭이
                // 개발사를 타고 이뤄지므로 Unknown 이면 관측 대상이 하나도 안 잡힌다.
                if (record.developer != null) backfillCompanies(wikidataId, developer, publisher)
                return@forEach
            }
            val result = runCatching {
                catalogSyncService.addGame(
                    ManualGameRequest(
                        title = record.title,
                        developer = developer,
                        publisher = publisher,
                        steamAppId = record.steamAppId,
                        wikidataId = wikidataId,
                        // 출시 연도를 안 넣으면 상태가 TBA 로 남아, 2023년에 나온 게임이
                        // "올해 눈여겨볼 작품" 목록에 계속 뜬다.
                        releaseDate = record.releaseYear?.let { java.time.LocalDate.of(it, 1, 1) },
                    ),
                )
            }.getOrNull()
            if (result?.status == "SUCCESS") {
                // 아직 안 나온 기대작까지 아카이브로 묻으면 안 된다. 그건 지금 볼 작품이다.
                val isPast = record.releaseYear != null && record.releaseYear < java.time.LocalDate.now().year
                if (isPast) jdbc.update("UPDATE game SET archive_only = b'1' WHERE wikidata_id = ?", wikidataId)
                added++
            }
        }

        var stored = 0
        records.forEach { record ->
            transactions.execute {
                stored += jdbc.update(
                    """
                    INSERT INTO game_award (game_id, wikidata_id, title, award_name, result, award_year, source_name, source_url)
                    SELECT g.id, ?, ?, ?, ?, ?, 'Wikidata', ?
                    FROM (SELECT ? AS wid) x
                    LEFT JOIN game g ON g.wikidata_id = x.wid
                    ON DUPLICATE KEY UPDATE game_id=VALUES(game_id), title=VALUES(title), verified_at=CURRENT_TIMESTAMP(6)
                    """.trimIndent(),
                    record.wikidataId, record.title, record.awardName, record.result, record.awardYear,
                    "https://www.wikidata.org/wiki/${record.wikidataId}", record.wikidataId,
                )
            }
        }
        return AwardSyncSummary("SUCCESS", records.size, stored, added)
    }

    @Transactional(readOnly = true)
    fun goty(): GotyResponse {
        val rows = jdbc.query(
            """
            SELECT g.slug, a.title, a.award_year, a.result, g.cover_image_url, a.source_url
            FROM game_award a LEFT JOIN game g ON g.id = a.game_id
            WHERE a.award_name = 'The Game Awards Game of the Year'
            ORDER BY a.award_year DESC, a.result ASC, a.title ASC
            """.trimIndent(),
        ) { rs, _ ->
            AwardedGame(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getString(4), rs.getString(5), rs.getString(6))
        }
        return GotyResponse(
            winners = rows.filter { it.result == "WINNER" },
            nominees = rows.filter { it.result == "NOMINEE" },
            watchlist = watchlist(),
        )
    }

    /**
     * 날짜 없이 들어간 수상작에 출시 연도를 채운다.
     * 상태가 TBA 로 남으면 이미 나온 게임이 "출시 예정" 목록에 계속 걸린다.
     */
    private fun backfillReleaseDate(wikidataId: String, year: Int) {
        val isPast = year < java.time.LocalDate.now().year
        jdbc.update(
            """
            UPDATE game SET release_date = ?, release_label = ?, status = ?
            WHERE wikidata_id = ? AND release_date IS NULL
            """.trimIndent(),
            java.sql.Date.valueOf(java.time.LocalDate.of(year, 1, 1)),
            if (isPast) "${year}년 출시" else "${year}년 출시 예정",
            if (isPast) "AVAILABLE" else "UPCOMING",
            wikidataId,
        )
    }

    /** 옛 동기화가 남긴 Unknown 개발사를 실제 이름으로 채운다. */
    private fun backfillCompanies(wikidataId: String, developer: String, publisher: String) {
        transactions.execute {
            listOf("developer_id" to developer, "publisher_id" to publisher).forEach { (column, name) ->
                jdbc.update("INSERT IGNORE INTO company (slug, name, type) VALUES (?, ?, 'UNKNOWN')",
                    name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "company-${name.hashCode()}" }, name)
                jdbc.update(
                    """
                    UPDATE game g JOIN company c ON c.name = ?
                    SET g.$column = c.id
                    WHERE g.wikidata_id = ?
                      AND (SELECT name FROM company WHERE id = g.$column) = 'Unknown'
                    """.trimIndent(),
                    name, wikidataId,
                )
            }
        }
    }

    /** 예측이 아니라 이력이다. 왜 목록에 있는지 reason 으로 밝힌다. */
    private fun watchlist(limit: Int = 12): List<WatchlistEntry> {
        val anticipated = jdbc.query(
            """
            SELECT g.slug, g.title, g.release_label, g.cover_image_url, a.award_year
            FROM game_award a JOIN game g ON g.id = a.game_id
            WHERE a.award_name = 'The Game Awards Most Anticipated Game'
              AND g.status = 'UPCOMING'
              AND a.award_year >= YEAR(CURDATE()) - 1
              AND (g.release_date IS NULL OR g.release_date >= CURDATE())
            ORDER BY a.award_year DESC
            """.trimIndent(),
            RowMapper { rs, _ ->
                WatchlistEntry(
                    rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                    "${rs.getInt(5)}년 The Game Awards 최고 기대작 후보에 올랐습니다.",
                )
            },
        )

        // 과거 GOTY 수상·후보 개발사/퍼블리셔의 미출시작.
        val byPedigree = jdbc.query(
            """
            SELECT g.slug, g.title, g.release_label, g.cover_image_url, c.name, COUNT(*) AS hits
            FROM game g
            JOIN company c ON c.id = g.developer_id OR c.id = g.publisher_id
            JOIN game past ON past.developer_id = c.id OR past.publisher_id = c.id
            JOIN game_award a ON a.game_id = past.id AND a.award_name = 'The Game Awards Game of the Year'
            WHERE g.status = 'UPCOMING' AND g.archive_only = b'0' AND past.id <> g.id
              AND c.slug <> 'independent-unknown'
            GROUP BY g.slug, g.title, g.release_label, g.cover_image_url, c.name
            ORDER BY hits DESC, g.discovery_score DESC
            LIMIT ?
            """.trimIndent(),
            RowMapper { rs, _ ->
                WatchlistEntry(
                    rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                    "${rs.getString(5)} 는 과거 GOTY 수상·후보작을 낸 곳입니다.",
                )
            },
            limit,
        )
        return (anticipated + byPedigree).distinctBy { it.slug }.take(limit)
    }
}
