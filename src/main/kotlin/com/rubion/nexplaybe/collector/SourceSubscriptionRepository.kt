package com.rubion.nexplaybe.collector

import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.jpa.repository.JpaRepository

interface SourceSubscriptionRepository : JpaRepository<SourceSubscription, Long> {
    @EntityGraph(attributePaths = ["source", "game"])
    fun findAllByActiveTrueOrderByIdAsc(): List<SourceSubscription>

    @EntityGraph(attributePaths = ["source", "game"])
    override fun findAll(): List<SourceSubscription>

    fun existsBySourceIdAndGameId(sourceId: Long, gameId: Long): Boolean
}
