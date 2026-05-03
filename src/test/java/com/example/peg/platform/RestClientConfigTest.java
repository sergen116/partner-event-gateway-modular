package com.example.peg.platform;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class RestClientConfigTest {

    @Test
    void downstreamRestClient_isBuiltFromBuilder() {
        DownstreamProperties props = new DownstreamProperties();
        props.setBaseUrl("http://example.test");
        props.setConnectTimeout(Duration.ofSeconds(2));
        props.setReadTimeout(Duration.ofSeconds(3));

        RestClient client = new RestClientConfig().downstreamRestClient(
                props, RestClient.builder());

        assertThat(client).isNotNull();
    }
}
