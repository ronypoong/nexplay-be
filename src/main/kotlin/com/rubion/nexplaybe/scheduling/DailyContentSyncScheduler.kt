package com.rubion.nexplaybe.scheduling

import com.rubion.nexplaybe.awards.AwardSyncSummary
import com.rubion.nexplaybe.awards.GameAwardService
import com.rubion.nexplaybe.anticipation.VoterHashRetention
import com.rubion.nexplaybe.cache.ReadCacheEvictor
import com.rubion.nexplaybe.catalog.CatalogSyncService
import com.rubion.nexplaybe.intelligence.EventIntelligenceService
import com.rubion.nexplaybe.intelligence.ExtractionSummary
import com.rubion.nexplaybe.intelligence.PromiseLedgerService
import com.rubion.nexplaybe.intelligence.PromiseSyncSummary
import com.rubion.nexplaybe.intelligence.ResolutionSummary
import com.rubion.nexplaybe.collector.SteamNewsCollector
import com.rubion.nexplaybe.metadata.RichMetadataIngestionService
import com.rubion.nexplaybe.catalog.CatalogSyncSummary
import com.rubion.nexplaybe.catalog.ClassificationEnrichmentSummary
import com.rubion.nexplaybe.collector.CollectorSummary
import com.rubion.nexplaybe.metadata.RichMetadataSyncSummary
import com.rubion.nexplaybe.wikipedia.WikipediaDescriptionService
import com.rubion.nexplaybe.wikipedia.WikipediaSyncSummary
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
    private val wikipediaDescriptionService: WikipediaDescriptionService,
    private val gameAwardService: GameAwardService,
    private val eventIntelligenceService: EventIntelligenceService,
    private val promiseLedgerService: PromiseLedgerService,
    private val readCacheEvictor: ReadCacheEvictor,
    private val syncRunRecorder: SyncRunRecorder,
    private val voterHashRetention: VoterHashRetention,
    @param:Value("\${nexplay.daily-sync.zone:Asia/Seoul}") private val zone: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // 각 단계를 격리하고, 무엇이 돌았고 무엇이 실패했는지 남긴다. 로그만 남기면
    // 아무도 보지 않아, 며칠째 멈춰 있어도 알 수 없다.
    private fun <T> step(name: String, fallback: T, action: () -> T): T =
        syncRunRecorder.record(name, fallback, action)

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
        val wiki = step("wikipedia-descriptions", WikipediaSyncSummary("FAILED", 0, 0, 0)) { wikipediaDescriptionService.enrichDescriptions() }
        val awards = step("game-awards", AwardSyncSummary("FAILED", 0, 0, 0)) { gameAwardService.sync() }
        val news = step("steam-news", CollectorSummary("FAILED", 0, 0, 0, 0, emptyList())) { steamNewsCollector.collect() }
        val extraction = step("event-extraction", ExtractionSummary("FAILED", 0, 0, 0)) { eventIntelligenceService.extract() }
        val promises = step("promise-extraction", PromiseSyncSummary("FAILED", 0, 0, 0)) { promiseLedgerService.extractPromises() }
        // 채점은 모델 없이도 돌아야 한다. 추출이 실패해도 어제까지 적힌 약속은 오늘 채점된다.
        val resolutions = step("promise-resolution", ResolutionSummary(0, 0, 0, 0, 0)) { promiseLedgerService.resolve() }
        // 처리방침에 적은 기간을 코드가 실제로 지키게 한다.
        step("voter-hash-retention", 0) { voterHashRetention.anonymizeOldHashes() }
        // 오늘치가 다 들어왔으니 어제 계산해 둔 목록은 버린다.
        readCacheEvictor.evictQuietly()
        log.info(
            "NEXPLAY daily content sync finished: catalogStatuses={}, insertedGames={}, refreshedStatuses={}, enrichedGames={}, extendedMetadata={}, wikipediaDescriptions={}, awards={}, relations={}, snapshots={}, newsStatus={}, newEvents={}, extractedEvents={}, promisesFound={}, promisesResolved={}, errors={}",
            catalogs.joinToString { "${it.year}:${it.status}" },
            catalogs.sumOf { it.inserted },
            refreshed,
            enrichment.enriched,
            extended.enriched,
            wiki.filled,
            awards.stored,
            relations.enriched,
            snapshots,
            news.status,
            news.newEvents,
            extraction.extracted,
            promises.promisesFound,
            resolutions.evaluated,
            news.errors.size,
        )
    }
}
