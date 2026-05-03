package com.example.peg.ingest;

import com.example.peg.PgmqPostgresInitializer;
import com.example.peg.delivery.DownstreamCallService;
import com.example.peg.partner.HmacVerifier;
import com.example.peg.platform.SecurityProperties;
import com.example.peg.shared.EventStatus;
import com.example.peg.shared.EventType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Controller-level IT covering the partner-facing endpoints with the real
 * security filter active. Uses MockMvc (not a live HTTP port) so the test
 * runs faster but still exercises the auth filter, validation, and
 * controller→service→repo wiring against Testcontainers Postgres.
 */
@SpringBootTest
@ContextConfiguration(initializers = PgmqPostgresInitializer.class)
class PartnerEventsControllerIT {

    private static final String PARTNER_ID = "partner-acme";
    private static final String SECRET = "acme-shared-secret-2024";

    @Autowired WebApplicationContext webContext;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired HmacVerifier verifier;
    @Autowired SecurityProperties props;

    @MockBean DownstreamCallService downstream;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(webContext)
                .addFilters(webContext.getBean(com.example.peg.partner.PartnerAuthFilter.class))
                .build();
        jdbc.update("DELETE FROM event_outbox");
        jdbc.update("DELETE FROM events");
        jdbc.update("DELETE FROM event_audit_log");
    }

    @Test
    void postWithoutAuthHeaders_returns401() throws Exception {
        mvc.perform(post("/api/v1/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventType\":\"OrderCreated\",\"payload\":{}}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void postWithUnknownPartner_returns401() throws Exception {
        byte[] body = "{\"eventType\":\"OrderCreated\",\"payload\":{}}".getBytes(StandardCharsets.UTF_8);
        String ts = Instant.now().toString();

        mvc.perform(post("/api/v1/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(props.getHeaders().getPartnerId(), "ghost")
                        .header(props.getHeaders().getTimestamp(), ts)
                        .header(props.getHeaders().getSignature(), "irrelevant")
                        .content(body))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void postWithBadSignature_returns401() throws Exception {
        byte[] body = "{\"eventType\":\"OrderCreated\",\"payload\":{}}".getBytes(StandardCharsets.UTF_8);

        mvc.perform(post("/api/v1/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(props.getHeaders().getPartnerId(), PARTNER_ID)
                        .header(props.getHeaders().getTimestamp(), Instant.now().toString())
                        .header(props.getHeaders().getSignature(), "obviously-wrong")
                        .content(body))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void postWithStaleTimestamp_returns401() throws Exception {
        byte[] body = "{\"eventType\":\"OrderCreated\",\"payload\":{}}".getBytes(StandardCharsets.UTF_8);
        String ts = Instant.now().minus(Duration.ofMinutes(30)).toString();
        String sig = sign(ts, "POST", "/api/v1/events", body);

        mvc.perform(post("/api/v1/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(props.getHeaders().getPartnerId(), PARTNER_ID)
                        .header(props.getHeaders().getTimestamp(), ts)
                        .header(props.getHeaders().getSignature(), sig)
                        .content(body))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void postWithValidSignature_persistsEvent() throws Exception {
        UUID eventId = UUID.randomUUID();
        ObjectNode body = mapper.createObjectNode().put("eventType", "OrderCreated").put("businessRef", "ORD-1");
        body.set("payload", mapper.createObjectNode().put("k", 1));
        byte[] bodyBytes = mapper.writeValueAsBytes(body);
        String ts = Instant.now().toString();
        String sig = sign(ts, "POST", "/api/v1/events", bodyBytes);

        mvc.perform(post("/api/v1/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(props.getHeaders().getPartnerId(), PARTNER_ID)
                        .header(props.getHeaders().getTimestamp(), ts)
                        .header(props.getHeaders().getSignature(), sig)
                        .header(props.getHeaders().getIdempotencyKey(), eventId.toString())
                        .content(bodyBytes))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventId").value(eventId.toString()))
                .andExpect(jsonPath("$.duplicate").value(false));

        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM events WHERE partner_id = ? AND event_id = ?",
                Long.class, PARTNER_ID, eventId);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void postWithInvalidIdempotencyKey_returns400() throws Exception {
        ObjectNode body = mapper.createObjectNode().put("eventType", "OrderCreated");
        body.set("payload", mapper.createObjectNode());
        byte[] bodyBytes = mapper.writeValueAsBytes(body);
        String ts = Instant.now().toString();
        String sig = sign(ts, "POST", "/api/v1/events", bodyBytes);

        mvc.perform(post("/api/v1/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(props.getHeaders().getPartnerId(), PARTNER_ID)
                        .header(props.getHeaders().getTimestamp(), ts)
                        .header(props.getHeaders().getSignature(), sig)
                        .header(props.getHeaders().getIdempotencyKey(), "not-a-uuid")
                        .content(bodyBytes))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_IDEMPOTENCY_KEY"));
    }

    @Test
    void postWithMissingPayload_returns400() throws Exception {
        // payload is @NotNull — missing it triggers MethodArgumentNotValidException
        byte[] bodyBytes = "{\"eventType\":\"OrderCreated\"}".getBytes(StandardCharsets.UTF_8);
        String ts = Instant.now().toString();
        String sig = sign(ts, "POST", "/api/v1/events", bodyBytes);

        mvc.perform(post("/api/v1/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(props.getHeaders().getPartnerId(), PARTNER_ID)
                        .header(props.getHeaders().getTimestamp(), ts)
                        .header(props.getHeaders().getSignature(), sig)
                        .content(bodyBytes))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void getEventById_returnsExistingEvent() throws Exception {
        // Seed an event in the DB directly so we don't depend on the outbox poller
        UUID eventId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO events (partner_id, event_id, event_type, business_ref, payload, status) " +
                "VALUES (?, ?, ?, ?, '{}'::jsonb, 'PROCESSED')",
                PARTNER_ID, eventId, EventType.ORDER_CREATED.name(), "ORD-7");

        String ts = Instant.now().toString();
        String path = "/api/v1/events/" + eventId;
        String sig = sign(ts, "GET", path, new byte[0]);

        mvc.perform(get(path)
                        .header(props.getHeaders().getPartnerId(), PARTNER_ID)
                        .header(props.getHeaders().getTimestamp(), ts)
                        .header(props.getHeaders().getSignature(), sig))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventId").value(eventId.toString()))
                .andExpect(jsonPath("$.status").value(EventStatus.PROCESSED.name()));
    }

    @Test
    void getEventById_returns404_whenAbsent() throws Exception {
        String ts = Instant.now().toString();
        UUID eventId = UUID.randomUUID();
        String path = "/api/v1/events/" + eventId;
        String sig = sign(ts, "GET", path, new byte[0]);

        mvc.perform(get(path)
                        .header(props.getHeaders().getPartnerId(), PARTNER_ID)
                        .header(props.getHeaders().getTimestamp(), ts)
                        .header(props.getHeaders().getSignature(), sig))
                .andExpect(status().isNotFound());
    }

    @Test
    void queryEvents_filtersByPartner_andRespectsPagination() throws Exception {
        // Seed 3 events for partner-acme; query should see only those, not partner-globex's
        for (int i = 0; i < 3; i++) {
            jdbc.update(
                    "INSERT INTO events (partner_id, event_id, event_type, business_ref, payload, status) " +
                    "VALUES (?, ?, ?, ?, '{}'::jsonb, 'PROCESSED')",
                    PARTNER_ID, UUID.randomUUID(), EventType.ORDER_CREATED.name(), "A-" + i);
        }
        jdbc.update(
                "INSERT INTO events (partner_id, event_id, event_type, business_ref, payload, status) " +
                "VALUES (?, ?, ?, ?, '{}'::jsonb, 'PROCESSED')",
                "partner-globex", UUID.randomUUID(), EventType.ORDER_CREATED.name(), "B-1");

        String ts = Instant.now().toString();
        String path = "/api/v1/events?page=0&size=2";
        String sig = sign(ts, "GET", "/api/v1/events", new byte[0]);

        MvcResult result = mvc.perform(get(path)
                        .header(props.getHeaders().getPartnerId(), PARTNER_ID)
                        .header(props.getHeaders().getTimestamp(), ts)
                        .header(props.getHeaders().getSignature(), sig))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(3))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andReturn();

        // No partner-globex leakage
        assertThat(result.getResponse().getContentAsString()).doesNotContain("B-1");
    }

    @Test
    void queryEvents_rejectsInvalidPaging() throws Exception {
        String ts = Instant.now().toString();
        String sig = sign(ts, "GET", "/api/v1/events", new byte[0]);

        mvc.perform(get("/api/v1/events?page=-1")
                        .header(props.getHeaders().getPartnerId(), PARTNER_ID)
                        .header(props.getHeaders().getTimestamp(), ts)
                        .header(props.getHeaders().getSignature(), sig))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PAGE"));
    }

    private String sign(String ts, String method, String path, byte[] body) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        String hashHex = HexFormat.of().formatHex(md.digest(SECRET.getBytes(StandardCharsets.UTF_8)));
        return verifier.signForTesting(hashHex, PARTNER_ID, ts, method, path, body);
    }
}
