package com.example.peg.shared;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * The 5 supported event types from the case spec, with their pgmq queue names.
 *
 * <p>Wire names ("OrderCreated") are the partner-facing strings; the internal
 * enum names use SCREAMING_SNAKE_CASE per Java convention.
 */
public enum EventType {

    ORDER_CREATED      ("OrderCreated",            "events_order_created"),
    SHIPMENT_UPDATED   ("ShipmentStatusUpdated",   "events_shipment_updated"),
    RETURN_REQUESTED   ("ReturnRequested",         "events_return_requested"),
    ADDRESS_UPDATED    ("DeliveryAddressUpdated",  "events_address_updated"),
    ORDER_CANCELLED    ("OrderCancelled",          "events_order_cancelled");

    private final String wireName;
    private final String queueName;

    EventType(String wireName, String queueName) {
        this.wireName = wireName;
        this.queueName = queueName;
    }

    @JsonValue
    public String wireName() {
        return wireName;
    }

    public String queueName() {
        return queueName;
    }

    @JsonCreator
    public static EventType fromWireName(String s) {
        for (EventType t : values()) {
            if (t.wireName.equals(s)) return t;
        }
        throw new IllegalArgumentException("unknown event type: " + s);
    }
}
