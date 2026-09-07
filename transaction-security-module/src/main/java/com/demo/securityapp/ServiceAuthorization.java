package com.demo.securityapp;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
public class ServiceAuthorization extends OncePerRequestFilter {
    private final byte[] app;
    private final byte[] admin;
    public ServiceAuthorization(Environment env) {
        app=token(env,"processor.app-token");admin=token(env,"processor.admin-token");
        if(MessageDigest.isEqual(app,admin))throw new IllegalArgumentException("Processor app/admin credentials must differ");
    }
    private byte[] token(Environment env,String name){String value=env.getRequiredProperty(name);if(value.length()<24)throw new IllegalArgumentException("Service credential must have at least 24 characters");return ("Bearer "+value).getBytes(StandardCharsets.UTF_8);}
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException {
        String path=request.getRequestURI();
        if(path.equals("/health")){chain.doFilter(request,response);return;}
        String raw=request.getHeader("Authorization");byte[] supplied=(raw==null?"":raw).getBytes(StandardCharsets.UTF_8);
        boolean isAdmin=MessageDigest.isEqual(supplied,admin);
        boolean adminOnly=path.endsWith("/hold")||path.startsWith("/internal/test/");
        if(!isAdmin && (adminOnly||!MessageDigest.isEqual(supplied,app))){response.setStatus(401);response.setContentType("application/json");response.getWriter().write("{\"error\":\"Unauthorized service credential\"}");return;}
        chain.doFilter(request,response);
    }
}
