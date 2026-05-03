package com.example.peg.platform;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class DownstreamPropertiesTest {

    @Test
    void defaultsAreSet() {
        DownstreamProperties p = new DownstreamProperties();
        assertThat(p.getBaseUrl()).isNotBlank();
        assertThat(p.getConnectTimeout()).isPositive();
        assertThat(p.getReadTimeout()).isPositive();
    }

    @Test
    void canOverrideEachField() {
        DownstreamProperties p = new DownstreamProperties();
        p.setBaseUrl("http://example.test");
        p.setConnectTimeout(Duration.ofSeconds(3));
        p.setReadTimeout(Duration.ofSeconds(7));
        assertThat(p.getBaseUrl()).isEqualTo("http://example.test");
        assertThat(p.getConnectTimeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(p.getReadTimeout()).isEqualTo(Duration.ofSeconds(7));
    }
}
