package com.example.peg.platform;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Overrides the default {@code swagger-initializer.js} served by springdoc/webjar
 * with a custom build that adds an HMAC {@code requestInterceptor}.
 *
 * <p>The user pastes {@code partnerId:rawSecret} into Authorize once; the interceptor
 * then signs every outgoing request, so {@code X-Timestamp} / {@code X-Signature}
 * never need to be entered manually in the UI.
 *
 * <p>{@code @RequestMapping} controllers take precedence over springdoc's resource
 * handler for the same path, so this transparently replaces the default initializer.
 */
@RestController
public class SwaggerInitializerController {

    @GetMapping("/swagger-ui/swagger-initializer.js")
    public ResponseEntity<String> swaggerInitializer() {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/javascript"))
                .header("Cache-Control", "no-store")
                .body(INITIALIZER_JS);
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
                    if (req.headers) delete req.headers["X-Partner-Creds"];

                    // Strip any legacy header values left over from older spec versions
                    if (req.headers) {
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

                    // HMAC key = SHA-256(rawSecret) raw bytes (matches HmacVerifier)
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
