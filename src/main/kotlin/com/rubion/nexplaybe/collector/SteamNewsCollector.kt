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
        try {
            val source = sourceRepository.findBySlug("steam-news-rss")
                ?: return CollectorSummary("SKIPPED_SOURCE_MISSING", 0, 0, 0, 0, emptyList())
            if (!source.active || source.policyStatus != PolicyStatus.ALLOWED) {
                return CollectorSummary("SKIPPED_SOURCE_DISABLED", 0, 0, 0, 0, emptyList())
            }
            val run = collectorRunRepository.save(CollectorRun(source = source, startedAt = Instant.now()))
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
            run.finishedAt = Instant.now()
            run.fetchedCount = fetched
            run.newItemCount = newItems
            run.eventCount = newEvents
            run.status = when { errors.isEmpty() -> CollectorRunStatus.SUCCESS; errors.size < subscriptions.size -> CollectorRunStatus.PARTIAL_FAILURE; else -> CollectorRunStatus.FAILED }
            run.errorMessage = errors.joinToString(" | ").take(1000).ifBlank { null }
            collectorRunRepository.save(run)
            return CollectorSummary(run.status.name, subscriptions.size, fetched, newItems, newEvents, errors)
        } finally {
            running.set(false)
        }
    }

    fun recentRuns() = collectorRunRepository.findAllByOrderByStartedAtDesc(PageRequest.of(0, 20))
}
