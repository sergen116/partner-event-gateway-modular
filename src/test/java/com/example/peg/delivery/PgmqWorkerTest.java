package com.example.peg.delivery;

import com.example.peg.platform.ConsumerProperties;
import com.example.peg.query.EventRepository;
import com.example.peg.shared.EventType;
import com.example.peg.shared.PartnerEventMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PgmqWorkerTest {

    private JdbcTemplate jdbc;
    private ObjectMapper mapper;
    private EventProcessor processor;
    private EventRepository events;
    private MeterRegistry meters;
    private ConsumerProperties props;
    private TestWorker worker;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        processor = mock(EventProcessor.class);
        events = mock(EventRepository.class);
        meters = new SimpleMeterRegistry();
        props = new ConsumerProperties();
        props.setBatchSize(Map.of("events_order_created", 5));
        props.setVisibilityTimeoutSeconds(10);
        props.setMaxAttempts(3);
        worker = new TestWorker(jdbc, mapper, processor, events, meters, props);
    }

    @Test
    void queueShortName_strippesPrefixAndDashes() {
        assertThat(worker.queueShortName()).isEqualTo("order-created");
    }

    @Test
    void poll_isNoOp_whenNoMessages() {
        when(jdbc.query(anyString(), any(RowMapper.class), any(), anyInt(), anyInt()))
                .thenReturn(List.of());
        worker.poll();

        verify(processor, never()).process(any(), anyString());
    }

    @Test
    void poll_processesAndDeletes_onSuccess() throws Exception {
        UUID eventId = UUID.randomUUID();
        PartnerEventMessage event = new PartnerEventMessage(
                eventId, "p", EventType.ORDER_CREATED, "ref",
                mapper.nullNode(), Instant.now(), null, null);
        String json = mapper.writeValueAsString(event);

        when(jdbc.query(anyString(), any(RowMapper.class), any(), anyInt(), anyInt()))
                .thenReturn(List.of(new com.example.peg.delivery.PgmqWorker.PgmqMessage(99L, 1, json)));
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq(EventType.ORDER_CREATED.queueName()), eq(99L)))
                .thenReturn(Boolean.TRUE);

        worker.poll();

        verify(processor, atLeastOnce()).process(any(), anyString());
        // Delete invoked
        verify(jdbc, atLeastOnce()).queryForObject(anyString(), eq(Boolean.class),
                eq(EventType.ORDER_CREATED.queueName()), eq(99L));
        assertThat(meters.find("peg.consumer.processed").counter().count()).isEqualTo(1.0);
    }

    @Test
    void poll_marksFailedAndArchives_afterMaxAttempts() throws Exception {
        UUID eventId = UUID.randomUUID();
        PartnerEventMessage event = new PartnerEventMessage(
                eventId, "p", EventType.ORDER_CREATED, "ref",
                mapper.nullNode(), Instant.now(), null, null);
        String json = mapper.writeValueAsString(event);

        when(jdbc.query(anyString(), any(RowMapper.class), any(), anyInt(), anyInt()))
                .thenReturn(List.of(new com.example.peg.delivery.PgmqWorker.PgmqMessage(50L, 3, json)));

        // Processor throws so handle() falls into the failure branch
        doThrow(new RuntimeException("downstream failed"))
                .when(processor).process(any(), anyString());

        // archive call returns true
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), anyString(), eq(50L)))
                .thenReturn(Boolean.TRUE);

        worker.poll();

        verify(events).markFailed(eq("p"), eq(eventId), anyString(), anyString());
        assertThat(meters.find("peg.consumer.failed").counter().count()).isEqualTo(1.0);
        assertThat(meters.find("peg.consumer.dlq").counter().count()).isEqualTo(1.0);
    }

    @Test
    void poll_doesNotArchive_whenAttemptsBelowMax() throws Exception {
        PartnerEventMessage event = new PartnerEventMessage(
                UUID.randomUUID(), "p", EventType.ORDER_CREATED, null,
                mapper.nullNode(), Instant.now(), null, null);
        String json = mapper.writeValueAsString(event);

        when(jdbc.query(anyString(), any(RowMapper.class), any(), anyInt(), anyInt()))
                .thenReturn(List.of(new com.example.peg.delivery.PgmqWorker.PgmqMessage(20L, 1, json)));
        doThrow(new RuntimeException("transient"))
                .when(processor).process(any(), anyString());

        worker.poll();

        verify(events, never()).markFailed(any(), any(), any(), any());
        assertThat(meters.find("peg.consumer.failed").counter().count()).isEqualTo(1.0);
    }

    @Test
    void poll_swallowsArchiveException_andContinues() throws Exception {
        PartnerEventMessage event = new PartnerEventMessage(
                UUID.randomUUID(), "p", EventType.ORDER_CREATED, null,
                mapper.nullNode(), Instant.now(), null, null);
        String json = mapper.writeValueAsString(event);

        when(jdbc.query(anyString(), any(RowMapper.class), any(), anyInt(), anyInt()))
                .thenReturn(List.of(new com.example.peg.delivery.PgmqWorker.PgmqMessage(99L, 5, json)));
        doThrow(new RuntimeException("downstream"))
                .when(processor).process(any(), anyString());
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), anyString(), eq(99L)))
                .thenThrow(new RuntimeException("archive failed"));

        // Should not throw
        worker.poll();
    }

    @Test
    void poll_swallowsReadException() {
        when(jdbc.query(anyString(), any(RowMapper.class), any(), anyInt(), anyInt()))
                .thenThrow(new RuntimeException("DB down"));
        worker.poll(); // no exception
    }

    @Test
    void shutdown_terminatesExecutor() {
        worker.shutdown();
        // shutdown is idempotent (calling twice should not throw)
        worker.shutdown();
    }

    @Test
    void allWorkerSubclasses_haveExpectedQueueNames() {
        var w2 = new AddressUpdatedWorker(jdbc, mapper, processor, events, meters, props);
        assertThat(w2.queueName()).isEqualTo(EventType.ADDRESS_UPDATED.queueName());
        var w3 = new OrderCancelledWorker(jdbc, mapper, processor, events, meters, props);
        assertThat(w3.queueName()).isEqualTo(EventType.ORDER_CANCELLED.queueName());
        var w4 = new OrderCreatedWorker(jdbc, mapper, processor, events, meters, props);
        assertThat(w4.queueName()).isEqualTo(EventType.ORDER_CREATED.queueName());
        var w5 = new ReturnRequestedWorker(jdbc, mapper, processor, events, meters, props);
        assertThat(w5.queueName()).isEqualTo(EventType.RETURN_REQUESTED.queueName());
        var w6 = new ShipmentUpdatedWorker(jdbc, mapper, processor, events, meters, props);
        assertThat(w6.queueName()).isEqualTo(EventType.SHIPMENT_UPDATED.queueName());

        // Each registers a concurrency gauge
        assertThat(meters.find("peg.consumer.concurrency.available").gauges().size())
                .isGreaterThanOrEqualTo(5);
    }

    /** Concrete worker for unit testing the abstract class. Pinned to ORDER_CREATED. */
    static class TestWorker extends PgmqWorker {
        TestWorker(JdbcTemplate jdbc, ObjectMapper mapper, EventProcessor processor,
                   EventRepository events, MeterRegistry meters, ConsumerProperties props) {
            super(jdbc, mapper, processor, events, meters, props);
        }
        @Override public String queueName() { return EventType.ORDER_CREATED.queueName(); }
    }
}
