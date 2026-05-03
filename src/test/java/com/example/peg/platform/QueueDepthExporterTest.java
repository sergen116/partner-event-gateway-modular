package com.example.peg.platform;

import com.example.peg.shared.EventType;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class QueueDepthExporterTest {

    private JdbcTemplate jdbc;
    private MeterRegistry meters;
    private RuntimeProperties runtime;
    private QueueDepthExporter exporter;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        meters = new SimpleMeterRegistry();
        runtime = new RuntimeProperties();
        exporter = new QueueDepthExporter(jdbc, meters, runtime);
    }

    @Test
    void registerGauges_skips_inConsumerOnlyMode() {
        runtime.setMode(RuntimeProperties.Mode.CONSUMER_ORDER_CREATED);
        exporter.registerGauges();
        assertThat(meters.find("peg.queue.length").gauges()).isEmpty();
    }

    @Test
    void registerGauges_createsOneGaugePerEventType_inApiMode() {
        runtime.setMode(RuntimeProperties.Mode.API);
        exporter.registerGauges();
        assertThat(meters.find("peg.queue.length").gauges())
                .hasSize(EventType.values().length);
        assertThat(meters.find("peg.queue.oldest_msg_age_seconds").gauges())
                .hasSize(EventType.values().length);
    }

    @Test
    void exportDepths_skips_inConsumerOnlyMode() {
        runtime.setMode(RuntimeProperties.Mode.CONSUMER_ORDER_CREATED);
        exporter.registerGauges();
        exporter.exportDepths();
        verify(jdbc, never()).query(anyString(), any(RowCallbackHandler.class), any(Object[].class));
    }

    @Test
    void exportDepths_updatesGauges_inApiMode() throws Exception {
        runtime.setMode(RuntimeProperties.Mode.API);
        exporter.registerGauges();

        doAnswer(invocation -> {
            RowCallbackHandler handler = invocation.getArgument(1);
            java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
            org.mockito.Mockito.when(rs.next()).thenReturn(true);
            org.mockito.Mockito.when(rs.getLong("queue_length")).thenReturn(42L);
            org.mockito.Mockito.when(rs.getLong("oldest_age")).thenReturn(7L);
            handler.processRow(rs);
            return null;
        }).when(jdbc).query(anyString(), any(RowCallbackHandler.class), any(Object[].class));

        exporter.exportDepths();

        // After update, gauges should reflect the new values
        assertThat(meters.find("peg.queue.length")
                .tag("queue", EventType.ORDER_CREATED.queueName()).gauge().value())
                .isEqualTo(42.0);
    }

    @Test
    void exportDepths_swallowsDataAccessExceptions() {
        runtime.setMode(RuntimeProperties.Mode.API);
        exporter.registerGauges();
        doThrow(new DataAccessResourceFailureException("db down"))
                .when(jdbc).query(anyString(), any(RowCallbackHandler.class), any(Object[].class));

        // Should not throw
        exporter.exportDepths();
    }
}
