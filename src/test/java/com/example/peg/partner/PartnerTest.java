package com.example.peg.partner;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class PartnerTest {

    @Test
    void previousSecretValid_returnsFalse_whenNoPreviousSecret() {
        Partner p = new Partner("p", "hash", null, null, true);
        assertThat(p.previousSecretValid(Instant.now())).isFalse();
    }

    @Test
    void previousSecretValid_returnsFalse_whenExpired() {
        Partner p = new Partner("p", "hash", "old-hash",
                Instant.now().minus(Duration.ofMinutes(1)), true);
        assertThat(p.previousSecretValid(Instant.now())).isFalse();
    }

    @Test
    void previousSecretValid_returnsTrue_whenWithinWindow() {
        Partner p = new Partner("p", "hash", "old-hash",
                Instant.now().plus(Duration.ofHours(1)), true);
        assertThat(p.previousSecretValid(Instant.now())).isTrue();
    }

    @Test
    void previousSecretValid_returnsFalse_whenHashSetButExpiryNull() {
        // Defensive — both fields must be set together
        Partner p = new Partner("p", "hash", "old-hash", null, true);
        assertThat(p.previousSecretValid(Instant.now())).isFalse();
    }
}
