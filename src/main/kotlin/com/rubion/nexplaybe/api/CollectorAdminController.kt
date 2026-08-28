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
) {
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
