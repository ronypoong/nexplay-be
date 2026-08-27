package com.rubion.nexplaybe.config

import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.CorsRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

@Configuration
class WebConfig : WebMvcConfigurer {
    override fun addCorsMappings(registry: CorsRegistry) {
        registry.addMapping("/api/**")
            .allowedOrigins("http://localhost:3003", "http://127.0.0.1:3003")
            .allowedMethods("GET", "OPTIONS")
            .allowedHeaders("*")
            .maxAge(3600)
    }
}
