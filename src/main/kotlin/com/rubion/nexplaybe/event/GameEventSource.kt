package com.rubion.nexplaybe.event

import com.rubion.nexplaybe.source.Source
import com.rubion.nexplaybe.rawitem.RawItem
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table

@Entity
@Table(name = "game_event_source")
class GameEventSource(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "game_event_id", nullable = false)
    val event: GameEvent,
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_id", nullable = false)
    val source: Source,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "raw_item_id")
    val rawItem: RawItem? = null,
    @Column(name = "source_url", nullable = false, length = 700)
    val sourceUrl: String,
    @Column(name = "is_official", nullable = false)
    val isOfficial: Boolean,
)
