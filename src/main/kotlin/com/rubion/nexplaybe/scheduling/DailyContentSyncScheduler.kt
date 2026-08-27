package com.rubion.nexplaybe.scheduling

import com.rubion.nexplaybe.catalog.CatalogSyncService
import com.rubion.nexplaybe.collector.SteamNewsCollector
import com.rubion.nexplaybe.metadata.RichMetadataIngestionService
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.LocalDate

@Component
class DailyContentSyncScheduler(
    private val catalogSyncService: CatalogSyncService,
    private val steamNewsCollector: SteamNewsCollector,
    private val richMetadataIngestionService: RichMetadataIngestionService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(
        cron = "\${nexplay.daily-sync.cron:0 0 6 * * *}",
        zone = "\${nexplay.daily-sync.zone:Asia/Seoul}",
    )
    fun syncDailyContent() {
        log.info("NEXPLAY daily content sync started")
        val year = LocalDate.now().year
        val catalogs = (year..year + 1).map(catalogSyncService::sync)
        val enrichment = catalogSyncService.enrichWikidataClassifications()
        val extended = richMetadataIngestionService.enrichFromSteam()
        val snapshots = richMetadataIngestionService.snapshotPopularity()
        val relations = richMetadataIngestionService.enrichWikidataRelations()
        val news = steamNewsCollector.collect()
        log.info(
            "NEXPLAY daily content sync finished: catalogStatuses={}, insertedGames={}, enrichedGames={}, extendedMetadata={}, relations={}, snapshots={}, newsStatus={}, newEvents={}, errors={}",
            catalogs.joinToString { "${it.year}:${it.status}" },
            catalogs.sumOf { it.inserted },
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
