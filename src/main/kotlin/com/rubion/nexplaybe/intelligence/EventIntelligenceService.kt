package com.rubion.nexplaybe.intelligence

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Date as SqlDate
import java.time.LocalDate

data class ExtractionSummary(val status: String, val candidates: Int, val extracted: Int, val failed: Int)

/**
 * 수집한 뉴스를 모델이 읽고 분류·구조화한다.
 *
 * 규칙 기반 분류는 제목 한 줄만 보고 판단해 363건 중 203건을 ANNOUNCEMENT 로 뭉갰다.
 * 제목이 영어·한국어·일본어로 섞여 있고 마케팅 문구가 많아 규칙으로는 한계가 분명하다.
 *
 * 여기서 하는 일은 "생성" 이 아니라 "추출" 이다. 원문에 있는 사실만 뽑고 없으면 null 을
 * 두게 한다. 판단 근거(reason)와 모델·프롬프트 판본을 함께 저장해, 나중에 모델이 바뀌면
 * 원문으로 다시 돌릴 수 있게 한다.
 *
 * 키가 없으면 기능 전체가 꺼진다. 조용히 엉뚱한 값을 쓰느니 아무것도 안 하는 편이 낫다.
 */
@Service
class EventIntelligenceService(
    private val jdbc: JdbcTemplate,
    private val transactions: TransactionTemplate,
    private val extractor: OpenAiExtractor,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val model: String get() = extractor.model

    fun extract(limit: Int = DEFAULT_LIMIT): ExtractionSummary {
        if (!extractor.enabled) {
            log.warn("이벤트 분류를 건너뜁니다. NEXPLAY_INTELLIGENCE_API_KEY 가 설정되지 않았습니다.")
            return ExtractionSummary("SKIPPED_NO_API_KEY", 0, 0, 0)
        }
        val candidates = jdbc.query(
            """
            SELECT e.id, e.title, COALESCE(r.raw_payload, e.summary) AS body, g.title AS game_title
            FROM game_event e
            JOIN game g ON g.id = e.game_id
            LEFT JOIN game_event_source s ON s.game_event_id = e.id
            LEFT JOIN raw_item r ON r.id = s.raw_item_id
            WHERE NOT EXISTS (
                SELECT 1 FROM game_event_extraction x
                WHERE x.event_id = e.id AND x.prompt_version = ?
            )
            GROUP BY e.id, e.title, body, g.title
            ORDER BY e.published_at DESC
            LIMIT ${limit.coerceIn(1, MAX_LIMIT)}
            """.trimIndent(),
            { rs, _ -> Candidate(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)) },
            PROMPT_VERSION,
        )
        if (candidates.isEmpty()) return ExtractionSummary("SUCCESS", 0, 0, 0)

        var extracted = 0
        var consecutiveFailures = 0
        var status = "SUCCESS"
        for (candidate in candidates) {
            if (consecutiveFailures >= FAILURE_CUTOFF) {
                status = "STOPPED_TOO_MANY_FAILURES"
                break
            }
            val result = try {
                classify(candidate)
            } catch (e: BudgetExhaustedException) {
                log.warn("{} — 남은 글은 다음 실행에서 이어서 본다.", e.message)
                status = "STOPPED_TOKEN_BUDGET"
                break
            } catch (e: Exception) {
                log.warn("이벤트 {} 분류 실패: {}", candidate.id, e.message)
                null
            }
            if (result == null) {
                consecutiveFailures++
                continue
            }
            consecutiveFailures = 0
            transactions.execute { store(candidate.id, result) }
            extracted++
        }
        return ExtractionSummary(status, candidates.size, extracted, candidates.size - extracted)
    }

    private fun classify(candidate: Candidate): EventExtraction? = extractor.extract(
        schemaName = "event_extraction",
        schema = EVENT_SCHEMA,
        systemPrompt = SYSTEM_PROMPT,
        userMessage = """
            게임: ${candidate.gameTitle}
            제목: ${candidate.title}
            본문:
            ${candidate.body.orEmpty().take(4_000)}
        """.trimIndent(),
        maxOutputTokens = 800,
        type = EventExtraction::class.java,
    )

    private fun store(eventId: Long, result: EventExtraction) {
        jdbc.update(
            """
            INSERT INTO game_event_extraction
              (event_id, event_type, confidence, summary_ko, discount_percent, mentioned_release_date,
               has_demo, is_marketing_noise, reason, model, prompt_version)
            VALUES (?,?,?,?,?,?,?,?,?,?,?)
            ON DUPLICATE KEY UPDATE
              event_type=VALUES(event_type), confidence=VALUES(confidence), summary_ko=VALUES(summary_ko),
              discount_percent=VALUES(discount_percent), mentioned_release_date=VALUES(mentioned_release_date),
              has_demo=VALUES(has_demo), is_marketing_noise=VALUES(is_marketing_noise),
              reason=VALUES(reason), model=VALUES(model)
            """.trimIndent(),
            eventId, result.eventType.take(40), result.confidence.take(10), result.summaryKo?.take(300),
            result.discountPercent, result.mentionedReleaseDate?.let { runCatching { SqlDate.valueOf(LocalDate.parse(it)) }.getOrNull() },
            result.hasDemo, result.isMarketingNoise, result.reason?.take(500), model, PROMPT_VERSION,
        )
    }

    private data class Candidate(val id: Long, val title: String, val body: String?, val gameTitle: String)

    private companion object {
        const val DEFAULT_LIMIT = 100
        const val MAX_LIMIT = 500
        const val FAILURE_CUTOFF = 5
        // 프롬프트를 바꾸면 올린다. 판본이 다르면 같은 이벤트를 다시 분류해 비교할 수 있다.
        const val PROMPT_VERSION = 1

        /**
         * strict 모드 스키마. 속성이 전부 required 여야 하므로, 값이 없을 수 있는 자리는
         * required 에서 빼는 대신 null 을 허용하는 식으로 표현한다.
         */
        val EVENT_SCHEMA = Schemas.obj(
            "eventType" to Schemas.enumOf(
                "이벤트 유형. ANNOUNCEMENT 는 다른 어느 것에도 해당하지 않을 때만 쓴다.",
                "RELEASE_DATE", "DELAY", "RELEASE", "PATCH", "MAJOR_UPDATE", "DLC", "EXPANSION",
                "TRAILER", "GAMEPLAY", "DEMO", "BETA", "DISCOUNT", "PREORDER", "ANNOUNCEMENT",
            ),
            "confidence" to Schemas.enumOf("판단 확신도", "HIGH", "MEDIUM", "LOW"),
            "summaryKo" to Schemas.nullableStr("한국어 한 줄 요약. 원문에 있는 사실만 쓴다. 최대 120자"),
            "discountPercent" to Schemas.nullableInt("할인율(%). 원문에 명시된 숫자만. 없으면 null"),
            "mentionedReleaseDate" to Schemas.nullableStr("원문이 언급한 출시일(YYYY-MM-DD). 명시되지 않았으면 null"),
            "hasDemo" to Schemas.bool("체험판/데모 배포를 알리는 글이면 true"),
            "isMarketingNoise" to Schemas.bool("게임 내용과 무관한 마케팅·커뮤니티 잡음이면 true"),
            "reason" to Schemas.nullableStr("그렇게 분류한 근거를 원문 표현을 인용해 한 문장으로. 최대 200자"),
        )
        val SYSTEM_PROMPT = """
            당신은 게임 뉴스를 분류하고 사실을 뽑아내는 일을 합니다.

            원문에 적힌 사실만 씁니다. 추측하거나 지어내지 않습니다. 확신이 서지 않으면
            해당 항목을 null 로 두고 confidence 를 LOW 로 표시하세요.

            eventType 은 원문이 실제로 알리는 것을 고릅니다. 할인 소식이면 DISCOUNT,
            예약 판매나 구매 특전이면 PREORDER 입니다. ANNOUNCEMENT 는 다른 어느 유형에도
            해당하지 않을 때만 씁니다.

            제목이 영어나 일본어여도 summaryKo 와 reason 은 한국어로 씁니다.

            게임 내용과 무관한 제휴, 행사 후기, 커뮤니티 잡담은 isMarketingNoise 를 true 로
            표시하세요. 지우지는 않고 표시만 합니다.
        """.trimIndent()
    }
}
