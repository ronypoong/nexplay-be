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
            val subscriptions = subscriptionRepository.findAllByActiveTrueOrderByIdAsc().filter { it.source.id == source.id }
            var fetched = 0
            var newItems = 0
            var newEvents = 0
            val errors = mutableListOf<String>()
            subscriptions.forEach { subscription ->
                runCatching {
                    val items = client.fetch(subscription.feedUrl)
                    fetched += items.size
                    val result = ingestionService.ingest(subscription, items)
                    newItems += result.newItems
                    newEvents += result.newEvents
                }.onFailure { errors += "${subscription.game.title}: ${it.message.orEmpty().take(300)}" }
            }
            started.finishedAt = Instant.now()
            started.fetchedCount = fetched
            started.newItemCount = newItems
            started.eventCount = newEvents
            started.status = when { errors.isEmpty() -> CollectorRunStatus.SUCCESS; errors.size < subscriptions.size -> CollectorRunStatus.PARTIAL_FAILURE; else -> CollectorRunStatus.FAILED }
            started.errorMessage = errors.joinToString(" | ").take(1000).ifBlank { null }
            collectorRunRepository.save(started)
            return CollectorSummary(started.status.name, subscriptions.size, fetched, newItems, newEvents, errors)
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
