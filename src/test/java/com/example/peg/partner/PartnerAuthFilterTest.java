package com.example.peg.partner;

import com.example.peg.platform.SecurityProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PartnerAuthFilterTest {

    private PartnerRepository partners;
    private HmacVerifier verifier;
    private SecurityProperties props;
    private Cache<String, Optional<Partner>> cache;
    private ObjectMapper objectMapper;
    private PartnerAuthFilter filter;

    @BeforeEach
    void setUp() {
        partners = mock(PartnerRepository.class);
        verifier = mock(HmacVerifier.class);
        props = new SecurityProperties();
        cache = Caffeine.newBuilder().maximumSize(100).build();
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        filter = new PartnerAuthFilter(partners, verifier, props, cache, objectMapper);
    }

    private static HttpServletResponse mockResponse() throws Exception {
        HttpServletResponse resp = mock(HttpServletResponse.class);
        when(resp.getWriter()).thenReturn(new PrintWriter(new StringWriter()));
        return resp;
    }

    @Test
    void shouldNotFilter_skipsActuator() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getRequestURI()).thenReturn("/actuator/health");
        assertThat(filter.shouldNotFilter(req)).isTrue();
    }

    @Test
    void shouldNotFilter_skipsApiDocs() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getRequestURI()).thenReturn("/v3/api-docs");
        assertThat(filter.shouldNotFilter(req)).isTrue();
    }

    @Test
    void shouldNotFilter_skipsSwaggerUi() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getRequestURI()).thenReturn("/swagger-ui/index.html");
        assertThat(filter.shouldNotFilter(req)).isTrue();
    }

    @Test
    void shouldNotFilter_skipsInternalEndpoints() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getRequestURI()).thenReturn("/api/v1/internal/events");
        assertThat(filter.shouldNotFilter(req)).isTrue();
    }

    @Test
    void shouldNotFilter_doesNotSkipPartnerEndpoints() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getRequestURI()).thenReturn("/api/v1/events");
        assertThat(filter.shouldNotFilter(req)).isFalse();
    }

    @Test
    void shouldNotFilter_skipsUnknownPaths() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getRequestURI()).thenReturn("/somewhere-else");
        assertThat(filter.shouldNotFilter(req)).isTrue();
    }

    @Test
    void doFilter_returnsUnauthorized_whenHeadersMissing() throws Exception {
        HttpServletRequest req = mock(HttpServletRequest.class);
        HttpServletResponse resp = mockResponse();
        FilterChain chain = mock(FilterChain.class);

        when(req.getRequestURI()).thenReturn("/api/v1/events");
        when(req.getHeader("X-Partner-Id")).thenReturn(null);

        filter.doFilter(req, resp, chain);

        verify(resp).setStatus(401);
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void doFilter_returnsUnauthorized_whenPartnerNotFound() throws Exception {
        HttpServletRequest req = stubRequest("partner-x", "ts", "sig", "{}");
        HttpServletResponse resp = mockResponse();
        when(partners.findById("partner-x")).thenReturn(Optional.empty());

        filter.doFilter(req, resp, mock(FilterChain.class));

        verify(resp).setStatus(401);
    }

    @Test
    void doFilter_returnsUnauthorized_whenSignatureFailsTwice() throws Exception {
        Partner partner = new Partner("p", "hash", null, null, true);
        HttpServletRequest req = stubRequest("p", "ts", "sig", "{}");
        HttpServletResponse resp = mockResponse();
        when(partners.findById("p")).thenReturn(Optional.of(partner));
        when(verifier.verify(any(), any(), any(), any(), any(), any())).thenReturn(false);

        filter.doFilter(req, resp, mock(FilterChain.class));

        verify(resp).setStatus(401);
        // Looked up twice: cache miss + invalidation/refresh
        verify(partners, times(2)).findById("p");
    }

    @Test
    void doFilter_setsAttributeAndCallsChain_onValidSignature() throws Exception {
        Partner partner = new Partner("p", "hash", null, null, true);
        HttpServletRequest req = stubRequest("p", Instant.now().toString(), "sig", "{}");
        HttpServletResponse resp = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);

        when(partners.findById("p")).thenReturn(Optional.of(partner));
        when(verifier.verify(any(), any(), any(), any(), any(), any())).thenReturn(true);

        filter.doFilter(req, resp, chain);

        // The wrapper delegates setAttribute() to the underlying mock request
        verify(req).setAttribute(PartnerAuthFilter.ATTR_PARTNER_ID, "p");
        verify(chain).doFilter(any(), any());
    }

    @Test
    void doFilter_acceptsAfterCacheRefresh_whenSecondVerifySucceeds() throws Exception {
        Partner stale = new Partner("p", "old-hash", null, null, true);
        Partner fresh = new Partner("p", "new-hash", null, null, true);

        // Pre-populate cache with stale entry
        cache.put("p", Optional.of(stale));

        HttpServletRequest req = stubRequest("p", Instant.now().toString(), "sig", "{}");
        when(partners.findById("p")).thenReturn(Optional.of(fresh));

        // First call (with stale partner) fails, second (after refresh) succeeds
        when(verifier.verify(any(), any(), any(), any(), any(), any()))
                .thenReturn(false, true);

        FilterChain chain = mock(FilterChain.class);
        filter.doFilter(req, mock(HttpServletResponse.class), chain);

        verify(chain).doFilter(any(), any());
        // Cache should now hold the refreshed partner
        assertThat(cache.getIfPresent("p")).contains(fresh);
    }

    @Test
    void doFilter_returnsUnauthorized_whenPartnerNotFoundOnRefresh() throws Exception {
        Partner stale = new Partner("p", "old", null, null, true);
        cache.put("p", Optional.of(stale));

        HttpServletRequest req = stubRequest("p", "ts", "sig", "{}");
        HttpServletResponse resp = mockResponse();
        // First call returns false → invalidate cache → repository finds nothing
        when(verifier.verify(any(), any(), any(), any(), any(), any())).thenReturn(false);
        when(partners.findById("p")).thenReturn(Optional.empty());

        filter.doFilter(req, resp, mock(FilterChain.class));

        verify(resp).setStatus(401);
    }

    private static HttpServletRequest stubRequest(String partnerId, String ts, String sig, String body) throws Exception {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getRequestURI()).thenReturn("/api/v1/events");
        when(req.getMethod()).thenReturn("POST");
        when(req.getHeader("X-Partner-Id")).thenReturn(partnerId);
        when(req.getHeader("X-Timestamp")).thenReturn(ts);
        when(req.getHeader("X-Signature")).thenReturn(sig);
        when(req.getInputStream()).thenReturn(new TestServletInputStream(body.getBytes(StandardCharsets.UTF_8)));
        return req;
    }

    static class TestServletInputStream extends ServletInputStream {
        private final ByteArrayInputStream in;
        TestServletInputStream(byte[] data) { this.in = new ByteArrayInputStream(data); }
        @Override public boolean isFinished() { return in.available() == 0; }
        @Override public boolean isReady() { return true; }
        @Override public void setReadListener(jakarta.servlet.ReadListener readListener) {}
        @Override public int read() { return in.read(); }
    }
}
