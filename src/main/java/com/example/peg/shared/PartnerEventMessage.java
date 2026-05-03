package com.example.peg.shared;

import com.fasterxml.jackson.annotation.JsonInclude;
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
 *
 * <p>{@code traceparent} / {@code tracestate} carry W3C trace context across
 * the async hop so a CONSUMER span on the worker side links back to the
 * PRODUCER span on the HTTP ingest. Both fields are nullable; older messages
 * written before tracing was wired in still deserialize cleanly.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PartnerEventMessage(
        UUID eventId,
        String partnerId,
        EventType eventType,
        String businessRef,
        JsonNode payload,
        Instant receivedAt,
        String traceparent,
        String tracestate
) {}
