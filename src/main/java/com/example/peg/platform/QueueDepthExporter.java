package com.example.peg.platform;

import com.example.peg.platform.RuntimeProperties;
import com.example.peg.shared.EventType;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Publishes pgmq queue depths as Micrometer gauges so KEDA / HPA / Prometheus
 * can read them.
 *
 * <p>Runs only on pods that handle the API role — one source of truth for these
 * metrics, not 5 consumer pods racing to publish them.
 *
 * <p>KEDA's postgres scaler can also query {@code pgmq.metrics()} directly
 * without going through Prometheus; see deployment manifests for that pattern.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class QueueDepthExporter {

    private final JdbcTemplate jdbc;
    private final MeterRegistry meterRegistry;
    private final RuntimeProperties runtime;
    private final Map<String, AtomicLong> depthRefs = new HashMap<>();
    private final Map<String, AtomicLong> oldestAgeRefs = new HashMap<>();

    @PostConstruct
    public void registerGauges() {
        if (!runtime.runsApi()) {
            log.info("queue depth exporter disabled — mode={} does not run API role",
                    runtime.getMode());
            return;
        }
        for (EventType type : EventType.values()) {
            AtomicLong depth = new AtomicLong(0);
            AtomicLong age = new AtomicLong(0);
            depthRefs.put(type.queueName(), depth);
            oldestAgeRefs.put(type.queueName(), age);

            meterRegistry.gauge("peg.queue.length",
                    Tags.of("queue", type.queueName()), depth);
            meterRegistry.gauge("peg.queue.oldest_msg_age_seconds",
                    Tags.of("queue", type.queueName()), age);
        }
        log.info("registered queue gauges for {} queues", depthRefs.size());
    }

    @Scheduled(fixedDelay = 10_000)
    public void exportDepths() {
        if (!runtime.runsApi()) return;

        for (EventType type : EventType.values()) {
            try {
                jdbc.query(
                        "SELECT queue_length, COALESCE(oldest_msg_age_sec, 0) AS oldest_age " +
                        "FROM pgmq.metrics(?)",
                        rs -> {
                            if (rs.next()) {
                                AtomicLong d = depthRefs.get(type.queueName());
                                AtomicLong a = oldestAgeRefs.get(type.queueName());
                                if (d != null) d.set(rs.getLong("queue_length"));
                                if (a != null) a.set(rs.getLong("oldest_age"));
                            }
                        }, type.queueName());
            } catch (DataAccessException e) {
                log.warn("failed to read metrics for queue={}: {}",
                        type.queueName(), e.getMessage());
            }
        }
    }
}
