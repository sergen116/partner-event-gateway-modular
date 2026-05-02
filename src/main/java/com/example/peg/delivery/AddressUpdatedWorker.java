package com.example.peg.delivery;

import com.example.peg.platform.ConsumerProperties;
import com.example.peg.shared.EventType;
import com.example.peg.delivery.EventProcessor;
import com.example.peg.query.EventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;

public class AddressUpdatedWorker extends PgmqWorker {

    public AddressUpdatedWorker(JdbcTemplate j, ObjectMapper m, EventProcessor p,
                          EventRepository events,
                          MeterRegistry r, ConsumerProperties props) {
        super(j, m, p, events, r, props);
    }

    @Override
    public String queueName() {
        return EventType.ADDRESS_UPDATED.queueName();
    }
}
