package com.paymesh.provider.web;

import com.paymesh.provider.ProviderSettings;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
public class AuthenticationFilter extends OncePerRequestFilter {
    private final ProviderSettings settings;
    public AuthenticationFilter(ProviderSettings settings) { this.settings = settings; }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        if (path.equals("/health")) { chain.doFilter(request, response); return; }
        boolean admin = path.startsWith("/__sim/");
        String provided = admin ? request.getHeader("X-Simulator-Admin-Key")
                : settings.kind().equals("adyen") ? request.getHeader("X-API-Key") : request.getHeader("Authorization");
        String expected = admin ? settings.adminKey() : settings.kind().equals("adyen")
                ? settings.apiKey() : "Bearer " + settings.apiKey();
        if (provided == null || !MessageDigest.isEqual(provided.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8))) {
            response.setStatus(401);
            response.setContentType("application/json");
            response.getWriter().write("{\"code\":\"unauthorized\",\"message\":\"Valid local simulator credentials required\"}");
            return;
        }
        response.setHeader("Cache-Control", "no-store");
        chain.doFilter(request, response);
    }
}
