package com.example.peg.shared;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

@Schema(description = "Standard error envelope")
public record ErrorResponse(
        String code,
        String message,
        Instant timestamp
) {}
