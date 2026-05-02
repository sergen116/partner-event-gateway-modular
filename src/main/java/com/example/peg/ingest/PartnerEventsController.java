package com.example.peg.ingest;

import com.example.peg.partner.PartnerAuthFilter;
import com.example.peg.platform.SecurityProperties;
import com.example.peg.query.EventQuery;
import com.example.peg.query.EventRepository;
import com.example.peg.query.EventResponse;
import com.example.peg.query.PageResponse;
import com.example.peg.shared.Errors;
import com.example.peg.shared.EventStatus;
import com.example.peg.shared.EventType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/events")
@Tag(name = "partner-events", description = "Partner-facing event submission and querying")
@RequiredArgsConstructor
public class PartnerEventsController {

    /** Default lookback window when the caller doesn't specify one. */
    private static final Duration DEFAULT_LOOKBACK = Duration.ofDays(90);

    private final EventIngestService ingest;
    private final EventRepository events;
    private final SecurityProperties securityProps;

    @PostMapping
    @Operation(summary = "Submit an event",
            description = "Submit an operational event. Pass an Idempotency-Key header to make " +
                    "retries safe — repeated submissions with the same key return the original " +
                    "event's status without re-processing.")
    public SubmitEventResponse submit(
            @Valid @RequestBody SubmitEventRequest body,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest request) {

        String partnerId = (String) request.getAttribute(PartnerAuthFilter.ATTR_PARTNER_ID);
        if (partnerId == null) {
            throw Errors.unauthorized("partner not authenticated");
        }

        UUID eventId = parseEventId(idempotencyKey);
        var result = ingest.ingest(partnerId, eventId, body);

        return new SubmitEventResponse(
                result.row().eventId(),
                result.row().status(),
                !result.newlyAccepted(),
                result.receivedAt());
    }

    @GetMapping("/{eventId}")
    @Operation(summary = "Get a specific event",
            description = "Retrieve a single event by its id. Partners can only see their own events.")
    public EventResponse getOne(@PathVariable UUID eventId, HttpServletRequest request) {
        String partnerId = (String) request.getAttribute(PartnerAuthFilter.ATTR_PARTNER_ID);
        return events.findById(partnerId, eventId)
                .map(EventResponse::from)
                .orElseThrow(() -> Errors.notFound("event not found"));
    }

    @GetMapping
    @Operation(summary = "Query own events",
            description = "Returns the calling partner's events with pagination and filters. " +
                    "The partner_id filter is enforced server-side from the auth context. " +
                    "If no time window is specified, defaults to the last 90 days so " +
                    "partition pruning bounds the scan.")
    public PageResponse<EventResponse> query(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @Parameter(description = "Filter by event type (wire name, e.g. OrderCreated)")
            @RequestParam(required = false) EventType eventType,
            @RequestParam(required = false) EventStatus status,
            @RequestParam(required = false) String businessRef,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @Parameter(description = "Filter to only PROCESSED or only FAILED events")
            @RequestParam(required = false) EventStatus processingOutcome,
            HttpServletRequest request) {

        String partnerId = (String) request.getAttribute(PartnerAuthFilter.ATTR_PARTNER_ID);
        validatePaging(page, size);

        // Default the time window for partition pruning. Callers can override
        // either bound; only when neither is set do we apply the default.
        Instant effectiveFrom = (from == null && to == null)
                ? Instant.now().minus(DEFAULT_LOOKBACK)
                : from;

        EventQuery q = EventQuery.builder()
                .partnerId(partnerId)              // enforced from auth context — tenant isolation
                .eventType(eventType)
                .status(status)
                .businessRef(businessRef)
                .fromCreatedAt(effectiveFrom)
                .toCreatedAt(to)
                .processingOutcome(processingOutcome)
                .page(page)
                .size(size)
                .build();

        long total = events.count(q);
        List<EventResponse> items = events.query(q).stream().map(EventResponse::from).toList();
        return PageResponse.of(items, page, size, total);
    }

    private UUID parseEventId(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return UUID.randomUUID();
        }
        try {
            return UUID.fromString(idempotencyKey);
        } catch (IllegalArgumentException e) {
            throw Errors.badRequest("INVALID_IDEMPOTENCY_KEY",
                    "Idempotency-Key must be a valid UUID");
        }
    }

    private static void validatePaging(int page, int size) {
        if (page < 0) throw Errors.badRequest("INVALID_PAGE", "page must be >= 0");
        if (size < 1 || size > 500) throw Errors.badRequest("INVALID_SIZE", "size must be 1..500");
    }
}
