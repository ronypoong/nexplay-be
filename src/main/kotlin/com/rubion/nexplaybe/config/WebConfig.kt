package com.rubion.nexplaybe.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.CorsRegistry
import com.rubion.nexplaybe.cache.AdminWriteCacheInterceptor
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

@Configuration
class WebConfig(
    // 배포 시 NEXPLAY_CORS_ALLOWED_ORIGINS 로 프론트 도메인을 넘긴다. 예전에는 localhost 가 코드에 박혀 있었다.
    @param:Value("\${nexplay.cors.allowed-origins:http://localhost:3003,http://127.0.0.1:3003}") private val allowedOrigins: List<String>,
    private val adminWriteCacheInterceptor: AdminWriteCacheInterceptor,
) : WebMvcConfigurer {
    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(adminWriteCacheInterceptor).addPathPatterns("/api/v1/admin/**")
    }

    override fun addCorsMappings(registry: CorsRegistry) {
        registry.addMapping("/api/**")
            .allowedOrigins(*allowedOrigins.toTypedArray())
            .allowedMethods("GET", "OPTIONS")
            .allowedHeaders("*")
            .maxAge(3600)
    }
}
