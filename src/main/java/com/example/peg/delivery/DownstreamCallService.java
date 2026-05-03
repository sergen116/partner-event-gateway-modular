package com.example.peg.delivery;

import com.example.peg.shared.PartnerEventMessage;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * Single seam between event handlers and the (future) downstream business
 * systems. Today the call is mocked in-process — it logs the notification and
 * sleeps briefly to simulate latency — but the resilience scaffolding is real:
 *
 * <ul>
 *   <li>{@code @Retry} smooths over fast transient failures
 *       (connection reset, read timeout). Retry budget is bounded by
 *       application.yml ({@code max-attempts × wait}); keep it well below
 *       the pgmq visibility-timeout-minus-5s deadline enforced by
 *       {@link PgmqWorker#processBatch}.</li>
 *   <li>{@code @CircuitBreaker} short-circuits subsequent calls when the
 *       failure rate trips, so a flaky downstream can't drown the
 *       virtual-thread pool in retries.</li>
 * </ul>
 *
 * <p>This must be a separate bean from {@link EventProcessor} — Resilience4j
 * relies on Spring's AOP proxy, and self-invocation inside the same bean
 * silently bypasses it.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class DownstreamCallService {

    static final String INSTANCE = "downstream";

    private final RestClient downstreamRestClient;

    /**
     * Notify the downstream system about a processed partner event.
     * Throws on failure so the outer pgmq retry / DLQ pipeline still
     * observes the failure and increments the configured counters.
     */
    @CircuitBreaker(name = INSTANCE, fallbackMethod = "onFailure")
    @Retry(name = INSTANCE)
    public void notify(PartnerEventMessage msg) {
        log.info("downstream notify (mock) partner={} eventId={} eventType={} businessRef={}",
                msg.partnerId(), msg.eventId(), msg.eventType(), msg.businessRef());
        try {
            Thread.sleep(5);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Fallback invoked once the breaker rejects the call (or all retries are
     * exhausted). Logging-only — we deliberately rethrow so the surrounding
     * EventProcessor transaction rolls back and pgmq redelivers / DLQs the
     * message according to the existing rules.
     */
    @SuppressWarnings("unused")
    private void onFailure(PartnerEventMessage msg, Throwable t) {
        // 4xx is a caller bug — re-throw the original so Retry's ignore-exceptions
        // (HttpClientErrorException) suppresses retries. Wrapping it in
        // DownstreamCallException would defeat that and cause noisy retries on a
        // permanent failure.
        if (t instanceof HttpClientErrorException httpClientErr) {
            log.warn("downstream notify rejected partner={} eventId={} status={}",
                    msg.partnerId(), msg.eventId(), httpClientErr.getStatusCode());
            throw httpClientErr;
        }
        if (t instanceof CallNotPermittedException) {
            log.warn("circuit open, skipping downstream notify partner={} eventId={}",
                    msg.partnerId(), msg.eventId());
        } else {
            log.warn("downstream notify failed partner={} eventId={} cause={}",
                    msg.partnerId(), msg.eventId(), t.toString());
        }
        throw new DownstreamCallException("downstream notify failed for " + msg.eventId(), t);
    }

    public static class DownstreamCallException extends RuntimeException {
        public DownstreamCallException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
