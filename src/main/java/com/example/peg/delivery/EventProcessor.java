package com.example.peg.delivery;

import com.example.peg.shared.PartnerEventMessage;
import com.example.peg.query.EventRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Idempotent event dispatch. Must be thread-safe — invoked from many virtual
 * threads concurrently across many pods. The idempotency guarantee is the
 * (partner_id, event_id) unique constraint plus an atomic status transition:
 * a redelivered message attempting to claim a row that's already PROCESSED
 * finds zero rows updated and returns silently.
 *
 * <p>The handlers below are intentionally minimal — the case spec says
 * "the platform is not expected to implement full downstream business processes."
 *
 * <h2>Transaction boundaries</h2>
 * Two short transactions bracket the downstream call, which itself runs
 * <em>outside</em> any transaction:
 * <pre>
 *   Tx 1 (claim):     tryMarkProcessing      → commit
 *   (no tx)           downstreamCallService.notify(...)
 *   Tx 2 (finalize):  markProcessed          → commit
 * </pre>
 * This keeps the JDBC connection and row lock out of the HTTP path. On any
 * exception the row is left in PROCESSING; pgmq redelivers after VT expiry
 * and the next claimer succeeds via the {@code PROCESSING → PROCESSING} rule
 * in {@link EventRepository#tryMarkProcessing}. After max-attempts, the
 * worker calls {@code markFailed} (PROCESSING/PENDING → FAILED).
 */
@Service
@Slf4j
public class EventProcessor {

    private final EventRepository repository;
    private final DownstreamCallService downstreamCallService;
    private final TransactionTemplate tx;

    public EventProcessor(EventRepository repository,
                          DownstreamCallService downstreamCallService,
                          PlatformTransactionManager txManager) {
        this.repository = repository;
        this.downstreamCallService = downstreamCallService;
        this.tx = new TransactionTemplate(txManager);
    }

    public void process(PartnerEventMessage msg, String actor) {
        Boolean claimed = tx.execute(status ->
                repository.tryMarkProcessing(msg.partnerId(), msg.eventId(), actor));
        if (claimed == null || !claimed) {
            log.debug("event already terminal partner={} eventId={}",
                    msg.partnerId(), msg.eventId());
            return;
        }

        switch (msg.eventType()) {
            case ORDER_CREATED      -> handleOrderCreated(msg);
            case SHIPMENT_UPDATED   -> handleShipmentUpdated(msg);
            case RETURN_REQUESTED   -> handleReturnRequested(msg);
            case ADDRESS_UPDATED    -> handleAddressUpdated(msg);
            case ORDER_CANCELLED    -> handleOrderCancelled(msg);
        }

        tx.executeWithoutResult(status ->
                repository.markProcessed(msg.partnerId(), msg.eventId(), actor));
    }

    // Per-type handlers route through DownstreamCallService, which owns the
    // Resilience4j circuit-breaker + retry around the outbound call. The
    // real downstream integrations (inventory, fraud, logistics) plug in
    // by extending DownstreamCallService — call sites here stay unchanged.

    private void handleOrderCreated(PartnerEventMessage msg) {
        downstreamCallService.notify(msg);
        log.debug("processed OrderCreated for {}", msg.businessRef());
    }

    private void handleShipmentUpdated(PartnerEventMessage msg) {
        downstreamCallService.notify(msg);
        log.debug("processed ShipmentStatusUpdated for {}", msg.businessRef());
    }

    private void handleReturnRequested(PartnerEventMessage msg) {
        downstreamCallService.notify(msg);
        log.debug("processed ReturnRequested for {}", msg.businessRef());
    }

    private void handleAddressUpdated(PartnerEventMessage msg) {
        downstreamCallService.notify(msg);
        log.debug("processed DeliveryAddressUpdated for {}", msg.businessRef());
    }

    private void handleOrderCancelled(PartnerEventMessage msg) {
        downstreamCallService.notify(msg);
        log.debug("processed OrderCancelled for {}", msg.businessRef());
    }
}
