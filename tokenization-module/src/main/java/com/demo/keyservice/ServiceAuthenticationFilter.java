package com.demo.keyservice;

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
import java.util.List;
import java.util.Set;

@Component
public final class ServiceAuthenticationFilter extends OncePerRequestFilter {
    private final String writer, verifier, admin, signer;
    public ServiceAuthenticationFilter(@Value("${keyservice.writer-token}") String writer,
            @Value("${keyservice.verifier-token}") String verifier, @Value("${keyservice.admin-token}") String admin,
            @Value("${keyservice.signer-token}") String signer) {
        List<String> tokens = List.of(writer, verifier, admin, signer);
        if (tokens.stream().anyMatch(String::isBlank) || Set.copyOf(tokens).size() != 4)
            throw new IllegalArgumentException("Four distinct nonempty service credentials are required");
        this.writer = writer; this.verifier = verifier; this.admin = admin; this.signer = signer;
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI();
        String method = request.getMethod();
        if ("GET".equals(method) && (path.equals("/health") || path.equals("/actuator/health")
                || path.equals("/v1/checkpoints/public-key") || path.equals("/v1/checkpoints/latest"))) {
            chain.doFilter(request, response); return;
        }
        String supplied = request.getHeader("Authorization");
        if (supplied == null || !supplied.startsWith("Bearer ")) { response.sendError(401); return; }
        String credential = supplied.substring(7);
        boolean permitted = switch (method + " " + path) {
            case "POST /v1/mac/issue" -> equal(credential, writer);
            case "POST /v1/mac/verify" -> equal(credential, verifier);
            case "POST /v1/keys/rotate" -> equal(credential, admin);
            case "POST /v1/checkpoints" -> equal(credential, signer);
            case "GET /v1/issuances/inventory" -> equal(credential, verifier);
            default -> "GET".equals(method) && (path.equals("/v1/issuances") || path.startsWith("/v1/issuances/"))
                    && (equal(credential, writer) || equal(credential, verifier));
        };
        if (!permitted) { response.sendError(403); return; }
        if (request.getContentLengthLong() > 16384) { response.sendError(413); return; }
        chain.doFilter(request, response);
    }
    private static boolean equal(String a, String b) { return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8)); }
}
