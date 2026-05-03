package com.example.peg.platform;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;

import java.util.HashMap;
import java.util.Map;

/**
 * W3C trace context bridge between the synchronous HTTP ingest path and the
 * async pgmq → worker path. The HTTP path's current span is serialized into
 * {@code traceparent} / {@code tracestate} headers stored on
 * {@link com.example.peg.shared.PartnerEventMessage}; the worker re-creates
 * a CONSUMER span as child of that producer context so the two halves of the
 * pipeline form a single trace.
 *
 * <p>Uses {@link GlobalOpenTelemetry} so no Micrometer / Tracer beans need to
 * be injected — the bridge dependency configures the global on startup.
 */
public final class TraceContextCarrier {

    private static final String INSTRUMENTATION_NAME = "com.example.peg";

    private TraceContextCarrier() {}

    /** Snapshot of the current trace context, or empty fields if no span is active. */
    public static Captured capture() {
        Map<String, String> carrier = new HashMap<>();
        GlobalOpenTelemetry.getPropagators()
                .getTextMapPropagator()
                .inject(Context.current(), carrier, Map::put);
        return new Captured(carrier.get("traceparent"), carrier.get("tracestate"));
    }

    /**
     * Starts a CONSUMER span as child of the producer context encoded in
     * {@code traceparent}/{@code tracestate}. If no upstream context exists,
     * a fresh root span is created so the worker still emits a trace.
     *
     * <p>Caller must close both returned values in reverse order
     * (scope first, then span) — typically in a try/finally.
     */
    public static SpanScope startConsumerSpan(String spanName,
                                              String traceparent,
                                              String tracestate) {
        Map<String, String> carrier = new HashMap<>();
        if (traceparent != null) carrier.put("traceparent", traceparent);
        if (tracestate != null) carrier.put("tracestate", tracestate);

        Context parent = GlobalOpenTelemetry.getPropagators()
                .getTextMapPropagator()
                .extract(Context.root(), carrier, MapGetter.INSTANCE);

        Tracer tracer = GlobalOpenTelemetry.getTracer(INSTRUMENTATION_NAME);
        Span span = tracer.spanBuilder(spanName)
                .setSpanKind(SpanKind.CONSUMER)
                .setParent(parent)
                .startSpan();
        Scope scope = span.makeCurrent();
        return new SpanScope(span, scope);
    }

    public record Captured(String traceparent, String tracestate) {}

    public record SpanScope(Span span, Scope scope) implements AutoCloseable {
        @Override
        public void close() {
            scope.close();
            span.end();
        }
    }

    private enum MapGetter implements io.opentelemetry.context.propagation.TextMapGetter<Map<String, String>> {
        INSTANCE;

        @Override
        public Iterable<String> keys(Map<String, String> carrier) {
            return carrier.keySet();
        }

        @Override
        public String get(Map<String, String> carrier, String key) {
            return carrier == null ? null : carrier.get(key);
        }
    }
}
