package com.example.peg.platform;

import io.swagger.v3.oas.models.OpenAPI;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OpenApiConfigTest {

    @Test
    void openApiBean_describesGatewayApi() {
        OpenAPI api = new OpenApiConfig().partnerEventGatewayOpenApi();
        assertThat(api.getInfo().getTitle()).isEqualTo("Partner Event Gateway API");
        assertThat(api.getInfo().getVersion()).isEqualTo("v1");
        assertThat(api.getComponents().getSecuritySchemes()).containsKey("PartnerHmac");
        assertThat(api.getSecurity()).isNotEmpty();
    }
}
