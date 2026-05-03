package com.example.peg.partner;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PartnerRepositoryTest {

    private JdbcTemplate jdbc;
    private PartnerRepository repo;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        repo = new PartnerRepository(jdbc);
    }

    @Test
    void findById_returnsEmpty_whenNoRow() {
        when(jdbc.queryForObject(any(String.class), any(RowMapper.class), eq("missing")))
                .thenThrow(new EmptyResultDataAccessException(1));

        Optional<Partner> result = repo.findById("missing");

        assertThat(result).isEmpty();
    }

    @Test
    void findById_mapsRow_whenFound() throws Exception {
        Partner mapped = new Partner("p", "hash", null, null, true);
        when(jdbc.queryForObject(any(String.class), any(RowMapper.class), eq("p")))
                .thenReturn(mapped);

        Optional<Partner> result = repo.findById("p");

        assertThat(result).contains(mapped);
    }

    @Test
    void rowMapper_mapsActiveRowWithoutPreviousSecret() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("partner_id")).thenReturn("p");
        when(rs.getString("secret_hash")).thenReturn("hash");
        when(rs.getString("previous_secret_hash")).thenReturn(null);
        when(rs.getTimestamp("previous_secret_expires_at")).thenReturn(null);
        when(rs.getBoolean("active")).thenReturn(true);

        Partner p = invokeRowMapper(rs);

        assertThat(p.partnerId()).isEqualTo("p");
        assertThat(p.secretHashHex()).isEqualTo("hash");
        assertThat(p.previousSecretHashHex()).isNull();
        assertThat(p.previousSecretExpiresAt()).isNull();
        assertThat(p.active()).isTrue();
    }

    @Test
    void rowMapper_mapsRotationWindowRow() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        Instant expiry = Instant.parse("2024-06-01T00:00:00Z");
        when(rs.getString("partner_id")).thenReturn("p");
        when(rs.getString("secret_hash")).thenReturn("new-hash");
        when(rs.getString("previous_secret_hash")).thenReturn("old-hash");
        when(rs.getTimestamp("previous_secret_expires_at")).thenReturn(Timestamp.from(expiry));
        when(rs.getBoolean("active")).thenReturn(true);

        Partner p = invokeRowMapper(rs);

        assertThat(p.previousSecretHashHex()).isEqualTo("old-hash");
        assertThat(p.previousSecretExpiresAt()).isEqualTo(expiry);
    }

    /** Capture the private rowmapper by triggering a queryForObject. */
    private Partner invokeRowMapper(ResultSet rs) throws Exception {
        org.mockito.ArgumentCaptor<RowMapper<Partner>> captor =
                org.mockito.ArgumentCaptor.forClass(RowMapper.class);
        when(jdbc.queryForObject(any(String.class), captor.capture(), eq("p")))
                .thenReturn(null);
        repo.findById("p");
        return captor.getValue().mapRow(rs, 1);
    }
}
