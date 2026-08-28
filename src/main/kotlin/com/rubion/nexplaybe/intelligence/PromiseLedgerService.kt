package com.rubion.nexplaybe.intelligence

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.LocalDate

data class PromiseSyncSummary(val status: String, val scanned: Int, val promisesFound: Int, val failed: Int)
data class ResolutionSummary(val evaluated: Int, val kept: Int, val broken: Int, val superseded: Int, val pending: Int)

/**
 * 약속과 결과의 대조표.
 *
 * 추출은 모델이 한다 — "Coming Fall 2026!" 같은 문장은 구조화된 필드 어디에도 없고
 * 본문을 읽어야만 나온다. 하지만 **채점은 모델이 하지 않는다.** 지켜졌는지 여부는
 * 현실과의 대조이므로 결정적 규칙으로 판정해야 재현 가능하다. 모델에게 채점을
 * 시키면 그 판정을 다시 검증할 방법이 없어진다.
 */
@Service
class PromiseLedgerService(
    private val jdbc: JdbcTemplate,
    private val transactions: TransactionTemplate,
    private val extractor: OpenAiExtractor,
    @param:Value("\${nexplay.intelligence.max-body-chars:1200}") private val maxBodyChars: Int,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val model: String get() = extractor.model

    fun extractPromises(limit: Int = DEFAULT_LIMIT): PromiseSyncSummary {
        if (!extractor.enabled) {
            log.warn("약속 추출을 건너뜁니다. NEXPLAY_INTELLIGENCE_API_KEY 가 설정되지 않았습니다.")
            return PromiseSyncSummary("SKIPPED_NO_API_KEY", 0, 0, 0)
        }
        val candidates = jdbc.query(
            """
            SELECT e.id, e.game_id, e.title, r.raw_payload, e.summary, e.event_date, g.title AS game_title
            FROM game_event e
            JOIN game g ON g.id = e.game_id
            LEFT JOIN game_event_source s ON s.game_event_id = e.id
            LEFT JOIN raw_item r ON r.id = s.raw_item_id
            WHERE NOT EXISTS (
                SELECT 1 FROM game_promise_scan sc WHERE sc.event_id = e.id AND sc.prompt_version = ?
            )
            GROUP BY e.id, e.game_id, e.title, r.raw_payload, e.summary, e.event_date, g.title, e.type, g.discovery_score
            -- 약속은 출시일·콘텐츠 발표에 있지 패치노트에 있지 않다. 분류와 같은
            -- 순서로 돌아야 같은 예산에서 나오는 약속이 많아진다.
            ORDER BY (
                CASE e.type
                    WHEN 'RELEASE_DATE' THEN 100 WHEN 'DELAY' THEN 100 WHEN 'RELEASE' THEN 90
                    WHEN 'EXPANSION' THEN 85 WHEN 'DLC' THEN 80 WHEN 'DEMO' THEN 70
                    WHEN 'ANNOUNCEMENT' THEN 60 WHEN 'TRAILER' THEN 45 WHEN 'PATCH' THEN 12 ELSE 40
                END
                + g.discovery_score / 5
                - LEAST(GREATEST(DATEDIFF(CURRENT_DATE, e.event_date), 0), 90) / 3
            ) DESC, e.published_at DESC
            LIMIT ${limit.coerceIn(1, MAX_LIMIT)}
            """.trimIndent(),
            { rs, _ ->
                Candidate(
                    rs.getLong(1), rs.getLong(2), rs.getString(3),
                    rs.getString(4), rs.getString(5), rs.getDate(6).toLocalDate(), rs.getString(7),
                )
            },
            PROMPT_VERSION,
        )
        if (candidates.isEmpty()) return PromiseSyncSummary("SUCCESS", 0, 0, 0)

        var found = 0
        var scanned = 0
        var failed = 0
        var consecutiveFailures = 0
        var status = "SUCCESS"
        for (candidate in candidates) {
            if (consecutiveFailures >= FAILURE_CUTOFF) {
                status = "STOPPED_TOO_MANY_FAILURES"
                break
            }
            val extraction = try {
                extract(candidate)
            } catch (e: BudgetExhaustedException) {
                log.warn("{} — 남은 글은 다음 실행에서 이어서 본다.", e.message)
                status = "STOPPED_TOKEN_BUDGET"
                break
            } catch (e: Exception) {
                log.warn("이벤트 {} 약속 추출 실패: {}", candidate.eventId, e.message)
                null
            }
            if (extraction == null) {
                consecutiveFailures++
                failed++
                continue
            }
            consecutiveFailures = 0
            scanned++
            // 약속이 없는 글이 대부분이다. 빈 결과도 결과이므로 반드시 남긴다.
            // 안 남기면 다음 실행에서 같은 글을 또 모델에게 보내고, LIMIT 에 걸려
            // 오래된 글에는 영영 닿지 못한다.
            transactions.execute { store(candidate, extraction) }
            found += extraction.promises.size
        }
        // scanned 는 후보 수가 아니라 실제로 본 수여야 한다. 중간에 멈췄는데 후보 수를
        // 그대로 돌려주면 "20건을 봤지만 아무것도 없었다" 로 읽힌다.
        return PromiseSyncSummary(status, scanned, found, failed)
    }

    private fun extract(candidate: Candidate): PromiseExtraction? {
        val body = (candidate.rawPayload ?: candidate.summary).orEmpty().take(maxBodyChars)
        return extractor.extract(
            schemaName = "promise_extraction",
            schema = PROMISE_SCHEMA,
            systemPrompt = SYSTEM_PROMPT,
            userMessage = """
                게임: ${candidate.gameTitle}
                발표일: ${candidate.eventDate}
                제목: ${candidate.title}
                본문:
                ${body.ifBlank { "(본문 없음 — 제목만으로 판단하고, 확실하지 않으면 약속을 반환하지 마세요)" }}
            """.trimIndent(),
            maxOutputTokens = 1_500,
            type = PromiseExtraction::class.java,
        )
    }

    private fun store(candidate: Candidate, extraction: PromiseExtraction) {
        jdbc.update(
            """
            INSERT INTO game_promise_scan (event_id, prompt_version, promises_found, model)
            VALUES (?,?,?,?)
            ON DUPLICATE KEY UPDATE promises_found=VALUES(promises_found), model=VALUES(model),
              scanned_at=CURRENT_TIMESTAMP(6)
            """.trimIndent(),
            candidate.eventId, PROMPT_VERSION, extraction.promises.size, model,
        )
        // 원문을 보관한 소식에서 뽑은 약속만 LIVE 다. 제목만 남은 과거 소식에서 뽑은 것은
        // 근거가 얇으므로 BACKTEST 로 갈라 점수에서 뺀다.
        val provenance = if (candidate.rawPayload.isNullOrBlank()) "BACKTEST" else "LIVE"
        extraction.promises.forEach { claim ->
            if (claim.claimType !in CLAIM_TYPES) return@forEach
            // 약속의 창은 발표보다 먼저 시작할 수 없다. 본문에 섞인 과거 연도를
            // 시점으로 잡는 일이 있다 — Gallipoli 발표에서 1915년을 집어 왔다.
            val from = claim.claimedFrom.toSqlDate()
                ?.let { if (it.toLocalDate() < candidate.eventDate) java.sql.Date.valueOf(candidate.eventDate) else it }
            val to = claim.claimedTo.toSqlDate()
            val windowValid = from == null || to == null || !to.toLocalDate().isBefore(from.toLocalDate())
            jdbc.update(
                """
                INSERT INTO game_promise
                  (game_id, event_id, claim_type, claim_key, claimed_value, claimed_from, claimed_to,
                   claim_precision, source_quote, announced_at, provenance, model, prompt_version)
                VALUES (?,?,?,SHA2(CONCAT(?,'|',?),256),?,?,?,?,?,?,?,?,?)
                ON DUPLICATE KEY UPDATE claimed_from=VALUES(claimed_from),
                  claimed_to=VALUES(claimed_to), claim_precision=VALUES(claim_precision),
                  source_quote=VALUES(source_quote)
                """.trimIndent(),
                candidate.gameId, candidate.eventId, claim.claimType,
                claim.claimType, claim.claimedValue.take(200), claim.claimedValue.take(200),
                from.takeIf { windowValid }, to.takeIf { windowValid },
                if (windowValid) claim.claimPrecision.take(20) else "NONE", claim.sourceQuote.take(500),
                java.sql.Date.valueOf(candidate.eventDate), provenance, model, PROMPT_VERSION,
            )
        }
    }

    private fun String?.toSqlDate(): java.sql.Date? =
        this?.let { runCatching { java.sql.Date.valueOf(LocalDate.parse(it)) }.getOrNull() }

    private data class Candidate(
        val eventId: Long, val gameId: Long, val title: String,
        val rawPayload: String?, val summary: String?, val eventDate: LocalDate, val gameTitle: String,
    )

    private companion object {
        const val DEFAULT_LIMIT = 100
        const val MAX_LIMIT = 500
        const val FAILURE_CUTOFF = 5
        const val PROMPT_VERSION = 1
        val CLAIM_TYPES = setOf("RELEASE_DATE", "KOREAN_SUPPORT", "CONTENT", "PLATFORM", "DEMO")

        val PROMISE_SCHEMA = Schemas.obj(
            "promises" to Schemas.arrayOf(
                "이 발표가 담고 있는 약속들. 미래 시점의 약속이 없으면 빈 배열",
                Schemas.obj(
                    "claimType" to Schemas.enumOf(
                        "약속의 종류",
                        "RELEASE_DATE", "KOREAN_SUPPORT", "CONTENT", "PLATFORM", "DEMO",
                    ),
                    "claimedValue" to Schemas.str("약속한 내용을 원문 그대로. 예: 'Fall 2026', '한국어 자막 지원'"),
                    "claimedFrom" to Schemas.nullableStr("약속 시점의 시작(YYYY-MM-DD). 'Fall 2026'이면 2026-09-01. 시점이 없으면 null"),
                    "claimedTo" to Schemas.nullableStr("약속 시점의 끝(YYYY-MM-DD). 'Fall 2026'이면 2026-11-30. 시점이 없으면 null"),
                    "claimPrecision" to Schemas.enumOf("시점의 정밀도", "DAY", "MONTH", "QUARTER", "SEASON", "YEAR", "NONE"),
                    "sourceQuote" to Schemas.str("이 약속의 근거가 된 원문 문장을 그대로 인용. 최대 300자"),
                ),
            ),
        )
        val SYSTEM_PROMPT = """
            당신은 게임 공식 발표에서 "앞으로 하겠다는 약속" 만 뽑아냅니다.

            약속이란 아직 일어나지 않은 일에 대한 진술입니다. 이미 일어난 일(출시했다,
            패치를 배포했다)은 약속이 아닙니다.

            대부분의 글에는 약속이 없습니다. 없으면 빈 배열을 반환하세요. 억지로 찾아내면
            대조표가 쓸모없어집니다.

            시점은 원문에 적힌 만큼만 옮깁니다.
              "Fall 2026"  -> 2026-09-01 ~ 2026-11-30, SEASON
              "2027년"      -> 2027-01-01 ~ 2027-12-31, YEAR
              "3월 15일"    -> 해당 날짜, DAY
              시점 없음      -> null, NONE

            sourceQuote 에는 그 약속의 근거가 된 문장을 원문 그대로 인용합니다.
            바꿔 쓰거나 번역하지 마세요. 나중에 이 인용으로 판정을 검증합니다.
        """.trimIndent()
    }

    /**
     * 채점. 모델을 쓰지 않는다 — 현실과의 대조이므로 규칙으로 판정해야 재현 가능하다.
     *
     * SUPERSEDED 가 가장 많이 나온다. 같은 게임에 더 나중 약속이 있으면 앞 약속은
     * 갈아치워진 것이고, 그 간격이 곧 밀린 기간이다. Golf With Your Friends 2 가
     * "Coming 2025" 에서 "Coming Fall 2026" 으로 간 것이 정확히 이 경우다.
     */
    fun resolve(): ResolutionSummary {
        val today = LocalDate.now()
        // 1) 뒤에 같은 종류의 약속이 또 나왔으면 앞 약속은 갈아치워진 것이다.
        val superseded = jdbc.update(
            """
            INSERT INTO game_promise_resolution (promise_id, status, actual_value, actual_date, slip_days, evidence)
            SELECT p.id, 'SUPERSEDED', later.claimed_value, later.claimed_to,
                   DATEDIFF(later.claimed_to, p.claimed_to),
                   CONCAT('이후 발표(', later.announced_at, ')에서 다시 약속됨')
            FROM game_promise p
            JOIN game_promise later
              ON later.game_id = p.game_id AND later.claim_type = p.claim_type
             AND later.announced_at > p.announced_at AND later.id <> p.id
            -- 마감끼리 잰다. 시작끼리 재면 "2026년"(1월 시작)과 "2026 가을"(9월 시작)의
            -- 차이가 실제로 밀린 기간처럼 보인다. 둘 다 2026년 안이라는 뜻일 뿐인데도.
            WHERE p.claimed_to IS NOT NULL AND later.claimed_to IS NOT NULL
              AND later.claimed_to > p.claimed_to
            ON DUPLICATE KEY UPDATE status=VALUES(status), actual_value=VALUES(actual_value),
              actual_date=VALUES(actual_date), slip_days=VALUES(slip_days),
              evidence=VALUES(evidence), evaluated_at=CURRENT_TIMESTAMP(6)
            """.trimIndent(),
        )

        // 2) 출시일 약속: 실제 출시했으면 창 안에 들어왔는지 본다.
        val released = jdbc.update(
            """
            INSERT INTO game_promise_resolution (promise_id, status, actual_value, actual_date, slip_days, evidence)
            SELECT p.id,
                   CASE WHEN g.release_date <= p.claimed_to THEN 'KEPT' ELSE 'BROKEN' END,
                   g.release_label, g.release_date, DATEDIFF(g.release_date, p.claimed_to),
                   '실제 출시일과 대조'
            FROM game_promise p JOIN game g ON g.id = p.game_id
            LEFT JOIN game_promise_resolution r ON r.promise_id = p.id
            WHERE p.claim_type = 'RELEASE_DATE' AND p.claimed_to IS NOT NULL
              AND g.status = 'AVAILABLE' AND g.release_date IS NOT NULL
              AND (r.promise_id IS NULL OR r.status = 'PENDING')
            ON DUPLICATE KEY UPDATE status=VALUES(status), actual_date=VALUES(actual_date),
              slip_days=VALUES(slip_days), evidence=VALUES(evidence), evaluated_at=CURRENT_TIMESTAMP(6)
            """.trimIndent(),
        )

        // 3) 약속한 창이 지났는데 아직 미출시고 새 약속도 없으면 어긴 것이다.
        val missed = jdbc.update(
            """
            INSERT INTO game_promise_resolution (promise_id, status, slip_days, evidence)
            SELECT p.id, 'BROKEN', DATEDIFF(?, p.claimed_to), '약속한 시점이 지났고 새 발표가 없음'
            FROM game_promise p JOIN game g ON g.id = p.game_id
            LEFT JOIN game_promise_resolution r ON r.promise_id = p.id
            WHERE p.claim_type = 'RELEASE_DATE' AND p.claimed_to IS NOT NULL AND p.claimed_to < ?
              AND g.status <> 'AVAILABLE' AND (r.promise_id IS NULL OR r.status = 'PENDING')
            ON DUPLICATE KEY UPDATE status=VALUES(status), slip_days=VALUES(slip_days),
              evidence=VALUES(evidence), evaluated_at=CURRENT_TIMESTAMP(6)
            """.trimIndent(),
            java.sql.Date.valueOf(today), java.sql.Date.valueOf(today),
        )

        // 4) 한국어 지원 약속: 이력에 ko 가 실제로 붙었는지 본다.
        val korean = jdbc.update(
            """
            INSERT INTO game_promise_resolution (promise_id, status, actual_date, slip_days, evidence)
            SELECT p.id, 'KEPT', DATE(h.observed_at), DATEDIFF(DATE(h.observed_at), p.announced_at),
                   '언어 이력에서 한국어 추가 관측'
            FROM game_promise p
            JOIN game_language_history h
              ON h.game_id = p.game_id AND h.language_code = 'ko'
             AND h.change_type IN ('ADDED','CHANGED') AND DATE(h.observed_at) >= p.announced_at
            LEFT JOIN game_promise_resolution r ON r.promise_id = p.id
            WHERE p.claim_type = 'KOREAN_SUPPORT' AND (r.promise_id IS NULL OR r.status = 'PENDING')
            ON DUPLICATE KEY UPDATE status=VALUES(status), actual_date=VALUES(actual_date),
              slip_days=VALUES(slip_days), evidence=VALUES(evidence), evaluated_at=CURRENT_TIMESTAMP(6)
            """.trimIndent(),
        )

        // 5) 나머지는 아직 판정할 수 없다. 모른다고 적어 두는 것도 기록이다.
        val pending = jdbc.update(
            """
            INSERT INTO game_promise_resolution (promise_id, status, evidence)
            SELECT p.id, 'PENDING', '아직 판정할 근거가 없음'
            FROM game_promise p
            LEFT JOIN game_promise_resolution r ON r.promise_id = p.id
            WHERE r.promise_id IS NULL
            """.trimIndent(),
        )
        return ResolutionSummary(superseded + released + missed + korean + pending, released, missed, superseded, pending)
    }
}
