package com.example.peg.delivery;

import com.example.peg.platform.ConsumerProperties;
import com.example.peg.shared.PartnerEventMessage;
import com.example.peg.query.EventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Base worker for a single pgmq queue. Subclasses pick the queue.
 *
 * <p>Design properties:
 * <ul>
 *   <li>Each batch poll fans out across virtual threads via {@code CompletableFuture.runAsync}.</li>
 *   <li>A per-worker {@link Semaphore} bounds concurrency so virtual threads
 *       can't drain the Hikari connection pool — virtual threads are cheap,
 *       DB connections are not.</li>
 *   <li>{@code allOf().orTimeout(VT - 5s)} enforces a batch deadline below VT.
 *       In-flight tasks are NOT cancelled — letting them complete naturally
 *       protects the delete/archive step. Genuinely stuck messages reappear
 *       via VT expiry.</li>
 *   <li>Idempotency lives in {@link EventProcessor} via the (partner_id, event_id)
 *       unique constraint.</li>
 * </ul>
 */
@Slf4j
public abstract class PgmqWorker {

    protected final JdbcTemplate jdbc;
    protected final ObjectMapper mapper;
    protected final EventProcessor processor;
    protected final EventRepository events;
    protected final MeterRegistry meterRegistry;
    protected final ConsumerProperties props;

    private final ExecutorService virtualExecutor;
    private final Semaphore concurrencyLimiter;
    private final int concurrency;

    protected PgmqWorker(JdbcTemplate jdbc, ObjectMapper mapper, EventProcessor processor,
                         EventRepository events,
                         MeterRegistry meterRegistry, ConsumerProperties props) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.processor = processor;
        this.events = events;
        this.meterRegistry = meterRegistry;
        this.props = props;
        this.concurrency = props.concurrencyFor(queueName());

        ThreadFactory factory = Thread.ofVirtual()
                .name("peg-vt-" + queueShortName() + "-", 0)
                .factory();
        this.virtualExecutor = Executors.newThreadPerTaskExecutor(factory);
        this.concurrencyLimiter = new Semaphore(this.concurrency);

        Gauge.builder("peg.consumer.concurrency.available",
                        concurrencyLimiter, Semaphore::availablePermits)
                .tag("queue", queueName())
                .register(meterRegistry);

        log.info("worker initialized queue={} concurrency={}", queueName(), concurrency);
    }

    public abstract String queueName();

    protected String queueShortName() {
        return queueName().replace("events_", "").replace('_', '-');
    }

    @PreDestroy
    public void shutdown() {
        log.info("shutting down worker queue={}", queueName());
        virtualExecutor.shutdown();
        try {
            if (!virtualExecutor.awaitTermination(
                    props.getVisibilityTimeoutSeconds(), TimeUnit.SECONDS)) {
                log.warn("worker for {} did not drain within VT", queueName());
                virtualExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            virtualExecutor.shutdownNow();
        }
    }

    /** Invoked at fixed delay by {@code WorkerScheduler} in the platform module. */
    public void poll() {
        try {
            List<PgmqMessage> batch = readBatch();
            if (batch.isEmpty()) return;

            log.debug("queue={} read {} messages", queueName(), batch.size());

            List<CompletableFuture<Void>> futures = batch.stream()
                    .map(this::submitForProcessing)
                    .toList();

            try {
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                        .orTimeout(props.getVisibilityTimeoutSeconds() - 5L, TimeUnit.SECONDS)
                        .join();
            } catch (CompletionException ce) {
                if (ce.getCause() instanceof TimeoutException) {
                    long inFlight = futures.stream().filter(f -> !f.isDone()).count();
                    log.error("batch processing exceeded VT for queue={}, {} tasks still running",
                            queueName(), inFlight);
                } else {
                    log.error("batch processing failed for queue={}", queueName(), ce);
                }
            }
        } catch (Exception e) {
            log.error("poll error queue={}", queueName(), e);
        }
    }

    private CompletableFuture<Void> submitForProcessing(PgmqMessage m) {
        return CompletableFuture.runAsync(() -> {
            try {
                concurrencyLimiter.acquire();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
            try {
                handle(m);
            } finally {
                concurrencyLimiter.release();
            }
        }, virtualExecutor);
    }

    private List<PgmqMessage> readBatch() {
        return jdbc.query(
                "SELECT msg_id, read_ct, message::text " +
                "FROM pgmq.read(?, ?, ?)",
                (rs, i) -> new PgmqMessage(
                        rs.getLong("msg_id"),
                        rs.getInt("read_ct"),
                        rs.getString("message")),
                queueName(),
                props.getVisibilityTimeoutSeconds(),
                props.getBatchSize());
    }

    private void handle(PgmqMessage m) {
        Timer.Sample sample = Timer.start(meterRegistry);
        PartnerEventMessage event = null;
        String actor = "worker:" + queueShortName();
        try {
            event = mapper.readValue(m.payload(), PartnerEventMessage.class);
            processor.process(event, actor);
            jdbc.queryForObject("SELECT pgmq.delete(?, ?)",
                    Boolean.class, queueName(), m.msgId());
            meterRegistry.counter("peg.consumer.processed", "queue", queueName()).increment();
        } catch (Exception ex) {
            log.error("processing failed queue={} msg_id={} attempt={}",
                    queueName(), m.msgId(), m.readCt(), ex);
            meterRegistry.counter("peg.consumer.failed", "queue", queueName()).increment();

            if (m.readCt() >= props.getMaxAttempts()) {
                try {
                    jdbc.queryForObject("SELECT pgmq.archive(?, ?)",
                            Boolean.class, queueName(), m.msgId());
                    if (event != null) {
                        events.markFailed(event.partnerId(), event.eventId(),
                                ex.getClass().getSimpleName() + ": " + ex.getMessage(),
                                actor);
                    }
                    meterRegistry.counter("peg.consumer.dlq", "queue", queueName()).increment();
                    log.warn("archived to DLQ queue={} msg_id={} after {} attempts",
                            queueName(), m.msgId(), m.readCt());
                } catch (Exception archiveEx) {
                    log.error("failed to archive queue={} msg_id={}",
                            queueName(), m.msgId(), archiveEx);
                }
            }
        } finally {
            sample.stop(meterRegistry.timer("peg.consumer.duration", "queue", queueName()));
        }
    }

    protected record PgmqMessage(long msgId, int readCt, String payload) {}
}
