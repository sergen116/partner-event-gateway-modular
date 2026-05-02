/**
 * Partner identity and request authentication.
 *
 * <p>Owns the {@code partners} table, HMAC-SHA256 verification, the auth
 * filter that runs in front of partner-facing endpoints, and a body-caching
 * request wrapper that lets the filter and Spring's {@code @RequestBody}
 * deserializer both read the body.
 *
 * <p>Public API surface:
 * <ul>
 *   <li>{@link PartnerAuthFilter} — registered at the servlet layer; transparently
 *       enforces HMAC + sets the resolved partner ID as a request attribute</li>
 *   <li>{@link PartnerRepository} — used by the seed migration and integration tests</li>
 * </ul>
 *
 * <p>Internal: {@link HmacVerifier}, {@link CachingRequestWrapper}.
 *
 * <p>Depends on: {@code shared}, {@code platform} (for {@code SecurityProperties}).
 * Other modules must NOT reach in to {@code HmacVerifier} directly — auth is
 * the filter's job, and downstream code reads the partner id from the request
 * attribute set by the filter.
 */
package com.example.peg.partner;
