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

@Service
class RichMetadataIngestionService(
    private val gameRepository: GameRepository,
    private val steam: SteamStoreClient,
    private val wikidata: WikidataCatalogClient,
    private val jdbc: JdbcTemplate,
    private val transactions: TransactionTemplate,
    private val catalogSyncService: CatalogSyncService,
) {

    private data class SteamGameRef(val id: Long, val title: String, val officialUrl: String?)
    fun enrichFromSteam(limit: Int = DEFAULT_ENRICH_LIMIT): RichMetadataSyncSummary {
        val candidateIds = jdbc.queryForList(
            """
            SELECT g.id FROM game g WHERE g.steam_app_id IS NOT NULL AND
              NOT EXISTS (SELECT 1 FROM game_data_provenance p WHERE p.game_id=g.id AND p.field_name='extended_metadata_checked' AND p.source_name='Steam Store')
            ORDER BY g.featured DESC, g.discovery_score DESC LIMIT ?
            """.trimIndent(), Long::class.java, limit.coerceIn(1, MAX_ENRICH_LIMIT),
        ).filterNotNull()
        if (candidateIds.isEmpty()) return RichMetadataSyncSummary("SUCCESS", 0, 0, 0)
        val candidates = gameRepository.findAllForDiscoveryByIds(candidateIds)
        if (candidates.isEmpty()) return RichMetadataSyncSummary("SUCCESS", 0, 0, 0)

        // DLC 연결에 쓰는 appId -> 게임 대응표. 예전에는 게임 1건마다 카탈로그 전체를 다시 읽었다.
        val bySteamAppId = steamGameRefs()
        // Steam 이 실제로 죽었을 때만 중단한다. 특정 앱 하나가 지역 제한이나 삭제 상태여도 나머지는 계속 진행한다.
        var consecutiveFailures = 0
        var enriched = 0
        for ((index, game) in candidates.withIndex()) {
            if (consecutiveFailures >= STEAM_FAILURE_CUTOFF) break
            // 한 번에 수백 건을 훑으므로 Steam 에 예의를 지킨다.
            if (index > 0) runCatching { Thread.sleep(REQUEST_INTERVAL_MS) }
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
        if (enriched == 0 && consecutiveFailures >= STEAM_FAILURE_CUTOFF) {
            return RichMetadataSyncSummary("SKIPPED_SOURCE_UNAVAILABLE", candidates.size, 0, candidates.size)
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
        listOf("languages", "media", "game_modes", "requirements", "price", "age_rating", "accessibility", "extended_metadata_checked").forEach { field ->
            jdbc.update(
                """INSERT INTO game_data_provenance (game_id,field_name,source_name,source_url,confidence,verified_at)
                VALUES (?,?,'Steam Store',?,'HIGH',?) ON DUPLICATE KEY UPDATE verified_at=VALUES(verified_at)""",
                game.id, field, sourceUrl, now,
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
        const val STEAM_FAILURE_CUTOFF = 5
        // 하루 12건이면 남은 400여 건을 채우는 데 한 달이 걸린다.
        const val DEFAULT_ENRICH_LIMIT = 200
        const val MAX_ENRICH_LIMIT = 500
        // Steam appdetails 는 IP 당 5분에 200건 언저리에서 막는다.
        // 300ms 로 돌렸더니 200건 중 197건이 차단됐다. 한 건당 1.5초면 그 한도 안에 들어간다.
        const val REQUEST_INTERVAL_MS = 1_500L
    }
}
