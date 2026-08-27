package com.rubion.nexplaybe.company

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table

enum class CompanyType { DEVELOPER, PUBLISHER, PLATFORM, MIXED, UNKNOWN }

@Entity
@Table(name = "company")
class Company(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
    @Column(nullable = false, unique = true, length = 120)
    val slug: String,
    @Column(nullable = false, length = 160)
    val name: String,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    val type: CompanyType = CompanyType.MIXED,
    @Column(length = 80)
    val country: String? = null,
    @Column(name = "official_url", length = 500)
    val officialUrl: String? = null,
    @Column(name = "wikidata_id", unique = true, length = 30)
    val wikidataId: String? = null,
    @Column(nullable = false)
    val major: Boolean = false,
)
