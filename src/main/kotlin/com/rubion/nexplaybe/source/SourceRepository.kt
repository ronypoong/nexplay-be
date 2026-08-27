package com.rubion.nexplaybe.source

import org.springframework.data.jpa.repository.JpaRepository

interface SourceRepository : JpaRepository<Source, Long> {
    fun findBySlug(slug: String): Source?
    fun findAllByOrderByNameAsc(): List<Source>
}
