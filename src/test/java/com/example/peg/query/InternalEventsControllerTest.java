package com.example.peg.query;

import com.example.peg.shared.ApiException;
import com.example.peg.shared.EventRecord;
import com.example.peg.shared.EventStatus;
import com.example.peg.shared.EventType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InternalEventsControllerTest {

    private EventRepository repo;
    private InternalEventsController controller;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        repo = mock(EventRepository.class);
        controller = new InternalEventsController(repo);
    }

    @Test
    void query_validatesNegativePage() {
        assertThatThrownBy(() ->
                controller.query(-1, 50, null, null, null, null, null, null, null))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("page must be >= 0");
    }

    @Test
    void query_validatesOversizedSize() {
        assertThatThrownBy(() ->
                controller.query(0, 1000, null, null, null, null, null, null, null))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("size must be 1..500");
    }

    @Test
    void query_appliesDefaultLookback_whenNoTimeBounds() {
        when(repo.count(any())).thenReturn(0L);
        when(repo.query(any())).thenReturn(List.of());

        controller.query(0, 50, null, null, null, null, null, null, null);

        ArgumentCaptor<EventQuery> captor = ArgumentCaptor.forClass(EventQuery.class);
        verify(repo).count(captor.capture());
        assertThat(captor.getValue().getFromCreatedAt()).isNotNull();
    }

    @Test
    void query_passesPartnerIdFilter_whenSupplied() {
        when(repo.count(any())).thenReturn(1L);
        UUID id = UUID.randomUUID();
        EventRecord row = new EventRecord(1L, "p", id, EventType.ORDER_CREATED,
                "ORD-1", mapper.nullNode(), EventStatus.PROCESSED, null,
                Instant.now(), Instant.now());
        when(repo.query(any())).thenReturn(List.of(row));

        Instant from = Instant.parse("2024-01-01T00:00:00Z");
        PageResponse<EventResponse> resp = controller.query(
                0, 25, "p", EventType.ORDER_CREATED, EventStatus.PROCESSED,
                "ORD-1", from, null, null);

        ArgumentCaptor<EventQuery> captor = ArgumentCaptor.forClass(EventQuery.class);
        verify(repo).count(captor.capture());
        EventQuery q = captor.getValue();
        assertThat(q.getPartnerId()).isEqualTo("p");
        assertThat(q.getEventType()).isEqualTo(EventType.ORDER_CREATED);
        assertThat(q.getStatus()).isEqualTo(EventStatus.PROCESSED);
        assertThat(q.getBusinessRef()).isEqualTo("ORD-1");
        assertThat(q.getFromCreatedAt()).isEqualTo(from);
        // Time supplied → default lookback NOT applied
        assertThat(q.getToCreatedAt()).isNull();
        assertThat(resp.totalItems()).isEqualTo(1L);
    }
}
