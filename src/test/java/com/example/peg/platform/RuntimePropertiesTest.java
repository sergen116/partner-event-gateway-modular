package com.example.peg.platform;

import com.example.peg.shared.EventType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimePropertiesTest {

    @Test
    void apiMode_runsApi_butNoConsumers() {
        RuntimeProperties p = new RuntimeProperties();
        p.setMode(RuntimeProperties.Mode.API);

        assertThat(p.runsApi()).isTrue();
        assertThat(p.runsAnyConsumer()).isFalse();
        assertThat(p.runsOutboxPoller()).isTrue();   // outbox runs alongside API
        assertThat(p.activeEventTypes()).isEmpty();
    }

    @Test
    void consumerAllMode_runsEverything() {
        RuntimeProperties p = new RuntimeProperties();
        p.setMode(RuntimeProperties.Mode.CONSUMER_ALL);

        assertThat(p.runsApi()).isTrue();
        assertThat(p.runsAnyConsumer()).isTrue();
        assertThat(p.runsOutboxPoller()).isTrue();
        assertThat(p.activeEventTypes()).containsExactlyInAnyOrder(EventType.values());
    }

    @Test
    void singleConsumerMode_runsOnlyItsType() {
        RuntimeProperties p = new RuntimeProperties();
        p.setMode(RuntimeProperties.Mode.CONSUMER_ORDER_CREATED);

        assertThat(p.runsApi()).isFalse();
        assertThat(p.runsOutboxPoller()).isFalse();
        assertThat(p.activeEventTypes()).containsExactly(EventType.ORDER_CREATED);
    }

    @Test
    void allConsumerModes_areSingleType() {
        for (RuntimeProperties.Mode mode : RuntimeProperties.Mode.values()) {
            if (mode == RuntimeProperties.Mode.API || mode == RuntimeProperties.Mode.CONSUMER_ALL) {
                continue;
            }
            RuntimeProperties p = new RuntimeProperties();
            p.setMode(mode);
            assertThat(p.activeEventTypes())
                    .as("mode %s should activate exactly one event type", mode)
                    .hasSize(1);
        }
    }
}
