package com.example.peg.delivery;

import com.example.peg.platform.RuntimeProperties;
import com.example.peg.query.EventRepository;
import com.example.peg.query.OutboxRepository;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;

/**
 * Drains the event_outbox table into pgmq.
 *
 * <p>The outbox pattern decouples "row committed in events table" from
 * "message delivered to pgmq". The controller writes both rows in one
 * transaction; this poller picks them up afterwards. If pgmq.send fails,
 * the outbox row stays for retry. With outbox, the API commit is the source
 * of truth and queue delivery is eventually-consistent.
 *
 * <p>SKIP LOCKED on claim means multiple API pods can each run the poller
 * without coordinating — they grab disjoint batches.
 *
 * <p>We use {@link TransactionTemplate} explicitly rather than {@code @Transactional}
 * to avoid Spring's self-invocation pitfall: a scheduled call to a {@code @Transactional}
 * method on the same instance would bypass the proxy.
 */
@Component
@Slf4j
public class OutboxPoller {

    private static final int BATCH_SIZE = 50;
    private static final Duration POLL_INTERVAL = Duration.ofMillis(250);

    private final OutboxRepository outbox;
    private final EventRepository events;
    private final JdbcTemplate jdbc;
    private final RuntimeProperties runtime;
    private final TaskScheduler taskScheduler;
    private final TransactionTemplate tx;

    public OutboxPoller(OutboxRepository outbox,
                        EventRepository events,
                        JdbcTemplate jdbc,
                        RuntimeProperties runtime,
                        TaskScheduler taskScheduler,
                        PlatformTransactionManager txManager) {
        this.outbox = outbox;
        this.events = events;
        this.jdbc = jdbc;
        this.runtime = runtime;
        this.taskScheduler = taskScheduler;
        this.tx = new TransactionTemplate(txManager);
    }

    @PostConstruct
    public void start() {
        if (!runtime.runsOutboxPoller()) {
            log.info("outbox poller disabled — runtime mode {} does not run API role",
                    runtime.getMode());
            return;
        }
        taskScheduler.scheduleWithFixedDelay(this::pollSafely, POLL_INTERVAL);
        log.info("outbox poller scheduled at {}ms intervals", POLL_INTERVAL.toMillis());
    }

    private void pollSafely() {
        try {
            int drained = drain();
            if (drained > 0) {
                log.info("outbox poll cycle complete — processed {} row(s){}",
                        drained, drained == BATCH_SIZE ? " (full batch, more may be pending)" : "");
            }
        } catch (Exception ex) {
            log.error("outbox poll failed", ex);
        }
    }

    /**
     * One pass over the outbox. The whole batch runs in one transaction so the
     * FOR UPDATE SKIP LOCKED locks are held until commit. If anything throws,
     * the transaction rolls back and locked rows become visible to the next poll.
     */
    int drain() {
        return tx.execute(status -> {
            var rows = outbox.claimBatch(BATCH_SIZE);
            if (rows.isEmpty()) {
                return 0;
            }
            log.info("outbox poll started — claimed {} row(s) for forwarding", rows.size());
            for (var row : rows) {
                try {
                    log.info("forwarding outbox row id={} eventId={} partnerId={} queue={} attempts={}",
                            row.id(), row.eventId(), row.partnerId(), row.queueName(), row.attempts());
                    sendToPgmq(row);
                    // Outbox row's job is done — events table is the audit source.
                    outbox.deleteSent(row.id());
                    events.markPending(row.partnerId(), row.eventId(), ACTOR);
                    log.info("forwarded outbox row id={} eventId={} — sent to pgmq, deleted, marked pending",
                            row.id(), row.eventId());
                } catch (Exception ex) {
                    log.error("failed to forward outbox row id={} eventId={}",
                            row.id(), row.eventId(), ex);
                    outbox.recordFailure(row.id());
                }
            }
            log.info("outbox drain finished — total={}", rows.size());
            return rows.size();
        });
    }

    private static final String ACTOR = "outbox-poller";

    private void sendToPgmq(OutboxRepository.OutboxRow row) {
        Long id = jdbc.queryForObject(
                "SELECT pgmq.send(?, ?::jsonb)",
                Long.class, row.queueName(), row.payload());
        if (id == null) {
            throw new IllegalStateException("pgmq.send returned null for queue " + row.queueName());
        }
        // We don't keep the msg_id — the outbox row is deleted on success, so
        // there's nowhere to put it. The events table tracks the lifecycle.
    }
}
