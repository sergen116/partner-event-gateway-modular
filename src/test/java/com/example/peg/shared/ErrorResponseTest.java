package com.example.peg.shared;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ErrorResponseTest {

    @Test
    void exposesAllFields() {
        Instant now = Instant.now();
        ErrorResponse r = new ErrorResponse("BAD", "boom", now);
        assertThat(r.code()).isEqualTo("BAD");
        assertThat(r.message()).isEqualTo("boom");
        assertThat(r.timestamp()).isEqualTo(now);
    }

    @Test
    void serializesAsJsonEnvelope() throws Exception {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        ErrorResponse r = new ErrorResponse("X", "y", Instant.parse("2024-06-01T00:00:00Z"));
        String json = mapper.writeValueAsString(r);
        assertThat(json).contains("\"code\":\"X\"")
                .contains("\"message\":\"y\"")
                .contains("\"timestamp\":");
    }
}
