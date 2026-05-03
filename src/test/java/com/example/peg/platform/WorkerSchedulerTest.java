package com.example.peg.platform;

import com.example.peg.delivery.PgmqWorker;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkerSchedulerTest {

    @Test
    void scheduleAll_startsEachWorker() {
        PgmqWorker w1 = mock(PgmqWorker.class);
        PgmqWorker w2 = mock(PgmqWorker.class);
        when(w1.queueName()).thenReturn("q1");
        when(w2.queueName()).thenReturn("q2");

        new WorkerScheduler(List.of(w1, w2)).scheduleAll();

        verify(w1, times(1)).start();
        verify(w2, times(1)).start();
    }

    @Test
    void scheduleAll_isNoOp_whenNoWorkers() {
        new WorkerScheduler(List.of()).scheduleAll();
    }
}
