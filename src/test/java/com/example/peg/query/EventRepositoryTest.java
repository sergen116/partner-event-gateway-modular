package com.example.peg.query;

import com.example.peg.audit.AuditLogger;
import com.example.peg.shared.EventRecord;
import com.example.peg.shared.EventStatus;
import com.example.peg.shared.EventType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.postgresql.util.PGobject;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EventRepositoryTest {

    private JdbcTemplate jdbc;
    private JdbcTemplate readJdbc;
    private ObjectMapper mapper;
    private AuditLogger audit;
    private EventRepository repo;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        readJdbc = mock(JdbcTemplate.class);
        mapper = new ObjectMapper();
        audit = mock(AuditLogger.class);
        repo = new EventRepository(jdbc, readJdbc, mapper, audit);
    }

    @Test
    void insertIfAbsent_writesAuditOnFirstInsert() throws Exception {
        UUID eventId = UUID.randomUUID();
        EventRecord inserted = recordFor(eventId);
        // Pre-insert findById misses; INSERT ... RETURNING * gives back the new row.
        when(jdbc.queryForObject(anyString(), any(RowMapper.class), eq("p"), eq(eventId)))
                .thenThrow(new EmptyResultDataAccessException(1));
        when(jdbc.query(anyString(), any(RowMapper.class), any(), any(), any(), any(), any()))
                .thenReturn(java.util.List.of(inserted));

        EventRepository.InsertResult result = repo.insertIfAbsent(
                "p", eventId, EventType.ORDER_CREATED, "ORD-1",
                mapper.readTree("{\"x\":1}"), "ingest");

        assertThat(result.wasInserted()).isTrue();
        verify(audit).transition("p", eventId, null, EventStatus.RECEIVED, "ingest", null);
    }

    @Test
    void insertIfAbsent_skipsAudit_whenAlreadyPresent() throws Exception {
        UUID eventId = UUID.randomUUID();
        EventRecord existing = recordFor(eventId);
        // Pre-insert findById hits → method returns immediately, INSERT never runs.
        when(jdbc.queryForObject(anyString(), any(RowMapper.class), eq("p"), eq(eventId)))
                .thenReturn(existing);

        EventRepository.InsertResult result = repo.insertIfAbsent(
                "p", eventId, EventType.ORDER_CREATED, "ORD-1",
                mapper.readTree("{\"x\":1}"), "ingest");

        assertThat(result.wasInserted()).isFalse();
        verify(audit, never()).transition(any(), any(), any(), any(), any(), any());
    }

    @Test
    void insertIfAbsent_throws_whenRowMissingAfterInsertConflict() {
        UUID eventId = UUID.randomUUID();
        // Pre-insert findById misses; INSERT ... RETURNING * returns no rows
        // (same-microsecond conflict), post-conflict findById also misses —
        // surface as IllegalStateException.
        when(jdbc.queryForObject(anyString(), any(RowMapper.class), eq("p"), eq(eventId)))
                .thenThrow(new EmptyResultDataAccessException(1));
        when(jdbc.query(anyString(), any(RowMapper.class), any(), any(), any(), any(), any()))
                .thenReturn(java.util.List.of());

        assertThatThrownBy(() -> repo.insertIfAbsent(
                "p", eventId, EventType.ORDER_CREATED, null,
                mapper.nullNode(), "ingest"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("event missing after insert");
    }

    @Test
    void findById_returnsEmpty_onMissingRow() {
        when(jdbc.queryForObject(anyString(), any(RowMapper.class),
                eq("p"), any(UUID.class)))
                .thenThrow(new EmptyResultDataAccessException(1));

        Optional<EventRecord> result = repo.findById("p", UUID.randomUUID());
        assertThat(result).isEmpty();
    }

    @Test
    void findById_withNullHint_callsBoundFreeQuery() {
        UUID id = UUID.randomUUID();
        EventRecord row = recordFor(id);
        when(jdbc.queryForObject(anyString(), any(RowMapper.class), eq("p"), eq(id)))
                .thenReturn(row);

        assertThat(repo.findById("p", id, null)).contains(row);
    }

    @Test
    void findById_withCreatedAtHint_appliesPartitionPruning() {
        UUID id = UUID.randomUUID();
        Instant hint = Instant.parse("2024-06-01T00:00:00Z");
        EventRecord row = recordFor(id);
        when(jdbc.queryForObject(anyString(), any(RowMapper.class),
                eq("p"), eq(id), any(Timestamp.class), any(Timestamp.class)))
                .thenReturn(row);

        assertThat(repo.findById("p", id, hint)).contains(row);
    }

    @Test
    void findById_withHint_returnsEmpty_whenMissing() {
        when(jdbc.queryForObject(anyString(), any(RowMapper.class),
                eq("p"), any(UUID.class), any(Timestamp.class), any(Timestamp.class)))
                .thenThrow(new EmptyResultDataAccessException(1));

        assertThat(repo.findById("p", UUID.randomUUID(),
                Instant.parse("2024-06-01T00:00:00Z"))).isEmpty();
    }

    @Test
    void markPending_writesAuditOnSuccessfulTransition() {
        UUID id = UUID.randomUUID();
        when(jdbc.update(anyString(), eq("p"), eq(id))).thenReturn(1);
        repo.markPending("p", id, "outbox-poller");
        verify(audit).transition("p", id, EventStatus.RECEIVED, EventStatus.PENDING,
                "outbox-poller", null);
    }

    @Test
    void markPending_skipsAudit_whenNoRowUpdated() {
        UUID id = UUID.randomUUID();
        when(jdbc.update(anyString(), eq("p"), eq(id))).thenReturn(0);
        repo.markPending("p", id, "outbox-poller");
        verify(audit, never()).transition(any(), any(), any(), any(), any(), any());
    }

    @Test
    void tryMarkProcessing_returnsTrue_andAudits() {
        UUID id = UUID.randomUUID();
        when(jdbc.update(anyString(), eq("p"), eq(id))).thenReturn(1);
        boolean ok = repo.tryMarkProcessing("p", id, "worker:foo");
        assertThat(ok).isTrue();
        verify(audit).transition("p", id, EventStatus.PENDING, EventStatus.PROCESSING,
                "worker:foo", null);
    }

    @Test
    void tryMarkProcessing_returnsFalse_whenNoRowUpdated() {
        when(jdbc.update(anyString(), anyString(), any(UUID.class))).thenReturn(0);
        assertThat(repo.tryMarkProcessing("p", UUID.randomUUID(), "actor")).isFalse();
        verify(audit, never()).transition(any(), any(), any(), any(), any(), any());
    }

    @Test
    void markProcessed_auditsTransition() {
        UUID id = UUID.randomUUID();
        when(jdbc.update(anyString(), eq("p"), eq(id))).thenReturn(1);
        repo.markProcessed("p", id, "worker:foo");
        verify(audit).transition("p", id, EventStatus.PROCESSING, EventStatus.PROCESSED,
                "worker:foo", null);
    }

    @Test
    void markFailed_truncatesLongErrorMessage() {
        String veryLong = "x".repeat(2000);
        UUID id = UUID.randomUUID();
        when(jdbc.update(anyString(), any(), any(), any())).thenReturn(1);

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        repo.markFailed("p", id, veryLong, "actor");

        verify(audit).transition(eq("p"), eq(id),
                eq(EventStatus.PROCESSING), eq(EventStatus.FAILED), eq("actor"),
                org.mockito.ArgumentMatchers.argThat(s -> ((String) s).length() == 1000));
    }

    @Test
    void markFailed_acceptsNullError() {
        UUID id = UUID.randomUUID();
        when(jdbc.update(anyString(), any(), any(), any())).thenReturn(1);
        repo.markFailed("p", id, null, "actor");
        verify(audit).transition("p", id, EventStatus.PROCESSING, EventStatus.FAILED, "actor", null);
    }

    @Test
    void query_buildsSqlAndPagesArgs() {
        when(readJdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(java.util.List.of());

        EventQuery q = EventQuery.builder().partnerId("p").page(2).size(20).build();
        repo.query(q);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(readJdbc).query(sql.capture(), any(RowMapper.class), any(Object[].class));
        assertThat(sql.getValue())
                .contains("ORDER BY created_at DESC, id DESC")
                .contains("LIMIT ? OFFSET ?")
                .contains("WHERE partner_id = ?");
    }

    @Test
    void count_returnsZero_whenNullFromQuery() {
        when(readJdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(null);
        assertThat(repo.count(EventQuery.builder().build())).isZero();
    }

    @Test
    void count_returnsValue() {
        when(readJdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(7L);
        assertThat(repo.count(EventQuery.builder().build())).isEqualTo(7L);
    }

    @Test
    void rowMapper_handlesPgObjectPayload_andNullProcessedAt() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        UUID id = UUID.randomUUID();
        Instant created = Instant.parse("2024-06-01T00:00:00Z");

        PGobject pg = new PGobject();
        pg.setType("jsonb");
        pg.setValue("{\"k\":42}");

        when(rs.getLong("id")).thenReturn(1L);
        when(rs.getString("partner_id")).thenReturn("p");
        when(rs.getObject("event_id")).thenReturn(id);
        when(rs.getString("event_type")).thenReturn("ORDER_CREATED");
        when(rs.getString("business_ref")).thenReturn("ORD-1");
        when(rs.getObject("payload")).thenReturn(pg);
        when(rs.getString("status")).thenReturn("RECEIVED");
        when(rs.getString("error")).thenReturn(null);
        when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(created));
        when(rs.getTimestamp("processed_at")).thenReturn(null);

        EventRecord r = invokeRowMapper(rs);
        assertThat(r.partnerId()).isEqualTo("p");
        assertThat(r.payload().get("k").intValue()).isEqualTo(42);
        assertThat(r.processedAt()).isNull();
    }

    @Test
    void rowMapper_handlesNullPayload_asJsonNull() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        UUID id = UUID.randomUUID();
        Instant created = Instant.parse("2024-06-01T00:00:00Z");
        Instant processed = Instant.parse("2024-06-01T00:01:00Z");

        when(rs.getLong("id")).thenReturn(1L);
        when(rs.getString("partner_id")).thenReturn("p");
        when(rs.getObject("event_id")).thenReturn(id);
        when(rs.getString("event_type")).thenReturn("ORDER_CREATED");
        when(rs.getString("business_ref")).thenReturn(null);
        when(rs.getObject("payload")).thenReturn(null);
        when(rs.getString("status")).thenReturn("PROCESSED");
        when(rs.getString("error")).thenReturn(null);
        when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(created));
        when(rs.getTimestamp("processed_at")).thenReturn(Timestamp.from(processed));

        EventRecord r = invokeRowMapper(rs);
        assertThat(r.payload().isNull()).isTrue();
        assertThat(r.processedAt()).isEqualTo(processed);
    }

    private EventRecord invokeRowMapper(ResultSet rs) throws Exception {
        ArgumentCaptor<RowMapper<EventRecord>> captor = ArgumentCaptor.forClass(RowMapper.class);
        UUID id = UUID.randomUUID();
        when(jdbc.queryForObject(anyString(), captor.capture(), eq("p"), eq(id))).thenReturn(null);
        repo.findById("p", id);
        return captor.getValue().mapRow(rs, 1);
    }

    private EventRecord recordFor(UUID id) {
        return new EventRecord(1L, "p", id,
                EventType.ORDER_CREATED, "ORD-1",
                mapper.nullNode(), EventStatus.RECEIVED, null,
                Instant.parse("2024-06-01T00:00:00Z"), null);
    }
}
