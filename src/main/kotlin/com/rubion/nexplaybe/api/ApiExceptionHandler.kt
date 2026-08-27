package com.rubion.nexplaybe.api

import com.rubion.nexplaybe.discovery.ResourceNotFoundException
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.time.Instant

data class ApiError(val timestamp: Instant, val status: Int, val error: String, val message: String, val path: String)

@RestControllerAdvice
class ApiExceptionHandler {
    @ExceptionHandler(ResourceNotFoundException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun notFound(exception: ResourceNotFoundException, request: HttpServletRequest) =
        ApiError(Instant.now(), 404, "Not Found", exception.message ?: "Resource not found", request.requestURI)
}
