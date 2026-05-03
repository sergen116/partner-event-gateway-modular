package com.example.peg.platform;

import com.example.peg.delivery.EventProcessor;
import com.example.peg.delivery.OrderCreatedWorker;
import com.example.peg.delivery.PgmqWorker;
import com.example.peg.query.EventRepository;
import com.example.peg.shared.EventType;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class WorkerRegistrationConfigTest {

    @Test
    void apiMode_buildsZeroWorkers() {
        WorkerRegistrationConfig cfg = config(RuntimeProperties.Mode.API);
        assertThat(cfg.activeWorkers()).isEmpty();
    }

    @Test
    void consumerAllMode_buildsOneWorkerPerEventType() {
        WorkerRegistrationConfig cfg = config(RuntimeProperties.Mode.CONSUMER_ALL);
        List<PgmqWorker> workers = cfg.activeWorkers();
        assertThat(workers).hasSize(EventType.values().length);
        assertThat(workers).extracting(PgmqWorker::queueName)
                .containsExactlyInAnyOrder(
                        EventType.ORDER_CREATED.queueName(),
                        EventType.SHIPMENT_UPDATED.queueName(),
                        EventType.RETURN_REQUESTED.queueName(),
                        EventType.ADDRESS_UPDATED.queueName(),
                        EventType.ORDER_CANCELLED.queueName());
    }

    @Test
    void singleConsumerMode_buildsOneWorker() {
        WorkerRegistrationConfig cfg = config(RuntimeProperties.Mode.CONSUMER_ORDER_CREATED);
        List<PgmqWorker> workers = cfg.activeWorkers();
        assertThat(workers).hasSize(1);
        assertThat(workers.get(0)).isInstanceOf(OrderCreatedWorker.class);
    }

    private WorkerRegistrationConfig config(RuntimeProperties.Mode mode) {
        RuntimeProperties runtime = new RuntimeProperties();
        runtime.setMode(mode);
        return new WorkerRegistrationConfig(
                mock(JdbcTemplate.class),
                new ObjectMapper(),
                mock(EventProcessor.class),
                mock(EventRepository.class),
                new SimpleMeterRegistry(),
                new ConsumerProperties(),
                runtime);
    }
}
