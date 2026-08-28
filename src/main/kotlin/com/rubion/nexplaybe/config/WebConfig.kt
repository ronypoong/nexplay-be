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
            // 기대 투표가 POST 다. 그 외 쓰기는 관리 API 뿐이고 그쪽은 토큰으로 막혀 있다.
            .allowedMethods("GET", "POST", "OPTIONS")
            .allowedHeaders("*")
            .maxAge(3600)
    }
}
