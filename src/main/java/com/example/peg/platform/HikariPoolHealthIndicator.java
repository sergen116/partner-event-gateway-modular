package com.example.peg.platform;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/**
 * Surfaces Hikari pool pressure to /actuator/health.
 *
 * <p>Reports {@code DEGRADED} (not {@code DOWN}) when callers are queued waiting
 * for a connection. {@code DOWN} would fail k8s readiness probes and remove a
 * pod that's already pool-pressured — counter-productive. {@code DEGRADED}
 * stays in service but signals the condition to dashboards and orchestrators
 * that opt to read it.
 *
 * <p>The instantaneous {@code threadsAwaitingConnection} snapshot is what's
 * reported; sustained-pressure paging is the Prometheus alert's job.
 */
@Component
@Slf4j
public class HikariPoolHealthIndicator implements HealthIndicator {

    static final Status DEGRADED = new Status("DEGRADED");

    private final HikariPoolMXBean pool;
    private final int max;

    public HikariPoolHealthIndicator(DataSource dataSource) {
        if (!(dataSource instanceof HikariDataSource hikari)) {
            throw new IllegalStateException(
                    "Expected HikariDataSource but found " + dataSource.getClass().getName());
        }
        this.pool = hikari.getHikariPoolMXBean();
        this.max = hikari.getMaximumPoolSize();
    }

    @Override
    public Health health() {
        int pending = pool.getThreadsAwaitingConnection();
        Health.Builder builder = (pending > 0 ? Health.status(DEGRADED) : Health.up())
                .withDetail("active", pool.getActiveConnections())
                .withDetail("idle", pool.getIdleConnections())
                .withDetail("pending", pending)
                .withDetail("max", max);
        return builder.build();
    }
}
