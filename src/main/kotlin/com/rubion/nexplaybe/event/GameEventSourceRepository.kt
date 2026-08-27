package com.rubion.nexplaybe.event

import org.springframework.data.jpa.repository.JpaRepository

interface GameEventSourceRepository : JpaRepository<GameEventSource, Long> {
    fun existsByEventIdAndSourceId(eventId: Long, sourceId: Long): Boolean
}
