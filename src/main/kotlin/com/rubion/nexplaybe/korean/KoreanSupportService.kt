package com.rubion.nexplaybe.korean

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import com.rubion.nexplaybe.cache.CacheConfig
import org.springframework.cache.annotation.Cacheable

data class PublisherKoreanRate(
    val publisher: String,
    val checked: Int,
    val supported: Int,
    val fullVoice: Int,
    val ratePercent: Int,
)

data class KoreanCoverage(
    val totalGames: Int,
    val checked: Int,
    val supported: Int,
    val fullVoice: Int,
    val unchecked: Int,
)

data class KoreanForecast(
    val slug: String,
    val title: String,
    val publisher: String,
    val releaseLabel: String,
    val probabilityPercent: Int,
    val basis: String,
)

/**
 * 한국어 지원이 새로 잡힌 기록 한 줄.
 *
 * changeType 은 ADDED(없던 한국어가 붙음) 또는 CONFIRMED(우리가 처음 확인함).
 * 둘을 합쳐 "추가" 라고 부르지 않는다 — 날짜의 뜻이 서로 다르다.
 */
data class KoreanSupportChange(
    val slug: String,
    val title: String,
    val publisher: String,
    val releaseLabel: String,
    val changeType: String,
    val audioSupported: Boolean,
    val observedOn: String,
)

data class KoreanRadarResponse(
    val coverage: KoreanCoverage,
    val publishers: List<PublisherKoreanRate>,
    val forecasts: List<KoreanForecast>,
    val fullVoiceGames: List<KoreanForecast>,
)

/**
 * "이 게임 한국어 나와요?" 를 1급 데이터로 다루는 곳.
 *
 * Steam 언어 목록은 이미 수집하고 있으므로, 퍼블리셔별 한국어 지원 이력을 세면
 * 아직 확인되지 않은 미출시작의 한국어 가능성을 근거와 함께 말할 수 있다.
 * 점수만 던지지 않고 basis 에 무엇을 보고 그렇게 판단했는지 함께 담는다.
 */
@Service
@Transactional(readOnly = true)
class KoreanSupportService(private val jdbc: JdbcTemplate) {

    @Cacheable(CacheConfig.SECTIONS, key = "'korean-' + #minSampleSize")
    fun radar(minSampleSize: Int = MIN_SAMPLE): KoreanRadarResponse {
        val coverage = jdbc.queryForObject(
            """
            SELECT COUNT(*) AS total,
                   SUM(korean_text_supported IS NOT NULL) AS checked,
                   SUM(korean_text_supported = b'1') AS supported,
                   SUM(korean_audio_supported = b'1') AS full_voice
            FROM game
            """.trimIndent(),
        ) { rs, _ ->
            val total = rs.getInt("total")
            val checked = rs.getInt("checked")
            KoreanCoverage(total, checked, rs.getInt("supported"), rs.getInt("full_voice"), total - checked)
        }!!

        val publishers = jdbc.query(
            """
            SELECT c.name,
                   COUNT(*) AS checked,
                   SUM(g.korean_text_supported = b'1') AS supported,
                   SUM(g.korean_audio_supported = b'1') AS full_voice
            FROM game g JOIN company c ON c.id = g.publisher_id
            WHERE g.korean_text_supported IS NOT NULL AND c.slug <> 'independent-unknown'
            GROUP BY c.name
            HAVING COUNT(*) >= ?
            ORDER BY (SUM(g.korean_text_supported = b'1') / COUNT(*)) DESC, COUNT(*) DESC
            """.trimIndent(),
            RowMapper { rs, _ ->
                val checked = rs.getInt("checked")
                val supported = rs.getInt("supported")
                PublisherKoreanRate(rs.getString("name"), checked, supported, rs.getInt("full_voice"), percent(supported, checked))
            },
            minSampleSize,
        )

        val rateByPublisher = publishers.associateBy { it.publisher }

        // 아직 한국어 여부를 모르는 미출시작에 퍼블리셔 이력을 적용한다.
        val forecasts = jdbc.query(
            """
            SELECT g.slug, g.title, c.name AS publisher, g.release_label
            FROM game g JOIN company c ON c.id = g.publisher_id
            WHERE g.korean_text_supported IS NULL AND g.status = 'UPCOMING'
            ORDER BY g.release_date ASC
            LIMIT 200
            """.trimIndent(),
        ) { rs, _ ->
            Quad(rs.getString("slug"), rs.getString("title"), rs.getString("publisher"), rs.getString("release_label"))
        }.mapNotNull { row ->
            val rate = rateByPublisher[row.publisher] ?: return@mapNotNull null
            KoreanForecast(
                row.slug, row.title, row.publisher, row.releaseLabel, rate.ratePercent,
                "${row.publisher} 작품 ${rate.checked}개 중 ${rate.supported}개가 한국어를 지원합니다.",
            )
        }.sortedByDescending { it.probabilityPercent }.take(24)

        // 자막까지는 흔하지만 음성까지 가는 게임은 드물다. 희소성 자체가 볼거리다.
        val fullVoice = jdbc.query(
            """
            SELECT g.slug, g.title, c.name AS publisher, g.release_label
            FROM game g JOIN company c ON c.id = g.publisher_id
            WHERE g.korean_audio_supported = b'1'
            ORDER BY g.discovery_score DESC
            LIMIT 24
            """.trimIndent(),
        ) { rs, _ ->
            KoreanForecast(
                rs.getString("slug"), rs.getString("title"), rs.getString("publisher"),
                rs.getString("release_label"), 100, "한국어 음성까지 지원합니다.",
            )
        }

        return KoreanRadarResponse(coverage, publishers, forecasts, fullVoice)
    }

    /**
     * 한국어 지원이 새로 잡힌 게임을 최근 순으로.
     *
     * `game_language_history` 는 언어 지원이 바뀐 순간을 남긴다. 두 가지가 섞여
     * 있어서 구분해 내보낸다.
     *
     * - `ADDED`: 미지원으로 보던 게임에 한국어가 붙은 것. 진짜 "추가" 다.
     * - `CONFIRMED`: 그 게임의 언어를 처음 본 것. 우리가 몰랐던 것이지 게임이
     *   바뀐 게 아니다. 이걸 "추가" 라고 부르면 거짓말이 된다.
     *
     * 2026-09-29 기준 ADDED 는 0건이다. 8월 말 첫 수집 때 전부 CONFIRMED 로
     * 들어왔고, 그 뒤 Steam 재조회가 막혀 변화를 관측하지 못했기 때문이다.
     * 재조회가 돌기 시작하면 ADDED 가 쌓인다.
     */
    @Cacheable(CacheConfig.SECTIONS, key = "'korean-recent-' + #limit")
    fun recentlySupported(limit: Int = RECENT_LIMIT): List<KoreanSupportChange> = jdbc.query(
        """
        SELECT g.slug, g.title, g.release_label,
               -- 퍼블리셔가 비어 있는 게임이 있어 개발사로 대신한다.
               COALESCE(p.name, d.name, '') AS publisher,
               h.previous_text IS NOT NULL AND h.previous_text = b'0' AS is_added,
               h.new_audio = b'1' AS audio,
               h.observed_at
        FROM game_language_history h
        JOIN game g ON g.id = h.game_id
        LEFT JOIN company p ON p.id = g.publisher_id
        LEFT JOIN company d ON d.id = g.developer_id
        WHERE h.language_code LIKE 'ko%'
          AND h.new_text = b'1'
          -- 같은 게임이 여러 번 관측돼도 가장 최근 것 하나만 센다.
          AND h.observed_at = (
              SELECT MAX(h2.observed_at) FROM game_language_history h2
              WHERE h2.game_id = h.game_id AND h2.language_code LIKE 'ko%' AND h2.new_text = b'1'
          )
        ORDER BY h.observed_at DESC, g.discovery_score DESC
        LIMIT ?
        """.trimIndent(), { rs, _ ->
            KoreanSupportChange(
                slug = rs.getString("slug"),
                title = rs.getString("title"),
                publisher = rs.getString("publisher") ?: "",
                releaseLabel = rs.getString("release_label") ?: "",
                changeType = if (rs.getBoolean("is_added")) "ADDED" else "CONFIRMED",
                audioSupported = rs.getBoolean("audio"),
                observedOn = rs.getTimestamp("observed_at").toLocalDateTime().toLocalDate().toString(),
            )
        },
        limit.coerceIn(1, MAX_RECENT_LIMIT),
    )

    /**
     * 게임 하나의 한국어 확률.
     *
     * 레이더 화면이 상위 24개만 묶어서 내주고 있었다. 정작 "이 게임 한국어 나와요?"
     * 를 묻는 사람은 그 게임의 상세 화면에 서 있는데, 거기에는 숫자가 없었다.
     *
     * 이미 확인된 게임에는 확률을 붙이지 않는다. 답이 있는데 짐작을 얹는 것은
     * 보태는 게 아니라 흐리는 것이다. 표본이 모자란 퍼블리셔도 비워 둔다 —
     * 한두 편으로 낸 비율은 그 자체가 거짓말이다.
     */
    @Cacheable(CacheConfig.SECTIONS, key = "'korean-forecast-' + #slug")
    fun forecastFor(slug: String): KoreanForecast? {
        val game = jdbc.query(
            """
            SELECT g.slug, g.title, g.release_label, g.status,
                   g.korean_text_supported IS NOT NULL AS known,
                   c.name AS publisher
            FROM game g JOIN company c ON c.id = g.publisher_id
            WHERE g.slug = ?
            """.trimIndent(),
            { rs, _ ->
                Quint(
                    rs.getString("slug"), rs.getString("title"), rs.getString("publisher"),
                    rs.getString("release_label") ?: "", rs.getBoolean("known") || rs.getString("status") != "UPCOMING",
                )
            },
            slug,
        ).firstOrNull() ?: return null
        if (game.settled) return null

        val rate = jdbc.query(
            """
            SELECT COUNT(*) AS checked, SUM(g.korean_text_supported = b'1') AS supported
            FROM game g JOIN company c ON c.id = g.publisher_id
            WHERE c.name = ? AND g.korean_text_supported IS NOT NULL
            """.trimIndent(),
            { rs, _ -> rs.getInt("checked") to rs.getInt("supported") },
            game.publisher,
        ).firstOrNull() ?: return null
        val (checked, supported) = rate
        if (checked < MIN_SAMPLE) return null

        return KoreanForecast(
            game.slug, game.title, game.publisher, game.releaseLabel, percent(supported, checked),
            "${game.publisher} 작품 ${checked}개 중 ${supported}개가 한국어를 지원합니다.",
        )
    }

    private fun percent(part: Int, whole: Int) = if (whole == 0) 0 else Math.round(100.0 * part / whole).toInt()

    private data class Quint(val slug: String, val title: String, val publisher: String, val releaseLabel: String, val settled: Boolean)

    private data class Quad(val slug: String, val title: String, val publisher: String, val releaseLabel: String)

    private companion object {
        const val MIN_SAMPLE = 3
        const val RECENT_LIMIT = 60
        const val MAX_RECENT_LIMIT = 200
    }
}
