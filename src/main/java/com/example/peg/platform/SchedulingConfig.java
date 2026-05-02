package com.example.peg.platform;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Spring's default TaskScheduler is single-threaded, which would serialize
 * all 5 per-queue poll loops plus the outbox poller and metrics exporter.
 * The custom bean lets every scheduled task run concurrently.
 *
 * <p>Message processing itself runs on per-worker virtual-thread executors —
 * this scheduler only handles the poll cadence, not the actual work.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {

    @Bean
    public TaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler s = new ThreadPoolTaskScheduler();
        s.setPoolSize(10);                       // 5 workers + outbox + metrics + headroom
        s.setThreadNamePrefix("peg-sched-");
        s.setWaitForTasksToCompleteOnShutdown(true);
        // VT (30s) + 5s buffer — let in-flight polls drain before forced shutdown.
        s.setAwaitTerminationSeconds(35);
        s.initialize();
        return s;
    }
}
