package com.example.peg.ingest;

import com.example.peg.delivery.OutboxRepository;
import com.example.peg.query.EventRepository;
import com.example.peg.shared.EventRecord;
import com.example.peg.shared.EventStatus;
import com.example.peg.shared.EventType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EventIngestServiceTest {

    private EventRepository events;
    private OutboxRepository outbox;
    private ObjectMapper mapper;
    private EventIngestService service;

    @BeforeEach
    void setUp() {
        events = mock(EventRepository.class);
        outbox = mock(OutboxRepository.class);
        mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        service = new EventIngestService(events, outbox, mapper);
    }

    @Test
    void ingest_writesEventAndOutbox_onFirstSubmission() throws Exception {
        UUID eventId = UUID.randomUUID();
        EventRecord row = new EventRecord(1L, "p", eventId,
                EventType.ORDER_CREATED, "ORD-1",
                mapper.readTree("{\"k\":1}"),
                EventStatus.RECEIVED, null,
                Instant.parse("2024-06-01T00:00:00Z"), null);
        when(events.insertIfAbsent(any(), any(), any(), any(), any(), eq("ingest")))
                .thenReturn(new EventRepository.InsertResult(row, true));

        SubmitEventRequest req = new SubmitEventRequest(
                EventType.ORDER_CREATED, "ORD-1", mapper.readTree("{\"k\":1}"));
        EventIngestService.IngestResult result = service.ingest("p", eventId, req);

        assertThat(result.newlyAccepted()).isTrue();
        assertThat(result.row()).isSameAs(row);
        assertThat(result.receivedAt()).isEqualTo(row.createdAt());

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(outbox).insert(eq("p"), eq(eventId),
                eq(EventType.ORDER_CREATED.queueName()), payloadCaptor.capture());

        String json = payloadCaptor.getValue();
        assertThat(json).contains("\"eventId\":\"" + eventId + "\"")
                .contains("\"partnerId\":\"p\"")
                .contains("\"eventType\":\"OrderCreated\"")
                .contains("\"businessRef\":\"ORD-1\"");
    }

    @Test
    void ingest_skipsOutbox_onDuplicate() throws Exception {
        UUID eventId = UUID.randomUUID();
        EventRecord row = new EventRecord(1L, "p", eventId,
                EventType.ORDER_CREATED, null,
                mapper.nullNode(), EventStatus.PROCESSED, null,
                Instant.now(), Instant.now());
        when(events.insertIfAbsent(any(), any(), any(), any(), any(), anyString()))
                .thenReturn(new EventRepository.InsertResult(row, false));

        SubmitEventRequest req = new SubmitEventRequest(
                EventType.ORDER_CREATED, null, mapper.nullNode());
        EventIngestService.IngestResult result = service.ingest("p", eventId, req);

        assertThat(result.newlyAccepted()).isFalse();
        verify(outbox, never()).insert(any(), any(), any(), any());
    }

    @Test
    void ingest_propagatesPayload_includingNullBusinessRef() throws Exception {
        UUID eventId = UUID.randomUUID();
        EventRecord row = new EventRecord(1L, "p", eventId,
                EventType.SHIPMENT_UPDATED, null,
                mapper.readTree("{\"x\":42}"),
                EventStatus.RECEIVED, null, Instant.now(), null);
        when(events.insertIfAbsent(any(), any(), any(), any(), any(), anyString()))
                .thenReturn(new EventRepository.InsertResult(row, true));

        SubmitEventRequest req = new SubmitEventRequest(
                EventType.SHIPMENT_UPDATED, null, mapper.readTree("{\"x\":42}"));
        service.ingest("p", eventId, req);

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(outbox).insert(eq("p"), eq(eventId),
                eq(EventType.SHIPMENT_UPDATED.queueName()), payload.capture());
        assertThat(payload.getValue()).contains("\"x\":42");
    }
}
