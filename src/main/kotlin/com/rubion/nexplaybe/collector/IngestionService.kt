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
    // 30일이었다. 아카이브를 만들겠다면서 30일 만료를 걸어둔 셈이었다.
    // 지금은 아무것도 이 값으로 지우지 않지만, 의도를 값으로 남겨 둔다.
    @param:org.springframework.beans.factory.annotation.Value("\${nexplay.archive.raw-retention-days:3650}")
    private val retentionDays: Long,
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
                    sourceUrl = item.url, title = item.title, publishedAt = item.publishedAt,
                    // 원문을 버리면 나중에 다시 분류할 수 없다. Steam RSS 는 과거 글을
                    // 다시 주지 않으므로 오늘 안 받아두면 그 하루는 영영 없다.
                    rawPayload = item.body.takeIf(String::isNotBlank),
                    contentHash = sha256("${item.title}|${item.url}"), fetchedAt = now,
                    expiresAt = now.plus(Duration.ofDays(retentionDays)),
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
