package com.demo.transferapp.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
public class ApiAuthorization extends OncePerRequestFilter {
    private final byte[] expected;

    public ApiAuthorization(@Value("${demo.api-token}") String token) {
        if (token == null || token.length() < 32) {
            throw new IllegalArgumentException("APP_API_TOKEN must be at least 32 characters");
        }
        expected = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getRequestURI().equals("/health") && request.getMethod().equals("GET")) {
            chain.doFilter(request, response);
            return;
        }
        String presented = request.getHeader("Authorization");
        if (presented == null
                || !MessageDigest.isEqual(expected, presented.getBytes(StandardCharsets.UTF_8))) {
            response.setStatus(401);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Application credential required\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
