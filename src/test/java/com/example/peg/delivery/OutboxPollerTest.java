package com.example.peg.delivery;

import com.example.peg.platform.RuntimeProperties;
import com.example.peg.query.EventRepository;
import com.example.peg.query.OutboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutboxPollerTest {

    private OutboxRepository outbox;
    private EventRepository events;
    private JdbcTemplate jdbc;
    private RuntimeProperties runtime;
    private TaskScheduler scheduler;
    private PlatformTransactionManager txManager;
    private OutboxPoller poller;

    @BeforeEach
    void setUp() {
        outbox = mock(OutboxRepository.class);
        events = mock(EventRepository.class);
        jdbc = mock(JdbcTemplate.class);
        runtime = new RuntimeProperties();
        scheduler = mock(TaskScheduler.class);
        txManager = mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        poller = new OutboxPoller(outbox, events, jdbc, runtime, scheduler, txManager);
    }

    @Test
    void start_skipsScheduling_inConsumerOnlyMode() {
        runtime.setMode(RuntimeProperties.Mode.CONSUMER_ORDER_CREATED);
        poller.start();
        verify(scheduler, never()).scheduleWithFixedDelay(any(Runnable.class), any(java.time.Duration.class));
    }

    @Test
    void start_schedulesPolling_inApiMode() {
        runtime.setMode(RuntimeProperties.Mode.API);
        poller.start();
        verify(scheduler, times(1)).scheduleWithFixedDelay(any(Runnable.class), any(java.time.Duration.class));
    }

    @Test
    void drain_returnsZero_whenNoRows() {
        when(outbox.claimBatch(anyInt())).thenReturn(List.of());
        int n = poller.drain();
        assertThat(n).isZero();
        verify(events, never()).markPending(any(), any(), any());
    }

    @Test
    void drain_forwardsAndDeletesEachRow() {
        UUID eventId = UUID.randomUUID();
        OutboxRepository.OutboxRow row = new OutboxRepository.OutboxRow(
                10L, "p", eventId, "events_order_created", "{}", 0);
        when(outbox.claimBatch(anyInt())).thenReturn(List.of(row));
        when(jdbc.queryForObject(anyString(), eq(Long.class), anyString(), anyString()))
                .thenReturn(123L);

        int n = poller.drain();

        assertThat(n).isEqualTo(1);
        verify(outbox).deleteSent(10L);
        verify(events).markPending("p", eventId, "outbox-poller");
        verify(outbox, never()).recordFailure(anyLong());
    }

    @Test
    void drain_recordsFailure_whenPgmqSendFails() {
        OutboxRepository.OutboxRow row = new OutboxRepository.OutboxRow(
                7L, "p", UUID.randomUUID(), "events_order_created", "{}", 0);
        when(outbox.claimBatch(anyInt())).thenReturn(List.of(row));
        when(jdbc.queryForObject(anyString(), eq(Long.class), anyString(), anyString()))
                .thenThrow(new RuntimeException("pgmq.send failed"));

        int n = poller.drain();

        assertThat(n).isEqualTo(1);
        verify(outbox).recordFailure(7L);
        verify(outbox, never()).deleteSent(anyLong());
        verify(events, never()).markPending(any(), any(), any());
    }

    @Test
    void drain_recordsFailure_whenPgmqSendReturnsNull() {
        OutboxRepository.OutboxRow row = new OutboxRepository.OutboxRow(
                8L, "p", UUID.randomUUID(), "events_order_created", "{}", 0);
        when(outbox.claimBatch(anyInt())).thenReturn(List.of(row));
        when(jdbc.queryForObject(anyString(), eq(Long.class), anyString(), anyString()))
                .thenReturn(null);

        int n = poller.drain();

        assertThat(n).isEqualTo(1);
        verify(outbox).recordFailure(8L);
    }
}
