package com.example.peg.delivery;

import com.example.peg.PgmqPostgresInitializer;
import com.example.peg.partner.HmacVerifier;
import com.example.peg.platform.SecurityProperties;
import com.example.peg.shared.EventStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * End-to-end DLQ path: downstream always returns 500, the worker fails its
 * retries, and after {@code max-attempts} pgmq.read cycles the message is
 * archived to the DLQ and the event row reaches FAILED with the captured
 * error string.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ContextConfiguration(initializers = PgmqPostgresInitializer.class)
class DeadLetterQueueIT {

    private static final String PARTNER_ID = "partner-acme";
    private static final String SECRET = "acme-shared-secret-2024";

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @DynamicPropertySource
    static void downstreamProps(DynamicPropertyRegistry registry) {
        registry.add("app.downstream.base-url", wireMock::baseUrl);
        registry.add("app.downstream.connect-timeout", () -> "200ms");
        registry.add("app.downstream.read-timeout", () -> "200ms");
    }

    @LocalServerPort int port;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired HmacVerifier verifier;
    @Autowired SecurityProperties props;

    private final RestTemplate http = new RestTemplate();

    @Test
    void persistentDownstreamFailure_archivesToDlq_andMarksFailed() throws Exception {
        // Stub downstream as permanently failing
        wireMock.stubFor(post(urlEqualTo("/notifications"))
                .willReturn(aResponse().withStatus(503)));

        UUID eventId = UUID.randomUUID();
        ObjectNode body = mapper.createObjectNode()
                .put("eventType", "OrderCreated")
                .put("businessRef", "DLQ-1");
        body.set("payload", mapper.createObjectNode());
        byte[] bytes = mapper.writeValueAsBytes(body);
        String ts = Instant.now().toString();
        String sig = sign(ts, "POST", "/api/v1/events", bytes);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(props.getHeaders().getPartnerId(), PARTNER_ID);
        headers.set(props.getHeaders().getTimestamp(), ts);
        headers.set(props.getHeaders().getSignature(), sig);
        headers.set(props.getHeaders().getIdempotencyKey(), eventId.toString());

        http.exchange(
                "http://localhost:" + port + "/api/v1/events",
                HttpMethod.POST,
                new HttpEntity<>(bytes, headers),
                String.class);

        // Wait until the event row is FAILED. With max-attempts=2 and
        // poll-interval-ms=100 / VT=10s, this should resolve in a few seconds.
        await().atMost(Duration.ofSeconds(45))
                .pollInterval(Duration.ofMillis(250))
                .untilAsserted(() -> {
                    String status = jdbc.queryForObject(
                            "SELECT status FROM events WHERE partner_id = ? AND event_id = ?",
                            String.class, PARTNER_ID, eventId);
                    assertThat(status).isEqualTo(EventStatus.FAILED.name());
                });

        // The message should be archived (no longer in the active queue).
        Long depth = jdbc.queryForObject(
                "SELECT queue_length FROM pgmq.metrics('events_order_created')", Long.class);
        assertThat(depth).isZero();

        // Audit log records the FAILED transition with an error string.
        String error = jdbc.queryForObject(
                "SELECT error FROM event_audit_log " +
                "WHERE partner_id = ? AND event_id = ? AND to_status = 'FAILED'",
                String.class, PARTNER_ID, eventId);
        assertThat(error).isNotBlank();
    }

    private String sign(String ts, String method, String path, byte[] body) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        String hashHex = HexFormat.of().formatHex(md.digest(SECRET.getBytes(StandardCharsets.UTF_8)));
        return verifier.signForTesting(hashHex, PARTNER_ID, ts, method, path, body);
    }
}
