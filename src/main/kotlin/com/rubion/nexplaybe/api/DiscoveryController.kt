package com.rubion.nexplaybe.api

import com.rubion.nexplaybe.discovery.DiscoveryService
import com.rubion.nexplaybe.editorial.EditorPickService
import com.rubion.nexplaybe.korean.KoreanSupportService
import com.rubion.nexplaybe.metadata.ExtendedGameMetadataService
import com.rubion.nexplaybe.trends.TrendService
import jakarta.validation.constraints.Size
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

@RestController
@RequestMapping("/api/v1")
class DiscoveryController(
    private val discoveryService: DiscoveryService,
    private val metadataService: ExtendedGameMetadataService,
    private val editorPickService: EditorPickService,
    private val koreanSupportService: KoreanSupportService,
    private val trendService: TrendService,
) {
    /** 시간이 쌓여야 보이는 것: 기대 지수 급상승과 출시일 변경 이력. */
    @GetMapping("/trends")
    fun trends() = trendService.trends()

    /** 주인장이 직접 고른 목록. 알고리즘 정렬과 대비되는 자리다. */
    @GetMapping("/editor-picks")
    fun editorPicks() = editorPickService.list()

    /** 한국어 지원 레이더: 커버리지, 퍼블리셔별 지원률, 미확인작 확률, 풀보이스 목록. */
    @GetMapping("/korean")
    fun koreanRadar() = koreanSupportService.radar()

    @GetMapping("/feed")
    fun feed() = discoveryService.feed()

    @GetMapping("/games")
    fun games(
        @RequestParam(required = false) platform: String?,
        @RequestParam(required = false) genre: String?,
        @RequestParam(required = false, name = "q") @Size(max = 100) query: String?,
    ) = discoveryService.games(platform, genre, query)

    @GetMapping("/games/{slug}")
    fun game(@PathVariable slug: String) = discoveryService.game(slug)

    /** 같은 장르 기반 관련작. 상세 화면이 전체 카탈로그를 받지 않도록 서버에서 고른다. */
    @GetMapping("/games/{slug}/related")
    fun related(@PathVariable slug: String, @RequestParam(defaultValue = "3") limit: Int) =
        discoveryService.related(slug, limit)

    @GetMapping("/games/{slug}/events")
    fun events(@PathVariable slug: String) = discoveryService.events(slug)

    @GetMapping("/games/{slug}/metadata")
    fun metadata(@PathVariable slug: String) = metadataService.get(slug)

    @GetMapping("/releases", "/calendar")
    fun releases(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
        @RequestParam(required = false) platform: String?,
    ) = discoveryService.releases(from, to, platform)

    @GetMapping("/trending", "/discover")
    fun discover(
        @RequestParam(required = false) platform: String?,
        @RequestParam(required = false) genre: String?,
    ) = discoveryService.games(platform, genre, null)
}
