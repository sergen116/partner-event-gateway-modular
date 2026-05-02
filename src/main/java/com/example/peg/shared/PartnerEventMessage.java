package com.example.peg.shared;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/**
 * Internal message envelope written to pgmq and read by consumers.
 *
 * <p>The (partnerId, eventId) pair is the idempotency key. A unique constraint
 * on the events table means redelivered messages find the row already claimed
 * and return silently — the queue's at-least-once semantics are made
 * effectively-once at the data layer.
 */
public record PartnerEventMessage(
        UUID eventId,
        String partnerId,
        EventType eventType,
        String businessRef,
        JsonNode payload,
        Instant receivedAt
) {}
