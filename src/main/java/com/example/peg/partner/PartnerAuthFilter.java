package com.example.peg.partner;

import com.example.peg.platform.SecurityProperties;
import com.example.peg.shared.Errors;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Authenticates partner-facing requests using HMAC-SHA256.
 *
 * <p>Headers required:
 * <ul>
 *   <li>{@code X-Partner-Id}</li>
 *   <li>{@code X-Timestamp} — RFC 3339 instant</li>
 *   <li>{@code X-Signature} — Base64 HMAC-SHA256</li>
 * </ul>
 *
 * <p>Applies only to {@code /api/v1/events*}. Internal endpoints
 * ({@code /api/v1/internal/**}) and observability endpoints are skipped.
 *
 * <p>On successful auth, the resolved partner id is set as a request attribute
 * for the controller to read.
 */
@Component
@Order(20)
@Slf4j
@RequiredArgsConstructor
public class PartnerAuthFilter extends OncePerRequestFilter {

    public static final String ATTR_PARTNER_ID = "peg.partner_id";

    private final PartnerRepository partners;
    private final HmacVerifier verifier;
    private final SecurityProperties props;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path.startsWith("/actuator")) return true;
        if (path.startsWith("/v3/api-docs")) return true;
        if (path.startsWith("/swagger-ui")) return true;
        if (path.startsWith("/api/v1/internal")) return true;
        // Only protect partner-facing /api/v1/events*
        return !path.startsWith("/api/v1/events");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        SecurityProperties.Headers h = props.getHeaders();
        String partnerId = request.getHeader(h.getPartnerId());
        String timestamp = request.getHeader(h.getTimestamp());
        String signature = request.getHeader(h.getSignature());

        if (partnerId == null || timestamp == null || signature == null) {
            throw Errors.unauthorized("missing authentication headers");
        }

        // Read body once and wrap so the controller can re-read it.
        byte[] body = request.getInputStream().readAllBytes();
        CachingRequestWrapper wrapped = new CachingRequestWrapper(request, body);

        var partner = partners.findById(partnerId)
                .orElseThrow(() -> Errors.unauthorized("unknown partner"));

        boolean ok = verifier.verify(partner, timestamp,
                request.getMethod(), request.getRequestURI(), body, signature);

        if (!ok) {
            throw Errors.unauthorized("signature verification failed");
        }

        wrapped.setAttribute(ATTR_PARTNER_ID, partner.partnerId());
        chain.doFilter(wrapped, response);
    }
}
