package com.example.peg.platform;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import javax.sql.DataSource;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HikariPoolHealthIndicatorTest {

    @Test
    void rejectsNonHikariDataSource() {
        DataSource ds = mock(DataSource.class);
        assertThatThrownBy(() -> new HikariPoolHealthIndicator(ds, ds))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Expected HikariDataSource");
    }

    @Test
    void reportsUp_whenNoThreadsAwaiting_fallbackPath() {
        HikariDataSource ds = mockPool(0, 2, 8, 20);

        Health health = new HikariPoolHealthIndicator(ds, ds).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails())
                .containsEntry("replica", "fallback-to-primary");
        @SuppressWarnings("unchecked")
        Map<String, Object> primary = (Map<String, Object>) health.getDetails().get("primary");
        assertThat(primary)
                .containsEntry("active", 2)
                .containsEntry("idle", 8)
                .containsEntry("pending", 0)
                .containsEntry("max", 20);
    }

    @Test
    void reportsDegraded_whenPrimaryHasThreadsWaiting_fallbackPath() {
        HikariDataSource ds = mockPool(5, 20, 0, 20);

        Health health = new HikariPoolHealthIndicator(ds, ds).health();

        assertThat(health.getStatus().getCode()).isEqualTo("DEGRADED");
        @SuppressWarnings("unchecked")
        Map<String, Object> primary = (Map<String, Object>) health.getDetails().get("primary");
        assertThat(primary.get("pending")).isEqualTo(5);
    }

    @Test
    void reportsBothPools_whenReplicaIsDistinct() {
        HikariDataSource primary = mockPool(0, 4, 6, 40);
        HikariDataSource replica = mockPool(0, 1, 9, 10);

        Health health = new HikariPoolHealthIndicator(primary, replica).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        @SuppressWarnings("unchecked")
        Map<String, Object> primaryDetails = (Map<String, Object>) health.getDetails().get("primary");
        @SuppressWarnings("unchecked")
        Map<String, Object> replicaDetails = (Map<String, Object>) health.getDetails().get("replica");
        assertThat(primaryDetails).containsEntry("max", 40).containsEntry("active", 4);
        assertThat(replicaDetails).containsEntry("max", 10).containsEntry("active", 1);
    }

    @Test
    void reportsDegraded_whenOnlyReplicaIsPressured() {
        HikariDataSource primary = mockPool(0, 2, 8, 40);
        HikariDataSource replica = mockPool(3, 10, 0, 10);

        Health health = new HikariPoolHealthIndicator(primary, replica).health();

        assertThat(health.getStatus().getCode()).isEqualTo("DEGRADED");
        @SuppressWarnings("unchecked")
        Map<String, Object> replicaDetails = (Map<String, Object>) health.getDetails().get("replica");
        assertThat(replicaDetails.get("pending")).isEqualTo(3);
    }

    private static HikariDataSource mockPool(int pending, int active, int idle, int max) {
        HikariDataSource ds = mock(HikariDataSource.class);
        HikariPoolMXBean pool = mock(HikariPoolMXBean.class);
        when(ds.getHikariPoolMXBean()).thenReturn(pool);
        when(ds.getMaximumPoolSize()).thenReturn(max);
        when(pool.getThreadsAwaitingConnection()).thenReturn(pending);
        when(pool.getActiveConnections()).thenReturn(active);
        when(pool.getIdleConnections()).thenReturn(idle);
        return ds;
    }
}
