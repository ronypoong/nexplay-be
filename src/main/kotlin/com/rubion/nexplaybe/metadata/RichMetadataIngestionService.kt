package com.rubion.nexplaybe.metadata

import com.rubion.nexplaybe.catalog.SteamStoreClient
import com.rubion.nexplaybe.catalog.SteamStoreMetadata
import com.rubion.nexplaybe.catalog.WikidataCatalogClient
import com.rubion.nexplaybe.game.Game
import com.rubion.nexplaybe.game.GameRepository
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.Instant

data class RichMetadataSyncSummary(val status: String, val candidates: Int, val enriched: Int, val failed: Int)

@Service
class RichMetadataIngestionService(
    private val gameRepository: GameRepository,
    private val steam: SteamStoreClient,
    private val wikidata: WikidataCatalogClient,
    private val jdbc: JdbcTemplate,
) {
    fun enrichFromSteam(limit: Int = 12): RichMetadataSyncSummary {
        val candidateIds = jdbc.queryForList(
            """
            SELECT g.id FROM game g WHERE g.steam_app_id IS NOT NULL AND
              NOT EXISTS (SELECT 1 FROM game_data_provenance p WHERE p.game_id=g.id AND p.field_name='extended_metadata_checked' AND p.source_name='Steam Store')
            ORDER BY g.featured DESC, g.discovery_score DESC LIMIT ?
            """.trimIndent(), Long::class.java, limit.coerceIn(1, 50),
        )
        val games = gameRepository.findAllForDiscovery().associateBy { it.id }
        val candidates = candidateIds.mapNotNull(games::get)
        if (candidates.isEmpty()) return RichMetadataSyncSummary("SUCCESS", 0, 0, 0)

        // Steam이 제한 응답을 보내면 첫 요청에서 바로 중단해 불필요한 재시도를 피한다.
        val first = runCatching { steam.fetchDetails(requireNotNull(candidates.first().steamAppId)) }.getOrNull()
            ?: return RichMetadataSyncSummary("SKIPPED_SOURCE_UNAVAILABLE", candidates.size, 0, candidates.size)
        var enriched = 0
        candidates.forEachIndexed { index, game ->
            val metadata = if (index == 0) first else runCatching { steam.fetchDetails(requireNotNull(game.steamAppId)) }.getOrNull()
            if (metadata != null) {
                persistSteamMetadata(game, metadata)
                enriched++
            }
        }
        return RichMetadataSyncSummary("SUCCESS", candidates.size, enriched, candidates.size - enriched)
    }

    @Transactional
    fun persistSteamMetadata(game: Game, data: SteamStoreMetadata) {
        val appId = requireNotNull(game.steamAppId)
        val sourceUrl = "https://store.steampowered.com/app/$appId"
        val now = Timestamp.from(Instant.now())
        if (data.gameModes.isNotEmpty()) {
            game.gameModes.clear()
            game.gameModes.addAll(data.gameModes)
        }
        val korean = data.languages.find { it.code == "ko" }
        game.koreanTextSupported = korean?.text
        game.koreanAudioSupported = korean?.audio
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
        val bySteamId = gameRepository.findAllForDiscovery().filter { it.steamAppId != null }.associateBy { it.steamAppId }
        data.dlcAppIds.mapNotNull(bySteamId::get).forEach { related ->
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
        val games = gameRepository.findAllForDiscovery()
        val byWikidata = games.filter { it.wikidataId != null }.associateBy { it.wikidataId }
        val relations = runCatching { wikidata.fetchRelations(byWikidata.keys.filterNotNull()) }
            .getOrElse { return RichMetadataSyncSummary("FAILED", byWikidata.size, 0, byWikidata.size) }
        relations.groupBy { it.gameId }.forEach { (gameWikidataId, items) ->
            val game = byWikidata[gameWikidataId] ?: return@forEach
            jdbc.update("DELETE FROM game_relation WHERE game_id=? AND source_name='Wikidata'", game.id)
            items.forEach { relation ->
                val related = byWikidata[relation.relatedId]
                jdbc.update(
                    """INSERT INTO game_relation (game_id,related_game_id,relation_type,external_title,external_url,source_name,verified_at)
                    VALUES (?,?,?,?,?,'Wikidata',?)""",
                    game.id, related?.id, relation.type, relation.relatedTitle,
                    "https://www.wikidata.org/wiki/${relation.relatedId}", Timestamp.from(Instant.now()),
                )
            }
        }
        return RichMetadataSyncSummary("SUCCESS", byWikidata.size, relations.size, 0)
    }
}
