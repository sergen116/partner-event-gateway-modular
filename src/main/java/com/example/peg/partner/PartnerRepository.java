package com.example.peg.partner;

import com.example.peg.partner.Partner;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class PartnerRepository {

    private final JdbcTemplate jdbc;

    private static final RowMapper<Partner> ROW = (rs, i) -> new Partner(
            rs.getString("partner_id"),
            rs.getString("secret_hash"),
            rs.getString("previous_secret_hash"),
            rs.getTimestamp("previous_secret_expires_at") == null
                    ? null
                    : rs.getTimestamp("previous_secret_expires_at").toInstant(),
            rs.getBoolean("active"));

    public Optional<Partner> findById(String partnerId) {
        try {
            Partner p = jdbc.queryForObject(
                    "SELECT partner_id, secret_hash, previous_secret_hash, " +
                    "       previous_secret_expires_at, active " +
                    "FROM partners WHERE partner_id = ?",
                    ROW, partnerId);
            return Optional.ofNullable(p);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }
}
