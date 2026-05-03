package com.example.peg.partner;

import com.example.peg.platform.SecurityProperties;
import com.example.peg.shared.ApiException;
import com.example.peg.shared.ErrorResponse;
import com.example.peg.shared.Errors;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;

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
    private static final String MDC_PARTNER_ID = "partner_id";

    private final PartnerRepository partners;
    private final HmacVerifier verifier;
    private final SecurityProperties props;
    private final Cache<String, Optional<Partner>> partnerCache;
    private final ObjectMapper objectMapper;

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

        Partner partner;
        CachingRequestWrapper wrapped;
        try {
            if (partnerId == null || timestamp == null || signature == null) {
                throw Errors.unauthorized("missing authentication headers");
            }

            // Read body once and wrap so the controller can re-read it.
            byte[] body = request.getInputStream().readAllBytes();
            wrapped = new CachingRequestWrapper(request, body);

            partner = partnerCache.get(partnerId, partners::findById)
                    .orElseThrow(() -> Errors.unauthorized("unknown partner"));

            boolean ok = verifier.verify(partner, timestamp,
                    request.getMethod(), request.getRequestURI(), body, signature);

            if (!ok) {
                // Possibly stale cache during rotation — invalidate, refresh, retry once.
                partnerCache.invalidate(partnerId);
                partner = partners.findById(partnerId)
                        .orElseThrow(() -> Errors.unauthorized("unknown partner"));
                ok = verifier.verify(partner, timestamp,
                        request.getMethod(), request.getRequestURI(), body, signature);
                if (!ok) {
                    throw Errors.unauthorized("signature verification failed");
                }
                partnerCache.put(partnerId, Optional.of(partner));
            }
        } catch (ApiException ex) {
            // Filter-thrown exceptions don't reach @RestControllerAdvice, so render the
            // error envelope here to keep the API contract consistent across layers.
            log.info("auth rejected path={} code={} reason={}",
                    request.getRequestURI(), ex.code(), ex.getMessage());
            writeError(response, ex);
            return;
        }

        wrapped.setAttribute(ATTR_PARTNER_ID, partner.partnerId());
        MDC.put(MDC_PARTNER_ID, partner.partnerId());
        try {
            chain.doFilter(wrapped, response);
        } finally {
            MDC.remove(MDC_PARTNER_ID);
        }
    }

    private void writeError(HttpServletResponse response, ApiException ex) throws IOException {
        response.setStatus(ex.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getWriter(),
                new ErrorResponse(ex.code(), ex.getMessage(), Instant.now()));
    }
}
