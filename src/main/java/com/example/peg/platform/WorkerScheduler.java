package com.example.peg.platform;

import com.example.peg.delivery.PgmqWorker;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Starts each active worker's work-conserving poll loop. Workers self-pace:
 * after a full batch they re-poll on the busy interval; after a partial or
 * empty batch they back off to the full poll interval.
 */
@Configuration
@Slf4j
@RequiredArgsConstructor
public class WorkerScheduler {

    private final List<PgmqWorker> workers;

    @PostConstruct
    public void scheduleAll() {
        for (PgmqWorker w : workers) {
            w.start();
        }
    }
}
