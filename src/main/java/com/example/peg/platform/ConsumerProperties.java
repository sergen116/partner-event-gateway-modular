package com.example.peg.platform;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

/**
 * Tuning for consumer workers. Per-queue concurrency caps the number of
 * virtual threads that can hold a Hikari connection at once for a given queue.
 * Tune by I/O profile of the event type.
 */
@ConfigurationProperties("app.consumer")
@Data
public class ConsumerProperties {

    private int pollIntervalMs = 500;
    private int busyPollIntervalMs = 20;
    private Map<String, Integer> batchSize = new HashMap<>();
    private int visibilityTimeoutSeconds = 30;
    private int maxAttempts = 5;
    private Map<String, Integer> concurrency = new HashMap<>();

    public int concurrencyFor(String queueName) {
        return concurrency.getOrDefault(queueName, 4);
    }

    public int batchSizeFor(String queueName) {
        return batchSize.getOrDefault(queueName, 10);
    }
}
