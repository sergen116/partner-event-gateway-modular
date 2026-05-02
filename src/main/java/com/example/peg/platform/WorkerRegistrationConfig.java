package com.example.peg.platform;

import com.example.peg.delivery.AddressUpdatedWorker;
import com.example.peg.delivery.OrderCancelledWorker;
import com.example.peg.delivery.OrderCreatedWorker;
import com.example.peg.delivery.PgmqWorker;
import com.example.peg.delivery.ReturnRequestedWorker;
import com.example.peg.delivery.ShipmentUpdatedWorker;
import com.example.peg.shared.EventType;
import com.example.peg.delivery.EventProcessor;
import com.example.peg.query.EventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * Constructs the worker beans active for this pod's runtime mode.
 * Returning {@code List<PgmqWorker>} makes the Stage 1→2 transition a
 * deployment-topology change rather than a code change — adding/removing
 * modes never touches workers.
 */
@Configuration
@Slf4j
@RequiredArgsConstructor
public class WorkerRegistrationConfig {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final EventProcessor processor;
    private final EventRepository eventRepository;
    private final MeterRegistry meterRegistry;
    private final ConsumerProperties consumerProps;
    private final RuntimeProperties runtime;

    @Bean
    public List<PgmqWorker> activeWorkers() {
        List<PgmqWorker> workers = new ArrayList<>();
        for (EventType type : runtime.activeEventTypes()) {
            workers.add(buildWorker(type));
        }
        log.info("runtime mode={} activated {} worker(s): {}",
                runtime.getMode(), workers.size(),
                workers.stream().map(PgmqWorker::queueName).toList());
        return workers;
    }

    private PgmqWorker buildWorker(EventType type) {
        return switch (type) {
            case ORDER_CREATED    -> new OrderCreatedWorker(jdbc, mapper, processor, eventRepository, meterRegistry, consumerProps);
            case SHIPMENT_UPDATED -> new ShipmentUpdatedWorker(jdbc, mapper, processor, eventRepository, meterRegistry, consumerProps);
            case RETURN_REQUESTED -> new ReturnRequestedWorker(jdbc, mapper, processor, eventRepository, meterRegistry, consumerProps);
            case ADDRESS_UPDATED  -> new AddressUpdatedWorker(jdbc, mapper, processor, eventRepository, meterRegistry, consumerProps);
            case ORDER_CANCELLED  -> new OrderCancelledWorker(jdbc, mapper, processor, eventRepository, meterRegistry, consumerProps);
        };
    }
}
