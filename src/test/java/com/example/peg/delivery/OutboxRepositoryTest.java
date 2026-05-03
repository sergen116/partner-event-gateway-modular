package com.example.peg.delivery;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutboxRepositoryTest {

    private JdbcTemplate jdbc;
    private OutboxRepository repo;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        repo = new OutboxRepository(jdbc);
    }

    @Test
    void insert_writesAllColumns() {
        UUID eventId = UUID.randomUUID();
        repo.insert("p", eventId, "events_order_created", "{\"x\":1}");

        verify(jdbc).update(anyString(),
                org.mockito.ArgumentMatchers.eq("p"),
                org.mockito.ArgumentMatchers.eq(eventId),
                org.mockito.ArgumentMatchers.eq("events_order_created"),
                org.mockito.ArgumentMatchers.eq("{\"x\":1}"));
    }

    @Test
    void claimBatch_executesQueryWithLimit() {
        when(jdbc.query(anyString(), any(RowMapper.class), anyInt())).thenReturn(List.of());
        repo.claimBatch(100);
        verify(jdbc).query(anyString(), any(RowMapper.class),
                org.mockito.ArgumentMatchers.eq(100));
    }

    @Test
    void claimBatch_rowMapperParsesAllColumns() throws Exception {
        ArgumentCaptor<RowMapper<OutboxRepository.OutboxRow>> captor =
                ArgumentCaptor.forClass(RowMapper.class);
        when(jdbc.query(anyString(), captor.capture(), anyInt())).thenReturn(List.of());

        repo.claimBatch(10);

        ResultSet rs = mock(ResultSet.class);
        UUID eventId = UUID.randomUUID();
        when(rs.getLong("id")).thenReturn(7L);
        when(rs.getString("partner_id")).thenReturn("p");
        when(rs.getObject("event_id")).thenReturn(eventId);
        when(rs.getString("queue_name")).thenReturn("q");
        when(rs.getString("payload")).thenReturn("{}");
        when(rs.getInt("attempts")).thenReturn(2);

        OutboxRepository.OutboxRow row = captor.getValue().mapRow(rs, 0);

        assertThat(row.id()).isEqualTo(7L);
        assertThat(row.partnerId()).isEqualTo("p");
        assertThat(row.eventId()).isEqualTo(eventId);
        assertThat(row.queueName()).isEqualTo("q");
        assertThat(row.payload()).isEqualTo("{}");
        assertThat(row.attempts()).isEqualTo(2);
    }

    @Test
    void deleteSent_executesDelete() {
        repo.deleteSent(42L);
        verify(jdbc).update(anyString(), org.mockito.ArgumentMatchers.eq(42L));
    }

    @Test
    void recordFailure_incrementsAttempts() {
        repo.recordFailure(11L);
        verify(jdbc).update(anyString(), org.mockito.ArgumentMatchers.eq(11L));
    }
}
