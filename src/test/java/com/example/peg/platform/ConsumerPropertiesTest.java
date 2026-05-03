package com.example.peg.platform;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ConsumerPropertiesTest {

    @Test
    void defaults_areReasonableForLocalDev() {
        ConsumerProperties props = new ConsumerProperties();
        assertThat(props.getPollIntervalMs()).isPositive();
        assertThat(props.getBusyPollIntervalMs()).isPositive();
        assertThat(props.getBusyPollIntervalMs()).isLessThan(props.getPollIntervalMs());
        assertThat(props.batchSizeFor("any-queue")).isPositive();
        assertThat(props.getVisibilityTimeoutSeconds()).isPositive();
        assertThat(props.getMaxAttempts()).isPositive();
    }

    @Test
    void concurrencyFor_returnsDefault_whenQueueAbsent() {
        ConsumerProperties props = new ConsumerProperties();
        assertThat(props.concurrencyFor("missing-queue")).isEqualTo(4);
    }

    @Test
    void concurrencyFor_returnsConfiguredValue_whenQueueDefined() {
        ConsumerProperties props = new ConsumerProperties();
        props.setConcurrency(Map.of("events_order_created", 16));
        assertThat(props.concurrencyFor("events_order_created")).isEqualTo(16);
        assertThat(props.concurrencyFor("events_other")).isEqualTo(4);
    }

    @Test
    void batchSizeFor_returnsDefault_whenQueueAbsent() {
        ConsumerProperties props = new ConsumerProperties();
        assertThat(props.batchSizeFor("missing-queue")).isEqualTo(10);
    }

    @Test
    void batchSizeFor_returnsConfiguredValue_whenQueueDefined() {
        ConsumerProperties props = new ConsumerProperties();
        props.setBatchSize(Map.of("events_order_created", 32));
        assertThat(props.batchSizeFor("events_order_created")).isEqualTo(32);
        assertThat(props.batchSizeFor("events_other")).isEqualTo(10);
    }
}
