package com.example.peg.shared;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/**
 * Persisted event record from the events table. Returned by the query API.
 */
public record EventRecord(
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
) {}
