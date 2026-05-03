package com.example.peg.platform;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI partnerEventGatewayOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Partner Event Gateway API")
                        .version("v1")
                        .description("""
                                Partner-facing API for submitting and querying operational events.

                                **Authentication:** HMAC-SHA256. Each request includes:
                                - `X-Partner-Id` — issued partner identifier
                                - `X-Timestamp` — RFC 3339 / ISO 8601 instant, within ±5 min
                                - `X-Signature` — Base64 HMAC-SHA256 of canonical message

                                Canonical message format:
                                `partnerId\\ntimestamp\\nHTTP_METHOD\\nrequest_path\\nrequest_body`

                                Use `Idempotency-Key` header for safe retries — repeat calls with the
                                same key return the original event's status without re-processing.
                                """)
                        .license(new License().name("Case Study").url("https://example.com")))
                .components(new Components()
                        .addSecuritySchemes("PartnerId",
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.APIKEY)
                                        .in(SecurityScheme.In.HEADER)
                                        .name("X-Partner-Id")
                                        .description("Issued partner identifier."))
                        .addSecuritySchemes("Timestamp",
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.APIKEY)
                                        .in(SecurityScheme.In.HEADER)
                                        .name("X-Timestamp")
                                        .description("RFC 3339 / ISO 8601 instant, within ±5 min."))
                        .addSecuritySchemes("Signature",
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.APIKEY)
                                        .in(SecurityScheme.In.HEADER)
                                        .name("X-Signature")
                                        .description("Base64 HMAC-SHA256 signature; see API description.")))
                .addSecurityItem(new SecurityRequirement()
                        .addList("PartnerId")
                        .addList("Timestamp")
                        .addList("Signature"));
    }
}
