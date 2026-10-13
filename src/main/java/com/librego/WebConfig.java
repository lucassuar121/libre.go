package com.librego;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS. Por defecto el frontend se sirve desde la misma aplicación (mismo origen), así que no hace falta nada.
 * Si publicás el frontend en OTRO dominio, definí CORS_ALLOWED_ORIGINS (lista separada por comas, solo https://...)
 * y, para que la cookie de sesión viaje entre sitios, SESSION_COOKIE_SAMESITE=none (requiere HTTPS).
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {
    @Value("${app.cors.allowed-origins:}") private String origins;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        if (origins == null || origins.isBlank()) return;
        registry.addMapping("/api/**")
                .allowedOrigins(origins.trim().split("\\s*,\\s*"))
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("Content-Type", "X-Company")
                .allowCredentials(true)
                .maxAge(3600);
    }
}
