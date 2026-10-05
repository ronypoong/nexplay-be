package com.rubion.nexplaybe.metadata

import com.rubion.nexplaybe.catalog.CatalogSyncService
import com.rubion.nexplaybe.catalog.SteamStoreClient
import com.rubion.nexplaybe.catalog.SteamStoreMetadata
import com.rubion.nexplaybe.catalog.WikidataCatalogClient
import com.rubion.nexplaybe.game.Game
import com.rubion.nexplaybe.game.GameRepository
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.Instant

data class RichMetadataSyncSummary(val status: String, val candidates: Int, val enriched: Int, val failed: Int)

/**
 * Steam 이 연속으로 거절해 한 건도 못 받아 온 경우. 조용히 넘기면 안 되는 상태다.
 *
 * 거절 사유를 메시지에 박는다. 이 문장은 sync_run 에 남아 /api/v1/status 로
 * 나가므로, Railway 로그를 열지 않고도 403 인지 429 인지 시간 초과인지 알 수 있다.
 */
class SteamSourceUnavailableException(candidates: Int, reason: String?) :
    RuntimeException("Steam appdetails 가 연속 거절했다. 후보 ${candidates}건 중 0건 수집. 마지막 사유: ${reason ?: "알 수 없음"}")

@Service
class RichMetadataIngestionService(
    private val gameRepository: GameRepository,
    private val steam: SteamStoreClient,
    private val wikidata: WikidataCatalogClient,
    private val jdbc: JdbcTemplate,
    private val transactions: TransactionTemplate,
    private val catalogSyncService: CatalogSyncService,
    /**
     * 며칠 지나면 Steam 을 다시 보는가.
     *
     * 예전에는 "한 번도 안 본 게임" 만 골라서, 451건이 완료로 표시된 뒤로는 Steam 을
     * 영영 다시 부르지 않았다. 가격도 언어 지원도 그 시점에 멈췄다는 뜻이고,
     * **한국어가 나중에 추가돼도 관측할 방법이 없었다** — 한국어 레이더와 약속
     * 대조표의 KOREAN_SUPPORT 판정이 통째로 성립하지 않는 상태였다.
     *
     * 458건을 하루 200건씩 도니 한 바퀴에 2.3일이다. 3일이면 밀리지 않고 돈다.
     */
    @param:org.springframework.beans.factory.annotation.Value("\${nexplay.catalog.steam-store.recheck-after-days:3}")
    private val recheckAfterDays: Int,
) {

    private data class SteamGameRef(val id: Long, val title: String, val officialUrl: String?)
    fun enrichFromSteam(limit: Int = DEFAULT_ENRICH_LIMIT): RichMetadataSyncSummary {
        val candidateIds = jdbc.queryForList(
            """
            SELECT g.id FROM game g
            LEFT JOIN game_data_provenance p
              ON p.game_id = g.id AND p.field_name = 'extended_metadata_checked' AND p.source_name = 'Steam Store'
            WHERE g.steam_app_id IS NOT NULL
              AND (p.verified_at IS NULL OR p.verified_at < DATE_SUB(NOW(), INTERVAL ? DAY))
            -- 한 번도 안 본 게임이 먼저다. 그 다음은 가장 오래 안 본 순서.
            ORDER BY p.verified_at IS NOT NULL, g.featured DESC, g.discovery_score DESC, p.verified_at
            LIMIT ?
            """.trimIndent(), Long::class.java, recheckAfterDays, limit.coerceIn(1, MAX_ENRICH_LIMIT),
        ).filterNotNull()
        if (candidateIds.isEmpty()) return RichMetadataSyncSummary("SUCCESS", 0, 0, 0)
        val candidates = gameRepository.findAllForDiscoveryByIds(candidateIds)
        if (candidates.isEmpty()) return RichMetadataSyncSummary("SUCCESS", 0, 0, 0)

        // DLC 연결에 쓰는 appId -> 게임 대응표. 예전에는 게임 1건마다 카탈로그 전체를 다시 읽었다.
        val bySteamAppId = steamGameRefs()
        /*
         * 예전에는 연속 5회 실패하면 그날 작업을 통째로 접었다. 그게 2026-09-06
         * 이후 매일 0건으로 끝난 진짜 이유였다 — 손으로 5건을 돌려 보니 2건이
         * 들어왔고, 그 직후부터는 계속 거절당했다. Steam 쪽 속도 제한이고,
         * Railway 처럼 여러 손님이 나눠 쓰는 주소에서는 남는 몫이 들쭉날쭉하다.
         *
         * 창이 열렸다 닫혔다 하는 상대에게 "다섯 번 연달아 거절당하면 포기" 는
         * 최악의 전략이다. 거절당하면 더 기다렸다가 계속 두드린다. 하루에 열 건을
         * 건져도 접는 것보다는 낫다.
         *
         * 아주 오래 아무것도 못 받으면 그때는 멈춘다 — 상대가 정말 죽었을 때
         * 5분을 허비할 이유는 없다.
         */
        var consecutiveFailures = 0
        var enriched = 0
        // 백오프가 붙으면 한 번 도는 데 걸리는 시간이 들쭉날쭉해진다. 하루 한 번
        // 도는 일이라도 끝은 있어야 하므로 벽시계로 끊는다.
        val deadline = System.currentTimeMillis() + MAX_RUN_MILLIS
        for ((index, game) in candidates.withIndex()) {
            if (consecutiveFailures >= STEAM_GIVE_UP_STREAK) break
            if (System.currentTimeMillis() > deadline) break
            // 한 번에 수백 건을 훑으므로 Steam 에 예의를 지킨다. 거절당한 직후에는
            // 더 길게 쉰다 — 같은 속도로 계속 두드리면 창이 영영 열리지 않는다.
            if (index > 0) {
                val pause = if (consecutiveFailures > 0) BACKOFF_INTERVAL_MS else REQUEST_INTERVAL_MS
                runCatching { Thread.sleep(pause) }
            }
            val metadata = runCatching { steam.fetchDetails(requireNotNull(game.steamAppId)) }.getOrNull()
            if (metadata == null) {
                consecutiveFailures++
                continue
            }
            consecutiveFailures = 0
            // persistSteamMetadata 는 같은 빈의 메서드라 @Transactional 자기호출이 프록시를 우회한다.
            // 8개 테이블 쓰기가 각각 auto-commit 되지 않도록 트랜잭션을 여기서 명시적으로 연다.
            transactions.execute { persistSteamMetadata(game, metadata, bySteamAppId) }
            enriched++
        }
        if (enriched == 0) {
            // 예전에는 요약만 SKIPPED_SOURCE_UNAVAILABLE 로 돌려보냈다. 단계 자체는
            // 예외를 던지지 않았으니 SyncRunRecorder 가 SUCCESS 로 적었고, 상태
            // 화면에도 초록불이 떴다. 그래서 2026-09-06 부터 이 단계가 하루도
            // 성공하지 못했는데 23일을 아무도 몰랐다. 한 건도 못 받아 왔으면
            // 그건 성공이 아니다 — 던져서 FAILED 로 남긴다.
            throw SteamSourceUnavailableException(candidates.size, steam.lastRejection)
        }
        return RichMetadataSyncSummary("SUCCESS", candidates.size, enriched, candidates.size - enriched)
    }

    private fun steamGameRefs(): Map<Long, SteamGameRef> = jdbc.query(
        "SELECT steam_app_id, id, title, official_url FROM game WHERE steam_app_id IS NOT NULL",
    ) { rs, _ -> rs.getLong(1) to SteamGameRef(rs.getLong(2), rs.getString(3), rs.getString(4)) }.toMap()

    private fun persistSteamMetadata(game: Game, data: SteamStoreMetadata, bySteamAppId: Map<Long, SteamGameRef>) {
        val appId = requireNotNull(game.steamAppId)
        val sourceUrl = "https://store.steampowered.com/app/$appId"
        val now = Timestamp.from(Instant.now())

        /*
         * 대표 이미지가 비어 있으면 채운다.
         *
         * 매일 Steam 에서 header_image 를 받아 오면서 저장은 하지 않고 있었다.
         * 이미지는 카탈로그에 처음 넣을 때만 채워지므로, 그 시점에 Steam 을 못
         * 불렀거나 앱 ID 를 나중에 알게 된 게임은 영영 비어 있었다. 실제로
         * 사진 없는 25개 중 14개가 확장 수집을 거치고도 그대로였다.
         *
         * 이미 있는 것은 덮지 않는다. 사람이 손으로 넣었거나 다른 출처에서 온
         * 것일 수 있고, 그걸 자동 수집이 밀어내면 안 된다.
         */
        if (game.coverImageUrl.isNullOrBlank() && data.headerImageUrl.isNotBlank()) {
            // 엔티티로 고친다. 같은 트랜잭션에서 이 엔티티의 다른 필드도 바꾸므로,
            // raw SQL 로 쓰면 Hibernate 가 flush 하면서 옛 값으로 되돌린다.
            game.coverImageUrl = data.headerImageUrl
            gameRepository.save(game)
            jdbc.update(
                """INSERT INTO game_data_provenance (game_id,field_name,source_name,source_url,confidence,verified_at)
                VALUES (?,'cover_image','Steam Store',?,'HIGH',?) ON DUPLICATE KEY UPDATE verified_at=VALUES(verified_at)""",
                game.id, sourceUrl, now,
            )
        }

        if (data.gameModes.isNotEmpty()) {
            game.gameModes.clear()
            game.gameModes.addAll(data.gameModes)
        }
        // 확장 메타데이터 수집은 전 게임을 한 번씩 훑는다. 소개문도 여기서 같이 채운다.
        catalogSyncService.applyStoreCopy(game, data)
        val korean = data.languages.find { it.code == "ko" }
        // 언어 목록을 읽어냈는데 한국어가 없으면 "확인 중"(null)이 아니라 "미지원"(false)이다.
        game.koreanTextSupported = if (data.languages.isEmpty()) null else korean != null
        game.koreanAudioSupported = if (data.languages.isEmpty()) null else korean?.audio ?: false
        gameRepository.save(game)

        recordLanguageChanges(game.id, data, sourceUrl)
        data.languages.forEach { language ->
            jdbc.update(
                """INSERT INTO game_language_support (game_id,language_code,language_name,text_supported,audio_supported,source_name,source_url,verified_at)
                VALUES (?,?,?,?,?,'Steam Store',?,?) ON DUPLICATE KEY UPDATE language_name=VALUES(language_name),text_supported=VALUES(text_supported),audio_supported=VALUES(audio_supported),verified_at=VALUES(verified_at)""",
                game.id, language.code, language.name, language.text, language.audio, sourceUrl, now,
            )
        }
        data.media.forEachIndexed { index, media ->
            jdbc.update(
                """INSERT INTO game_media (game_id,type,external_id,title,url,thumbnail_url,official,source_name,sort_order,verified_at)
                VALUES (?,?,?,?,?,?,b'1','Steam Store',?,?) ON DUPLICATE KEY UPDATE title=VALUES(title),url=VALUES(url),thumbnail_url=VALUES(thumbnail_url),verified_at=VALUES(verified_at)""",
                game.id, media.type, media.externalId, media.title, media.url, media.thumbnailUrl, index + 10, now,
            )
        }
        listOf("MINIMUM" to data.minimumRequirements, "RECOMMENDED" to data.recommendedRequirements).forEach { (level, raw) ->
            if (!raw.isNullOrBlank()) jdbc.update(
                """INSERT INTO system_requirement (game_id,platform,requirement_level,raw_text,source_name,source_url,verified_at)
                VALUES (?,'PC',?,?,'Steam Store',?,?) ON DUPLICATE KEY UPDATE raw_text=VALUES(raw_text),verified_at=VALUES(verified_at)""",
                game.id, level, raw, sourceUrl, now,
            )
        }
        data.price?.let { price ->
            jdbc.update(
                """INSERT INTO game_price_snapshot (game_id,store,region,currency,initial_price,final_price,discount_percent,store_url,captured_at)
                SELECT ?,'STEAM','KR',?,?,?,?,?,? FROM DUAL WHERE NOT EXISTS (
                  SELECT 1 FROM game_price_snapshot WHERE game_id=? AND store='STEAM' AND DATE(captured_at)=CURRENT_DATE
                )""",
                game.id, price.currency, price.initial, price.final, price.discountPercent, sourceUrl, now, game.id,
            )
        }
        data.ageRatings.forEach { rating ->
            jdbc.update(
                """INSERT INTO game_age_rating (game_id,rating_system,rating,descriptors,source_name,source_url,verified_at)
                VALUES (?,?,?,?,'Steam Store',?,?) ON DUPLICATE KEY UPDATE rating=VALUES(rating),descriptors=VALUES(descriptors),verified_at=VALUES(verified_at)""",
                game.id, rating.system, rating.rating, rating.descriptors, sourceUrl, now,
            )
        }
        data.accessibilityFeatures.forEach { feature ->
            jdbc.update(
                """INSERT INTO game_accessibility_feature (game_id,category,feature,source_name,source_url,verified_at)
                VALUES (?,'STORE_FEATURE',?,'Steam Store',?,?) ON DUPLICATE KEY UPDATE verified_at=VALUES(verified_at)""",
                game.id, feature, sourceUrl, now,
            )
        }
        data.dlcAppIds.mapNotNull(bySteamAppId::get).forEach { related ->
            jdbc.update(
                """INSERT INTO game_relation (game_id,related_game_id,relation_type,external_title,external_url,source_name,verified_at)
                VALUES (?,?,'DLC',?,?, 'Steam Store',?) ON DUPLICATE KEY UPDATE verified_at=VALUES(verified_at)""",
                game.id, related.id, related.title, related.officialUrl, now,
            )
        }
        // 리뷰 수는 값이 바뀌었을 때만 한 줄 남긴다. 매일 같은 값을 넣으면 그래프에
        // 가짜 평평한 구간이 생기고, 관측한 것과 반복한 것을 구분할 수 없게 된다.
        data.reviewCount?.let { count ->
            val previous = jdbc.query(
                "SELECT steam_review_count FROM game WHERE id = ?",
                { rs, _ -> rs.getLong(1).takeUnless { rs.wasNull() } }, game.id,
            ).firstOrNull()
            if (previous != count) {
                jdbc.update("UPDATE game SET steam_review_count = ? WHERE id = ?", count, game.id)
                jdbc.update(
                    "INSERT IGNORE INTO game_review_history (game_id, review_count, observed_at) VALUES (?,?,?)",
                    game.id, count, now,
                )
            }
        }
        listOf("languages", "media", "game_modes", "requirements", "price", "age_rating", "accessibility", "extended_metadata_checked").forEach { field ->
            jdbc.update(
                """INSERT INTO game_data_provenance (game_id,field_name,source_name,source_url,confidence,verified_at)
                VALUES (?,?,'Steam Store',?,'HIGH',?) ON DUPLICATE KEY UPDATE verified_at=VALUES(verified_at)""",
                game.id, field, sourceUrl, now,
            )
        }
    }

    /**
     * 언어 지원이 바뀌면 이력으로 남긴다.
     *
     * game_language_support 는 덮어쓰기라 과거가 사라진다. "이 게임이 언제부터
     * 한국어를 지원했나" 는 지금 남기지 않으면 나중에 만들 수 없는 정보다.
     * 바뀐 것만 남긴다 — 매일 같은 값을 다시 적으면 이력이 아니라 로그가 된다.
     */
    private fun recordLanguageChanges(gameId: Long, data: SteamStoreMetadata, sourceUrl: String) {
        val before = jdbc.query(
            "SELECT language_code, text_supported, audio_supported FROM game_language_support WHERE game_id = ?",
            { rs, _ -> rs.getString(1) to (rs.getBoolean(2) to rs.getBoolean(3)) },
            gameId,
        ).toMap()
        val after = data.languages.associate { it.code to (it.text to it.audio) }

        (before.keys + after.keys).forEach { code ->
            val old = before[code]
            val new = after[code]
            if (old == new) return@forEach
            val changeType = when {
                old == null -> "ADDED"
                new == null -> "REMOVED"
                else -> "CHANGED"
            }
            jdbc.update(
                """
                INSERT INTO game_language_history
                  (game_id, language_code, change_type, previous_text, previous_audio, new_text, new_audio, source_name, source_url)
                VALUES (?,?,?,?,?,?,?, 'Steam Store', ?)
                """.trimIndent(),
                gameId, code, changeType, old?.first, old?.second, new?.first, new?.second, sourceUrl,
            )
        }
    }

    fun snapshotPopularity(): Int = jdbc.update(
        """INSERT IGNORE INTO popularity_snapshot (game_id,snapshot_date,discovery_score,anticipation_score,follower_count,official_news_30d,trailer_view_count,source_name)
        SELECT g.id,CURRENT_DATE,g.discovery_score,g.anticipation_score,g.follower_count,
          (SELECT COUNT(*) FROM game_event e WHERE e.game_id=g.id AND e.event_date>=CURRENT_DATE - INTERVAL 30 DAY),NULL,'NEXPLAY'
        FROM game g""",
    )

    fun enrichWikidataRelations(): RichMetadataSyncSummary {
        val byWikidata = jdbc.query(
            "SELECT wikidata_id, id FROM game WHERE wikidata_id IS NOT NULL",
        ) { rs, _ -> rs.getString(1) to rs.getLong(2) }.toMap()
        if (byWikidata.isEmpty()) return RichMetadataSyncSummary("SUCCESS", 0, 0, 0)
        val relations = runCatching { wikidata.fetchRelations(byWikidata.keys) }
            .getOrElse { return RichMetadataSyncSummary("FAILED", byWikidata.size, 0, byWikidata.size) }
        var enriched = 0
        relations.groupBy { it.gameId }.forEach { (gameWikidataId, items) ->
            val gameId = byWikidata[gameWikidataId] ?: return@forEach
            // DELETE 와 INSERT 가 갈라지면 중간에 죽었을 때 관계가 통째로 사라진다.
            transactions.execute {
                jdbc.update("DELETE FROM game_relation WHERE game_id=? AND source_name='Wikidata'", gameId)
                items.forEach { relation ->
                    jdbc.update(
                        """INSERT INTO game_relation (game_id,related_game_id,relation_type,external_title,external_url,source_name,verified_at)
                        VALUES (?,?,?,?,?,'Wikidata',?)""",
                        gameId, byWikidata[relation.relatedId], relation.type, relation.relatedTitle,
                        "https://www.wikidata.org/wiki/${relation.relatedId}", Timestamp.from(Instant.now()),
                    )
                }
            }
            enriched++
        }
        return RichMetadataSyncSummary("SUCCESS", byWikidata.size, enriched, 0)
    }

    private companion object {
        /*
         * 몇 번 연달아 거절당하면 그날을 접는가.
         *
         * 5였다. 창이 열렸다 닫혔다 하는 상대에게는 너무 짧아서, 창이 닫힌
         * 순간에 걸리면 한 건도 못 건지고 끝났다. 20이면 백오프까지 쳐서
         * 5분쯤 버틴다 — 그 사이 창이 한 번은 열린다.
         */
        const val STEAM_GIVE_UP_STREAK = 20
        // 하루 12건이면 남은 400여 건을 채우는 데 한 달이 걸린다.
        //
        // 200건에서 100건으로 내렸다. 카탈로그가 2,337개로 커져서 한 바퀴가
        // 23일이 되지만, 한 바퀴를 빨리 도는 것보다 매일 도는 쪽이 낫다.
        const val DEFAULT_ENRICH_LIMIT = 100
        const val MAX_ENRICH_LIMIT = 500
        // Steam appdetails 는 IP 당 5분에 200건 언저리에서 막는다.
        //
        // 1.5초는 5분에 정확히 200건 — 한도의 경계선이다. 경계선에 붙여 두면
        // Steam 이 조금만 조여도 첫 다섯 건에서 끊긴다. 게임 한 건이 kr/us 두 번을
        // 부르니 실제로는 이미 한도의 두 배를 쓰고 있었다. 3초면 절반으로 내려간다.
        const val REQUEST_INTERVAL_MS = 3_000L
        // 거절당한 뒤에 쉬는 시간. 같은 속도로 계속 두드리면 창이 영영 안 열린다.
        const val BACKOFF_INTERVAL_MS = 15_000L
        // 한 번 도는 데 쓸 수 있는 시간. 백오프가 붙으면 길어지므로 끝을 정해 둔다.
        const val MAX_RUN_MILLIS = 10 * 60 * 1000L
    }
}
