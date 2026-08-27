package com.rubion.nexplaybe.catalog

import com.rubion.nexplaybe.collector.CollectorRun
import com.rubion.nexplaybe.collector.CollectorRunRepository
import com.rubion.nexplaybe.collector.CollectorRunStatus
import com.rubion.nexplaybe.collector.SourceSubscription
import com.rubion.nexplaybe.collector.SourceSubscriptionRepository
import com.rubion.nexplaybe.company.Company
import com.rubion.nexplaybe.company.CompanyRepository
import com.rubion.nexplaybe.company.CompanyType
import com.rubion.nexplaybe.game.Game
import com.rubion.nexplaybe.game.GameRepository
import com.rubion.nexplaybe.game.GameStatus
import com.rubion.nexplaybe.release.Release
import com.rubion.nexplaybe.release.ReleaseRepository
import com.rubion.nexplaybe.release.ReleaseStatus
import com.rubion.nexplaybe.source.PolicyStatus
import com.rubion.nexplaybe.source.SourceRepository
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.text.Normalizer
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.absoluteValue

data class CatalogSyncSummary(
    val status: String,
    val year: Int,
    val fetched: Int,
    val inserted: Int,
    val companiesCreated: Int,
    val error: String? = null,
)

data class StoreEnrichmentSummary(
    val status: String,
    val candidates: Int,
    val enriched: Int,
    val failed: Int,
)

data class ClassificationEnrichmentSummary(
    val status: String,
    val candidates: Int,
    val enriched: Int,
    val failed: Int,
)

@Service
class CatalogSyncService(
    private val client: WikidataCatalogClient,
    private val steamStoreClient: SteamStoreClient,
    private val sourceRepository: SourceRepository,
    private val collectorRunRepository: CollectorRunRepository,
    private val subscriptionRepository: SourceSubscriptionRepository,
    private val companyRepository: CompanyRepository,
    private val gameRepository: GameRepository,
    private val releaseRepository: ReleaseRepository,
) {
    private val running = AtomicBoolean(false)

    fun sync(year: Int = LocalDate.now().year): CatalogSyncSummary {
        if (!running.compareAndSet(false, true)) return CatalogSyncSummary("SKIPPED_ALREADY_RUNNING", year, 0, 0, 0)
        var run: CollectorRun? = null
        try {
            val source = sourceRepository.findBySlug("wikidata-catalog")
                ?: return CatalogSyncSummary("SKIPPED_SOURCE_MISSING", year, 0, 0, 0)
            if (!source.active || source.policyStatus != PolicyStatus.ALLOWED) {
                return CatalogSyncSummary("SKIPPED_SOURCE_DISABLED", year, 0, 0, 0)
            }
            run = collectorRunRepository.save(CollectorRun(source = source, startedAt = Instant.now()))
            val discoveredItems = client.fetchReleaseYear(year)
                .filter { gameRepository.findByWikidataId(it.wikidataId) == null }
            val items = enrichNewItemsFromSteam(discoveredItems)
            val classifications = client.fetchClassifications(items.map { it.wikidataId })
            val classifiedItems = items.map { item ->
                val classification = classifications[item.wikidataId]
                item.copy(
                    genres = (item.genres + classification?.genres.orEmpty()).toSet(),
                    platforms = (item.platforms + classification?.platforms.orEmpty()).toSet(),
                    gameModes = (item.gameModes + classification?.gameModes.orEmpty()).toSet(),
                )
            }
            var inserted = 0
            var companiesCreated = 0
            classifiedItems.forEach { item ->
                if (gameRepository.findByWikidataId(item.wikidataId) != null) return@forEach
                val developerResult = resolveCompany(item.developers.firstOrNull(), CompanyType.DEVELOPER)
                val publisherResult = resolveCompany(item.publishers.firstOrNull(), CompanyType.PUBLISHER)
                companiesCreated += developerResult.second + publisherResult.second
                val game = gameRepository.save(toGame(item, developerResult.first, publisherResult.first))
                game.platforms.forEach { platform -> releaseRepository.save(toRelease(game, platform, item.releaseDate)) }
                inserted++
            }
            run.finishedAt = Instant.now()
            run.status = CollectorRunStatus.SUCCESS
            run.fetchedCount = classifiedItems.size
            run.newItemCount = inserted
            collectorRunRepository.save(run)
            refreshMagazineSubscriptions()
            return CatalogSyncSummary("SUCCESS", year, classifiedItems.size, inserted, companiesCreated)
        } catch (error: Exception) {
            run?.let {
                it.finishedAt = Instant.now()
                it.status = CollectorRunStatus.FAILED
                it.errorMessage = (error.message ?: error.javaClass.simpleName).take(1000)
                collectorRunRepository.save(it)
            }
            return CatalogSyncSummary("FAILED", year, 0, 0, 0, error.message)
        } finally {
            running.set(false)
        }
    }

    private fun enrichNewItemsFromSteam(items: List<CatalogGameItem>): List<CatalogGameItem> {
        val first = items.firstOrNull() ?: return emptyList()
        val firstMetadata = first.steamAppId?.let(steamStoreClient::fetchDetails) ?: return items
        val enriched = linkedMapOf(first.wikidataId to applyStoreMetadata(first, firstMetadata))
        items.drop(1).take(MAX_STEAM_DETAILS_PER_SYNC - 1).forEach { item ->
            val metadata = item.steamAppId?.let(steamStoreClient::fetchDetails) ?: return@forEach
            enriched[item.wikidataId] = applyStoreMetadata(item, metadata)
        }
        return items.map { enriched[it.wikidataId] ?: it }
    }

    private fun applyStoreMetadata(item: CatalogGameItem, metadata: SteamStoreMetadata) = item.copy(
        title = metadata.name,
        imageUrl = metadata.headerImageUrl,
        imageSource = "STEAM_STOREFRONT_API",
        genres = metadata.genres,
        platforms = metadata.platforms,
        gameModes = metadata.gameModes,
    )

    fun enrichIncompleteStoreMetadata(limit: Int = 500): StoreEnrichmentSummary {
        val candidates = gameRepository.findAllForDiscovery()
            .asSequence()
            .filter { it.steamAppId != null }
            .filter { game ->
                game.genres.isEmpty() || game.genres.all { it == "2026 신작" || it == "미분류" } ||
                    game.platforms.isEmpty() || game.platforms.all { it == "미정" } || game.gameModes.isEmpty()
            }
            .take(limit.coerceIn(1, 2_000))
            .toList()
        if (candidates.isEmpty()) return StoreEnrichmentSummary("SUCCESS", 0, 0, 0)

        val executor = java.util.concurrent.Executors.newFixedThreadPool(6)
        val fetched = try {
            candidates.map { game ->
                game to executor.submit<SteamStoreMetadata?> { steamStoreClient.fetchDetails(requireNotNull(game.steamAppId)) }
            }.map { (game, future) -> game to runCatching { future.get() }.getOrNull() }
        } finally {
            executor.shutdown()
        }

        var enriched = 0
        fetched.forEach { (game, metadata) ->
            if (metadata == null) return@forEach
            val hadUnknownPlatform = game.platforms.isEmpty() || game.platforms.all { it == "미정" }
            if (metadata.genres.isNotEmpty()) {
                game.genres.clear()
                game.genres.addAll(metadata.genres)
            }
            if (metadata.platforms.isNotEmpty()) {
                game.platforms.clear()
                game.platforms.addAll(metadata.platforms)
            }
            if (metadata.gameModes.isNotEmpty()) {
                game.gameModes.clear()
                game.gameModes.addAll(metadata.gameModes)
            }
            gameRepository.save(game)
            val releaseDate = game.releaseDate
            if (hadUnknownPlatform && metadata.platforms.isNotEmpty() && releaseDate != null) {
                releaseRepository.deleteAllByGameId(game.id)
                metadata.platforms.forEach { platform -> releaseRepository.save(toRelease(game, platform, releaseDate)) }
            }
            enriched++
        }
        return StoreEnrichmentSummary("SUCCESS", candidates.size, enriched, candidates.size - enriched)
    }

    fun enrichWikidataClassifications(limit: Int = 1_000, includeComplete: Boolean = false): ClassificationEnrichmentSummary {
        val candidates = gameRepository.findAllForDiscovery()
            .asSequence()
            .filter { it.wikidataId != null }
            .filter { game -> includeComplete ||
                game.genres.isEmpty() || game.genres.all { it == "2026 신작" || it == "미분류" } ||
                    game.platforms.isEmpty() || game.platforms.all { it == "미정" } || game.gameModes.isEmpty()
            }
            .take(limit.coerceIn(1, 5_000))
            .toList()
        if (candidates.isEmpty()) return ClassificationEnrichmentSummary("SUCCESS", 0, 0, 0)
        val classifications = runCatching { client.fetchClassifications(candidates.mapNotNull { it.wikidataId }) }
            .getOrElse { return ClassificationEnrichmentSummary("FAILED", candidates.size, 0, candidates.size) }
        var enriched = 0
        candidates.forEach { game ->
            val classification = classifications[game.wikidataId] ?: return@forEach
            val hadUnknownPlatform = game.platforms.isEmpty() || game.platforms.all { it == "미정" }
            var changed = false
            if (classification.genres.isNotEmpty()) {
                if (game.genres.all { it == "2026 신작" || it == "미분류" }) game.genres.clear()
                game.genres.addAll(classification.genres)
                changed = true
            }
            if (classification.platforms.isNotEmpty()) {
                if (game.platforms.all { it == "미정" }) game.platforms.clear()
                game.platforms.addAll(classification.platforms)
                changed = true
            }
            if (classification.gameModes.isNotEmpty()) {
                game.gameModes.addAll(classification.gameModes)
                changed = true
            }
            if (!changed) return@forEach
            gameRepository.save(game)
            val releaseDate = game.releaseDate
            if (hadUnknownPlatform && classification.platforms.isNotEmpty() && releaseDate != null) {
                releaseRepository.deleteAllByGameId(game.id)
                classification.platforms.forEach { platform -> releaseRepository.save(toRelease(game, platform, releaseDate)) }
            }
            enriched++
        }
        return ClassificationEnrichmentSummary("SUCCESS", candidates.size, enriched, candidates.size - enriched)
    }

    private fun resolveCompany(ref: CatalogCompanyRef?, type: CompanyType): Pair<Company, Int> {
        if (ref == null) return Pair(requireNotNull(companyRepository.findBySlug("independent-unknown")), 0)
        ref.wikidataId?.let { companyRepository.findByWikidataId(it) }?.let { return Pair(it, 0) }
        companyRepository.findByNameIgnoreCase(ref.name)?.let { return Pair(it, 0) }
        var slug = slugify(ref.name)
        if (companyRepository.findBySlug(slug) != null) slug += "-${ref.wikidataId?.lowercase() ?: ref.name.hashCode().absoluteValue}"
        return Pair(companyRepository.save(Company(slug = slug, name = ref.name, type = type, wikidataId = ref.wikidataId)), 1)
    }

    private fun refreshMagazineSubscriptions() {
        val steamSource = sourceRepository.findBySlug("steam-news-rss") ?: return
        gameRepository.findAllForDiscovery()
            .asSequence()
            .filter { it.steamAppId != null }
            .take(MAX_MAGAZINE_SUBSCRIPTIONS)
            .forEach { game ->
                if (!subscriptionRepository.existsBySourceIdAndGameId(steamSource.id, game.id)) {
                    val appId = requireNotNull(game.steamAppId)
                    subscriptionRepository.save(
                        SourceSubscription(
                            source = steamSource,
                            game = game,
                            externalGameId = appId.toString(),
                            feedUrl = "https://store.steampowered.com/feeds/news/app/$appId/",
                        ),
                    )
                }
            }
    }

    private fun toGame(item: CatalogGameItem, developer: Company, publisher: Company): Game {
        val today = LocalDate.now()
        var slug = slugify(item.title)
        if (gameRepository.findBySlug(slug) != null) slug += "-${item.wikidataId.lowercase()}"
        val dayDistance = ChronoUnit.DAYS.between(today, item.releaseDate).absoluteValue
        val recencyBase = if (item.releaseDate.isAfter(today)) 99 else 92
        val majorCompanyBoost = if (developer.major || publisher.major) 3 else 0
        val score = (recencyBase - (dayDistance / 10).coerceAtMost(15) + majorCompanyBoost).coerceIn(75, 99)
        val colors = colorsFor(item.wikidataId)
        val yearOnly = item.releaseDate.monthValue == 1 && item.releaseDate.dayOfMonth == 1
        val releaseLabel = if (yearOnly) "${item.releaseDate.year}년 출시 예정" else "%d. %02d. %02d".format(item.releaseDate.year, item.releaseDate.monthValue, item.releaseDate.dayOfMonth)
        val symbol = item.title.filter(Char::isLetterOrDigit).take(2).uppercase().ifBlank { "✦" }
        return Game(
            slug = slug,
            originalTitle = item.title,
            title = item.title,
            tagline = "${item.releaseDate.year}년 신작 · 최신 출시 일정",
            description = "Wikidata CC0 구조화 데이터에서 확인한 ${item.releaseDate.year}년 게임입니다. 공식 채널의 발표와 업데이트가 연결되면 일정과 상세 정보를 계속 보강합니다.",
            developer = developer,
            publisher = publisher,
            releaseDate = item.releaseDate,
            officialUrl = item.officialUrl,
            steamAppId = item.steamAppId,
            wikidataId = item.wikidataId,
            catalogSource = "WIKIDATA_CC0",
            coverImageUrl = item.imageUrl,
            imageSource = item.imageSource,
            releaseLabel = releaseLabel,
            status = if (yearOnly || item.releaseDate.isAfter(today)) GameStatus.UPCOMING else GameStatus.AVAILABLE,
            discoveryScore = BigDecimal.valueOf(score.toLong()),
            anticipationScore = BigDecimal.valueOf((score + if (item.releaseDate.isAfter(today)) 1 else -3).coerceIn(70, 100).toLong()),
            followerCount = 0,
            accent = colors.first,
            accentSecondary = colors.second,
            symbol = symbol,
            featured = item.steamAppId in FEATURED_APP_IDS,
            genres = item.genres.ifEmpty { setOf("미분류") }.toCollection(linkedSetOf()),
            platforms = item.platforms.ifEmpty { setOf("PC") }.toCollection(linkedSetOf()),
            gameModes = item.gameModes.toCollection(linkedSetOf()),
        )
    }

    private fun toRelease(game: Game, platform: String, releaseDate: LocalDate) = Release(
        game = game,
        platform = platform,
        releaseDate = releaseDate,
        status = if ((releaseDate.monthValue == 1 && releaseDate.dayOfMonth == 1) || releaseDate.isAfter(LocalDate.now())) ReleaseStatus.EXPECTED else ReleaseStatus.RELEASED,
    )

    private fun slugify(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKD)
        .lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "game-${value.hashCode().absoluteValue}" }

    private fun colorsFor(seed: String): Pair<String, String> {
        val palettes = listOf(
            "#6d5dfc" to "#2d245d", "#ef6c57" to "#713b43", "#2fa8a0" to "#174b58",
            "#d49b35" to "#5f4221", "#4d84dd" to "#223c68", "#b65bd6" to "#482858",
        )
        return palettes[seed.hashCode().absoluteValue % palettes.size]
    }

    private companion object {
        val FEATURED_APP_IDS = setOf(3764200L, 3357650L, 2483190L, 2362060L, 2499860L, 2288340L)
        const val MAX_MAGAZINE_SUBSCRIPTIONS = 50
        const val MAX_STEAM_DETAILS_PER_SYNC = 24
    }
}
