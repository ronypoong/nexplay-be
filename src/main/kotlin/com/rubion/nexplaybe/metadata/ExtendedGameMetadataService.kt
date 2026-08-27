package com.rubion.nexplaybe.metadata

import com.rubion.nexplaybe.discovery.ResourceNotFoundException
import com.rubion.nexplaybe.game.GameRepository
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service

data class LanguageSupportResponse(val code: String, val name: String, val text: Boolean, val audio: Boolean, val source: String, val verifiedAt: String)
data class MediaResponse(val id: Long, val type: String, val title: String?, val url: String, val thumbnailUrl: String?, val official: Boolean, val source: String)
data class GameRelationResponse(val type: String, val slug: String?, val title: String, val url: String?, val source: String)
data class ReleaseRevisionResponse(val platform: String, val previousDate: String?, val newDate: String?, val type: String, val announcedAt: String, val source: String, val sourceUrl: String?)
data class PopularitySnapshotResponse(val date: String, val score: Int, val anticipationScore: Int, val followers: Long, val officialNews30d: Int, val trailerViews: Long?)
data class SystemRequirementResponse(val platform: String, val level: String, val os: String?, val processor: String?, val memory: String?, val graphics: String?, val storage: String?, val rawText: String?, val source: String)
data class PriceSnapshotResponse(val store: String, val region: String, val currency: String, val initialPrice: Long, val finalPrice: Long, val discountPercent: Int, val storeUrl: String, val capturedAt: String)
data class AgeRatingResponse(val system: String, val rating: String, val descriptors: String?, val source: String)
data class AccessibilityResponse(val category: String, val feature: String, val source: String)
data class ProvenanceResponse(val field: String, val source: String, val sourceUrl: String?, val confidence: String, val verifiedAt: String)

data class ExtendedGameMetadataResponse(
    val languages: List<LanguageSupportResponse>,
    val media: List<MediaResponse>,
    val relations: List<GameRelationResponse>,
    val releaseHistory: List<ReleaseRevisionResponse>,
    val popularityHistory: List<PopularitySnapshotResponse>,
    val systemRequirements: List<SystemRequirementResponse>,
    val prices: List<PriceSnapshotResponse>,
    val ageRatings: List<AgeRatingResponse>,
    val accessibility: List<AccessibilityResponse>,
    val provenance: List<ProvenanceResponse>,
    val completenessScore: Int,
    val missingData: List<String>,
)

@Service
class ExtendedGameMetadataService(
    private val gameRepository: GameRepository,
    private val jdbc: JdbcTemplate,
) {
    fun get(slug: String): ExtendedGameMetadataResponse {
        val game = gameRepository.findBySlug(slug) ?: throw ResourceNotFoundException("Game not found: $slug")
        val id = game.id
        val languages = jdbc.query("SELECT language_code, language_name, text_supported, audio_supported, source_name, verified_at FROM game_language_support WHERE game_id=? ORDER BY language_name", { rs, _ ->
            LanguageSupportResponse(rs.getString(1), rs.getString(2), rs.getBoolean(3), rs.getBoolean(4), rs.getString(5), rs.getTimestamp(6).toInstant().toString())
        }, id)
        val media = jdbc.query("SELECT id,type,title,url,thumbnail_url,official,source_name FROM game_media WHERE game_id=? ORDER BY sort_order,id", { rs, _ ->
            MediaResponse(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getBoolean(6), rs.getString(7))
        }, id)
        val relations = jdbc.query("""
            SELECT r.relation_type, g.slug, COALESCE(g.title,r.external_title), COALESCE(r.external_url,g.official_url), r.source_name
            FROM game_relation r LEFT JOIN game g ON g.id=r.related_game_id WHERE r.game_id=? ORDER BY r.relation_type
        """.trimIndent(), { rs, _ ->
            GameRelationResponse(rs.getString(1), rs.getString(2), rs.getString(3) ?: "관련 게임", rs.getString(4), rs.getString(5))
        }, id)
        val releaseHistory = jdbc.query("SELECT platform,previous_date,new_date,change_type,announced_at,source_name,source_url FROM release_revision WHERE game_id=? ORDER BY announced_at DESC,id DESC", { rs, _ ->
            ReleaseRevisionResponse(rs.getString(1), rs.getDate(2)?.toLocalDate()?.toString(), rs.getDate(3)?.toLocalDate()?.toString(), rs.getString(4), rs.getDate(5).toLocalDate().toString(), rs.getString(6), rs.getString(7))
        }, id)
        val popularity = jdbc.query("SELECT snapshot_date,discovery_score,anticipation_score,follower_count,official_news_30d,trailer_view_count FROM popularity_snapshot WHERE game_id=? ORDER BY snapshot_date DESC LIMIT 90", { rs, _ ->
            PopularitySnapshotResponse(rs.getDate(1).toLocalDate().toString(), rs.getBigDecimal(2).toInt(), rs.getBigDecimal(3).toInt(), rs.getLong(4), rs.getInt(5), rs.getLong(6).takeUnless { rs.wasNull() })
        }, id)
        val requirements = jdbc.query("SELECT platform,requirement_level,os,processor,memory,graphics,storage,raw_text,source_name FROM system_requirement WHERE game_id=? ORDER BY platform,requirement_level", { rs, _ ->
            SystemRequirementResponse(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9))
        }, id)
        val prices = jdbc.query("SELECT store,region,currency,initial_price,final_price,discount_percent,store_url,captured_at FROM game_price_snapshot WHERE game_id=? ORDER BY captured_at DESC LIMIT 10", { rs, _ ->
            PriceSnapshotResponse(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4), rs.getLong(5), rs.getInt(6), rs.getString(7), rs.getTimestamp(8).toInstant().toString())
        }, id)
        val ratings = jdbc.query("SELECT rating_system,rating,descriptors,source_name FROM game_age_rating WHERE game_id=? ORDER BY rating_system", { rs, _ ->
            AgeRatingResponse(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4))
        }, id)
        val accessibility = jdbc.query("SELECT category,feature,source_name FROM game_accessibility_feature WHERE game_id=? ORDER BY category,feature", { rs, _ ->
            AccessibilityResponse(rs.getString(1), rs.getString(2), rs.getString(3))
        }, id)
        val provenance = jdbc.query("SELECT field_name,source_name,source_url,confidence,verified_at FROM game_data_provenance WHERE game_id=? ORDER BY field_name", { rs, _ ->
            ProvenanceResponse(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getTimestamp(5).toInstant().toString())
        }, id)
        val hasEvents = jdbc.queryForObject("SELECT COUNT(*) FROM game_event WHERE game_id=?", Int::class.java, id)!! > 0
        val dimensions = linkedMapOf(
            "이미지" to (game.coverImageUrl != null), "공식 웹사이트" to (game.officialUrl != null),
            "장르" to game.genres.isNotEmpty(), "플랫폼" to game.platforms.none { it == "미정" },
            "게임 모드" to game.gameModes.isNotEmpty(), "언어 지원" to languages.isNotEmpty(),
            "공식 미디어" to media.any { it.official }, "공식 소식" to hasEvents,
            "시스템 요구사항" to requirements.isNotEmpty(), "연령 등급" to ratings.isNotEmpty(),
            "접근성" to accessibility.isNotEmpty(), "게임 관계" to relations.isNotEmpty(),
        )
        return ExtendedGameMetadataResponse(
            languages, media, relations, releaseHistory, popularity, requirements, prices, ratings, accessibility, provenance,
            dimensions.values.count { it } * 100 / dimensions.size,
            dimensions.filterValues { !it }.keys.toList(),
        )
    }
}
