package com.example.peg.shared;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EventStatusTest {

    @Test
    void enumeratesFiveStates() {
        assertThat(EventStatus.values()).containsExactly(
                EventStatus.RECEIVED,
                EventStatus.PENDING,
                EventStatus.PROCESSING,
                EventStatus.PROCESSED,
                EventStatus.FAILED);
    }

    @Test
    void valueOf_roundTrip() {
        for (EventStatus s : EventStatus.values()) {
            assertThat(EventStatus.valueOf(s.name())).isEqualTo(s);
        }
    }
}
