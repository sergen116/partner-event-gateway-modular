package com.example.peg.platform;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Surfaces Hikari pool pressure to /actuator/health for both the writer and
 * the (optional) read-replica pool.
 *
 * <p>Reports {@code DEGRADED} (not {@code DOWN}) when callers on either pool
 * are queued waiting for a connection. {@code DOWN} would fail k8s readiness
 * probes and remove a pod that's already pool-pressured — counter-productive.
 * {@code DEGRADED} stays in service but signals the condition to dashboards
 * and orchestrators that opt to read it.
 *
 * <p>The instantaneous {@code threadsAwaitingConnection} snapshot is what's
 * reported; sustained-pressure paging is the Prometheus alert's job.
 *
 * <p>When the replica DataSource is the same instance as the primary (no
 * {@code REPLICA_DB_URL} configured), the {@code replica} entry is reported
 * as {@code "fallback-to-primary"} rather than duplicating the primary's
 * counters.
 */
@Component
@Slf4j
public class HikariPoolHealthIndicator implements HealthIndicator {

    static final Status DEGRADED = new Status("DEGRADED");

    private final PoolView primary;
    private final PoolView replica;

    public HikariPoolHealthIndicator(
            @Qualifier("primaryDataSource") DataSource primary,
            @Qualifier("replicaDataSource") DataSource replica) {
        this.primary = PoolView.of(primary);
        this.replica = (replica == primary) ? null : PoolView.of(replica);
    }

    @Override
    public Health health() {
        Map<String, Object> primaryDetails = primary.snapshot();
        boolean pendingOnPrimary = (int) primaryDetails.get("pending") > 0;

        Health.Builder builder = Health.up()
                .withDetail("primary", primaryDetails);

        if (replica == null) {
            builder.withDetail("replica", "fallback-to-primary");
            return (pendingOnPrimary ? builder.status(DEGRADED) : builder).build();
        }

        Map<String, Object> replicaDetails = replica.snapshot();
        boolean pendingOnReplica = (int) replicaDetails.get("pending") > 0;
        builder.withDetail("replica", replicaDetails);

        if (pendingOnPrimary || pendingOnReplica) {
            builder.status(DEGRADED);
        }
        return builder.build();
    }

    private record PoolView(HikariPoolMXBean pool, int max) {

        static PoolView of(DataSource ds) {
            if (!(ds instanceof HikariDataSource hikari)) {
                throw new IllegalStateException(
                        "Expected HikariDataSource but found " + ds.getClass().getName());
            }
            return new PoolView(hikari.getHikariPoolMXBean(), hikari.getMaximumPoolSize());
        }

        Map<String, Object> snapshot() {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("active", pool.getActiveConnections());
            details.put("idle", pool.getIdleConnections());
            details.put("pending", pool.getThreadsAwaitingConnection());
            details.put("max", max);
            return details;
        }
    }
}
