package com.librego;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** Cabeceras de seguridad básicas y sin caché para las respuestas de la API (datos privados). */
@Component
public class SecurityHeadersFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        res.setHeader("X-Content-Type-Options", "nosniff");
        res.setHeader("X-Frame-Options", "DENY");
        res.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");
        if (req.isSecure()) res.setHeader("Strict-Transport-Security", "max-age=31536000");
        if (req.getRequestURI().startsWith("/api/")) res.setHeader("Cache-Control", "no-store");
        chain.doFilter(req, res);
    }
}
