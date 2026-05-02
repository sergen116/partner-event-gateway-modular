package com.example.peg.delivery;

import com.example.peg.shared.PartnerEventMessage;
import com.example.peg.query.EventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Idempotent event dispatch. Must be thread-safe — invoked from many virtual
 * threads concurrently across many pods. The idempotency guarantee is the
 * (partner_id, event_id) unique constraint plus an atomic status transition:
 * a redelivered message attempting to claim a row that's already PROCESSED
 * finds zero rows updated and returns silently.
 *
 * <p>The handlers below are intentionally minimal — the case spec says
 * "the platform is not expected to implement full downstream business processes."
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class EventProcessor {

    private final EventRepository repository;

    @Transactional
    public void process(PartnerEventMessage msg, String actor) {
        boolean claimed = repository.tryMarkProcessing(msg.partnerId(), msg.eventId(), actor);
        if (!claimed) {
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
        repository.markProcessed(msg.partnerId(), msg.eventId(), actor);
    }

    // The real implementations are out of scope per the case spec.
    // They would call inventory services, fraud checks, logistics partners, etc.

    private void handleOrderCreated(PartnerEventMessage msg) {
        log.debug("processed OrderCreated for {}", msg.businessRef());
    }

    private void handleShipmentUpdated(PartnerEventMessage msg) {
        log.debug("processed ShipmentStatusUpdated for {}", msg.businessRef());
    }

    private void handleReturnRequested(PartnerEventMessage msg) {
        log.debug("processed ReturnRequested for {}", msg.businessRef());
    }

    private void handleAddressUpdated(PartnerEventMessage msg) {
        log.debug("processed DeliveryAddressUpdated for {}", msg.businessRef());
    }

    private void handleOrderCancelled(PartnerEventMessage msg) {
        log.debug("processed OrderCancelled for {}", msg.businessRef());
    }
}
