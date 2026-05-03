package com.example.peg.platform;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityPropertiesTest {

    @Test
    void defaultHeaderNames_matchHmacContract() {
        SecurityProperties props = new SecurityProperties();
        assertThat(props.getHeaders().getPartnerId()).isEqualTo("X-Partner-Id");
        assertThat(props.getHeaders().getTimestamp()).isEqualTo("X-Timestamp");
        assertThat(props.getHeaders().getSignature()).isEqualTo("X-Signature");
        assertThat(props.getHeaders().getIdempotencyKey()).isEqualTo("Idempotency-Key");
    }

    @Test
    void defaultAlgorithm_isHmacSha256() {
        SecurityProperties props = new SecurityProperties();
        assertThat(props.getAlgorithm()).isEqualTo("HmacSHA256");
    }

    @Test
    void defaultSkew_isFiveMinutes() {
        SecurityProperties props = new SecurityProperties();
        assertThat(props.getTimestampSkew()).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void canOverrideHeaderNames() {
        SecurityProperties props = new SecurityProperties();
        SecurityProperties.Headers h = new SecurityProperties.Headers();
        h.setPartnerId("X-Custom-Partner");
        props.setHeaders(h);
        assertThat(props.getHeaders().getPartnerId()).isEqualTo("X-Custom-Partner");
    }
}
