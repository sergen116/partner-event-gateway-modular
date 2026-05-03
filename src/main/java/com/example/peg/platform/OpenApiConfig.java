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

                                **Authentication (real partners):** HMAC-SHA256. Each request includes:
                                - `X-Partner-Id` — issued partner identifier
                                - `X-Timestamp` — RFC 3339 / ISO 8601 instant, within ±5 min
                                - `X-Signature` — Base64 HMAC-SHA256 of canonical message

                                Canonical message format:
                                `partnerId\\ntimestamp\\nHTTP_METHOD\\nrequest_path\\nrequest_body`

                                **Authentication in Swagger UI:** click *Authorize* and paste
                                `partnerId:rawSecret` (e.g. `partner-acme:acme-shared-secret-2024`).
                                Swagger UI computes a fresh `X-Timestamp` and `X-Signature` for every
                                request — the raw secret never leaves the browser.

                                Use `Idempotency-Key` header for safe retries — repeat calls with the
                                same key return the original event's status without re-processing.
                                """)
                        .license(new License().name("Case Study").url("https://example.com")))
                .components(new Components()
                        .addSecuritySchemes("PartnerCreds",
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.APIKEY)
                                        .in(SecurityScheme.In.HEADER)
                                        .name("X-Partner-Creds")
                                        .description("Format: `partnerId:rawSecret`. Swagger UI auto-generates "
                                                + "X-Partner-Id, X-Timestamp, X-Signature from this — "
                                                + "the X-Partner-Creds header itself is stripped before send.")))
                .addSecurityItem(new SecurityRequirement().addList("PartnerCreds"));
    }
}
