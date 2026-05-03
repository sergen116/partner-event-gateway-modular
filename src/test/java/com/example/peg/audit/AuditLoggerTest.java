package com.example.peg.audit;

import com.example.peg.shared.EventStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuditLoggerTest {

    private JdbcTemplate jdbc;
    private AuditLogger logger;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        logger = new AuditLogger(jdbc);
    }

    @Test
    void transition_writesAllColumns_withFromStatus() {
        UUID id = UUID.randomUUID();
        logger.transition("p", id, EventStatus.PENDING, EventStatus.PROCESSING, "worker:foo", null);

        verify(jdbc).update(anyString(),
                eq("p"), eq(id),
                eq("PENDING"), eq("PROCESSING"),
                eq("worker:foo"), eq((String) null));
    }

    @Test
    void transition_writesNullFromStatus_forInitialReceived() {
        UUID id = UUID.randomUUID();
        logger.transition("p", id, null, EventStatus.RECEIVED, "ingest", null);

        verify(jdbc).update(anyString(),
                eq("p"), eq(id),
                eq((String) null), eq("RECEIVED"),
                eq("ingest"), eq((String) null));
    }

    @Test
    void transition_writesError_onFailedTransition() {
        UUID id = UUID.randomUUID();
        logger.transition("p", id, EventStatus.PROCESSING, EventStatus.FAILED, "worker:foo", "boom");

        verify(jdbc).update(anyString(),
                eq("p"), eq(id),
                eq("PROCESSING"), eq("FAILED"),
                eq("worker:foo"), eq("boom"));
    }

    @Test
    void historyFor_withoutHint_skipsTimestampParameter() {
        UUID id = UUID.randomUUID();
        when(jdbc.query(anyString(), any(RowMapper.class), eq("p"), eq(id)))
                .thenReturn(List.of());

        logger.historyFor("p", id, null);

        verify(jdbc).query(anyString(), any(RowMapper.class), eq("p"), eq(id));
    }

    @Test
    void historyFor_withHint_passesTimestamp() {
        UUID id = UUID.randomUUID();
        Instant after = Instant.parse("2024-06-01T00:00:00Z");
        when(jdbc.query(anyString(), any(RowMapper.class),
                eq("p"), eq(id), any(Timestamp.class)))
                .thenReturn(List.of());

        logger.historyFor("p", id, after);

        verify(jdbc).query(anyString(), any(RowMapper.class),
                eq("p"), eq(id), any(Timestamp.class));
    }

    @Test
    void rowMapper_mapsAllColumns_withFromStatus() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        UUID id = UUID.randomUUID();
        Instant occurred = Instant.parse("2024-06-01T00:00:00Z");
        when(rs.getLong("id")).thenReturn(7L);
        when(rs.getString("partner_id")).thenReturn("p");
        when(rs.getObject("event_id")).thenReturn(id);
        when(rs.getString("from_status")).thenReturn("PENDING");
        when(rs.getString("to_status")).thenReturn("PROCESSING");
        when(rs.getString("actor")).thenReturn("worker:foo");
        when(rs.getString("error")).thenReturn(null);
        when(rs.getTimestamp("occurred_at")).thenReturn(Timestamp.from(occurred));

        AuditRecord r = invokeRowMapper(rs);

        assertThat(r.id()).isEqualTo(7L);
        assertThat(r.partnerId()).isEqualTo("p");
        assertThat(r.eventId()).isEqualTo(id);
        assertThat(r.fromStatus()).isEqualTo(EventStatus.PENDING);
        assertThat(r.toStatus()).isEqualTo(EventStatus.PROCESSING);
        assertThat(r.actor()).isEqualTo("worker:foo");
        assertThat(r.error()).isNull();
        assertThat(r.occurredAt()).isEqualTo(occurred);
    }

    @Test
    void rowMapper_mapsNullFromStatus_forInitialReceived() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        UUID id = UUID.randomUUID();
        when(rs.getLong("id")).thenReturn(1L);
        when(rs.getString("partner_id")).thenReturn("p");
        when(rs.getObject("event_id")).thenReturn(id);
        when(rs.getString("from_status")).thenReturn(null);
        when(rs.getString("to_status")).thenReturn("RECEIVED");
        when(rs.getString("actor")).thenReturn("ingest");
        when(rs.getString("error")).thenReturn(null);
        when(rs.getTimestamp("occurred_at")).thenReturn(Timestamp.from(Instant.now()));

        AuditRecord r = invokeRowMapper(rs);
        assertThat(r.fromStatus()).isNull();
        assertThat(r.toStatus()).isEqualTo(EventStatus.RECEIVED);
    }

    private AuditRecord invokeRowMapper(ResultSet rs) throws Exception {
        ArgumentCaptor<RowMapper<AuditRecord>> captor =
                ArgumentCaptor.forClass(RowMapper.class);
        UUID id = UUID.randomUUID();
        when(jdbc.query(anyString(), captor.capture(), eq("p"), eq(id))).thenReturn(List.of());
        logger.historyFor("p", id, null);
        return captor.getValue().mapRow(rs, 0);
    }
}
