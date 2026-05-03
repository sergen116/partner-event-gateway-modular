package com.example.peg.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class JacksonConfigTest {

    @Test
    void objectMapper_serializesInstantAsIsoString() throws Exception {
        ObjectMapper mapper = new JacksonConfig().objectMapper();
        Instant i = Instant.parse("2024-06-01T00:00:00Z");
        String json = mapper.writeValueAsString(i);
        assertThat(json).isEqualTo("\"2024-06-01T00:00:00Z\"");
    }

    @Test
    void objectMapper_omitsNullFields() throws Exception {
        ObjectMapper mapper = new JacksonConfig().objectMapper();
        record Holder(String a, String b) {}
        String json = mapper.writeValueAsString(new Holder("set", null));
        assertThat(json).contains("\"a\":\"set\"").doesNotContain("\"b\"");
    }

    @Test
    void objectMapper_disablesTimestampSerialization() {
        ObjectMapper mapper = new JacksonConfig().objectMapper();
        assertThat(mapper.getSerializationConfig()
                .isEnabled(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)).isFalse();
    }
}
