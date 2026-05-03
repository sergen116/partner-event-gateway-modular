package com.example.peg.ingest;

import com.example.peg.platform.TraceContextCarrier;
import com.example.peg.shared.EventRecord;
import com.example.peg.shared.PartnerEventMessage;
import com.example.peg.delivery.OutboxRepository;
import com.example.peg.query.EventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Accepts an incoming event from a partner and writes it durably:
 * one row in {@code events} (status=RECEIVED) and one row in {@code event_outbox}.
 * Both writes happen inside the same transaction. The
 * {@code com.example.peg.delivery.OutboxPoller} later forwards the outbox row
 * into pgmq and transitions the event to PENDING.
 *
 * <p>Idempotency: duplicate submissions (same partner_id + event_id) hit the
 * unique constraint, the INSERT becomes a no-op, and we return the existing row
 * with {@code wasInserted=false}. The outbox row is only created on first insert.
 */
@Service
@RequiredArgsConstructor
public class EventIngestService {

    private static final String ACTOR = "ingest";

    private final EventRepository events;
    private final OutboxRepository outbox;
    private final ObjectMapper mapper;

    @Transactional
    public IngestResult ingest(String partnerId, UUID eventId, SubmitEventRequest req) {
        var insertResult = events.insertIfAbsent(
                partnerId, eventId, req.eventType(), req.businessRef(), req.payload(), ACTOR);

        EventRecord row = insertResult.row();

        if (insertResult.wasInserted()) {
            // Snapshot the active HTTP span so the downstream worker can
            // resume the trace as a CONSUMER span linked back to ingest.
            TraceContextCarrier.Captured trace = TraceContextCarrier.capture();
            PartnerEventMessage message = new PartnerEventMessage(
                    eventId, partnerId, req.eventType(),
                    req.businessRef(), req.payload(),
                    row.createdAt(),
                    trace.traceparent(), trace.tracestate());
            outbox.insert(partnerId, eventId, req.eventType().queueName(), writeJson(message));
        }

        return new IngestResult(row, insertResult.wasInserted());
    }

    private String writeJson(PartnerEventMessage msg) {
        try {
            return mapper.writeValueAsString(msg);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("failed to serialize event", e);
        }
    }

    public record IngestResult(EventRecord row, boolean newlyAccepted) {
        public Instant receivedAt() { return row.createdAt(); }
    }
}
