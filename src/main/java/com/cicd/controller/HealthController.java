package com.cicd.controller;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class HealthController implements HealthIndicator {

    @GetMapping("/hello")
    public ResponseEntity<Map<String, String>> hello() {
        return ResponseEntity.ok(Map.of(
                "message", "CI/CD Pipeline Demo",
                "status", "running"
        ));
    }

    @GetMapping("/version")
    public ResponseEntity<Map<String, String>> version() {
        return ResponseEntity.ok(Map.of(
                "version", "1.0.0",
                "buildTime", System.getenv().getOrDefault("BUILD_TIME", "local")
        ));
    }

    @Override
    public Health health() {
        return Health.up()
                .withDetail("pipeline", "operational")
                .build();
    }
}
