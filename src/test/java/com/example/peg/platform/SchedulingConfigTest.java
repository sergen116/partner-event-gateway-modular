package com.example.peg.platform;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import static org.assertj.core.api.Assertions.assertThat;

class SchedulingConfigTest {

    @Test
    void taskScheduler_isInitializedAndCanScheduleWork() throws Exception {
        TaskScheduler scheduler = new SchedulingConfig().taskScheduler();
        assertThat(scheduler).isInstanceOf(ThreadPoolTaskScheduler.class);
        ThreadPoolTaskScheduler ts = (ThreadPoolTaskScheduler) scheduler;
        assertThat(ts.getThreadNamePrefix()).isEqualTo("peg-sched-");
        // Submitting a runnable should not throw — the scheduler is initialized
        ts.execute(() -> {});
        ts.shutdown();
    }
}
