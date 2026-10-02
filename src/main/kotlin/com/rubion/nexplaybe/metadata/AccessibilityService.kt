package com.rubion.nexplaybe.metadata

import com.rubion.nexplaybe.cache.CacheConfig
import org.springframework.cache.annotation.Cacheable
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** 접근성 기능 하나와 그 기능을 가진 게임 수. */
data class AccessibilityFeatureCount(val feature: String, val gameCount: Int)

/** 접근성 기능이 확인된 게임 한 줄. */
data class AccessibleGame(
    val slug: String,
    val title: String,
    val developer: String,
    val releaseLabel: String,
    val koreanTextSupported: Boolean?,
    val features: List<String>,
)

/** 이름이 AccessibilityResponse 가 아닌 이유: 같은 패키지에 게임 상세용으로 이미 있다. */
data class AccessibilityOverviewResponse(
    val features: List<AccessibilityFeatureCount>,
    val games: List<AccessibleGame>,
)

/**
 * 접근성 기능으로 게임을 찾는 길.
 *
 * Steam 이 주는 접근성 항목을 이미 모으고 있었지만, 쓰이는 곳이 게임 상세의
 * 작은 칩뿐이었다. 그마저 옆 기둥에 있어서 좁은 화면에서는 숨는다. 정작
 * "난이도 조정 되는 게임" 이나 "색약 모드 있는 게임" 을 찾는 사람은 그 칩에
 * 닿을 방법이 없다.
 *
 * 국내에 이걸 정리해 둔 곳이 사실상 없다. 수요가 큰 영역은 아니지만, 필요한
 * 사람에게는 대안이 없는 정보다.
 *
 * 640개 게임이라 한 번에 다 내보낸다. 기능별로 나눠 부르게 하면 한글 기능
 * 이름이 그대로 쿼리에 실려 주소가 지저분해진다.
 */
@Service
@Transactional(readOnly = true)
class AccessibilityService(private val jdbc: JdbcTemplate) {

    @Cacheable(CacheConfig.SECTIONS, key = "'accessibility'")
    fun overview(): AccessibilityOverviewResponse {
        val features = jdbc.query(
            """
            SELECT f.feature, COUNT(DISTINCT f.game_id) AS game_count
            FROM game_accessibility_feature f
            GROUP BY f.feature
            ORDER BY game_count DESC, f.feature
            """.trimIndent(),
        ) { rs, _ -> AccessibilityFeatureCount(rs.getString("feature"), rs.getInt("game_count")) }

        val rows = jdbc.query(
            """
            SELECT g.slug, g.title, g.release_label, g.korean_text_supported,
                   COALESCE(d.name, '') AS developer, f.feature
            FROM game_accessibility_feature f
            JOIN game g ON g.id = f.game_id
            LEFT JOIN company d ON d.id = g.developer_id
            -- 아카이브 전용으로 들어온 수상작은 카탈로그 화면에 세우지 않는다.
            WHERE g.archive_only = b'0'
            ORDER BY g.discovery_score DESC, g.title
            """.trimIndent(),
        ) { rs, _ ->
            Triple(
                rs.getString("slug"),
                AccessibleGame(
                    slug = rs.getString("slug"),
                    title = rs.getString("title"),
                    developer = rs.getString("developer"),
                    releaseLabel = rs.getString("release_label") ?: "",
                    // getBoolean 은 NULL 을 false 로 바꾼다. "미확인" 과 "미지원" 은 다른 말이다.
                    koreanTextSupported = rs.getObject("korean_text_supported")?.let { rs.getBoolean("korean_text_supported") },
                    features = emptyList(),
                ),
                rs.getString("feature"),
            )
        }

        // 게임 한 줄에 기능을 모아 붙인다. 순서는 위 쿼리가 정한 등장 순서를 따른다.
        val bySlug = LinkedHashMap<String, AccessibleGame>()
        rows.filterNotNull().forEach { (slug, game, feature) ->
            val current = bySlug[slug] ?: game
            bySlug[slug] = current.copy(features = current.features + feature)
        }
        return AccessibilityOverviewResponse(features, bySlug.values.toList())
    }
}
