package com.rubion.nexplaybe.api

import com.rubion.nexplaybe.discovery.DiscoveryService
import com.rubion.nexplaybe.metadata.ExtendedGameMetadataService
import jakarta.validation.constraints.Size
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

@RestController
@RequestMapping("/api/v1")
class DiscoveryController(
    private val discoveryService: DiscoveryService,
    private val metadataService: ExtendedGameMetadataService,
) {
    @GetMapping("/feed")
    fun feed() = discoveryService.feed()

    @GetMapping("/games")
    fun games(
        @RequestParam(required = false) platform: String?,
        @RequestParam(required = false) genre: String?,
        @RequestParam(required = false, name = "q") @Size(max = 100) query: String?,
    ) = discoveryService.games(platform, genre, query)

    @GetMapping("/games/{slug}")
    fun game(@PathVariable slug: String) = discoveryService.game(slug)

    @GetMapping("/games/{slug}/events")
    fun events(@PathVariable slug: String) = discoveryService.events(slug)

    @GetMapping("/games/{slug}/metadata")
    fun metadata(@PathVariable slug: String) = metadataService.get(slug)

    @GetMapping("/releases", "/calendar")
    fun releases(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
        @RequestParam(required = false) platform: String?,
    ) = discoveryService.releases(from, to, platform)

    @GetMapping("/trending", "/discover")
    fun discover(
        @RequestParam(required = false) platform: String?,
        @RequestParam(required = false) genre: String?,
    ) = discoveryService.games(platform, genre, null)
}
