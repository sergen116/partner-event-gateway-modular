package com.example.peg.delivery;

import com.example.peg.shared.EventType;
import com.example.peg.shared.PartnerEventMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class DownstreamCallServiceTest {

    private RestClient restClient;
    private DownstreamCallService service;

    @BeforeEach
    void setUp() {
        restClient = mock(RestClient.class);
        service = new DownstreamCallService(restClient);
    }

    @Test
    void notify_completesWithoutThrowing() {
        PartnerEventMessage msg = new PartnerEventMessage(
                UUID.randomUUID(), "p", EventType.ORDER_CREATED, "ORD-1",
                new ObjectMapper().nullNode(), Instant.now(), null, null);

        assertThatCode(() -> service.notify(msg)).doesNotThrowAnyException();
    }

    @Test
    void onFailure_wrapsThrowableInDownstreamCallException() throws Exception {
        PartnerEventMessage msg = new PartnerEventMessage(
                UUID.randomUUID(), "p", EventType.ORDER_CREATED, "ORD-1",
                new ObjectMapper().nullNode(), Instant.now(), null, null);

        Method method = DownstreamCallService.class.getDeclaredMethod(
                "onFailure", PartnerEventMessage.class, Throwable.class);
        method.setAccessible(true);

        assertThatThrownBy(() -> {
            try {
                method.invoke(service, msg, new RuntimeException("flaky"));
            } catch (java.lang.reflect.InvocationTargetException ex) {
                throw ex.getCause();
            }
        }).isInstanceOf(DownstreamCallService.DownstreamCallException.class)
          .hasMessageContaining(msg.eventId().toString());
    }

    @Test
    void onFailure_handlesCallNotPermittedException() throws Exception {
        PartnerEventMessage msg = new PartnerEventMessage(
                UUID.randomUUID(), "p", EventType.ORDER_CREATED, "ORD-1",
                new ObjectMapper().nullNode(), Instant.now(), null, null);

        CircuitBreaker cb = CircuitBreaker.of("test", CircuitBreakerConfig.ofDefaults());
        cb.transitionToOpenState();
        Throwable rejection = CallNotPermittedException.createCallNotPermittedException(cb);

        Method method = DownstreamCallService.class.getDeclaredMethod(
                "onFailure", PartnerEventMessage.class, Throwable.class);
        method.setAccessible(true);

        assertThatThrownBy(() -> {
            try {
                method.invoke(service, msg, rejection);
            } catch (java.lang.reflect.InvocationTargetException ex) {
                throw ex.getCause();
            }
        }).isInstanceOf(DownstreamCallService.DownstreamCallException.class);
    }

    @Test
    void downstreamCallException_carriesCauseAndMessage() {
        Throwable cause = new RuntimeException("inner");
        DownstreamCallService.DownstreamCallException ex =
                new DownstreamCallService.DownstreamCallException("msg", cause);
        assertThat(ex.getMessage()).isEqualTo("msg");
        assertThat(ex.getCause()).isSameAs(cause);
    }
}
