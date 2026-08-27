package com.rubion.nexplaybe.company

import org.springframework.data.jpa.repository.JpaRepository

interface CompanyRepository : JpaRepository<Company, Long> {
    fun findByWikidataId(wikidataId: String): Company?
    fun findByNameIgnoreCase(name: String): Company?
    fun findBySlug(slug: String): Company?
    fun findAllByMajorTrueOrderByNameAsc(): List<Company>
}
