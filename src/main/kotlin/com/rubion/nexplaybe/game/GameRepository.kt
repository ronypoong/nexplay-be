package com.rubion.nexplaybe.game

import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface GameRepository : JpaRepository<Game, Long> {
    @EntityGraph(attributePaths = ["developer", "publisher", "genres", "platforms", "gameModes"])
    @Query("select distinct g from Game g where g.archiveOnly = false order by g.featured desc, case when g.developer.major = true or g.publisher.major = true then 0 else 1 end, g.discoveryScore desc, g.releaseDate asc")
    fun findAllForDiscovery(): List<Game>

    /** 검색용. 아카이브 전용 게임도 포함한다 — 목록에서 빼는 것과 못 찾게 하는 것은 다르다. */
    @EntityGraph(attributePaths = ["developer", "publisher", "genres", "platforms", "gameModes"])
    @Query("select distinct g from Game g order by g.featured desc, g.discoveryScore desc, g.releaseDate asc")
    fun findAllForSearch(): List<Game>

    @EntityGraph(attributePaths = ["developer", "publisher", "genres", "platforms", "gameModes"])
    @Query("select distinct g from Game g where g.id in :ids")
    fun findAllForDiscoveryByIds(@Param("ids") ids: Collection<Long>): List<Game>

    @EntityGraph(attributePaths = ["developer", "publisher", "genres", "platforms", "gameModes"])
    fun findBySlug(slug: String): Game?

    @EntityGraph(attributePaths = ["developer", "publisher", "genres", "platforms", "gameModes"])
    fun findByWikidataId(wikidataId: String): Game?

    fun findBySteamAppId(steamAppId: Long): Game?
}
