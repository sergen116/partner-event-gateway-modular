package com.example.peg.ingest;

import com.example.peg.partner.PartnerAuthFilter;
import com.example.peg.platform.SecurityProperties;
import com.example.peg.query.EventQuery;
import com.example.peg.query.EventRepository;
import com.example.peg.query.EventResponse;
import com.example.peg.query.PageResponse;
import com.example.peg.shared.ApiException;
import com.example.peg.shared.EventRecord;
import com.example.peg.shared.EventStatus;
import com.example.peg.shared.EventType;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PartnerEventsControllerTest {

    private EventIngestService ingest;
    private EventRepository events;
    private PartnerEventsController controller;
    private HttpServletRequest request;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        ingest = mock(EventIngestService.class);
        events = mock(EventRepository.class);
        controller = new PartnerEventsController(ingest, events, new SecurityProperties());
        request = mock(HttpServletRequest.class);
        when(request.getAttribute(PartnerAuthFilter.ATTR_PARTNER_ID)).thenReturn("p");
    }

    @Test
    void submit_throwsUnauthorized_whenPartnerAttributeMissing() {
        when(request.getAttribute(PartnerAuthFilter.ATTR_PARTNER_ID)).thenReturn(null);

        SubmitEventRequest body = new SubmitEventRequest(EventType.ORDER_CREATED, null, mapper.nullNode());

        assertThatThrownBy(() -> controller.submit(body, null, request))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("partner not authenticated");
    }

    @Test
    void submit_generatesUUID_whenIdempotencyKeyIsBlank() {
        EventRecord row = recordFor(UUID.randomUUID());
        when(ingest.ingest(eq("p"), any(UUID.class), any(SubmitEventRequest.class)))
                .thenReturn(new EventIngestService.IngestResult(row, true));

        SubmitEventRequest body = new SubmitEventRequest(EventType.ORDER_CREATED, null, mapper.nullNode());

        SubmitEventResponse response = controller.submit(body, "  ", request);

        assertThat(response.duplicate()).isFalse();
        assertThat(response.eventId()).isEqualTo(row.eventId());
    }

    @Test
    void submit_usesIdempotencyKey_whenValidUuid() {
        UUID key = UUID.randomUUID();
        EventRecord row = recordFor(key);
        when(ingest.ingest(eq("p"), eq(key), any())).thenReturn(
                new EventIngestService.IngestResult(row, false));

        SubmitEventRequest body = new SubmitEventRequest(EventType.ORDER_CREATED, null, mapper.nullNode());
        SubmitEventResponse response = controller.submit(body, key.toString(), request);

        assertThat(response.duplicate()).isTrue();
        assertThat(response.eventId()).isEqualTo(key);
        verify(ingest).ingest("p", key, body);
    }

    @Test
    void submit_rejectsNonUuidIdempotencyKey() {
        SubmitEventRequest body = new SubmitEventRequest(EventType.ORDER_CREATED, null, mapper.nullNode());
        assertThatThrownBy(() -> controller.submit(body, "not-a-uuid", request))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("must be a valid UUID");
    }

    @Test
    void getOne_returnsEventWhenFound() {
        UUID id = UUID.randomUUID();
        EventRecord row = recordFor(id);
        when(events.findById("p", id)).thenReturn(Optional.of(row));

        EventResponse response = controller.getOne(id, request);

        assertThat(response.eventId()).isEqualTo(id);
        assertThat(response.partnerId()).isEqualTo("p");
    }

    @Test
    void getOne_throwsNotFound_whenAbsent() {
        UUID id = UUID.randomUUID();
        when(events.findById("p", id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.getOne(id, request))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("event not found");
    }

    @Test
    void query_validatesPaging_negativePage() {
        assertThatThrownBy(() -> controller.query(-1, 50, null, null, null, null, null, null, request))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("page must be >= 0");
    }

    @Test
    void query_validatesPaging_oversizedPage() {
        assertThatThrownBy(() -> controller.query(0, 1000, null, null, null, null, null, null, request))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("size must be 1..500");
    }

    @Test
    void query_validatesPaging_zeroSize() {
        assertThatThrownBy(() -> controller.query(0, 0, null, null, null, null, null, null, request))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("size must be 1..500");
    }

    @Test
    void query_appliesDefaultLookback_whenNoFromOrTo() {
        when(events.count(any())).thenReturn(0L);
        when(events.query(any())).thenReturn(List.of());

        controller.query(0, 50, null, null, null, null, null, null, request);

        ArgumentCaptor<EventQuery> q = ArgumentCaptor.forClass(EventQuery.class);
        verify(events).count(q.capture());
        assertThat(q.getValue().getFromCreatedAt()).isNotNull();
        assertThat(q.getValue().getToCreatedAt()).isNull();
        assertThat(q.getValue().getPartnerId()).isEqualTo("p"); // tenant-isolation
    }

    @Test
    void query_passesExplicitTimeBounds_whenSupplied() {
        Instant from = Instant.parse("2024-01-01T00:00:00Z");
        Instant to = Instant.parse("2024-02-01T00:00:00Z");
        when(events.count(any())).thenReturn(2L);
        when(events.query(any())).thenReturn(List.of(recordFor(UUID.randomUUID())));

        PageResponse<EventResponse> response = controller.query(
                0, 50, EventType.ORDER_CREATED, EventStatus.PENDING,
                "ORD-1", from, to, EventStatus.FAILED, request);

        ArgumentCaptor<EventQuery> q = ArgumentCaptor.forClass(EventQuery.class);
        verify(events).count(q.capture());
        EventQuery captured = q.getValue();
        assertThat(captured.getFromCreatedAt()).isEqualTo(from);
        assertThat(captured.getToCreatedAt()).isEqualTo(to);
        assertThat(captured.getEventType()).isEqualTo(EventType.ORDER_CREATED);
        assertThat(captured.getStatus()).isEqualTo(EventStatus.PENDING);
        assertThat(captured.getBusinessRef()).isEqualTo("ORD-1");
        assertThat(captured.getProcessingOutcome()).isEqualTo(EventStatus.FAILED);
        assertThat(response.totalItems()).isEqualTo(2L);
        assertThat(response.items()).hasSize(1);
    }

    private EventRecord recordFor(UUID id) {
        return new EventRecord(1L, "p", id,
                EventType.ORDER_CREATED, "ORD-1",
                mapper.nullNode(),
                EventStatus.RECEIVED, null,
                Instant.parse("2024-06-01T00:00:00Z"), null);
    }
}
