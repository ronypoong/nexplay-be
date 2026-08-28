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
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.text.Normalizer
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.absoluteValue
import org.springframework.beans.factory.annotation.Value
import org.springframework.transaction.annotation.Transactional

data class CatalogSyncSummary(
    val status: String,
    val year: Int,
    val fetched: Int,
    val inserted: Int,
    val companiesCreated: Int,
    val dateChanges: Int = 0,
    val error: String? = null,
)

/**
 * Wikidata 출시연도 + Steam 검증만으로 카탈로그를 만들면, 아직 스토어 페이지가 없거나
 * 출시일이 발표되지 않은 대작은 통째로 빠진다. 실제로 한국 개발사 게임이 455개 중 6개뿐이었다.
 * 출처(Steam appId 또는 Wikidata Q번호)를 명시해 직접 넣을 수 있는 경로를 둔다.
 */
data class ManualGameRequest(
    val title: String,
    val developer: String,
    val publisher: String,
    val steamAppId: Long? = null,
    val wikidataId: String? = null,
    val releaseDate: LocalDate? = null,
    val officialUrl: String? = null,
    val tagline: String? = null,
    val description: String? = null,
    // Jackson 이 코틀린 기본값을 채우지 않고 null 을 넘겨서, non-null Set 으로 두면
    // 이 필드들을 생략한 요청이 전부 400 이 된다. nullable 로 받고 안에서 비운다.
    val genres: Set<String>? = null,
    val platforms: Set<String>? = null,
    val gameModes: Set<String>? = null,
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
    private val jdbc: JdbcTemplate,
    /**
     * 한 번 돌 때 새로 구독할 게임 수.
     *
     * 한 번에 다 늘리면 그날 수집이 갑자기 몇 배로 길어지고, 무언가 잘못됐을 때
     * 되돌리기도 어렵다. 하루 120개씩 늘리면 사나흘이면 전부 덮는다.
     */
    @param:Value("\${nexplay.catalog.max-new-subscriptions-per-run:120}")
    private val maxNewSubscriptionsPerRun: Int,
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
            val fetchedItems = client.fetchReleaseYear(year)
            // 예전에는 이미 아는 게임을 여기서 걸러내고 끝이라, Wikidata 가 출시일을 바꿔도
            // 아무도 보지 않았다. release_revision 이 전부 INITIAL_CONFIRMATION 이었던 이유다.
            val dateChanges = recordReleaseDateChanges(fetchedItems)
            val discoveredItems = fetchedItems.filter { gameRepository.findByWikidataId(it.wikidataId) == null }
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
            return CatalogSyncSummary("SUCCESS", year, classifiedItems.size, inserted, companiesCreated, dateChanges)
        } catch (error: Exception) {
            run?.let {
                it.finishedAt = Instant.now()
                it.status = CollectorRunStatus.FAILED
                it.errorMessage = (error.message ?: error.javaClass.simpleName).take(1000)
                collectorRunRepository.save(it)
            }
            return CatalogSyncSummary("FAILED", year, 0, 0, 0, error = error.message)
        } finally {
            running.set(false)
        }
    }

    // 이전 구현은 첫 항목 하나가 실패하면 나머지를 통째로 건너뛰었다. 첫 항목은 Wikidata 응답 순서라
    // 사실상 무작위이므로, steamAppId 가 없는 항목은 예산을 쓰지 않고 넘기고
    // Steam 자체가 죽은 경우만 연속 실패로 판단해 중단한다.
    private fun enrichNewItemsFromSteam(items: List<CatalogGameItem>): List<CatalogGameItem> {
        if (items.isEmpty()) return emptyList()
        val enriched = linkedMapOf<String, CatalogGameItem>()
        var budget = MAX_STEAM_DETAILS_PER_SYNC
        var consecutiveFailures = 0
        for (item in items) {
            if (budget <= 0 || consecutiveFailures >= STEAM_FAILURE_CUTOFF) break
            val appId = item.steamAppId ?: continue
            budget--
            val metadata = runCatching { steamStoreClient.fetchDetails(appId) }.getOrNull()
            if (metadata == null) {
                consecutiveFailures++
                continue
            }
            consecutiveFailures = 0
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
    ).also {
        storeCopy[item.wikidataId] = metadata.aboutTheGame to metadata.shortDescription
    }

    /** applyStoreMetadata 가 넘겨준 소개문. CatalogGameItem 을 건드리지 않고 toGame 까지 전달한다. */
    private val storeCopy = mutableMapOf<String, Pair<String?, String?>>()

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
            val platformsBefore = game.platforms.toSet()
            val hadUnknownPlatform = game.platforms.isEmpty() || game.platforms.all { it == "미정" }
            if (metadata.genres.isNotEmpty()) {
                game.genres.clear()
                game.genres.addAll(metadata.genres)
            }
            // Steam 스토어는 windows/mac/linux 만 알 수 있어 결과가 항상 "PC" 하나다.
            // 여기서 clear 하면 Wikidata 가 채운 PS5·Xbox·Switch 2 가 지워지므로 병합만 한다.
            if (metadata.platforms.isNotEmpty()) {
                if (hadUnknownPlatform) game.platforms.clear()
                game.platforms.addAll(metadata.platforms)
            }
            if (metadata.gameModes.isNotEmpty()) {
                game.gameModes.clear()
                game.gameModes.addAll(metadata.gameModes)
            }
            applyStoreCopy(game, metadata)
            gameRepository.save(game)
            syncReleases(game, platformsBefore)
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
            val platformsBefore = game.platforms.toSet()
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
            syncReleases(game, platformsBefore)
            enriched++
        }
        return ClassificationEnrichmentSummary("SUCCESS", candidates.size, enriched, candidates.size - enriched)
    }

    /**
     * Wikidata 가 알려주는 출시일이 저장된 값과 다르면 이력으로 남기고 게임을 갱신한다.
     * 연기 이력이 쌓여야 "이 개발사는 얼마나 미루는가" 를 말할 수 있다.
     */
    private fun recordReleaseDateChanges(items: List<CatalogGameItem>): Int {
        var changes = 0
        items.forEach { item ->
            val game = gameRepository.findByWikidataId(item.wikidataId) ?: return@forEach
            val previous = game.releaseDate ?: return@forEach
            if (previous == item.releaseDate) return@forEach
            // 연도만 아는 날짜(1월 1일)끼리 해가 같으면 정밀도 차이일 뿐 변경이 아니다.
            if (isYearOnly(previous) && isYearOnly(item.releaseDate) && previous.year == item.releaseDate.year) return@forEach
            val changeType = when {
                isYearOnly(previous) || isYearOnly(item.releaseDate) -> "DATE_CHANGE"
                item.releaseDate.isAfter(previous) -> "DELAY"
                else -> "DATE_CHANGE"
            }
            game.platforms.forEach { platform ->
                jdbc.update(
                    """INSERT INTO release_revision (game_id,platform,previous_date,new_date,change_type,announced_at,source_name,source_url)
                    VALUES (?,?,?,?,?,CURRENT_DATE,'Wikidata',?)""",
                    game.id, platform, java.sql.Date.valueOf(previous), java.sql.Date.valueOf(item.releaseDate),
                    changeType, "https://www.wikidata.org/wiki/${item.wikidataId}",
                )
            }
            jdbc.update(
                "UPDATE game SET release_date=?, release_label=?, status=? WHERE id=?",
                java.sql.Date.valueOf(item.releaseDate), releaseLabelFor(item.releaseDate),
                if (isStillUpcoming(item.releaseDate)) GameStatus.UPCOMING.name else GameStatus.AVAILABLE.name,
                game.id,
            )
            jdbc.update(
                "UPDATE game_release SET release_date=?, status=? WHERE game_id=?",
                java.sql.Date.valueOf(item.releaseDate),
                if (isStillUpcoming(item.releaseDate)) ReleaseStatus.EXPECTED.name else ReleaseStatus.RELEASED.name,
                game.id,
            )
            changes++
        }
        return changes
    }

    private fun isYearOnly(date: LocalDate) = date.monthValue == 1 && date.dayOfMonth == 1

    private fun releaseLabelFor(date: LocalDate, today: LocalDate = LocalDate.now()): String = when {
        isYearOnly(date) && date.year < today.year -> "${date.year}년 출시"
        isYearOnly(date) -> "${date.year}년 출시 예정"
        else -> "%d. %02d. %02d".format(date.year, date.monthValue, date.dayOfMonth)
    }

    /**
     * 상태는 저장 컬럼이라 시간이 지나면 저절로 어긋난다.
     * 2026-01-01 로 들어간 "2026년 출시 예정" 게임은 2027년이 되어도 예정으로 남는다.
     * 매일 한 번 현재 날짜 기준으로 다시 맞춘다. V18 마이그레이션과 같은 규칙이다.
     */
    fun refreshReleaseStatuses(): Int {
        val games = jdbc.update(
            """
            UPDATE game SET status='AVAILABLE',
              release_label = IF(release_label = CONCAT(YEAR(release_date),'년 출시 예정'),
                                 CONCAT(YEAR(release_date),'년 출시'), release_label)
            WHERE status='UPCOMING' AND release_date IS NOT NULL
              AND ((MONTH(release_date)=1 AND DAY(release_date)=1 AND YEAR(release_date) < YEAR(CURDATE()))
                OR (NOT (MONTH(release_date)=1 AND DAY(release_date)=1) AND release_date < CURDATE()))
            """.trimIndent(),
        )
        val releases = jdbc.update(
            """
            UPDATE game_release SET status='RELEASED'
            WHERE status='EXPECTED'
              AND ((MONTH(release_date)=1 AND DAY(release_date)=1 AND YEAR(release_date) < YEAR(CURDATE()))
                OR (NOT (MONTH(release_date)=1 AND DAY(release_date)=1) AND release_date < CURDATE()))
            """.trimIndent(),
        )
        return games + releases
    }

    /**
     * 소개문을 Steam 것으로 채운다. 사람이 손본 문구는 덮지 않고,
     * 자동 생성된 보일러플레이트만 교체한다.
     */
    fun applyStoreCopy(game: Game, metadata: SteamStoreMetadata): Boolean {
        var changed = false
        metadata.aboutTheGame?.let { about ->
            if (isBoilerplate(game.description) || game.description.length < about.length / 2) {
                game.description = about
                changed = true
            }
        }
        metadata.shortDescription?.let { short ->
            if (isBoilerplate(game.tagline)) {
                game.tagline = short.take(240)
                changed = true
            }
        }
        return changed
    }

    private fun isBoilerplate(text: String) = BOILERPLATE_MARKERS.any { text.contains(it) }

    // 플랫폼 집합이 바뀌면 release 행도 같이 맞춘다.
    // 예전에는 "미정" 이었던 경우에만 다시 만들어서 game.platforms 와 release.platform 이 어긋났다.
    private fun syncReleases(game: Game, platformsBefore: Set<String>) {
        val releaseDate = game.releaseDate ?: return
        if (game.platforms == platformsBefore) return
        releaseRepository.deleteAllByGameId(game.id)
        game.platforms.forEach { platform -> releaseRepository.save(toRelease(game, platform, releaseDate)) }
    }

    /**
     * 출처를 명시한 수동 등록. steamAppId 가 있으면 제목·이미지·장르를 스토어에서 다시 확인해 덮어쓴다.
     * 출시일을 모르면 TBA 로 둔다 — 모르는 것을 아는 척하지 않는다.
     */
    fun addGame(request: ManualGameRequest): CatalogSyncSummary {
        require(request.steamAppId != null || request.wikidataId != null) {
            "steamAppId 또는 wikidataId 중 하나는 있어야 합니다. 출처 없는 게임은 넣지 않습니다."
        }
        request.wikidataId?.let { id ->
            require(id.matches(Regex("Q\\d+"))) { "wikidataId 형식이 올바르지 않습니다: $id" }
            gameRepository.findByWikidataId(id)?.let { return CatalogSyncSummary("ALREADY_EXISTS", 0, 1, 0, 0) }
        }
        val storeMetadata = request.steamAppId?.let { appId -> runCatching { steamStoreClient.fetchDetails(appId) }.getOrNull() }
        val today = LocalDate.now()
        val title = storeMetadata?.name ?: request.title
        val developer = resolveCompany(CatalogCompanyRef(null, request.developer), CompanyType.DEVELOPER).first
        val publisher = resolveCompany(CatalogCompanyRef(null, request.publisher), CompanyType.PUBLISHER).first
        var slug = slugify(title)
        if (gameRepository.findBySlug(slug) != null) slug += "-${request.wikidataId?.lowercase() ?: request.steamAppId}"
        val genres = (storeMetadata?.genres.orEmpty() + request.genres.orEmpty()).ifEmpty { setOf("미분류") }
        val platforms = (storeMetadata?.platforms.orEmpty() + request.platforms.orEmpty()).ifEmpty { setOf("미정") }
        val colors = colorsFor(request.wikidataId ?: request.steamAppId.toString())
        val game = gameRepository.save(
            Game(
                slug = slug,
                originalTitle = request.title,
                title = title,
                tagline = request.tagline ?: storeMetadata?.shortDescription?.take(240) ?: "주목할 신작",
                description = request.description ?: storeMetadata?.aboutTheGame
                    ?: "${storeMetadata?.let { "Steam 스토어" } ?: "Wikidata"}에서 확인한 정보입니다. 공식 발표가 연결되면 일정과 상세 정보를 계속 보강합니다.",
                developer = developer,
                publisher = publisher,
                releaseDate = request.releaseDate,
                officialUrl = request.officialUrl,
                steamAppId = request.steamAppId,
                wikidataId = request.wikidataId,
                catalogSource = if (storeMetadata != null) "STEAM_STOREFRONT_API" else "WIKIDATA_CC0",
                coverImageUrl = storeMetadata?.headerImageUrl,
                imageSource = storeMetadata?.let { "STEAM_STOREFRONT_API" },
                releaseLabel = request.releaseDate?.let { releaseLabelFor(it, today) } ?: "출시일 미정",
                status = when {
                    request.releaseDate == null -> GameStatus.TBA
                    isStillUpcoming(request.releaseDate, today) -> GameStatus.UPCOMING
                    else -> GameStatus.AVAILABLE
                },
                discoveryScore = BigDecimal.valueOf(95),
                anticipationScore = BigDecimal.valueOf(98),
                followerCount = 0,
                accent = colors.first,
                accentSecondary = colors.second,
                symbol = title.filter(Char::isLetterOrDigit).take(2).uppercase().ifBlank { "✦" },
                featured = false,
                genres = genres.toCollection(linkedSetOf()),
                platforms = platforms.toCollection(linkedSetOf()),
                gameModes = (storeMetadata?.gameModes.orEmpty() + request.gameModes.orEmpty()).toCollection(linkedSetOf()),
            ),
        )
        request.releaseDate?.let { date -> game.platforms.forEach { releaseRepository.save(toRelease(game, it, date)) } }
        return CatalogSyncSummary("SUCCESS", request.releaseDate?.year ?: 0, 1, 1, 0)
    }

    /** 대표 이미지가 비어 있는 게임. 자동으로 못 채운 것만 남는다. */
    @Transactional(readOnly = true)
    fun gamesMissingCover(): List<Map<String, Any?>> = jdbc.queryForList(
        """
        SELECT slug, title, steam_app_id, wikidata_id, discovery_score
        FROM game
        WHERE archive_only = 0 AND (cover_image_url IS NULL OR cover_image_url = '')
        ORDER BY discovery_score DESC, title
        """.trimIndent(),
    )

    /**
     * 대표 이미지를 손으로 지정한다.
     *
     * 자동 수집은 이미 값이 있으면 덮지 않으므로, 한 번 넣으면 유지된다.
     * 어디서 온 값인지 남긴다 — 나중에 "이건 어디서 왔지" 를 물을 수 있어야 한다.
     */
    @Transactional
    fun setCoverImage(slug: String, url: String): Map<String, Any?> {
        require(url.startsWith("https://")) { "이미지 주소는 https 로 시작해야 합니다" }
        require(url.length <= 700) { "이미지 주소가 너무 깁니다" }
        val updated = jdbc.update("UPDATE game SET cover_image_url = ? WHERE slug = ?", url, slug)
        if (updated == 0) throw com.rubion.nexplaybe.discovery.ResourceNotFoundException("Game not found: $slug")
        jdbc.update(
            """INSERT INTO game_data_provenance (game_id,field_name,source_name,source_url,confidence,verified_at)
            SELECT id,'cover_image','Manual',?,'HIGH',NOW() FROM game WHERE slug = ?
            ON DUPLICATE KEY UPDATE source_url=VALUES(source_url), verified_at=VALUES(verified_at)""",
            url, slug,
        )
        return mapOf("slug" to slug, "coverImageUrl" to url)
    }

    private fun resolveCompany(ref: CatalogCompanyRef?, type: CompanyType): Pair<Company, Int> {
        if (ref == null) return Pair(requireNotNull(companyRepository.findBySlug("independent-unknown")), 0)
        ref.wikidataId?.let { companyRepository.findByWikidataId(it) }?.let { return Pair(it, 0) }
        companyRepository.findByNameIgnoreCase(ref.name)?.let { return Pair(it, 0) }
        var slug = slugify(ref.name)
        if (companyRepository.findBySlug(slug) != null) slug += "-${ref.wikidataId?.lowercase() ?: ref.name.hashCode().absoluteValue}"
        return Pair(companyRepository.save(Company(slug = slug, name = ref.name, type = type, wikidataId = ref.wikidataId)), 1)
    }

    /**
     * 공식 소식을 지켜볼 게임을 늘린다.
     *
     * 예전에는 상위 50개만 봤다. `.take(50)` 이 존재 확인보다 먼저 오니 매번 같은
     * 50개를 보고, 그것들이 이미 구독돼 있으면 새 구독은 영영 생기지 않았다.
     * 실제로 스팀 ID 가 있는 455개 중 85개만 구독 중이었다.
     *
     * 나머지 370개의 발표는 매일 그냥 사라진다. 아카이브는 지켜보는 것만 쌓이고,
     * 오늘 안 본 발표는 나중에 되살릴 수 없다 — Steam RSS 는 앱당 최근 10건만 준다.
     *
     * 아직 구독하지 않은 게임을 먼저 집는다. 그래야 한도가 있어도 매번 앞으로 나간다.
     */
    private fun refreshMagazineSubscriptions() {
        val steamSource = sourceRepository.findBySlug("steam-news-rss") ?: return
        gameRepository.findAllForDiscovery()
            .asSequence()
            .filter { it.steamAppId != null }
            .filterNot { subscriptionRepository.existsBySourceIdAndGameId(steamSource.id, it.id) }
            .take(maxNewSubscriptionsPerRun)
            .forEach { game ->
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

    private fun toGame(item: CatalogGameItem, developer: Company, publisher: Company): Game {
        val today = LocalDate.now()
        var slug = slugify(item.title)
        if (gameRepository.findBySlug(slug) != null) slug += "-${item.wikidataId.lowercase()}"
        val dayDistance = ChronoUnit.DAYS.between(today, item.releaseDate).absoluteValue
        val recencyBase = if (item.releaseDate.isAfter(today)) 99 else 92
        val majorCompanyBoost = if (developer.major || publisher.major) 3 else 0
        val score = (recencyBase - (dayDistance / 10).coerceAtMost(15) + majorCompanyBoost).coerceIn(75, 99)
        val colors = colorsFor(item.wikidataId)
        // Wikidata 가 연도만 알 때 1월 1일로 내려준다. 이걸 "예정" 으로 두면 지난 해 게임이 영원히 출시 예정으로 남는다.
        val yearOnly = item.releaseDate.monthValue == 1 && item.releaseDate.dayOfMonth == 1
        val stillUpcoming = if (yearOnly) item.releaseDate.year >= today.year else item.releaseDate.isAfter(today)
        val releaseLabel = releaseLabelFor(item.releaseDate, today)
        val symbol = item.title.filter(Char::isLetterOrDigit).take(2).uppercase().ifBlank { "✦" }
        return Game(
            slug = slug,
            originalTitle = item.title,
            title = item.title,
            tagline = storeCopy[item.wikidataId]?.second?.take(240)
                ?: "${item.releaseDate.year}년 신작 · 최신 출시 일정",
            description = storeCopy[item.wikidataId]?.first
                ?: "Wikidata CC0 구조화 데이터에서 확인한 ${item.releaseDate.year}년 게임입니다. 공식 채널의 발표와 업데이트가 연결되면 일정과 상세 정보를 계속 보강합니다.",
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
            status = if (stillUpcoming) GameStatus.UPCOMING else GameStatus.AVAILABLE,
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
        status = if (isStillUpcoming(releaseDate)) ReleaseStatus.EXPECTED else ReleaseStatus.RELEASED,
    )

    // 연도만 아는 날짜(1월 1일)는 그 해가 지나지 않았을 때만 "예정" 이다.
    private fun isStillUpcoming(releaseDate: LocalDate, today: LocalDate = LocalDate.now()): Boolean =
        if (releaseDate.monthValue == 1 && releaseDate.dayOfMonth == 1) releaseDate.year >= today.year
        else releaseDate.isAfter(today)

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
        const val MAX_STEAM_DETAILS_PER_SYNC = 24
        const val STEAM_FAILURE_CUTOFF = 5
        val BOILERPLATE_MARKERS = listOf(
            "Wikidata CC0 구조화 데이터에서 확인한", "에서 확인한 정보입니다",
            "년 신작 · 최신 출시 일정", "주목할 신작",
        )
    }
}
