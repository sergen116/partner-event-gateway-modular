package com.example.peg.query;

import com.example.peg.shared.EventStatus;
import com.example.peg.shared.EventType;
import lombok.Builder;
import lombok.Getter;

import java.time.Instant;

/**
 * Filters for querying events. Extensible — add a field, add a clause in
 * {@link EventRepository#buildWhereClause}. The list of supported filters
 * matches the case spec: partner, event type, status, date interval,
 * business reference, processing outcome.
 */
@Getter
@Builder
public class EventQuery {
    private final String partnerId;
    private final EventType eventType;
    private final EventStatus status;
    private final Instant fromCreatedAt;
    private final Instant toCreatedAt;
    private final String businessRef;
    /** Filter to PROCESSED or FAILED — the "processing outcome" filter. */
    private final EventStatus processingOutcome;

    @Builder.Default
    private final int page = 0;
    @Builder.Default
    private final int size = 50;
}
