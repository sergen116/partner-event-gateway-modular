package com.example.peg.query;

import com.example.peg.shared.EventRecord;
import com.example.peg.shared.EventStatus;
import com.example.peg.shared.EventType;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

@Schema(description = "Event details from the events table")
public record EventResponse(
        long id,
        String partnerId,
        UUID eventId,
        EventType eventType,
        String businessRef,
        JsonNode payload,
        EventStatus status,
        String error,
        Instant createdAt,
        Instant processedAt
) {
    public static EventResponse from(EventRecord r) {
        return new EventResponse(
                r.id(),
                r.partnerId(),
                r.eventId(),
                r.eventType(),
                r.businessRef(),
                r.payload(),
                r.status(),
                r.error(),
                r.createdAt(),
                r.processedAt()
        );
    }
}
