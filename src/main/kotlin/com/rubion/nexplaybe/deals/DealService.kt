package com.rubion.nexplaybe.deals

import com.rubion.nexplaybe.cache.CacheConfig
import org.springframework.cache.annotation.Cacheable
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

data class DealResponse(
    val slug: String,
    val title: String,
    val developer: String?,
    val coverImageUrl: String?,
    val accent: String?,
    val symbol: String?,
    val score: Int,
    val status: String,
    val releaseLabel: String?,
    val currency: String,
    /** 표시용으로 이미 나눈 값. 스팀은 원화도 100 을 곱해 보낸다(21,000원 -> 2100000). */
    val originalPrice: Long,
    val salePrice: Long,
    val discountPercent: Int,
    val storeUrl: String,
    /** 언제 본 값인가. 할인은 끝나면 사라지므로 화면에서 이걸 숨기지 않는다. */
    val checkedAt: String,
)

data class DealsResponse(
    val deals: List<DealResponse>,
    val total: Int,
    /** 가장 큰 할인율. 화면 상단 요약에 쓴다. */
    val maxDiscount: Int,
    /** 마지막으로 가격을 확인한 시각. 목록이 비어 있을 때는 null. */
    val checkedAt: String?,
)

/**
 * 지금 할인 중인 게임.
 *
 * 다른 목록과 성격이 다르다. 출시 예정작은 기다리는 것 말고 할 일이 없고
 * 데모는 받아서 해 보면 되는데, 이건 값이 붙어 있고 기간이 끝나면 사라진다.
 * 놓치면 손해라는 점에서 데모·베타 화면과 같은 자리에 있다.
 *
 * 가격은 매일 스팀에서 한 번 받아 [game_price_snapshot] 에 쌓는다. 여기서는
 * 게임마다 마지막으로 본 값만 꺼낸다. 어제 끝난 할인을 오늘 보여 주는 일이
 * 없도록, 언제 본 값인지를 함께 내보내고 화면에서 숨기지 않는다.
 */
@Service
@Transactional(readOnly = true)
class DealService(private val jdbc: JdbcTemplate) {

    @Cacheable(CacheConfig.SECTIONS, key = "'deals-' + #limit")
    fun deals(limit: Int = DEFAULT_LIMIT): DealsResponse {
        val rows = jdbc.query(
            """
            SELECT g.slug, g.title, c.name AS developer, g.cover_image_url, g.accent, g.symbol,
                   g.discovery_score, g.status, g.release_label,
                   p.currency, p.initial_price, p.final_price, p.discount_percent,
                   p.store_url, p.captured_at
            FROM game_price_snapshot p
            -- 게임마다 마지막으로 본 값 하나만. 이력 전체를 훑으면 어제 끝난 할인이
            -- 오늘 목록에 남는다.
            JOIN (
                SELECT game_id, MAX(captured_at) AS latest
                FROM game_price_snapshot GROUP BY game_id
            ) l ON l.game_id = p.game_id AND l.latest = p.captured_at
            JOIN game g ON g.id = p.game_id
            LEFT JOIN company c ON c.id = g.developer_id
            WHERE p.discount_percent > 0
              -- archive_only 는 거르지 않는다. 그 표시는 "신작 발견 피드에 소식을
              -- 올리지 않는다" 는 뜻이지 게임이 별로라는 뜻이 아니다. 실제로 할인
              -- 중인 archive_only 게임 8개가 전부 점수 95 짜리 명작이었다 —
              -- Control, Red Dead Redemption 2, Celeste, Psychonauts 2, Stray.
              -- 할인 화면에서 제일 보고 싶은 것들을 거르고 있었다.
              --
              -- 대신 등급으로 거른다. 처음 만들었을 때 목록 맨 위가 성인물이었다.
              -- 점수만으로는 걸러지지 않는데(전부 기본값 77), 등급표에는 IGRS 와
              -- STEAM_GERMANY 가 BANNED 로 적어 두고 있었다. 한국어 게임 매거진이
              -- 첫 화면에 세울 것은 아니다. 할인 중인 것 중 19개가 여기 걸린다.
              AND NOT EXISTS (
                  SELECT 1 FROM game_age_rating a
                  WHERE a.game_id = g.id AND a.rating = 'BANNED'
              )
            -- 90% 깎인 무명 게임보다 50% 깎인 대작이 위여야 한다. 눈여겨보는
            -- 정도(70~100)를 기준으로 두고 할인율을 절반만 얹으면, 비슷한 게임
            -- 사이에서는 더 많이 깎인 쪽이 이기고 격차가 크면 중요도가 이긴다.
            ORDER BY g.discovery_score + p.discount_percent / 2.0 DESC,
                     p.discount_percent DESC
            LIMIT ?
            """.trimIndent(),
            { rs, _ ->
                DealResponse(
                    slug = rs.getString("slug"),
                    title = rs.getString("title"),
                    developer = rs.getString("developer"),
                    coverImageUrl = rs.getString("cover_image_url"),
                    accent = rs.getString("accent"),
                    symbol = rs.getString("symbol"),
                    score = rs.getBigDecimal("discovery_score")?.toInt() ?: 0,
                    status = rs.getString("status"),
                    releaseLabel = rs.getString("release_label"),
                    currency = rs.getString("currency"),
                    // 스팀은 최소 화폐 단위로 보낸다. 원화도 100 을 곱한 값이라
                    // 그대로 찍으면 21,000원이 2,100,000원이 된다.
                    originalPrice = rs.getLong("initial_price") / 100,
                    salePrice = rs.getLong("final_price") / 100,
                    discountPercent = rs.getInt("discount_percent"),
                    storeUrl = rs.getString("store_url"),
                    checkedAt = rs.getTimestamp("captured_at").toInstant().toString(),
                )
            },
            limit.coerceIn(1, MAX_LIMIT),
        )
        return DealsResponse(
            deals = rows,
            total = rows.size,
            maxDiscount = rows.maxOfOrNull { it.discountPercent } ?: 0,
            checkedAt = rows.maxOfOrNull { it.checkedAt },
        )
    }

    private companion object {
        const val DEFAULT_LIMIT = 60
        const val MAX_LIMIT = 200
    }
}
