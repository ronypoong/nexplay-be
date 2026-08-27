package com.rubion.nexplaybe.collector

import com.rubion.nexplaybe.game.Game
import com.rubion.nexplaybe.source.Source
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
@Table(name = "source_subscription")
class SourceSubscription(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_id", nullable = false)
    val source: Source,
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "game_id", nullable = false)
    val game: Game,
    @Column(name = "external_game_id", nullable = false, length = 100)
    val externalGameId: String,
    @Column(name = "feed_url", nullable = false, length = 700)
    val feedUrl: String,
    @Column(nullable = false)
    val active: Boolean = true,
)
