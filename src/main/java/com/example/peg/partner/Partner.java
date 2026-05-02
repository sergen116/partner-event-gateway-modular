package com.example.peg.partner;

import java.time.Instant;

/**
 * Partner record loaded for HMAC verification.
 *
 * <p>{@code secretHashHex} is the SHA-256 hex of the partner's secret —
 * the secret itself never appears in storage, logs, or memory beyond the
 * verifier's stack frame. {@code previousSecretHashHex} supports rotation:
 * during the rotation window, requests signed with either secret are accepted.
 */
public record Partner(
        String partnerId,
        String secretHashHex,
        String previousSecretHashHex,
        Instant previousSecretExpiresAt,
        boolean active
) {
    public boolean previousSecretValid(Instant now) {
        return previousSecretHashHex != null
                && previousSecretExpiresAt != null
                && previousSecretExpiresAt.isAfter(now);
    }
}
