/**
 * Asynchronous delivery and processing.
 *
 * <p>Two cooperating components:
 * <ul>
 *   <li>{@link OutboxPoller} drains the {@code event_outbox} table into pgmq
 *       (FOR UPDATE SKIP LOCKED, deletes on send). Runs only on pods with the
 *       API runtime role.</li>
 *   <li>{@link PgmqWorker} subclasses (one per event type) read from pgmq,
 *       fan out across virtual threads bounded by a Semaphore, and dispatch
 *       to {@link EventProcessor} which runs the per-type handler logic.
 *       Workers run only on pods with the appropriate consumer runtime role.</li>
 * </ul>
 *
 * <p>Depends on: {@code shared}, {@code query} (events table state transitions),
 * {@code audit} (records each transition), {@code platform}.
 *
 * <p>Critically does NOT depend on {@code ingest} — the ingest path writes to
 * {@link OutboxRepository} (this module's write API) but the inverse coupling
 * is not allowed. Stage 2 production deploys this module's workers in their
 * own Kubernetes Deployments without the API code running.
 */
package com.example.peg.delivery;
