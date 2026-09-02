package com.rubion.nexplaybe.collector

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.jpa.repository.JpaRepository

interface SourceSubscriptionRepository : JpaRepository<SourceSubscription, Long> {
    @EntityGraph(attributePaths = ["source", "game"])
    fun findAllByActiveTrueOrderByIdAsc(): List<SourceSubscription>

    /**
     * 수집기가 한 묶음씩 가져갈 때 쓴다.
     *
     * 번호로 끊는다(OFFSET 이 아니라 id > afterId). OFFSET 은 뒤로 갈수록 앞의
     * 행을 세고 지나가야 해서 마지막 묶음이 제일 비싸고, 도는 동안 구독이
     * 추가되면 경계가 밀려 한 건을 건너뛰거나 두 번 본다.
     */
    @EntityGraph(attributePaths = ["source", "game"])
    fun findByActiveTrueAndSourceIdAndIdGreaterThanOrderByIdAsc(
        sourceId: Long,
        afterId: Long,
        pageable: Pageable,
    ): List<SourceSubscription>

    fun countByActiveTrueAndSourceId(sourceId: Long): Long

    @EntityGraph(attributePaths = ["source", "game"])
    override fun findAll(): List<SourceSubscription>

    fun existsBySourceIdAndGameId(sourceId: Long, gameId: Long): Boolean
}
