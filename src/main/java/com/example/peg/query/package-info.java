/**
 * Event query API — partner-scoped and internal.
 *
 * <p>{@link PartnerEventsController}'s GET endpoints are in {@code ingest}
 * (controller per access boundary), but the query specification, repository,
 * and DTOs live here so they're shared between the partner and internal paths.
 *
 * <p>Public API surface:
 * <ul>
 *   <li>{@link InternalEventsController} — cross-partner query endpoint (no auth, per case spec)</li>
 *   <li>{@link EventRepository} — events table reads + state-transition writes</li>
 *   <li>{@link EventQuery} — extensible filter specification (partner, type, status, date range, business ref, outcome)</li>
 *   <li>{@link EventResponse}, {@link PageResponse} — response DTOs</li>
 * </ul>
 *
 * <p>The repository is shared across modules ({@code ingest} writes via
 * {@code insertIfAbsent}, {@code delivery} writes via {@code markPending}/etc.,
 * {@code query} reads). This is the only repository in the system that crosses
 * module boundaries — every other module owns its data.
 *
 * <p>Depends on: {@code shared}, {@code partner} (request attribute conventions
 * in {@code PartnerEventsController}), {@code audit} (each state transition
 * writes an audit row), {@code platform}.
 */
package com.example.peg.query;
