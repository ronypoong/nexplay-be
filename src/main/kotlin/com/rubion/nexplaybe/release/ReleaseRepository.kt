package com.rubion.nexplaybe.release

import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.transaction.annotation.Transactional

interface ReleaseRepository : JpaRepository<Release, Long> {
    @EntityGraph(attributePaths = ["game", "game.developer", "game.publisher", "game.genres", "game.platforms", "game.gameModes"])
    @Query("select distinct r from Release r order by r.releaseDate asc")
    fun findAllForCalendar(): List<Release>

    fun existsByGameIdAndPlatformAndRegion(gameId: Long, platform: String, region: String): Boolean

    @Transactional
    fun deleteAllByGameId(gameId: Long)
}
