package com.example.peg.ingest;

import com.example.peg.shared.EventType;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(description = "Event submission payload")
public record SubmitEventRequest(

        @NotNull
        @Schema(description = "Event type wire name", example = "OrderCreated")
        EventType eventType,

        @Size(max = 128)
        @Schema(description = "Partner's business reference (order id, shipment id, etc.)",
                example = "ORD-2024-001234")
        String businessRef,

        @NotNull
        @Schema(description = "Event-specific payload — opaque JSON")
        JsonNode payload
) {}
