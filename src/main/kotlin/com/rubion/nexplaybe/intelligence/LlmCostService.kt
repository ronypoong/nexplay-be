package com.rubion.nexplaybe.intelligence

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** 100만 토큰당 달러. OpenAI 가 단가를 바꾸면 설정만 고치면 된다. */
data class ModelPrice(var input: Double = 0.0, var output: Double = 0.0)

@ConfigurationProperties(prefix = "nexplay.intelligence")
class PricingProperties {
    /** 모델 이름 -> 단가. 표에 없는 모델은 금액을 추정하지 않는다. */
    var pricing: MutableMap<String, ModelPrice> = mutableMapOf()
}

@Configuration
@EnableConfigurationProperties(PricingProperties::class)
class PricingConfig

data class DailyCost(
    val date: String,
    val calls: Long,
    val promptTokens: Long,
    val completionTokens: Long,
    val totalTokens: Long,
    /** 단가표에 없는 모델이 섞여 있으면 null. 모르는 것을 0 으로 적으면 거짓말이 된다. */
    val estimatedUsd: Double?,
    val models: List<String>,
)

data class CostSummary(
    val days: List<DailyCost>,
    val last30DaysUsd: Double?,
    /** 최근 7일 평균으로 본 한 달 예상. 쓴 날이 없으면 null. */
    val projectedMonthlyUsd: Double?,
    val note: String,
)

/**
 * 모델에 쓴 돈을 어림한다.
 *
 * **추정이다.** 토큰 수는 우리가 센 것이고 단가는 설정에 적어 둔 값이라, 실제
 * 청구액과 다를 수 있다. 그래도 매일 얼마쯤 나가는지 감이 있어야 통제가 된다 —
 * 청구서가 올 때까지 모르는 것보다 낫다.
 */
@Service
@Transactional(readOnly = true)
class LlmCostService(
    private val jdbc: JdbcTemplate,
    private val pricing: PricingProperties,
) {

    fun summary(days: Int = 14): CostSummary {
        val rows = jdbc.query(
            """
            SELECT usage_date, model, SUM(calls) AS calls,
                   SUM(prompt_tokens) AS p, SUM(completion_tokens) AS c, SUM(total_tokens) AS t
            FROM llm_usage
            WHERE usage_date >= DATE_SUB(CURRENT_DATE, INTERVAL ? DAY)
            GROUP BY usage_date, model
            ORDER BY usage_date DESC
            """.trimIndent(),
            { rs, _ ->
                Row(
                    rs.getDate("usage_date").toLocalDate().toString(), rs.getString("model"),
                    rs.getLong("calls"), rs.getLong("p"), rs.getLong("c"), rs.getLong("t"),
                )
            },
            days.coerceIn(1, 90),
        )

        val byDate = rows.groupBy { it.date }.map { (date, group) ->
            // 단가를 모르는 모델이 하나라도 섞이면 그날 금액은 말하지 않는다.
            val known = group.all { pricing.pricing.containsKey(it.model) }
            DailyCost(
                date = date,
                calls = group.sumOf { it.calls },
                promptTokens = group.sumOf { it.prompt },
                completionTokens = group.sumOf { it.completion },
                totalTokens = group.sumOf { it.total },
                estimatedUsd = if (!known) null else group.sumOf { row ->
                    val price = pricing.pricing.getValue(row.model)
                    row.prompt / 1_000_000.0 * price.input + row.completion / 1_000_000.0 * price.output
                },
                models = group.map { it.model }.distinct(),
            )
        }.sortedByDescending { it.date }

        val priced = byDate.mapNotNull { it.estimatedUsd }
        // 최근 이레만 본다. 따라잡기 같은 한 번뿐인 일이 앞에 있으면 앞으로의 예상이 부풀려진다.
        val recent = byDate.take(7).mapNotNull { it.estimatedUsd }

        return CostSummary(
            days = byDate,
            last30DaysUsd = priced.takeIf { it.isNotEmpty() }?.sum(),
            projectedMonthlyUsd = recent.takeIf { it.isNotEmpty() }?.let { it.sum() / it.size * 30 },
            note = "우리가 센 토큰에 설정한 단가를 곱한 어림값입니다. 실제 청구는 OpenAI 대시보드가 기준입니다.",
        )
    }

    private data class Row(
        val date: String, val model: String, val calls: Long,
        val prompt: Long, val completion: Long, val total: Long,
    )
}
