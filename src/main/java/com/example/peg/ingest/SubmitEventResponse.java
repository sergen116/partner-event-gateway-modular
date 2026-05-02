package com.example.peg.ingest;

import com.example.peg.shared.EventStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

@Schema(description = "Response for an accepted event submission")
public record SubmitEventResponse(

        @Schema(description = "Server-assigned event id (also used as idempotency key)")
        UUID eventId,

        @Schema(description = "Current status. New submissions return PENDING; " +
                "duplicate submissions return the existing event's current status.")
        EventStatus status,

        @Schema(description = "True if this submission was a no-op duplicate")
        boolean duplicate,

        @Schema(description = "Time the event was first accepted by the gateway")
        Instant receivedAt
) {}
