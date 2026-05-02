package com.example.peg.query;

import com.example.peg.shared.EventStatus;
import com.example.peg.shared.EventType;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit tests for the Specifications filter API. No DB needed — these
 * tests verify the SQL fragments and bound-parameter list that
 * {@link EventSpecifications#compile} produces.
 *
 * <p>Adding a new filter is a one-line change in {@code SPECS}; adding a
 * test here is one new method. Together they're the case spec's
 * "extensible filter" requirement made concrete.
 */
class EventSpecificationsTest {

    @Test
    void emptyQuery_producesNoWhereClause() {
        var frag = EventSpecifications.compile(EventQuery.builder().build());
        assertThat(frag.whereClause()).isEmpty();
        assertThat(frag.args()).isEmpty();
    }

    @Test
    void singleFilter_isLeadingWhere() {
        var frag = EventSpecifications.compile(EventQuery.builder()
                .partnerId("partner-acme")
                .build());
        assertThat(frag.whereClause()).isEqualTo(" WHERE partner_id = ?");
        assertThat(frag.args()).containsExactly("partner-acme");
    }

    @Test
    void multipleFilters_areAndedTogether() {
        var frag = EventSpecifications.compile(EventQuery.builder()
                .partnerId("partner-acme")
                .eventType(EventType.ORDER_CREATED)
                .status(EventStatus.PENDING)
                .build());
        assertThat(frag.whereClause())
                .isEqualTo(" WHERE partner_id = ? AND event_type = ? AND status = ?");
        assertThat(frag.args()).containsExactly("partner-acme", "ORDER_CREATED", "PENDING");
    }

    @Test
    void timeBounds_areConvertedToTimestamp() {
        Instant from = Instant.parse("2024-01-01T00:00:00Z");
        Instant to = Instant.parse("2024-01-31T23:59:59Z");
        var frag = EventSpecifications.compile(EventQuery.builder()
                .fromCreatedAt(from)
                .toCreatedAt(to)
                .build());
        assertThat(frag.whereClause())
                .isEqualTo(" WHERE created_at >= ? AND created_at <= ?");
        assertThat(frag.args()).containsExactly(Timestamp.from(from), Timestamp.from(to));
    }

    @Test
    void processingOutcome_compilesToStatusEquals() {
        var frag = EventSpecifications.compile(EventQuery.builder()
                .processingOutcome(EventStatus.FAILED)
                .build());
        assertThat(frag.whereClause()).isEqualTo(" WHERE status = ?");
        assertThat(frag.args()).containsExactly("FAILED");
    }

    @Test
    void businessRef_isOptionalEqualityFilter() {
        var frag = EventSpecifications.compile(EventQuery.builder()
                .businessRef("ORD-12345")
                .build());
        assertThat(frag.whereClause()).isEqualTo(" WHERE business_ref = ?");
        assertThat(frag.args()).containsExactly("ORD-12345");
    }

    @Test
    void filtersInRegistryOrder_ensureStableSql() {
        // The order in EventSpecifications.SPECS determines the SQL output,
        // which downstream tests + EXPLAIN ANALYZE expectations depend on.
        // If this assertion drifts, update SPECS deliberately.
        Instant t = Instant.parse("2024-06-01T00:00:00Z");
        var frag = EventSpecifications.compile(EventQuery.builder()
                .businessRef("ORD-1")
                .partnerId("p")
                .toCreatedAt(t)
                .eventType(EventType.SHIPMENT_UPDATED)
                .build());
        assertThat(frag.whereClause())
                .isEqualTo(" WHERE partner_id = ? AND event_type = ? AND created_at <= ? AND business_ref = ?");
    }
}
