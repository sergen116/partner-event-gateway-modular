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
 */
@Configuration
public class RestClientConfig {

    @Bean
    public RestClient downstreamRestClient(DownstreamProperties props) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) props.getConnectTimeout().toMillis());
        factory.setReadTimeout((int) props.getReadTimeout().toMillis());

        return RestClient.builder()
                .baseUrl(props.getBaseUrl())
                .requestFactory(factory)
                .build();
    }
}
