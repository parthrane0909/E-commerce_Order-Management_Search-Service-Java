package com.meridian.backend;

import com.meridian.backend.clients.ElasticsearchClientWrapper;
import com.meridian.backend.clients.MongoClientWrapper;
import com.meridian.backend.clients.RabbitMQClient;
import com.meridian.backend.common.dto.HealthDependency;
import com.meridian.backend.common.dto.HealthResponse;
import com.meridian.backend.core.config.AppConfig;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class HealthController {

    private final DataSource dataSource;
    private final MongoClientWrapper mongoClient;
    private final ElasticsearchClientWrapper elasticsearchClient;
    private final RabbitMQClient rabbitMQClient;
    private final AppConfig appConfig;

    @GetMapping("/health")
    public ResponseEntity<HealthResponse> health() {
        Map<String, HealthDependency> dependencies = new HashMap<>();
        boolean allUp = true;

        // PostgreSQL
        Instant start = Instant.now();
        boolean pgUp = checkPostgres();
        dependencies.put("postgresql", HealthDependency.builder().status(pgUp ? "up" : "down").latencyMs(Duration.between(start, Instant.now()).toMillis()).build());
        allUp &= pgUp;

        // MongoDB
        start = Instant.now();
        boolean mongoUp = checkMongo();
        dependencies.put("mongodb", HealthDependency.builder().status(mongoUp ? "up" : "down").latencyMs(Duration.between(start, Instant.now()).toMillis()).build());
        allUp &= mongoUp;

        // Elasticsearch
        start = Instant.now();
        boolean esUp = checkElasticsearch();
        dependencies.put("elasticsearch", HealthDependency.builder().status(esUp ? "up" : "down").latencyMs(Duration.between(start, Instant.now()).toMillis()).build());
        allUp &= esUp;

        // RabbitMQ
        start = Instant.now();
        boolean rabbitUp = checkRabbitMQ();
        dependencies.put("rabbitmq", HealthDependency.builder().status(rabbitUp ? "up" : "down").latencyMs(Duration.between(start, Instant.now()).toMillis()).build());
        allUp &= rabbitUp;

        HealthResponse response = HealthResponse.builder()
            .status(allUp ? "ok" : "degraded")
            .environment(appConfig.getEnvironment())
            .dependencies(dependencies)
            .build();

        return ResponseEntity.status(allUp ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE).body(response);
    }

    private boolean checkPostgres() {
        try (var conn = dataSource.getConnection()) {
            return conn.isValid(2);
        } catch (Exception ex) {
            return false;
        }
    }

    private boolean checkMongo() {
        return mongoClient.ping();
    }

    private boolean checkElasticsearch() {
        return elasticsearchClient.ping();
    }

    private boolean checkRabbitMQ() {
        return rabbitMQClient.ping();
    }
}