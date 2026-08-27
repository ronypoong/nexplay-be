package com.rubion.nexplaybe.api

import com.rubion.nexplaybe.company.CompanyRepository
import org.springframework.web.bind.annotation.GetMapping
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
)

@RestController
@RequestMapping("/api/v1/companies")
class CompanyController(private val companyRepository: CompanyRepository) {
    @GetMapping
    fun companies(@RequestParam(defaultValue = "false") major: Boolean): List<CompanyResponse> =
        (if (major) companyRepository.findAllByMajorTrueOrderByNameAsc() else companyRepository.findAll().sortedBy { it.name })
            .map { CompanyResponse(it.id, it.slug, it.name, it.type.name, it.country, it.officialUrl, it.wikidataId, it.major) }
}
