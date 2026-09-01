package com.rubion.nexplaybe.api

import com.rubion.nexplaybe.company.CompanyRepository
import com.rubion.nexplaybe.discovery.CatalogSnapshot
import com.rubion.nexplaybe.discovery.ResourceNotFoundException
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

data class CompanyResponse(
    val id: Long,
    val slug: String,
    val name: String,
    val type: String,
    val country: String?,
    val officialUrl: String?,
    val wikidataId: String?,
    val major: Boolean,
    /**
     * 이 회사가 만들었거나 낸 게임 수.
     *
     * 목록에서 이게 없으면 1,900곳이 이름만 늘어선 전화번호부가 된다.
     * 어디를 눌러야 볼 게 있는지 알 수 없다.
     */
    val gameCount: Int = 0,
)

/** 회사 하나와 그 회사의 게임. */
data class CompanyDetailResponse(
    val company: CompanyResponse,
    /** 개발한 게임. */
    val developed: List<GameCardResponse>,
    /** 배급한 게임. 개발도 배급도 같은 회사면 developed 에만 넣는다. */
    val published: List<GameCardResponse>,
)

@RestController
@RequestMapping("/api/v1/companies")
class CompanyController(
    private val companyRepository: CompanyRepository,
    private val catalogSnapshot: CatalogSnapshot,
) {
    /**
     * 회사 목록.
     *
     * 게임이 한 편도 없는 회사는 빼고 준다. 카탈로그를 채우다 보면 이름만 남고
     * 게임은 다른 회사로 옮겨 간 항목이 생기는데, 그걸 목록에 세워 두면
     * 눌러도 빈 화면만 나온다.
     *
     * minGames 는 화면이 정하게 둔다. 등록된 1,900곳 중 1,500곳이 게임 한 편짜리
     * 인디 스튜디오라, 전부 세우면 훑어볼 수 없는 전화번호부가 된다. 그렇다고
     * 서버가 잘라 버리면 검색에서도 못 찾게 되므로 기본값은 전부다.
     */
    @GetMapping
    fun companies(
        @RequestParam(defaultValue = "false") major: Boolean,
        @RequestParam(defaultValue = "1") minGames: Int,
    ): List<CompanyResponse> {
        val counts = gameCounts()
        val floor = minGames.coerceAtLeast(1)
        return (if (major) companyRepository.findAllByMajorTrueOrderByNameAsc() else companyRepository.findAll())
            .asSequence()
            .map { it.toResponse(counts[it.slug] ?: 0) }
            .filter { it.gameCount >= floor }
            .sortedWith(compareByDescending<CompanyResponse> { it.major }.thenByDescending { it.gameCount }.thenBy { it.name })
            .toList()
    }

    @GetMapping("/{slug}")
    fun company(@PathVariable slug: String): CompanyDetailResponse {
        val company = companyRepository.findBySlug(slug)
            ?: throw ResourceNotFoundException("Company not found: $slug")
        val entries = catalogSnapshot.entries()
        val developed = entries.filter { it.developerSlug == slug }
        // 개발과 배급이 같은 회사인 게임을 양쪽에 다 세우면 같은 카드가 두 번 나온다.
        val published = entries.filter { it.publisherSlug == slug && it.developerSlug != slug }
        return CompanyDetailResponse(
            company = company.toResponse(developed.size + published.size),
            developed = developed.map { it.card },
            published = published.map { it.card },
        )
    }

    /** 카탈로그를 한 번 훑어 회사별 게임 수를 센다. 스냅샷은 캐시되어 있다. */
    private fun gameCounts(): Map<String, Int> {
        val counts = mutableMapOf<String, Int>()
        catalogSnapshot.entries().forEach { entry ->
            counts.merge(entry.developerSlug, 1, Int::plus)
            if (entry.publisherSlug != entry.developerSlug) counts.merge(entry.publisherSlug, 1, Int::plus)
        }
        return counts
    }
}

private fun com.rubion.nexplaybe.company.Company.toResponse(gameCount: Int) = CompanyResponse(
    id, slug, name, type.name, country, officialUrl, wikidataId, major, gameCount,
)
