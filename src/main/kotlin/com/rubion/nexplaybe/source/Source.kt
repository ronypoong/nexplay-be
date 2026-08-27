package com.rubion.nexplaybe.source

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDate

enum class SourceType { OFFICIAL_API, OFFICIAL_RSS, OFFICIAL_WEB, STRUCTURED_DATA_API, MEDIA }
enum class CollectionMethod { API, RSS, WEB }
enum class PolicyStatus { NEW, LEGAL_REVIEW, ALLOWED, BLOCKED }

@Entity
@Table(name = "source")
class Source(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
    @Column(nullable = false, unique = true, length = 120)
    val slug: String,
    @Column(nullable = false, length = 160)
    val name: String,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    val type: SourceType,
    @Column(name = "base_url", length = 500)
    val baseUrl: String?,
    @Enumerated(EnumType.STRING)
    @Column(name = "collection_method", nullable = false, length = 20)
    val collectionMethod: CollectionMethod,
    @Column(name = "terms_url", length = 500)
    val termsUrl: String?,
    @Enumerated(EnumType.STRING)
    @Column(name = "policy_status", nullable = false, length = 30)
    val policyStatus: PolicyStatus,
    @Column(name = "rate_limit_per_hour", nullable = false)
    val rateLimitPerHour: Int,
    @Column(name = "attribution_rule", length = 500)
    val attributionRule: String?,
    @Column(name = "robots_checked_at")
    val robotsCheckedAt: LocalDate?,
    @Column(name = "last_legal_review_at")
    val lastLegalReviewAt: LocalDate?,
    @Column(nullable = false)
    val official: Boolean,
    @Column(nullable = false)
    val active: Boolean = true,
)
