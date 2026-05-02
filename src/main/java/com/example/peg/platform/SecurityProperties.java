package com.example.peg.platform;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * HMAC authentication settings for partner API.
 */
@ConfigurationProperties("app.security")
@Data
public class SecurityProperties {

    /**
     * Maximum allowed clock skew between partner and gateway. Requests with
     * timestamps outside this window are rejected to prevent replay.
     */
    private Duration timestampSkew = Duration.ofMinutes(5);

    /**
     * Algorithm for HMAC. Fixed at HmacSHA256 — change requires partner coordination.
     */
    private String algorithm = "HmacSHA256";

    /** Header names for partner authentication. */
    private Headers headers = new Headers();

    @Data
    public static class Headers {
        private String partnerId = "X-Partner-Id";
        private String timestamp = "X-Timestamp";
        private String signature = "X-Signature";
        private String idempotencyKey = "Idempotency-Key";
    }
}
