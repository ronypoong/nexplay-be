package com.rubion.nexplaybe.api

import com.rubion.nexplaybe.discovery.DiscoveryService
import com.rubion.nexplaybe.editorial.EditorPickService
import com.rubion.nexplaybe.intelligence.EventDetailService
import com.rubion.nexplaybe.intelligence.PromiseQueryService
import com.rubion.nexplaybe.korean.KoreanSupportService
import com.rubion.nexplaybe.anticipation.AnticipationService
import com.rubion.nexplaybe.popularity.AudienceService
import com.rubion.nexplaybe.scheduling.SyncStatusService
import com.rubion.nexplaybe.metadata.ExtendedGameMetadataService
import com.rubion.nexplaybe.awards.GameAwardService
import com.rubion.nexplaybe.trends.TrendService
import jakarta.validation.constraints.Size
import org.springframework.format.annotation.DateTimeFormat
import jakarta.servlet.http.HttpServletRequest
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus
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
    private val gameAwardService: GameAwardService,
    private val promiseQueryService: PromiseQueryService,
    private val syncStatusService: SyncStatusService,
    private val anticipationService: AnticipationService,
    private val audienceService: AudienceService,
    private val eventDetailService: EventDetailService,
) {
    /**
     * "기대돼요". 누르면 켜지고 다시 누르면 꺼진다.
     *
     * 계정을 만들지 않는다. 같은 사람이 두 번 세는 것만 막으면 되고, 그건
     * 소금을 섞은 IP 해시로 충분하다 — 원본 주소는 저장하지 않는다.
     */
    @PostMapping("/games/{slug}/anticipate")
    fun toggleAnticipation(
        @PathVariable slug: String,
        @RequestBody(required = false) body: AnticipateRequest?,
        request: HttpServletRequest,
    ) = anticipationService.toggle(slug, clientIpOf(request), body?.reason)

    @GetMapping("/games/{slug}/anticipate")
    fun anticipationState(@PathVariable slug: String, request: HttpServletRequest) =
        anticipationService.state(slug, clientIpOf(request))

    /**
     * 한 번 봤다고 기록한다. 화면이 마운트될 때 한 번만 보낸다.
     *
     * 서버 렌더에서 세지 않는 이유는 프론트가 응답을 캐시하기 때문이다. 캐시를
     * 타면 사람이 와도 서버는 모른다. 그리고 크롤러까지 세게 된다.
     */
    @PostMapping("/games/{slug}/view")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun recordView(@PathVariable slug: String) = audienceService.recordView(slug)

    /** 지금 가장 기대받는 게임. 표본이 얇으면 비어 있다. */
    @GetMapping("/anticipated")
    fun anticipated() = anticipationService.ranking()

    /**
     * Cloudflare 를 거치므로 소켓 주소는 항상 엣지 서버다. 진짜 방문자 주소는
     * CF-Connecting-IP 에 있다. 이걸 안 보면 모든 방문자가 한 사람이 된다.
     */
    private fun clientIpOf(request: HttpServletRequest): String =
        request.getHeader("CF-Connecting-IP")
            ?: request.getHeader("X-Forwarded-For")?.substringBefore(',')?.trim()
            ?: request.remoteAddr
            ?: "unknown"

    /**
     * 수집이 살아 있는지. 마지막 갱신 시각과 단계별 결과를 준다.
     *
     * 공개로 둔다. 언제 갱신됐는지는 데이터 서비스에서 신뢰의 근거이고,
     * 여기에 비밀은 없다.
     */
    @GetMapping("/status")
    fun status() = syncStatusService.status()

    /** 모아 둔 공식 소식. 중요도 순으로 내려간다. */
    @GetMapping("/events")
    fun events(@RequestParam(defaultValue = "0") page: Int) = discoveryService.eventFeed(page)

    /**
     * 소식 하나를 자세히. 원문은 옮기지 않고 우리가 뽑은 사실만 보여 준다.
     */
    @GetMapping("/events/{id}")
    fun eventDetail(@PathVariable id: Long) = eventDetailService.detail(id)

    /** 약속과 결과의 대조표: 퍼블리셔별 신뢰도와 실제로 밀린 기록. */
    @GetMapping("/promises")
    fun promises() = promiseQueryService.ledger()

    /** 한 게임이 지금까지 한 약속과 그 결말. */
    @GetMapping("/promises/{slug}")
    fun promisesForGame(@PathVariable slug: String) = promiseQueryService.forGame(slug)

    /** GOTY 수상·후보 아카이브와, 이력에 근거한 올해 관측 대상. */
    @GetMapping("/goty")
    fun goty() = gameAwardService.goty()

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

/** 이유는 선택이다. 안 적어도 누를 수 있어야 한다. */
data class AnticipateRequest(val reason: String? = null)
