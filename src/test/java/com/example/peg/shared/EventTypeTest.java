package com.example.peg.shared;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventTypeTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void wireNames_matchCaseSpec() {
        // The case spec lists exactly these 5 type names
        assertThat(EventType.ORDER_CREATED.wireName()).isEqualTo("OrderCreated");
        assertThat(EventType.SHIPMENT_UPDATED.wireName()).isEqualTo("ShipmentStatusUpdated");
        assertThat(EventType.RETURN_REQUESTED.wireName()).isEqualTo("ReturnRequested");
        assertThat(EventType.ADDRESS_UPDATED.wireName()).isEqualTo("DeliveryAddressUpdated");
        assertThat(EventType.ORDER_CANCELLED.wireName()).isEqualTo("OrderCancelled");
    }

    @Test
    void queueNames_followNamingConvention() {
        for (EventType t : EventType.values()) {
            assertThat(t.queueName()).startsWith("events_");
        }
    }

    @Test
    void fromWireName_acceptsCaseSpecValues() {
        assertThat(EventType.fromWireName("OrderCreated")).isEqualTo(EventType.ORDER_CREATED);
        assertThat(EventType.fromWireName("ShipmentStatusUpdated")).isEqualTo(EventType.SHIPMENT_UPDATED);
    }

    @Test
    void fromWireName_rejectsUnknown() {
        assertThatThrownBy(() -> EventType.fromWireName("NotAnEventType"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown event type");
    }

    @Test
    void serializesToWireName() throws Exception {
        String json = mapper.writeValueAsString(EventType.ORDER_CREATED);
        assertThat(json).isEqualTo("\"OrderCreated\"");
    }

    @Test
    void deserializesFromWireName() throws Exception {
        EventType t = mapper.readValue("\"DeliveryAddressUpdated\"", EventType.class);
        assertThat(t).isEqualTo(EventType.ADDRESS_UPDATED);
    }
}
