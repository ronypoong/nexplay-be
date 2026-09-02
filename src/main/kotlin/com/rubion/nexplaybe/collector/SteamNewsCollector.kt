package com.rubion.nexplaybe.collector

import com.rubion.nexplaybe.source.PolicyStatus
import com.rubion.nexplaybe.source.SourceRepository
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

data class CollectorSummary(
    val status: String,
    val subscriptions: Int,
    val fetchedItems: Int,
    val newItems: Int,
    val newEvents: Int,
    val errors: List<String>,
)

@Service
class SteamNewsCollector(
    private val sourceRepository: SourceRepository,
    private val subscriptionRepository: SourceSubscriptionRepository,
    private val collectorRunRepository: CollectorRunRepository,
    private val client: SteamNewsRssClient,
    private val ingestionService: IngestionService,
) {
    private val running = AtomicBoolean(false)

    private companion object {
        const val PACE_MS = 250L

        /**
         * 한 번에 들고 있을 구독 수.
         *
         * 100 이면 1,693건이 17묶음이 된다. 더 잘게 끊어도 메모리는 별로 안 줄고
         * 왕복만 늘어난다 — 묶음 하나를 도는 데 25초가 걸리므로, 왕복 한 번의
         * 비용은 어느 쪽이든 묻힌다.
         */
        const val CHUNK_SIZE = 100

        /** 실패 메시지는 어차피 1,000자로 잘려 저장된다. 그 앞까지만 모은다. */
        const val MAX_REPORTED_ERRORS = 20
    }

    fun collect(): CollectorSummary {
        if (!running.compareAndSet(false, true)) return CollectorSummary("SKIPPED_ALREADY_RUNNING", 0, 0, 0, 0, emptyList())
        var run: CollectorRun? = null
        try {
            val source = sourceRepository.findBySlug("steam-news-rss")
                ?: return CollectorSummary("SKIPPED_SOURCE_MISSING", 0, 0, 0, 0, emptyList())
            if (!source.active || source.policyStatus != PolicyStatus.ALLOWED) {
                return CollectorSummary("SKIPPED_SOURCE_DISABLED", 0, 0, 0, 0, emptyList())
            }
            val started = collectorRunRepository.save(CollectorRun(source = source, startedAt = Instant.now()))
            run = started
            val total = subscriptionRepository.countByActiveTrueAndSourceId(source.id).toInt()
            var fetched = 0
            var newItems = 0
            var newEvents = 0
            var processed = 0
            var failed = 0
            val errors = mutableListOf<String>()
            // 예전에는 구독을 통째로 한 번에 올렸다. 1,693건을 게임·출처까지 붙여
            // 불러 놓고, 건마다 250ms 를 쉬며 7분 동안 그걸 다 들고 있었다.
            // 그때 잠깐 커진 힙은 그 뒤로도 잘 줄지 않는다.
            //
            // 묶음으로 끊으면 한 번에 들고 있는 것이 CHUNK_SIZE 건으로 줄고,
            // 지나간 묶음은 다음 GC 때 회수된다. 총 왕복은 17번 늘지만 7분짜리
            // 작업에서 그 값은 보이지도 않는다.
            var afterId = 0L
            while (true) {
                val chunk = subscriptionRepository.findByActiveTrueAndSourceIdAndIdGreaterThanOrderByIdAsc(
                    source.id, afterId, PageRequest.of(0, CHUNK_SIZE),
                )
                if (chunk.isEmpty()) break
                chunk.forEach { subscription ->
                    // 구독이 수백 개가 되면 몰아치지 않는다. 되찾을 수 없는 데이터를 받는
                    // 일이라 급할 이유가 없고, 막히면 그날치를 통째로 잃는다.
                    if (processed > 0) runCatching { Thread.sleep(PACE_MS) }
                    processed++
                    runCatching {
                        val items = client.fetch(subscription.feedUrl)
                        fetched += items.size
                        val result = ingestionService.ingest(subscription, items)
                        newItems += result.newItems
                        newEvents += result.newEvents
                    }.onFailure {
                        failed++
                        // 전부 실패하는 날에는 1,693건이 쌓인다. 어차피 아래에서 1,000자로
                        // 자르는 값이라, 앞의 몇 개만 남기고 나머지는 세기만 한다.
                        if (errors.size < MAX_REPORTED_ERRORS) {
                            errors += "${subscription.game.title}: ${it.message.orEmpty().take(300)}"
                        }
                    }
                }
                afterId = chunk.last().id
            }
            if (failed > errors.size) errors += "외 ${failed - errors.size}건 더 실패"
            started.finishedAt = Instant.now()
            started.fetchedCount = fetched
            started.newItemCount = newItems
            started.eventCount = newEvents
            started.status = when { failed == 0 -> CollectorRunStatus.SUCCESS; failed < processed -> CollectorRunStatus.PARTIAL_FAILURE; else -> CollectorRunStatus.FAILED }
            started.errorMessage = errors.joinToString(" | ").take(1000).ifBlank { null }
            collectorRunRepository.save(started)
            return CollectorSummary(started.status.name, total, fetched, newItems, newEvents, errors)
        } catch (error: Exception) {
            // 예전에는 여기서 예외가 나면 collector_run 이 RUNNING 인 채로 영원히 남았다.
            // V14 마이그레이션이 한 번 치웠지만 마이그레이션은 1회성이라 원인을 코드에서 막는다.
            run?.let {
                it.finishedAt = Instant.now()
                it.status = CollectorRunStatus.FAILED
                it.errorMessage = (error.message ?: error.javaClass.simpleName).take(1000)
                collectorRunRepository.save(it)
            }
            return CollectorSummary("FAILED", 0, 0, 0, 0, listOf(error.message ?: error.javaClass.simpleName))
        } finally {
            running.set(false)
        }
    }

    fun recentRuns() = collectorRunRepository.findAllByOrderByStartedAtDesc(PageRequest.of(0, 20))
}
