package com.demo.securetransfer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class SecureTransferDemoApplication {
    public static void main(String[] args) {
        SpringApplication.run(SecureTransferDemoApplication.class, args);
    }
}
