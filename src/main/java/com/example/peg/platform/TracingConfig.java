package com.example.peg.platform;

import io.micrometer.tracing.otel.bridge.Slf4JEventListener;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the autoconfigured {@link OpenTelemetry} bean into the two static
 * surfaces our codebase expects:
 *
 * <ol>
 *   <li>{@link GlobalOpenTelemetry} — {@link TraceContextCarrier} resolves its
 *       tracer / propagators here. Spring Boot's autoconfiguration creates an
 *       {@code OpenTelemetrySdk} bean but does <em>not</em> register it as
 *       Global, so without this call the carrier silently falls back to the
 *       no-op tracer (spans emit all-zero trace/span IDs).</li>
 *   <li>{@link Slf4JEventListener} — overridden so trace/span IDs land in MDC
 *       under snake_case keys ({@code trace_id}, {@code span_id}), matching
 *       the rest of our MDC contract ({@code partner_id}, {@code event_id},
 *       …) consumed by logback-spring.xml and the JSON encoder.</li>
 * </ol>
 */
@Configuration
class TracingConfig {

    private final OpenTelemetry openTelemetry;

    TracingConfig(OpenTelemetry openTelemetry) {
        this.openTelemetry = openTelemetry;
    }

    @PostConstruct
    void registerGlobalOpenTelemetry() {
        try {
            GlobalOpenTelemetry.set(openTelemetry);
        } catch (IllegalStateException alreadySet) {
            // GlobalOpenTelemetry.set() throws on second call. Safe to ignore —
            // happens when a test context reloads after an earlier registration.
        }
    }

    /**
     * Static so Spring can register the listener without instantiating
     * {@code TracingConfig}. The enclosing class injects {@link OpenTelemetry},
     * whose build chain pulls every {@code EventListener} bean — making this
     * method non-static would create a constructor cycle through
     * {@code otelTracerEventPublisher}.
     */
    @Bean
    static Slf4JEventListener otelSlf4JEventListener() {
        return new Slf4JEventListener("trace_id", "span_id");
    }
}
