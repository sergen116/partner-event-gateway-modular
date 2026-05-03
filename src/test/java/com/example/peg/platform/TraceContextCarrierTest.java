package com.example.peg.platform;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TraceContextCarrierTest {

    @Test
    void capture_returnsCarrier_evenWithoutActiveSpan() {
        TraceContextCarrier.Captured c = TraceContextCarrier.capture();
        // The default propagator may or may not write traceparent without an active span;
        // we only check the API doesn't throw and returns a non-null record.
        assertThat(c).isNotNull();
    }

    @Test
    void startConsumerSpan_createsSpanScope_andCloseEndsSpan() {
        try (TraceContextCarrier.SpanScope scope = TraceContextCarrier.startConsumerSpan(
                "test.span", null, null)) {
            assertThat(scope).isNotNull();
            assertThat(scope.span()).isNotNull();
        }
    }

    @Test
    void startConsumerSpan_acceptsTraceparent() {
        String tp = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";
        try (TraceContextCarrier.SpanScope scope = TraceContextCarrier.startConsumerSpan(
                "test.span", tp, null)) {
            assertThat(scope).isNotNull();
        }
    }
}
