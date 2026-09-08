package com.demo.securityapp.controller;

import com.demo.securityapp.notification.NotificationDispatcher;
import com.demo.securityapp.notification.NotificationStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Operator-only observability; this endpoint never triggers delivery or changes recipients. */
@RestController
public class NotificationController {
    private final NotificationDispatcher dispatcher;

    public NotificationController(NotificationDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @GetMapping("/internal/notifications/status")
    NotificationStatus status() {
        return dispatcher.status();
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, String>> unavailable() {
        return ResponseEntity.status(503).body(Map.of("error", "Notification status unavailable"));
    }
}
