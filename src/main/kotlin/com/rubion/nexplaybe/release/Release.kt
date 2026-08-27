package com.rubion.nexplaybe.release

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
import jakarta.persistence.Table
import java.time.LocalDate

enum class ReleaseStatus { CONFIRMED, EXPECTED, DELAYED, RELEASED }

@Entity
@Table(name = "game_release")
class Release(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "game_id", nullable = false)
    val game: Game,
    @Column(nullable = false, length = 40)
    val platform: String,
    @Column(name = "release_date", nullable = false)
    val releaseDate: LocalDate,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    val status: ReleaseStatus,
    @Column(nullable = false, length = 20)
    val region: String = "GLOBAL",
)
