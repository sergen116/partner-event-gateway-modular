package com.example.peg.platform;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Connection settings for the (future) downstream business systems that
 * event handlers in {@link com.example.peg.delivery.EventProcessor} will
 * notify. Today the call site is a stub demonstrating the resilience
 * scaffolding (circuit breaker + retry) end-to-end.
 *
 * <p>Timeouts must stay well under the pgmq visibility-timeout-minus-5s
 * deadline enforced by {@code PgmqWorker.processBatch}; otherwise an
 * in-flight retry can outlive its message lease.
 */
@ConfigurationProperties("app.downstream")
@Data
public class DownstreamProperties {

    /** Base URL of the downstream stub. Overridden per environment / per test. */
    private String baseUrl = "http://localhost:9999";

    /** Time to establish a TCP connection. */
    private Duration connectTimeout = Duration.ofSeconds(1);

    /** Time to read a response after connect. */
    private Duration readTimeout = Duration.ofSeconds(2);
}
