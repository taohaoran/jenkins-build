package com.demo.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

@RestController
public class HealthController {

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
            "status", "UP",
            "service", "java-app",
            "timestamp", Instant.now().toString()
        );
    }

    @GetMapping("/")
    public Map<String, String> root() {
        return Map.of("message", "Hello from Java App");
    }
}
