package com.example.peg;

import com.example.peg.ingest.SubmitEventResponse;
import com.example.peg.platform.SecurityProperties;
import com.example.peg.shared.EventStatus;
import com.example.peg.partner.HmacVerifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * End-to-end integration test exercising the full pipeline:
 * partner submits an HMAC-signed event → API persists + outbox → outbox poller forwards
 * to pgmq → consumer worker processes → events row reaches PROCESSED.
 *
 * <p>Uses Testcontainers Postgres with pgmq baked in. Real Spring Boot app context,
 * real HTTP, real Postgres, real pgmq operations.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ContextConfiguration(initializers = PgmqPostgresInitializer.class)
class IngestToConsumeIT {

    private static final String PARTNER_ID = "partner-acme";
    private static final String SECRET = "acme-shared-secret-2024";

    @LocalServerPort
    int port;

    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired HmacVerifier verifier;
    @Autowired SecurityProperties securityProps;

    private final RestTemplate http = new RestTemplate();

    @Test
    void submittedEvent_isProcessedEndToEnd() throws Exception {
        UUID eventId = UUID.randomUUID();
        ObjectNode payload = mapper.createObjectNode()
                .put("orderId", "ORD-001")
                .put("total", 99.99);

        ObjectNode body = mapper.createObjectNode()
                .put("eventType", "OrderCreated")
                .put("businessRef", "ORD-001");
        body.set("payload", payload);

        SubmitEventResponse response = postEvent(eventId, body);

        assertThat(response.eventId()).isEqualTo(eventId);
        assertThat(response.status()).isEqualTo(EventStatus.RECEIVED);
        assertThat(response.duplicate()).isFalse();

        // Wait for outbox poller + consumer worker to process the event
        await().atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    String status = jdbc.queryForObject(
                            "SELECT status FROM events WHERE partner_id = ? AND event_id = ?",
                            String.class, PARTNER_ID, eventId);
                    assertThat(status).isEqualTo("PROCESSED");
                });

        // Outbox row was deleted on successful send (delete-on-send semantics).
        // The events table is the audit source; the outbox is delivery machinery.
        Long outboxCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM event_outbox " +
                "WHERE partner_id = ? AND event_id = ?",
                Long.class, PARTNER_ID, eventId);
        assertThat(outboxCount).isZero();

        // Audit log captured every state transition.
        Long auditRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM event_audit_log " +
                "WHERE partner_id = ? AND event_id = ?",
                Long.class, PARTNER_ID, eventId);
        assertThat(auditRows).as("RECEIVED, PENDING, PROCESSING, PROCESSED").isEqualTo(4);
    }

    @Test
    void duplicateSubmission_returnsExistingEvent_doesNotReprocess() throws Exception {
        UUID eventId = UUID.randomUUID();
        ObjectNode body = mapper.createObjectNode()
                .put("eventType", "DeliveryAddressUpdated")
                .put("businessRef", "ORD-002");
        body.set("payload", mapper.createObjectNode().put("street", "1 Main St"));

        SubmitEventResponse first = postEvent(eventId, body);
        assertThat(first.duplicate()).isFalse();

        // Resubmit with the same idempotency key
        SubmitEventResponse second = postEvent(eventId, body);
        assertThat(second.duplicate()).isTrue();
        assertThat(second.eventId()).isEqualTo(first.eventId());

        // Only one row in events
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM events WHERE partner_id = ? AND event_id = ?",
                Long.class, PARTNER_ID, eventId);
        assertThat(count).isEqualTo(1);

        // At most one outbox row was ever created — the second submission
        // (a duplicate) should not have inserted a second outbox row. The row
        // may already be gone if the poller drained it; that's also fine.
        Long outboxCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM event_outbox WHERE partner_id = ? AND event_id = ?",
                Long.class, PARTNER_ID, eventId);
        assertThat(outboxCount).isLessThanOrEqualTo(1);
    }

    @Test
    void requestWithoutHmacHeaders_returns401() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        try {
            http.exchange(
                    "http://localhost:" + port + "/api/v1/events",
                    HttpMethod.POST,
                    new HttpEntity<>("{\"eventType\":\"OrderCreated\",\"payload\":{}}", headers),
                    String.class);
            assertThat(false).as("expected 401").isTrue();
        } catch (org.springframework.web.client.HttpClientErrorException ex) {
            assertThat(ex.getStatusCode().value()).isEqualTo(401);
        }
    }

    @Test
    void requestWithBadSignature_returns401() throws Exception {
        UUID eventId = UUID.randomUUID();
        ObjectNode body = mapper.createObjectNode()
                .put("eventType", "OrderCreated");
        body.set("payload", mapper.createObjectNode());
        byte[] bodyBytes = mapper.writeValueAsBytes(body);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(securityProps.getHeaders().getPartnerId(), PARTNER_ID);
        headers.set(securityProps.getHeaders().getTimestamp(), Instant.now().toString());
        headers.set(securityProps.getHeaders().getSignature(), "obviously-wrong-signature");
        headers.set(securityProps.getHeaders().getIdempotencyKey(), eventId.toString());

        try {
            http.exchange(
                    "http://localhost:" + port + "/api/v1/events",
                    HttpMethod.POST,
                    new HttpEntity<>(bodyBytes, headers),
                    String.class);
            assertThat(false).as("expected 401").isTrue();
        } catch (org.springframework.web.client.HttpClientErrorException ex) {
            assertThat(ex.getStatusCode().value()).isEqualTo(401);
        }
    }

    /** Submit a properly-signed event and return the parsed response. */
    private SubmitEventResponse postEvent(UUID eventId, JsonNode body) throws Exception {
        byte[] bodyBytes = mapper.writeValueAsBytes(body);
        String ts = Instant.now().toString();
        String sig = sign(ts, "POST", "/api/v1/events", bodyBytes);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(securityProps.getHeaders().getPartnerId(), PARTNER_ID);
        headers.set(securityProps.getHeaders().getTimestamp(), ts);
        headers.set(securityProps.getHeaders().getSignature(), sig);
        headers.set(securityProps.getHeaders().getIdempotencyKey(), eventId.toString());

        ResponseEntity<SubmitEventResponse> resp = http.exchange(
                "http://localhost:" + port + "/api/v1/events",
                HttpMethod.POST,
                new HttpEntity<>(bodyBytes, headers),
                SubmitEventResponse.class);

        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        return resp.getBody();
    }

    private String sign(String ts, String method, String path, byte[] body) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        String secretHashHex = HexFormat.of().formatHex(
                md.digest(SECRET.getBytes(StandardCharsets.UTF_8)));
        return verifier.signForTesting(secretHashHex, PARTNER_ID, ts, method, path, body);
    }
}
