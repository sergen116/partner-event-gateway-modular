package com.example.peg.shared;

/**
 * Lifecycle states for an event.
 *
 * <ul>
 *   <li>{@link #RECEIVED} — accepted by API, not yet enqueued (only seen during outbox window)</li>
 *   <li>{@link #PENDING} — enqueued to pgmq, awaiting consumer pickup</li>
 *   <li>{@link #PROCESSING} — claimed by a consumer, in-flight</li>
 *   <li>{@link #PROCESSED} — completed successfully</li>
 *   <li>{@link #FAILED} — exhausted retries; in DLQ for inspection</li>
 * </ul>
 */
public enum EventStatus {
    RECEIVED,
    PENDING,
    PROCESSING,
    PROCESSED,
    FAILED
}
