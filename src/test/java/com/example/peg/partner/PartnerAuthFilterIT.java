package com.example.peg.partner;

import com.example.peg.PgmqPostgresInitializer;
import com.example.peg.delivery.DownstreamCallService;
import com.example.peg.platform.SecurityProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Filter-level IT for partner authentication. Exercises:
 * <ul>
 *   <li>Cache hit on repeated requests (one DB lookup, two requests)</li>
 *   <li>Secret rotation window — a partner mid-rotation with both secrets
 *       valid still authenticates correctly when signing with the previous
 *       secret.</li>
 * </ul>
 */
@SpringBootTest
@ContextConfiguration(initializers = PgmqPostgresInitializer.class)
class PartnerAuthFilterIT {

    private static final String PARTNER_ID = "partner-acme";
    private static final String CURRENT_SECRET = "acme-shared-secret-2024";

    @Autowired WebApplicationContext webContext;
    @Autowired ObjectMapper mapper;
    @Autowired HmacVerifier verifier;
    @Autowired SecurityProperties props;
    @Autowired JdbcTemplate jdbc;
    @Autowired Cache<String, Optional<Partner>> cache;

    @MockBean DownstreamCallService downstream;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(webContext)
                .addFilters(webContext.getBean(PartnerAuthFilter.class))
                .build();
        cache.invalidateAll();
        jdbc.update("DELETE FROM event_outbox");
        jdbc.update("DELETE FROM events");
        jdbc.update("DELETE FROM event_audit_log");
        // Reset partner row to current state
        jdbc.update(
                "UPDATE partners SET previous_secret_hash = NULL, " +
                "previous_secret_expires_at = NULL WHERE partner_id = ?",
                PARTNER_ID);
    }

    @Test
    void authenticatesWithPreviousSecret_duringRotationWindow() throws Exception {
        // Set up rotation: current secret = a different value, previous secret = the original
        String newSecret = "acme-rotated-secret-2025";
        String oldHashHex = sha256Hex(CURRENT_SECRET);
        String newHashHex = sha256Hex(newSecret);

        jdbc.update(
                "UPDATE partners SET secret_hash = ?, previous_secret_hash = ?, " +
                "previous_secret_expires_at = ? WHERE partner_id = ?",
                newHashHex, oldHashHex,
                Timestamp.from(Instant.now().plus(Duration.ofHours(1))),
                PARTNER_ID);

        try {
            // Sign with the OLD (previous) secret — should still work
            UUID eventId = UUID.randomUUID();
            byte[] body = mapper.writeValueAsBytes(mapper.createObjectNode()
                    .put("eventType", "OrderCreated")
                    .set("payload", mapper.createObjectNode()));
            String ts = Instant.now().toString();
            String sig = verifier.signForTesting(oldHashHex, PARTNER_ID, ts,
                    "POST", "/api/v1/events", body);

            mvc.perform(post("/api/v1/events")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header(props.getHeaders().getPartnerId(), PARTNER_ID)
                            .header(props.getHeaders().getTimestamp(), ts)
                            .header(props.getHeaders().getSignature(), sig)
                            .header(props.getHeaders().getIdempotencyKey(), eventId.toString())
                            .content(body))
                    .andExpect(status().isOk());
        } finally {
            // Restore the partner row so other tests aren't affected
            jdbc.update(
                    "UPDATE partners SET secret_hash = ?, previous_secret_hash = NULL, " +
                    "previous_secret_expires_at = NULL WHERE partner_id = ?",
                    oldHashHex, PARTNER_ID);
        }
    }

    @Test
    void cacheHit_reusesPartnerLookupAcrossRequests() throws Exception {
        UUID eventId = UUID.randomUUID();
        byte[] body = mapper.writeValueAsBytes(mapper.createObjectNode()
                .put("eventType", "OrderCreated")
                .set("payload", mapper.createObjectNode()));

        // First call — cache miss; the entry is loaded from DB
        sendValid(eventId, body);
        assertThat(cache.getIfPresent(PARTNER_ID)).isPresent();

        // Modify the partner row directly to make it inactive — but the cache
        // still holds the old (active) record, so the second request still
        // succeeds. (After the cache TTL expires the next request would fail.)
        jdbc.update("UPDATE partners SET active = FALSE WHERE partner_id = ?", PARTNER_ID);
        try {
            sendValid(UUID.randomUUID(), body);
        } finally {
            jdbc.update("UPDATE partners SET active = TRUE WHERE partner_id = ?", PARTNER_ID);
            cache.invalidate(PARTNER_ID);
        }
    }

    private void sendValid(UUID eventId, byte[] body) throws Exception {
        String ts = Instant.now().toString();
        String hashHex = sha256Hex(CURRENT_SECRET);
        String sig = verifier.signForTesting(hashHex, PARTNER_ID, ts,
                "POST", "/api/v1/events", body);

        mvc.perform(post("/api/v1/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(props.getHeaders().getPartnerId(), PARTNER_ID)
                        .header(props.getHeaders().getTimestamp(), ts)
                        .header(props.getHeaders().getSignature(), sig)
                        .header(props.getHeaders().getIdempotencyKey(), eventId.toString())
                        .content(body))
                .andExpect(status().isOk());
    }

    private static String sha256Hex(String secret) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(secret.getBytes(StandardCharsets.UTF_8)));
    }
}
