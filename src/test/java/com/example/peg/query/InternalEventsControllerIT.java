package com.example.peg.query;

import com.example.peg.PgmqPostgresInitializer;
import com.example.peg.shared.EventType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Internal endpoint IT — partner_id can be specified explicitly, no HMAC
 * (operator OIDC would sit in front in production). Uses MockMvc against
 * a real Spring context + Testcontainers Postgres.
 */
@SpringBootTest
@ContextConfiguration(initializers = PgmqPostgresInitializer.class)
class InternalEventsControllerIT {

    @Autowired WebApplicationContext webContext;
    @Autowired JdbcTemplate jdbc;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(webContext).build();
        jdbc.update("DELETE FROM events");
    }

    @Test
    void crossPartnerQuery_returnsAllPartners_whenNoFilter() throws Exception {
        seed("partner-acme", EventType.ORDER_CREATED, "A-1");
        seed("partner-globex", EventType.SHIPMENT_UPDATED, "B-1");

        mvc.perform(get("/api/v1/internal/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(2));
    }

    @Test
    void crossPartnerQuery_filtersByExplicitPartner() throws Exception {
        seed("partner-acme", EventType.ORDER_CREATED, "A-1");
        seed("partner-globex", EventType.ORDER_CREATED, "B-1");

        mvc.perform(get("/api/v1/internal/events?partnerId=partner-acme"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].partnerId").value("partner-acme"));
    }

    @Test
    void crossPartnerQuery_filtersByEventType() throws Exception {
        seed("partner-acme", EventType.ORDER_CREATED, "A-1");
        seed("partner-acme", EventType.SHIPMENT_UPDATED, "A-2");

        mvc.perform(get("/api/v1/internal/events?eventType=OrderCreated"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(1));
    }

    @Test
    void crossPartnerQuery_rejectsInvalidPaging() throws Exception {
        mvc.perform(get("/api/v1/internal/events?size=0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SIZE"));
    }

    private void seed(String partner, EventType type, String ref) {
        jdbc.update(
                "INSERT INTO events (partner_id, event_id, event_type, business_ref, payload, status) " +
                "VALUES (?, ?, ?, ?, '{}'::jsonb, 'PROCESSED')",
                partner, UUID.randomUUID(), type.name(), ref);
    }
}
