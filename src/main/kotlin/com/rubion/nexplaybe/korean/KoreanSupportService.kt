package com.rubion.nexplaybe.korean

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

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

    private fun percent(part: Int, whole: Int) = if (whole == 0) 0 else Math.round(100.0 * part / whole).toInt()

    private data class Quad(val slug: String, val title: String, val publisher: String, val releaseLabel: String)

    private companion object {
        const val MIN_SAMPLE = 3
    }
}
