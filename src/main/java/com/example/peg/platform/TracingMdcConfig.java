package com.example.peg.platform;

import io.micrometer.tracing.otel.bridge.Slf4JEventListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Overrides Spring Boot's default {@link Slf4JEventListener} so trace/span IDs
 * land in MDC under snake_case keys ({@code trace_id}, {@code span_id}),
 * matching the rest of our MDC contract ({@code partner_id}, {@code event_id},
 * etc.) used by logback-spring.xml and the JSON encoder.
 */
@Configuration
class TracingMdcConfig {

    @Bean
    Slf4JEventListener otelSlf4JEventListener() {
        return new Slf4JEventListener("trace_id", "span_id");
    }
}
