package com.example.peg.partner;

import com.example.peg.platform.SecurityProperties;
import com.example.peg.partner.Partner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

class HmacVerifierTest {

    private static final String SECRET = "test-secret-2024";
    private static final String PARTNER_ID = "partner-test";
    private HmacVerifier verifier;
    private String secretHashHex;

    @BeforeEach
    void setUp() throws NoSuchAlgorithmException {
        SecurityProperties props = new SecurityProperties();
        verifier = new HmacVerifier(props);

        // SHA-256 of secret, hex-encoded — matches what would be stored in partners.secret_hash
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] hash = md.digest(SECRET.getBytes(StandardCharsets.UTF_8));
        secretHashHex = HexFormat.of().formatHex(hash);
    }

    @Test
    void verify_returnsTrue_forCorrectlySignedRequest() {
        Partner partner = new Partner(PARTNER_ID, secretHashHex, null, null, true);
        String ts = Instant.now().toString();
        byte[] body = "{\"eventType\":\"OrderCreated\"}".getBytes(StandardCharsets.UTF_8);

        String sig = verifier.signForTesting(secretHashHex, PARTNER_ID, ts,
                "POST", "/api/v1/events", body);

        boolean ok = verifier.verify(partner, ts, "POST", "/api/v1/events", body, sig);

        assertThat(ok).isTrue();
    }

    @Test
    void verify_returnsFalse_forTamperedBody() {
        Partner partner = new Partner(PARTNER_ID, secretHashHex, null, null, true);
        String ts = Instant.now().toString();
        byte[] body = "{\"eventType\":\"OrderCreated\"}".getBytes(StandardCharsets.UTF_8);
        byte[] tamperedBody = "{\"eventType\":\"OrderCancelled\"}".getBytes(StandardCharsets.UTF_8);

        String sig = verifier.signForTesting(secretHashHex, PARTNER_ID, ts,
                "POST", "/api/v1/events", body);

        boolean ok = verifier.verify(partner, ts, "POST", "/api/v1/events", tamperedBody, sig);

        assertThat(ok).isFalse();
    }

    @Test
    void verify_returnsFalse_forTamperedPath() {
        Partner partner = new Partner(PARTNER_ID, secretHashHex, null, null, true);
        String ts = Instant.now().toString();
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);

        String sig = verifier.signForTesting(secretHashHex, PARTNER_ID, ts,
                "POST", "/api/v1/events", body);

        // Same signature but trying to access a different path
        boolean ok = verifier.verify(partner, ts, "POST", "/api/v1/events/123", body, sig);

        assertThat(ok).isFalse();
    }

    @Test
    void verify_returnsFalse_forStaleTimestamp() {
        Partner partner = new Partner(PARTNER_ID, secretHashHex, null, null, true);
        String staleTs = Instant.now().minus(Duration.ofMinutes(10)).toString();
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);

        String sig = verifier.signForTesting(secretHashHex, PARTNER_ID, staleTs,
                "POST", "/api/v1/events", body);

        boolean ok = verifier.verify(partner, staleTs, "POST", "/api/v1/events", body, sig);

        assertThat(ok).isFalse();
    }

    @Test
    void verify_returnsFalse_forFutureTimestampBeyondSkew() {
        Partner partner = new Partner(PARTNER_ID, secretHashHex, null, null, true);
        String futureTs = Instant.now().plus(Duration.ofMinutes(10)).toString();
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);

        String sig = verifier.signForTesting(secretHashHex, PARTNER_ID, futureTs,
                "POST", "/api/v1/events", body);

        boolean ok = verifier.verify(partner, futureTs, "POST", "/api/v1/events", body, sig);

        assertThat(ok).isFalse();
    }

    @Test
    void verify_returnsFalse_forUnparseableTimestamp() {
        Partner partner = new Partner(PARTNER_ID, secretHashHex, null, null, true);
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);

        // Sign with a real timestamp, present a junk one — both must fail anyway
        String realTs = Instant.now().toString();
        String sig = verifier.signForTesting(secretHashHex, PARTNER_ID, realTs,
                "POST", "/api/v1/events", body);

        boolean ok = verifier.verify(partner, "not-a-timestamp",
                "POST", "/api/v1/events", body, sig);

        assertThat(ok).isFalse();
    }

    @Test
    void verify_returnsFalse_forInactivePartner() {
        Partner inactive = new Partner(PARTNER_ID, secretHashHex, null, null, false);
        String ts = Instant.now().toString();
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);

        String sig = verifier.signForTesting(secretHashHex, PARTNER_ID, ts,
                "POST", "/api/v1/events", body);

        boolean ok = verifier.verify(inactive, ts, "POST", "/api/v1/events", body, sig);

        assertThat(ok).isFalse();
    }

    @Test
    void verify_returnsFalse_forBadlyFormattedSignature() {
        Partner partner = new Partner(PARTNER_ID, secretHashHex, null, null, true);
        String ts = Instant.now().toString();
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);

        // Garbage base64
        boolean ok = verifier.verify(partner, ts, "POST", "/api/v1/events", body, "not-base64!!");

        assertThat(ok).isFalse();
    }

    @Test
    void verify_acceptsPreviousSecret_duringRotationWindow() throws NoSuchAlgorithmException {
        // Set up a partner mid-rotation: new secret active, previous still valid for an hour
        String newSecret = "new-secret-2024";
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        String newHashHex = HexFormat.of().formatHex(md.digest(newSecret.getBytes(StandardCharsets.UTF_8)));

        Partner rotating = new Partner(PARTNER_ID, newHashHex,
                /* previousSecretHashHex */ secretHashHex,
                /* previousSecretExpiresAt */ Instant.now().plus(Duration.ofHours(1)),
                true);

        String ts = Instant.now().toString();
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);

        // Partner still using OLD secret (= secretHashHex)
        String sigWithOldSecret = verifier.signForTesting(secretHashHex, PARTNER_ID, ts,
                "POST", "/api/v1/events", body);

        boolean ok = verifier.verify(rotating, ts, "POST", "/api/v1/events", body, sigWithOldSecret);

        assertThat(ok).as("during rotation window, previous secret is accepted").isTrue();
    }

    @Test
    void verify_rejectsPreviousSecret_afterRotationExpired() throws NoSuchAlgorithmException {
        String newSecret = "new-secret-2024";
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        String newHashHex = HexFormat.of().formatHex(md.digest(newSecret.getBytes(StandardCharsets.UTF_8)));

        Partner expiredRotation = new Partner(PARTNER_ID, newHashHex,
                secretHashHex,
                Instant.now().minus(Duration.ofMinutes(1)),    // expired
                true);

        String ts = Instant.now().toString();
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);

        String sigWithOldSecret = verifier.signForTesting(secretHashHex, PARTNER_ID, ts,
                "POST", "/api/v1/events", body);

        boolean ok = verifier.verify(expiredRotation, ts, "POST", "/api/v1/events", body, sigWithOldSecret);

        assertThat(ok).as("expired previous secret must be rejected").isFalse();
    }
}
