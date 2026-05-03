package com.example.peg.delivery;

import com.example.peg.PgmqPostgresInitializer;
import com.example.peg.shared.EventType;
import com.example.peg.shared.PartnerEventMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Verifies the resilience scaffolding around DownstreamCallService:
 * <ul>
 *   <li>Each failed call is retried up to {@code max-attempts} times
 *       (Resilience4j @Retry).</li>
 *   <li>Sustained failures trip the circuit breaker into OPEN state, after
 *       which subsequent calls short-circuit instead of hitting the wire.</li>
 * </ul>
 *
 * <p>Uses a real Spring context (so the AOP proxy is in effect) and WireMock
 * for the downstream stub. Postgres is started for context-load consistency
 * with the rest of the IT suite, but no pgmq traffic is exercised here.
 */
@SpringBootTest
@ContextConfiguration(initializers = PgmqPostgresInitializer.class)
class DownstreamCallServiceIT {

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @DynamicPropertySource
    static void downstreamProps(DynamicPropertyRegistry registry) {
        registry.add("app.downstream.base-url", wireMock::baseUrl);
        registry.add("app.downstream.connect-timeout", () -> "500ms");
        registry.add("app.downstream.read-timeout", () -> "500ms");
    }

    @Autowired DownstreamCallService service;
    @Autowired CircuitBreakerRegistry circuitBreakerRegistry;
    @Autowired ObjectMapper mapper;

    @BeforeEach
    void resetState() {
        // Clean slate per test: reset the breaker so each test sees its own
        // failure window without bleed-over from earlier tests.
        circuitBreakerRegistry.circuitBreaker(DownstreamCallService.INSTANCE).reset();
    }

    @Test
    void serverErrors_retryUpToMaxAttempts() {
        wireMock.stubFor(post(urlEqualTo("/notifications"))
                .willReturn(aResponse().withStatus(503)));

        Throwable thrown = catchThrowable(() -> service.notify(message()));

        assertThat(thrown).isInstanceOf(DownstreamCallService.DownstreamCallException.class);

        // 3 = max-attempts in application.yml — proves @Retry kicked in.
        wireMock.verify(3, com.github.tomakehurst.wiremock.client.WireMock
                .postRequestedFor(urlEqualTo("/notifications")));
    }

    @Test
    void sustainedFailures_openTheCircuit() {
        wireMock.stubFor(post(urlEqualTo("/notifications"))
                .willReturn(aResponse().withStatus(503)));

        CircuitBreaker breaker = circuitBreakerRegistry.circuitBreaker(DownstreamCallService.INSTANCE);

        // With Retry wrapping CircuitBreaker (default decoration order in
        // resilience4j-spring-boot3), each attempt registers as a separate
        // CB call. minimum-number-of-calls=10 + 50% failure threshold means
        // 4 outer notify() calls × 3 retries = 12 CB events, all failing —
        // the breaker should be OPEN by the time we're done.
        for (int i = 0; i < 4; i++) {
            try { service.notify(message()); } catch (RuntimeException ignored) { }
        }

        assertThat(breaker.getState())
                .as("breaker opens after sustained failures")
                .isEqualTo(CircuitBreaker.State.OPEN);

        // Once OPEN, further calls short-circuit — WireMock sees no new requests.
        wireMock.resetRequests();
        try { service.notify(message()); } catch (RuntimeException ignored) { }
        wireMock.verify(0, com.github.tomakehurst.wiremock.client.WireMock
                .postRequestedFor(urlEqualTo("/notifications")));
    }

    @Test
    void clientErrors_areNotRetried() {
        wireMock.stubFor(post(urlEqualTo("/notifications"))
                .willReturn(aResponse().withStatus(400)));

        catchThrowable(() -> service.notify(message()));

        // 4xx is in retry.ignore-exceptions — single attempt, no retry.
        wireMock.verify(1, com.github.tomakehurst.wiremock.client.WireMock
                .postRequestedFor(urlEqualTo("/notifications")));
    }

    private PartnerEventMessage message() {
        return new PartnerEventMessage(
                UUID.randomUUID(),
                "partner-acme",
                EventType.ORDER_CREATED,
                "ORD-RESILIENCE",
                mapper.createObjectNode(),
                Instant.now(),
                null,
                null
        );
    }
}
