package com.example.peg.platform;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Serves a customized {@code /swagger-ui/swagger-initializer.js} that wires
 * an HMAC {@code requestInterceptor} into Swagger UI.
 *
 * <p>Implemented as a high-precedence servlet filter — running before Spring's
 * {@code DispatcherServlet}, it cannot lose to springdoc's resource handler.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SwaggerInitializerFilter extends OncePerRequestFilter {

    // Springdoc's index redirect produces a double-prefixed URL
    // (/swagger-ui/swagger-ui/index.html), so the browser resolves
    // ./swagger-initializer.js to the doubled path. Match either form.
    private static final String SUFFIX = "/swagger-initializer.js";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String uri = request.getRequestURI();
        if (!"GET".equalsIgnoreCase(request.getMethod())
                || uri == null
                || !uri.endsWith(SUFFIX)
                || !uri.startsWith("/swagger-ui/")) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpStatus.OK.value());
        response.setContentType("application/javascript");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write(INITIALIZER_JS);
        response.getWriter().flush();
    }

    private static final String INITIALIZER_JS = """
            window.onload = () => {
              console.log("[peg-swagger] custom initializer with HMAC interceptor loaded");

              window.ui = SwaggerUIBundle({
                url: "/v3/api-docs",
                dom_id: "#swagger-ui",
                deepLinking: true,
                presets: [SwaggerUIBundle.presets.apis, SwaggerUIStandalonePreset],
                plugins: [SwaggerUIBundle.plugins.DownloadUrl],
                layout: "StandaloneLayout",
                persistAuthorization: false,

                requestInterceptor: async (req) => {
                  try {
                    const creds = req.headers && req.headers["X-Partner-Creds"];
                    if (req.headers) {
                      delete req.headers["X-Partner-Creds"];
                      delete req.headers["X-Partner-Id"];
                      delete req.headers["X-Timestamp"];
                      delete req.headers["X-Signature"];
                    }

                    if (!creds || !creds.includes(":")) {
                      console.warn("[peg-swagger] no X-Partner-Creds — request unsigned. " +
                                   "Click Authorize and enter `partnerId:rawSecret`.");
                      return req;
                    }

                    const sep = creds.indexOf(":");
                    const partnerId = creds.substring(0, sep).trim();
                    const rawSecret = creds.substring(sep + 1).trim();
                    if (!partnerId || !rawSecret) return req;

                    const enc = new TextEncoder();
                    const ts = new Date().toISOString();
                    const path = new URL(req.url, window.location.origin).pathname;
                    const method = (req.method || "GET").toUpperCase();
                    const body =
                      typeof req.body === "string" ? req.body
                      : req.body == null ? ""
                      : JSON.stringify(req.body);

                    const keyBytes = await crypto.subtle.digest("SHA-256", enc.encode(rawSecret));
                    const key = await crypto.subtle.importKey(
                      "raw", keyBytes, { name: "HMAC", hash: "SHA-256" }, false, ["sign"]
                    );
                    const canonical = partnerId + "\\n" + ts + "\\n" + method + "\\n" + path + "\\n" + body;
                    const sigBytes = new Uint8Array(
                      await crypto.subtle.sign("HMAC", key, enc.encode(canonical))
                    );
                    let bin = "";
                    for (const b of sigBytes) bin += String.fromCharCode(b);

                    req.headers["X-Partner-Id"] = partnerId;
                    req.headers["X-Timestamp"] = ts;
                    req.headers["X-Signature"] = btoa(bin);

                    console.log("[peg-swagger] signed request",
                                { partnerId, ts, method, path, bodyLen: body.length });
                  } catch (e) {
                    console.error("[peg-swagger] HMAC signing failed:", e);
                  }
                  return req;
                }
              });
            };
            """;
}
