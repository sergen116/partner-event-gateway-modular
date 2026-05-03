package com.example.peg.platform;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Sync HTTP client used by event handlers to notify downstream systems.
 * Sync (not WebClient) because handlers run on virtual threads — the
 * blocking call doesn't pin a platform thread, and annotation-based
 * Resilience4j (@CircuitBreaker / @Retry) composes naturally without
 * needing a TimeLimiter.
 *
 * <p>The injected {@link RestClient.Builder} is the auto-configured one,
 * which carries Spring's observation interceptor — outbound calls receive
 * {@code traceparent} headers automatically when a span is active.
 */
@Configuration
public class RestClientConfig {

    @Bean
    public RestClient downstreamRestClient(DownstreamProperties props,
                                           RestClient.Builder builder) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) props.getConnectTimeout().toMillis());
        factory.setReadTimeout((int) props.getReadTimeout().toMillis());

        return builder
                .baseUrl(props.getBaseUrl())
                .requestFactory(factory)
                .build();
    }
}
