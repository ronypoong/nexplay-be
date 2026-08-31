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
    // Steam 소개문으로 갱신되므로 var 다. 사람이 손본 문구는 CatalogSyncService 가 덮지 않는다.
    @Column(nullable = false, length = 240)
    var tagline: String,
    @Column(nullable = false, columnDefinition = "TEXT")
    var description: String,
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "developer_id", nullable = false)
    // 발표 직후에는 Wikidata 가 개발사를 모르는 일이 흔하다. 나중에 스토어가
    // 알려주면 고쳐 넣어야 하므로 var 다.
    var developer: Company,
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "publisher_id", nullable = false)
    var publisher: Company,
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
    /**
     * 대표 이미지. Steam 확장 수집이 나중에 채울 수 있어야 해서 var 다.
     * val 이면 같은 트랜잭션에서 엔티티를 고칠 때 Hibernate 가 옛 값으로 되돌린다.
     */
    var coverImageUrl: String? = null,
    @Column(name = "image_source", length = 40)
    // 표지를 나중에 채우면 출처도 같이 바뀐다. val 이면 화면에는 새 그림이,
    // 기록에는 옛 출처가 남는다.
    var imageSource: String? = null,
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
    // GOTY 아카이브로 들어온 과거 수상작. 신작 화면(홈·디스커버·캘린더)에는 나오지 않는다.
    @Column(name = "archive_only", nullable = false)
    val archiveOnly: Boolean = false,
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
