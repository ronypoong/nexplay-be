package com.rubion.nexplaybe.rawitem

import org.springframework.data.jpa.repository.JpaRepository

interface RawItemRepository : JpaRepository<RawItem, Long> {
    fun existsBySourceIdAndExternalId(sourceId: Long, externalId: String): Boolean
}
