package com.rubion.nexplaybe.scheduling

import com.rubion.nexplaybe.catalog.CatalogSyncService
import com.rubion.nexplaybe.collector.SteamNewsCollector
import com.rubion.nexplaybe.metadata.RichMetadataIngestionService
import com.rubion.nexplaybe.catalog.CatalogSyncSummary
import com.rubion.nexplaybe.catalog.ClassificationEnrichmentSummary
import com.rubion.nexplaybe.collector.CollectorSummary
import com.rubion.nexplaybe.metadata.RichMetadataSyncSummary
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.ZoneId

@Component
class DailyContentSyncScheduler(
    private val catalogSyncService: CatalogSyncService,
    private val steamNewsCollector: SteamNewsCollector,
    private val richMetadataIngestionService: RichMetadataIngestionService,
    @param:Value("\${nexplay.daily-sync.zone:Asia/Seoul}") private val zone: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private fun <T> step(name: String, fallback: T, action: () -> T): T = runCatching(action)
        .onFailure { log.error("NEXPLAY daily sync step failed: {}", name, it) }
        .getOrDefault(fallback)

    @Scheduled(
        cron = "\${nexplay.daily-sync.cron:0 0 6 * * *}",
        zone = "\${nexplay.daily-sync.zone:Asia/Seoul}",
    )
    fun syncDailyContent() {
        log.info("NEXPLAY daily content sync started")
        val year = LocalDate.now(ZoneId.of(zone)).year
        // 각 단계를 격리한다. 예전에는 중간 단계가 던지면 마지막의 뉴스 수집이 그날 통째로 실행되지 않았다.
        val catalogs = (year..year + 1).map { step("catalog-$it", CatalogSyncSummary("FAILED", it, 0, 0, 0)) { catalogSyncService.sync(it) } }
        val refreshed = step("release-status-refresh", 0) { catalogSyncService.refreshReleaseStatuses() }
        val enrichment = step("wikidata-classifications", ClassificationEnrichmentSummary("FAILED", 0, 0, 0)) { catalogSyncService.enrichWikidataClassifications() }
        val extended = step("steam-extended", RichMetadataSyncSummary("FAILED", 0, 0, 0)) { richMetadataIngestionService.enrichFromSteam() }
        val snapshots = step("popularity-snapshot", 0) { richMetadataIngestionService.snapshotPopularity() }
        val relations = step("wikidata-relations", RichMetadataSyncSummary("FAILED", 0, 0, 0)) { richMetadataIngestionService.enrichWikidataRelations() }
        val news = step("steam-news", CollectorSummary("FAILED", 0, 0, 0, 0, emptyList())) { steamNewsCollector.collect() }
        log.info(
            "NEXPLAY daily content sync finished: catalogStatuses={}, insertedGames={}, refreshedStatuses={}, enrichedGames={}, extendedMetadata={}, relations={}, snapshots={}, newsStatus={}, newEvents={}, errors={}",
            catalogs.joinToString { "${it.year}:${it.status}" },
            catalogs.sumOf { it.inserted },
            refreshed,
            enrichment.enriched,
            extended.enriched,
            relations.enriched,
            snapshots,
            news.status,
            news.newEvents,
            news.errors.size,
        )
    }
}
