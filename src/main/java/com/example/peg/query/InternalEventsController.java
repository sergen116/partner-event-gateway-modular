package com.example.peg.query;

import com.example.peg.shared.Errors;
import com.example.peg.shared.EventStatus;
import com.example.peg.shared.EventType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Internal cross-partner querying. Per the case spec, internal user
 * authentication and role model are not implemented; in production this would
 * sit behind operator OIDC and probably hit a read replica.
 *
 * <p>Defaults the time window to last 90 days when neither {@code from} nor
 * {@code to} is supplied, so partition pruning bounds the scan even on
 * unfiltered queries.
 */
@RestController
@RequestMapping("/api/v1/internal/events")
@Tag(name = "internal-events", description = "Internal cross-partner querying")
@RequiredArgsConstructor
public class InternalEventsController {

    private static final Duration DEFAULT_LOOKBACK = Duration.ofDays(90);

    private final EventRepository events;

    @GetMapping
    @Operation(summary = "Query events across all partners",
            description = "Internal-only endpoint that returns events across all partners, " +
                    "with the same filtering as the partner endpoint plus an explicit partner filter. " +
                    "Defaults to the last 90 days when no time window is supplied so partition " +
                    "pruning keeps the scan bounded.")
    public PageResponse<EventResponse> query(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String partnerId,
            @RequestParam(required = false) EventType eventType,
            @RequestParam(required = false) EventStatus status,
            @RequestParam(required = false) String businessRef,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) EventStatus processingOutcome) {

        if (page < 0) throw Errors.badRequest("INVALID_PAGE", "page must be >= 0");
        if (size < 1 || size > 500) throw Errors.badRequest("INVALID_SIZE", "size must be 1..500");

        Instant effectiveFrom = (from == null && to == null)
                ? Instant.now().minus(DEFAULT_LOOKBACK)
                : from;

        EventQuery q = EventQuery.builder()
                .partnerId(partnerId)
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
}
