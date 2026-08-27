package com.rubion.nexplaybe.discovery

import com.rubion.nexplaybe.api.FeedResponse
import com.rubion.nexplaybe.api.GameEventResponse
import com.rubion.nexplaybe.api.GameResponse
import com.rubion.nexplaybe.api.ReleaseResponse
import com.rubion.nexplaybe.api.toResponse
import com.rubion.nexplaybe.event.GameEventRepository
import com.rubion.nexplaybe.game.GameRepository
import com.rubion.nexplaybe.release.ReleaseRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDate

@Service
@Transactional(readOnly = true)
class DiscoveryService(
    private val gameRepository: GameRepository,
    private val eventRepository: GameEventRepository,
    private val releaseRepository: ReleaseRepository,
) {
    private val clock: Clock = Clock.systemUTC()

    fun feed(): FeedResponse = FeedResponse(
        games = gameRepository.findAllForDiscovery().map { it.toResponse() },
        events = eventRepository.findFeedEvents().map { it.toResponse(clock) },
    )

    fun games(platform: String?, genre: String?, query: String?): List<GameResponse> =
        gameRepository.findAllForDiscovery()
            .asSequence()
            .filter { platform.isNullOrBlank() || it.platforms.any { value -> value.equals(platform, true) } }
            .filter { genre.isNullOrBlank() || it.genres.any { value -> value.equals(genre, true) } }
            .filter {
                query.isNullOrBlank() || listOf(it.title, it.developer.name, it.publisher.name)
                    .plus(it.genres).plus(it.platforms)
                    .any { value -> value.contains(query, true) }
            }
            .map { it.toResponse() }
            .toList()

    fun game(slug: String): GameResponse = gameRepository.findBySlug(slug)?.toResponse()
        ?: throw ResourceNotFoundException("Game not found: $slug")

    fun events(slug: String): List<GameEventResponse> {
        if (gameRepository.findBySlug(slug) == null) throw ResourceNotFoundException("Game not found: $slug")
        return eventRepository.findByGameSlug(slug).map { it.toResponse(clock) }
    }

    fun releases(from: LocalDate?, to: LocalDate?, platform: String?): List<ReleaseResponse> =
        releaseRepository.findAllForCalendar()
            .asSequence()
            .filter { from == null || !it.releaseDate.isBefore(from) }
            .filter { to == null || !it.releaseDate.isAfter(to) }
            .filter { platform.isNullOrBlank() || it.platform.equals(platform, true) }
            .map { it.toResponse() }
            .toList()
}

class ResourceNotFoundException(message: String) : RuntimeException(message)
