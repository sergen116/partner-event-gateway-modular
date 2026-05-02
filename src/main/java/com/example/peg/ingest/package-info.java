/**
 * Event ingestion — the partner-facing write path.
 *
 * <p>Accepts HMAC-authenticated event submissions, writes the events row + an
 * outbox row in a single transaction, and returns 200 to the partner. The
 * outbox poller (in {@code delivery}) forwards the message to pgmq
 * asynchronously.
 *
 * <p>Public API surface:
 * <ul>
 *   <li>{@link PartnerEventsController} — REST controller, HMAC-protected by the partner module's filter</li>
 *   <li>{@link EventIngestService} — transactional ingest service, callable from controllers or batch importers</li>
 * </ul>
 *
 * <p>Depends on: {@code shared}, {@code partner} (request attribute conventions),
 * {@code delivery} (writes to {@code OutboxRepository}), {@code query} (writes to
 * {@code EventRepository}), {@code audit} (records RECEIVED transitions),
 * {@code platform}.
 */
package com.example.peg.ingest;
