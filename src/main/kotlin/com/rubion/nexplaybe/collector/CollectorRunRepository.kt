package com.rubion.nexplaybe.collector

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.jpa.repository.JpaRepository

interface CollectorRunRepository : JpaRepository<CollectorRun, Long> {
    @EntityGraph(attributePaths = ["source"])
    fun findAllByOrderByStartedAtDesc(pageable: Pageable): List<CollectorRun>
}
