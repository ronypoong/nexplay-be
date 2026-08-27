package com.rubion.nexplaybe.event

import com.rubion.nexplaybe.game.Game
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.OneToMany
import jakarta.persistence.OrderBy
import jakarta.persistence.Table
import java.time.Instant
import java.time.LocalDate

enum class GameEventType { ANNOUNCEMENT, TRAILER, GAMEPLAY, RELEASE_DATE, DELAY, RELEASE, EARLY_ACCESS, BETA, DEMO, DLC, EXPANSION, MAJOR_UPDATE, PATCH, CANCELLATION }

@Entity
@Table(name = "game_event")
class GameEvent(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "game_id", nullable = false)
    val game: Game,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    val type: GameEventType,
    @Column(nullable = false, length = 240)
    val title: String,
    @Column(nullable = false, columnDefinition = "TEXT")
    val summary: String,
    @Column(name = "event_date", nullable = false)
    val eventDate: LocalDate,
    @Column(name = "published_at", nullable = false)
    val publishedAt: Instant,
    @OneToMany(mappedBy = "event", fetch = FetchType.LAZY)
    @OrderBy("id ASC")
    val sources: MutableList<GameEventSource> = mutableListOf(),
)
