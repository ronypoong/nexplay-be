package com.rubion.nexplaybe.rawitem

import com.rubion.nexplaybe.game.Game
import com.rubion.nexplaybe.source.Source
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
import java.time.Instant

enum class ProcessingStatus { NEW, PROCESSED, FAILED }

@Entity
@Table(name = "raw_item")
class RawItem(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_id", nullable = false)
    val source: Source,
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "game_id", nullable = false)
    val game: Game,
    @Column(name = "external_id", nullable = false, length = 255)
    val externalId: String,
    @Column(name = "source_url", nullable = false, length = 700)
    val sourceUrl: String,
    @Column(nullable = false, length = 500)
    val title: String,
    @Column(name = "published_at", nullable = false)
    val publishedAt: Instant,
    @Column(name = "raw_payload", columnDefinition = "LONGTEXT")
    val rawPayload: String?,
    @Column(name = "content_hash", nullable = false, length = 64)
    val contentHash: String,
    @Column(name = "fetched_at", nullable = false)
    val fetchedAt: Instant,
    @Column(name = "expires_at", nullable = false)
    val expiresAt: Instant,
    @Enumerated(EnumType.STRING)
    @Column(name = "processing_status", nullable = false, length = 30)
    var processingStatus: ProcessingStatus = ProcessingStatus.NEW,
    @Column(name = "processed_at")
    var processedAt: Instant? = null,
)
