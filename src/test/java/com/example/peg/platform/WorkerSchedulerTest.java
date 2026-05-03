package com.example.peg.platform;

import com.example.peg.delivery.PgmqWorker;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkerSchedulerTest {

    @Test
    void scheduleAll_registersOneFixedDelayPerWorker() {
        TaskScheduler scheduler = mock(TaskScheduler.class);
        ConsumerProperties props = new ConsumerProperties();
        props.setPollIntervalMs(500);

        PgmqWorker w1 = mock(PgmqWorker.class);
        PgmqWorker w2 = mock(PgmqWorker.class);
        when(w1.queueName()).thenReturn("q1");
        when(w2.queueName()).thenReturn("q2");

        new WorkerScheduler(List.of(w1, w2), scheduler, props).scheduleAll();

        verify(scheduler, times(2)).scheduleWithFixedDelay(any(Runnable.class), any(java.time.Duration.class));
    }

    @Test
    void scheduleAll_isNoOp_whenNoWorkers() {
        TaskScheduler scheduler = mock(TaskScheduler.class);
        new WorkerScheduler(List.of(), scheduler, new ConsumerProperties()).scheduleAll();
        verify(scheduler, times(0)).scheduleWithFixedDelay(any(Runnable.class), any(java.time.Duration.class));
    }
}
