package com.rubion.nexplaybe.scheduling

import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.sql.Timestamp
import java.time.Instant

/**
 * 각 동기화 단계가 언제 돌았고 어떻게 끝났는지 남긴다.
 *
 * 기록 자체가 실패해도 본 작업을 막지 않는다. 남기지 못한 건 나중에 알 수 없는
 * 문제지만, 그것 때문에 오늘치 수집을 통째로 놓치는 건 더 큰 손해다.
 */
@Component
class SyncRunRecorder(private val jdbc: JdbcTemplate) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun <T> record(step: String, fallback: T, action: () -> T): T {
        val startedAt = Instant.now()
        return runCatching(action)
            .onSuccess { save(step, "SUCCESS", summarize(it), startedAt) }
            .onFailure {
                log.error("NEXPLAY daily sync step failed: {}", step, it)
                save(step, "FAILED", it.message?.take(500), startedAt)
            }
            .getOrDefault(fallback)
    }

    private fun save(step: String, status: String, detail: String?, startedAt: Instant) {
        runCatching {
            jdbc.update(
                "INSERT INTO sync_run (step, status, detail, started_at, finished_at) VALUES (?,?,?,?,?)",
                step, status, detail, Timestamp.from(startedAt), Timestamp.from(Instant.now()),
            )
        }.onFailure { log.warn("동기화 기록 실패: {}", it.message) }
    }

    /** 요약 객체를 그대로 문자열로 남긴다. 나중에 "그날 몇 건이었나" 를 볼 수 있어야 한다. */
    private fun summarize(value: Any?): String? = value?.toString()?.take(500)
}
