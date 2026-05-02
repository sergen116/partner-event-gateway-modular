package com.example.peg.platform;

import com.example.peg.shared.EventType;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.EnumSet;
import java.util.Set;

/**
 * Determines which roles this pod runs.
 *
 * <p>Stage 2 deploys one image in seven modes via APP_RUNTIME_MODE:
 * an API-only role, a Stage-1 fallback ("CONSUMER_ALL") for local dev,
 * and one mode per event type for production per-queue scaling.
 */
@ConfigurationProperties("app.runtime")
@Data
public class RuntimeProperties {

    private Mode mode = Mode.CONSUMER_ALL;

    public enum Mode {
        API,
        CONSUMER_ALL,
        CONSUMER_ORDER_CREATED,
        CONSUMER_SHIPMENT_UPDATED,
        CONSUMER_RETURN_REQUESTED,
        CONSUMER_ADDRESS_UPDATED,
        CONSUMER_ORDER_CANCELLED
    }

    public Set<EventType> activeEventTypes() {
        return switch (mode) {
            case API                       -> EnumSet.noneOf(EventType.class);
            case CONSUMER_ALL              -> EnumSet.allOf(EventType.class);
            case CONSUMER_ORDER_CREATED    -> EnumSet.of(EventType.ORDER_CREATED);
            case CONSUMER_SHIPMENT_UPDATED -> EnumSet.of(EventType.SHIPMENT_UPDATED);
            case CONSUMER_RETURN_REQUESTED -> EnumSet.of(EventType.RETURN_REQUESTED);
            case CONSUMER_ADDRESS_UPDATED  -> EnumSet.of(EventType.ADDRESS_UPDATED);
            case CONSUMER_ORDER_CANCELLED  -> EnumSet.of(EventType.ORDER_CANCELLED);
        };
    }

    public boolean runsApi() {
        return mode == Mode.API || mode == Mode.CONSUMER_ALL;
    }

    public boolean runsAnyConsumer() {
        return !activeEventTypes().isEmpty();
    }

    public boolean runsOutboxPoller() {
        // Outbox runs alongside the API since the API writes to it.
        return runsApi();
    }
}
