package com.demo.keyservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class KeyServiceApplication {
    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(KeyServiceApplication.class);
        application.setAdditionalProfiles("keyservice");
        application.run(args);
    }
}
