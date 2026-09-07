package com.demo.securityapp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class SecurityApplication {
    public static void main(String[] args) {
        System.setProperty("spring.config.name", "security-application");
        SpringApplication.run(SecurityApplication.class, args);
    }
}
