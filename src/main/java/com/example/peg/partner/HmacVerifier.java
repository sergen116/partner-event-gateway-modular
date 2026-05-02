package com.example.peg.partner;

import com.example.peg.platform.SecurityProperties;
import com.example.peg.partner.Partner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

/**
 * HMAC-SHA256 request verification.
 *
 * <p>Canonical message for the signature:
 * <pre>
 *   partnerId\n
 *   timestamp\n
 *   HTTP_METHOD\n
 *   request_path\n
 *   request_body
 * </pre>
 *
 * <p>The HMAC key is the SHA-256 of the partner's secret. Both the partner
 * and the gateway derive the key the same way; storing only SHA-256(secret)
 * means the raw secret never appears in DB rows or logs. (For real production,
 * the secret would live in a KMS / sealed secret store rather than a DB column;
 * see the README for the documented simplification.)
 *
 * <p>Anti-replay via timestamp window. Constant-time comparison everywhere
 * timing could leak information.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class HmacVerifier {

    private final SecurityProperties props;

    /**
     * Verify a presented signature against the partner's stored secret hash.
     *
     * @return true if the signature matches and the timestamp is within skew
     */
    public boolean verify(Partner partner,
                          String timestamp,
                          String method,
                          String path,
                          byte[] body,
                          String presentedSignatureBase64) {
        if (!partner.active()) {
            log.debug("partner {} is inactive", partner.partnerId());
            return false;
        }
        if (!withinSkew(timestamp)) {
            log.debug("partner {} timestamp outside skew", partner.partnerId());
            return false;
        }

        byte[] canonical = buildCanonicalMessage(partner.partnerId(), timestamp, method, path, body);

        // Try current secret hash, then previous (if rotation window active).
        if (matches(partner.secretHashHex(), canonical, presentedSignatureBase64)) {
            return true;
        }
        if (partner.previousSecretValid(Instant.now())
                && matches(partner.previousSecretHashHex(), canonical, presentedSignatureBase64)) {
            log.info("partner {} authenticated with previous secret (rotation window)",
                    partner.partnerId());
            return true;
        }
        return false;
    }

    private boolean matches(String secretHashHex, byte[] canonical, String presentedSignatureBase64) {
        try {
            byte[] keyBytes = HexFormat.of().parseHex(secretHashHex);
            byte[] expected = hmacSha256(keyBytes, canonical);
            byte[] presented = Base64.getDecoder().decode(presentedSignatureBase64);
            return MessageDigest.isEqual(expected, presented);
        } catch (IllegalArgumentException ex) {
            // Bad base64 / hex format — not a match
            return false;
        }
    }

    private boolean withinSkew(String timestamp) {
        try {
            Instant ts = Instant.parse(timestamp);
            Duration delta = Duration.between(ts, Instant.now()).abs();
            return delta.compareTo(props.getTimestampSkew()) <= 0;
        } catch (Exception ex) {
            return false;
        }
    }

    private byte[] hmacSha256(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance(props.getAlgorithm());
            mac.init(new SecretKeySpec(key, props.getAlgorithm()));
            return mac.doFinal(data);
        } catch (NoSuchAlgorithmException | java.security.InvalidKeyException ex) {
            throw new IllegalStateException("HMAC computation failed", ex);
        }
    }

    private static byte[] buildCanonicalMessage(String partnerId, String timestamp,
                                                String method, String path, byte[] body) {
        // partnerId\ntimestamp\nMETHOD\npath\nbody
        byte[] header = (partnerId + "\n" + timestamp + "\n" + method.toUpperCase()
                + "\n" + path + "\n").getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[header.length + body.length];
        System.arraycopy(header, 0, out, 0, header.length);
        System.arraycopy(body, 0, out, header.length, body.length);
        return out;
    }

    /**
     * Helper exposed for tests / partner SDKs: compute the signature a partner
     * would send for given inputs, given the SHA-256 hex of their secret.
     */
    public String signForTesting(String secretHashHex, String partnerId, String timestamp,
                                 String method, String path, byte[] body) {
        byte[] keyBytes = HexFormat.of().parseHex(secretHashHex);
        byte[] canonical = buildCanonicalMessage(partnerId, timestamp, method, path, body);
        return Base64.getEncoder().encodeToString(hmacSha256(keyBytes, canonical));
    }
}
