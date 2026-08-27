package com.rubion.nexplaybe.collector

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

enum class CollectorRunStatus { RUNNING, SUCCESS, PARTIAL_FAILURE, FAILED }

@Entity
@Table(name = "collector_run")
class CollectorRun(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_id", nullable = false)
    val source: Source,
    @Column(name = "started_at", nullable = false)
    val startedAt: Instant,
    @Column(name = "finished_at")
    var finishedAt: Instant? = null,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    var status: CollectorRunStatus = CollectorRunStatus.RUNNING,
    @Column(name = "fetched_count", nullable = false)
    var fetchedCount: Int = 0,
    @Column(name = "new_item_count", nullable = false)
    var newItemCount: Int = 0,
    @Column(name = "event_count", nullable = false)
    var eventCount: Int = 0,
    @Column(name = "error_message", length = 1000)
    var errorMessage: String? = null,
)
