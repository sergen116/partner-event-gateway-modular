package com.example.peg.query;

import com.example.peg.shared.EventStatus;
import com.example.peg.shared.EventType;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class EventQueryTest {

    @Test
    void builderDefaults_pageZeroSizeFifty() {
        EventQuery q = EventQuery.builder().build();
        assertThat(q.getPage()).isZero();
        assertThat(q.getSize()).isEqualTo(50);
        assertThat(q.getPartnerId()).isNull();
    }

    @Test
    void builderSetsAllFields() {
        Instant from = Instant.parse("2024-01-01T00:00:00Z");
        EventQuery q = EventQuery.builder()
                .partnerId("p")
                .eventType(EventType.ORDER_CREATED)
                .status(EventStatus.PENDING)
                .processingOutcome(EventStatus.PROCESSED)
                .businessRef("ORD-1")
                .fromCreatedAt(from)
                .toCreatedAt(from.plusSeconds(3600))
                .page(2)
                .size(100)
                .build();

        assertThat(q.getPartnerId()).isEqualTo("p");
        assertThat(q.getEventType()).isEqualTo(EventType.ORDER_CREATED);
        assertThat(q.getStatus()).isEqualTo(EventStatus.PENDING);
        assertThat(q.getProcessingOutcome()).isEqualTo(EventStatus.PROCESSED);
        assertThat(q.getBusinessRef()).isEqualTo("ORD-1");
        assertThat(q.getFromCreatedAt()).isEqualTo(from);
        assertThat(q.getToCreatedAt()).isEqualTo(from.plusSeconds(3600));
        assertThat(q.getPage()).isEqualTo(2);
        assertThat(q.getSize()).isEqualTo(100);
    }
}
