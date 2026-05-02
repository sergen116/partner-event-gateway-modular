package com.example.peg.platform;

import com.example.peg.delivery.PgmqWorker;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;

import java.time.Duration;
import java.util.List;

/**
 * Registers each active worker's {@link PgmqWorker#poll()} on the application
 * {@link TaskScheduler}. Replaces {@code @Scheduled} annotations on workers
 * because workers are now built dynamically by mode rather than picked up as
 * {@code @Component} beans.
 */
@Configuration
@Slf4j
@RequiredArgsConstructor
public class WorkerScheduler {

    private final List<PgmqWorker> workers;
    private final TaskScheduler taskScheduler;
    private final ConsumerProperties consumerProps;

    @PostConstruct
    public void scheduleAll() {
        Duration delay = Duration.ofMillis(consumerProps.getPollIntervalMs());
        for (PgmqWorker w : workers) {
            taskScheduler.scheduleWithFixedDelay(w::poll, delay);
            log.info("scheduled poll loop queue={} interval={}ms",
                    w.queueName(), delay.toMillis());
        }
    }
}
