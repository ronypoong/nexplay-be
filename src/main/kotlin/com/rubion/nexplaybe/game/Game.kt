package com.rubion.nexplaybe.game

import com.rubion.nexplaybe.company.Company
import jakarta.persistence.Column
import jakarta.persistence.ElementCollection
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
import java.math.BigDecimal
import java.time.LocalDate

enum class GameStatus { AVAILABLE, UPCOMING, TBA }

@Entity
@Table(name = "game")
class Game(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
    @Column(nullable = false, unique = true, length = 120)
    val slug: String,
    @Column(name = "original_title", length = 180)
    val originalTitle: String? = null,
    @Column(nullable = false, length = 180)
    val title: String,
    @Column(nullable = false, length = 240)
    val tagline: String,
    @Column(nullable = false, columnDefinition = "TEXT")
    val description: String,
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "developer_id", nullable = false)
    val developer: Company,
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "publisher_id", nullable = false)
    val publisher: Company,
    @Column(name = "release_date")
    val releaseDate: LocalDate?,
    @Column(name = "official_url", length = 500)
    val officialUrl: String? = null,
    @Column(name = "steam_app_id", unique = true)
    val steamAppId: Long? = null,
    @Column(name = "wikidata_id", unique = true, length = 30)
    val wikidataId: String? = null,
    @Column(name = "catalog_source", nullable = false, length = 40)
    val catalogSource: String = "MANUAL",
    @Column(name = "cover_image_url", length = 1000)
    val coverImageUrl: String? = null,
    @Column(name = "image_source", length = 40)
    val imageSource: String? = null,
    @Column(name = "korean_text_supported")
    var koreanTextSupported: Boolean? = null,
    @Column(name = "korean_audio_supported")
    var koreanAudioSupported: Boolean? = null,
    @Column(name = "release_label", nullable = false, length = 80)
    val releaseLabel: String,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    val status: GameStatus,
    @Column(name = "discovery_score", nullable = false, precision = 5, scale = 2)
    val discoveryScore: BigDecimal,
    @Column(name = "anticipation_score", nullable = false, precision = 5, scale = 2)
    val anticipationScore: BigDecimal = discoveryScore,
    @Column(name = "follower_count", nullable = false)
    val followerCount: Long,
    @Column(nullable = false, length = 20)
    val accent: String,
    @Column(name = "accent_secondary", nullable = false, length = 20)
    val accentSecondary: String,
    @Column(nullable = false, length = 12)
    val symbol: String,
    @Column(nullable = false)
    val featured: Boolean = false,
    @ElementCollection(fetch = FetchType.LAZY)
    @jakarta.persistence.CollectionTable(name = "game_genre", joinColumns = [JoinColumn(name = "game_id")])
    @Column(name = "genre", nullable = false, length = 60)
    val genres: MutableSet<String> = linkedSetOf(),
    @ElementCollection(fetch = FetchType.LAZY)
    @jakarta.persistence.CollectionTable(name = "game_platform", joinColumns = [JoinColumn(name = "game_id")])
    @Column(name = "platform", nullable = false, length = 40)
    val platforms: MutableSet<String> = linkedSetOf(),
    @ElementCollection(fetch = FetchType.LAZY)
    @jakarta.persistence.CollectionTable(name = "game_mode", joinColumns = [JoinColumn(name = "game_id")])
    @Column(name = "mode", nullable = false, length = 60)
    val gameModes: MutableSet<String> = linkedSetOf(),
)
