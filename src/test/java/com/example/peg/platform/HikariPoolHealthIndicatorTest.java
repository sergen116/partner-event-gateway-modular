package com.example.peg.platform;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HikariPoolHealthIndicatorTest {

    @Test
    void rejectsNonHikariDataSource() {
        DataSource ds = mock(DataSource.class);
        assertThatThrownBy(() -> new HikariPoolHealthIndicator(ds))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Expected HikariDataSource");
    }

    @Test
    void reportsUp_whenNoThreadsAwaiting() {
        HikariDataSource ds = mock(HikariDataSource.class);
        HikariPoolMXBean pool = mock(HikariPoolMXBean.class);
        when(ds.getHikariPoolMXBean()).thenReturn(pool);
        when(ds.getMaximumPoolSize()).thenReturn(20);
        when(pool.getThreadsAwaitingConnection()).thenReturn(0);
        when(pool.getActiveConnections()).thenReturn(2);
        when(pool.getIdleConnections()).thenReturn(8);

        Health health = new HikariPoolHealthIndicator(ds).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails())
                .containsEntry("active", 2)
                .containsEntry("idle", 8)
                .containsEntry("pending", 0)
                .containsEntry("max", 20);
    }

    @Test
    void reportsDegraded_whenThreadsAreWaiting() {
        HikariDataSource ds = mock(HikariDataSource.class);
        HikariPoolMXBean pool = mock(HikariPoolMXBean.class);
        when(ds.getHikariPoolMXBean()).thenReturn(pool);
        when(ds.getMaximumPoolSize()).thenReturn(20);
        when(pool.getThreadsAwaitingConnection()).thenReturn(5);
        when(pool.getActiveConnections()).thenReturn(20);
        when(pool.getIdleConnections()).thenReturn(0);

        Health health = new HikariPoolHealthIndicator(ds).health();

        assertThat(health.getStatus().getCode()).isEqualTo("DEGRADED");
        assertThat(health.getDetails().get("pending")).isEqualTo(5);
    }
}
