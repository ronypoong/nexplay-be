package com.rubion.nexplaybe.collector

import com.rubion.nexplaybe.event.GameEvent
import com.rubion.nexplaybe.event.GameEventRepository
import com.rubion.nexplaybe.event.GameEventSource
import com.rubion.nexplaybe.event.GameEventSourceRepository
import com.rubion.nexplaybe.rawitem.ProcessingStatus
import com.rubion.nexplaybe.rawitem.RawItem
import com.rubion.nexplaybe.rawitem.RawItemRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

data class IngestionResult(val newItems: Int, val newEvents: Int)

@Service
class IngestionService(
    private val rawItemRepository: RawItemRepository,
    private val eventRepository: GameEventRepository,
    private val eventSourceRepository: GameEventSourceRepository,
    private val classifier: EventClassifier,
) {
    @Transactional
    fun ingest(subscription: SourceSubscription, items: List<SteamNewsItem>): IngestionResult {
        var newItems = 0
        var newEvents = 0
        items.forEach { item ->
            val externalId = item.externalId.takeIf { it.length <= 255 } ?: sha256(item.externalId)
            if (rawItemRepository.existsBySourceIdAndExternalId(subscription.source.id, externalId)) return@forEach
            val now = Instant.now()
            val rawItem = rawItemRepository.save(
                RawItem(
                    source = subscription.source, game = subscription.game, externalId = externalId,
                    sourceUrl = item.url, title = item.title, publishedAt = item.publishedAt, rawPayload = null,
                    contentHash = sha256("${item.title}|${item.url}"), fetchedAt = now,
                    expiresAt = now.plus(Duration.ofDays(30)),
                )
            )
            newItems++
            val eventType = classifier.classify(item.title)
            val displayTitle = classifier.localizedTitle(item.title)
            val eventDate = item.publishedAt.atZone(ZoneId.of("Asia/Seoul")).toLocalDate()
            val normalized = classifier.normalizedTitle(displayTitle)
            val existing = eventRepository.findMergeCandidates(subscription.game.id, eventType, eventDate.minusDays(1), eventDate.plusDays(1))
                .firstOrNull { classifier.normalizedTitle(it.title) == normalized }
            val event = existing ?: eventRepository.save(
                GameEvent(
                    game = subscription.game, type = eventType, title = displayTitle,
                    summary = item.summary.ifBlank { "${subscription.game.title} 공식 소식: $displayTitle" },
                    eventDate = eventDate, publishedAt = item.publishedAt,
                )
            ).also { newEvents++ }
            if (!eventSourceRepository.existsByEventIdAndSourceId(event.id, subscription.source.id)) {
                eventSourceRepository.save(
                    GameEventSource(
                        event = event, source = subscription.source, rawItem = rawItem,
                        sourceUrl = item.url, isOfficial = subscription.source.official,
                    )
                )
            }
            rawItem.processingStatus = ProcessingStatus.PROCESSED
            rawItem.processedAt = Instant.now()
        }
        return IngestionResult(newItems, newEvents)
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
