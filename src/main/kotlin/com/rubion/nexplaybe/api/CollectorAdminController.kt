package com.rubion.nexplaybe.api

import com.rubion.nexplaybe.collector.CollectorRun
import com.rubion.nexplaybe.collector.SourceSubscriptionRepository
import com.rubion.nexplaybe.collector.SteamNewsCollector
import com.rubion.nexplaybe.editorial.EditorPickRequest
import com.rubion.nexplaybe.editorial.EditorPickService
import com.rubion.nexplaybe.catalog.CatalogSyncService
import com.rubion.nexplaybe.awards.GameAwardService
import com.rubion.nexplaybe.catalog.ManualGameRequest
import com.rubion.nexplaybe.metadata.RichMetadataIngestionService
import com.rubion.nexplaybe.source.Source
import com.rubion.nexplaybe.cache.ReadCacheEvictor
import com.rubion.nexplaybe.collector.RawPayloadBackfill
import com.rubion.nexplaybe.intelligence.EventIntelligenceService
import com.rubion.nexplaybe.intelligence.PromiseLedgerService
import com.rubion.nexplaybe.source.SourceRepository
import com.rubion.nexplaybe.wikipedia.WikipediaDescriptionService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

data class SourceResponse(
    val id: Long, val name: String, val type: String, val collectionMethod: String,
    val policyStatus: String, val official: Boolean, val active: Boolean,
    val rateLimitPerHour: Int, val lastLegalReviewAt: String?, val subscriptions: Int,
)

data class CollectorRunResponse(
    val id: Long, val source: String, val status: String, val startedAt: String,
    val finishedAt: String?, val fetchedCount: Int, val newItemCount: Int,
    val eventCount: Int, val errorMessage: String?,
)

@RestController
@RequestMapping("/api/v1/admin")
class CollectorAdminController(
    private val steamNewsCollector: SteamNewsCollector,
    private val catalogSyncService: CatalogSyncService,
    private val richMetadataIngestionService: RichMetadataIngestionService,
    private val sourceRepository: SourceRepository,
    private val subscriptionRepository: SourceSubscriptionRepository,
    private val editorPickService: EditorPickService,
    private val wikipediaDescriptionService: WikipediaDescriptionService,
    private val gameAwardService: GameAwardService,
    private val eventIntelligenceService: EventIntelligenceService,
    private val promiseLedgerService: PromiseLedgerService,
    private val readCacheEvictor: ReadCacheEvictor,
    private val rawPayloadBackfill: RawPayloadBackfill,
) {
    /** 수집한 뉴스를 모델이 읽고 분류·구조화한다. 키가 없으면 SKIPPED 를 돌려준다. */
    @PostMapping("/events/extract")
    fun extractEvents(@RequestParam(defaultValue = "100") limit: Int) =
        eventIntelligenceService.extract(limit)

    /**
     * 본문이 빈 채로 저장된 소식의 원문을 피드에서 되받아 채운다.
     * 비어 있는 칸만 채우고 새 행이나 이벤트는 만들지 않는다.
     */
    @PostMapping("/collectors/steam/backfill-bodies")
    fun backfillBodies(@RequestParam(defaultValue = "30") limit: Int) = rawPayloadBackfill.run(limit)

    /** 발표 원문에서 "앞으로 하겠다"는 약속만 뽑아 대조표에 적는다. */
    @PostMapping("/promises/extract")
    fun extractPromises(@RequestParam(defaultValue = "100") limit: Int) =
        promiseLedgerService.extractPromises(limit)

    /** 적힌 약속을 실제 출시일·언어 이력과 대조해 채점한다. 모델을 쓰지 않는다. */
    @PostMapping("/promises/resolve")
    fun resolvePromises() = promiseLedgerService.resolve()

    @PostMapping("/awards/sync")
    fun syncAwards() = gameAwardService.sync()

    /** Steam 이 못 채운 소개를 위키백과로 메운다. */
    @PostMapping("/catalog/wikipedia/descriptions")
    fun enrichWikipediaDescriptions(@RequestParam(defaultValue = "200") limit: Int) =
        wikipediaDescriptionService.enrichDescriptions(limit)

    /** 출처를 명시한 수동 카탈로그 등록. 스토어 페이지가 없는 미발표 대작을 넣을 때 쓴다. */
    @PostMapping("/catalog/games")
    fun addGame(@RequestBody request: ManualGameRequest) = catalogSyncService.addGame(request)

    @PostMapping("/editor-picks")
    fun addEditorPick(@RequestBody request: EditorPickRequest) = editorPickService.upsert(request)

    @DeleteMapping("/editor-picks/{slug}")
    fun removeEditorPick(@PathVariable slug: String) = mapOf("removed" to editorPickService.remove(slug))

    @PostMapping("/collectors/steam/run")
    fun runSteamCollector() = steamNewsCollector.collect()

    @PostMapping("/catalog/wikidata/sync")
    fun syncCatalog(@RequestParam(required = false) year: Int?) = catalogSyncService.sync(year ?: java.time.LocalDate.now().year)

    @PostMapping("/catalog/steam/enrich")
    fun enrichSteamMetadata(@RequestParam(defaultValue = "25") limit: Int) = catalogSyncService.enrichIncompleteStoreMetadata(limit)

    @PostMapping("/catalog/steam/extended")
    fun enrichExtendedSteamMetadata(@RequestParam(defaultValue = "12") limit: Int) = richMetadataIngestionService.enrichFromSteam(limit)

    @PostMapping("/catalog/popularity/snapshot")
    fun snapshotPopularity() = mapOf("inserted" to richMetadataIngestionService.snapshotPopularity())

    @PostMapping("/catalog/wikidata/relations")
    fun enrichWikidataRelations() = richMetadataIngestionService.enrichWikidataRelations()

    @PostMapping("/catalog/wikidata/classifications")
    fun enrichWikidataClassifications(
        @RequestParam(defaultValue = "1000") limit: Int,
        @RequestParam(defaultValue = "false") includeComplete: Boolean,
    ) = catalogSyncService.enrichWikidataClassifications(limit, includeComplete)

    /**
     * 대표 이미지가 비어 있는 게임.
     *
     * Steam 에 없거나 어느 지역에서도 안 열리는 게임은 자동으로 채울 방법이 없다.
     * 그런 것만 추려 손으로 넣을 수 있게 한다.
     */
    @GetMapping("/catalog/games/missing-cover")
    fun gamesMissingCover() = catalogSyncService.gamesMissingCover()

    /** 대표 이미지를 손으로 지정한다. 자동 수집은 이미 있는 값을 덮지 않는다. */
    @PostMapping("/catalog/games/{slug}/cover")
    fun setCover(@PathVariable slug: String, @RequestBody request: CoverRequest) =
        catalogSyncService.setCoverImage(slug, request.url)

    @GetMapping("/collectors/runs")
    fun recentRuns() = steamNewsCollector.recentRuns().map(CollectorRun::toResponse)

    @GetMapping("/sources")
    fun sources(): List<SourceResponse> {
        val counts = subscriptionRepository.findAll().groupingBy { it.source.id }.eachCount()
        return sourceRepository.findAllByOrderByNameAsc().map { it.toResponse(counts[it.id] ?: 0) }
    }
}

private fun Source.toResponse(subscriptions: Int) = SourceResponse(
    id, name, type.name, collectionMethod.name, policyStatus.name, official, active,
    rateLimitPerHour, lastLegalReviewAt?.toString(), subscriptions,
)

private fun CollectorRun.toResponse() = CollectorRunResponse(
    id, source.name, status.name, startedAt.toString(), finishedAt?.toString(),
    fetchedCount, newItemCount, eventCount, errorMessage,
)

/** 이미지 주소는 https 여야 한다. 화면이 https 로 열리므로 http 는 브라우저가 막는다. */
data class CoverRequest(val url: String)
