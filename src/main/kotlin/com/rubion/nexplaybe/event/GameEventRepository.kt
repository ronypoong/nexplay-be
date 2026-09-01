package com.rubion.nexplaybe.event

import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDate

interface GameEventRepository : JpaRepository<GameEvent, Long> {
    @EntityGraph(attributePaths = ["game", "sources", "sources.source"])
    @Query("select distinct e from GameEvent e order by e.publishedAt desc")
    fun findFeedEvents(): List<GameEvent>

    @EntityGraph(attributePaths = ["game", "sources", "sources.source"])
    @Query("select distinct e from GameEvent e where e.id in :ids")
    fun findFeedEventsByIds(@Param("ids") ids: Collection<Long>): List<GameEvent>

    @EntityGraph(attributePaths = ["game", "sources", "sources.source"])
    @Query("select distinct e from GameEvent e where e.game.slug = :slug order by e.publishedAt desc")
    fun findByGameSlug(@Param("slug") slug: String): List<GameEvent>

    /**
     * 데모·베타 화면이 쓴다. 기간이 지나면 못 해 보는 것들이라 최근 것만 본다.
     * 전체 피드를 받아 코틀린에서 거르면 1만 건을 매번 끌고 오게 된다.
     */
    @EntityGraph(attributePaths = ["game", "sources", "sources.source"])
    @Query("select distinct e from GameEvent e where e.type in :types and e.eventDate >= :since order by e.publishedAt desc")
    fun findByTypesSince(
        @Param("types") types: Collection<GameEventType>,
        @Param("since") since: LocalDate,
    ): List<GameEvent>

    @EntityGraph(attributePaths = ["game", "sources", "sources.source"])
    @Query("select distinct e from GameEvent e where e.game.id = :gameId and e.type = :type and e.eventDate between :from and :to")
    fun findMergeCandidates(
        @Param("gameId") gameId: Long,
        @Param("type") type: GameEventType,
        @Param("from") from: LocalDate,
        @Param("to") to: LocalDate,
    ): List<GameEvent>
}
