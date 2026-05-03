package com.example.peg.delivery;

import com.example.peg.query.EventRepository;
import com.example.peg.shared.EventType;
import com.example.peg.shared.PartnerEventMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EventProcessorTest {

    private EventRepository repo;
    private DownstreamCallService downstream;
    private EventProcessor processor;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        repo = mock(EventRepository.class);
        downstream = mock(DownstreamCallService.class);
        processor = new EventProcessor(repo, downstream);
    }

    @Test
    void process_skipsAndReturns_whenAlreadyTerminal() {
        PartnerEventMessage msg = msg(EventType.ORDER_CREATED);
        when(repo.tryMarkProcessing(eq("p"), eq(msg.eventId()), anyString())).thenReturn(false);

        processor.process(msg, "actor");

        verify(downstream, never()).notify(any());
        verify(repo, never()).markProcessed(any(), any(), any());
    }

    @Test
    void process_notifiesAndMarksProcessed_forAllSupportedTypes() {
        for (EventType type : EventType.values()) {
            PartnerEventMessage msg = msg(type);
            when(repo.tryMarkProcessing(eq("p"), eq(msg.eventId()), anyString())).thenReturn(true);

            processor.process(msg, "actor");

            verify(downstream).notify(msg);
            verify(repo).markProcessed("p", msg.eventId(), "actor");
        }
        verify(downstream, times(EventType.values().length)).notify(any());
    }

    @Test
    void process_propagatesDownstreamException_doesNotMarkProcessed() {
        PartnerEventMessage msg = msg(EventType.ORDER_CREATED);
        when(repo.tryMarkProcessing(any(), any(), any())).thenReturn(true);
        org.mockito.Mockito.doThrow(new RuntimeException("boom")).when(downstream).notify(msg);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> processor.process(msg, "actor"))
                .isInstanceOf(RuntimeException.class).hasMessageContaining("boom");

        verify(repo, never()).markProcessed(any(), any(), any());
    }

    private PartnerEventMessage msg(EventType type) {
        return new PartnerEventMessage(
                UUID.randomUUID(), "p", type, "ref",
                mapper.nullNode(), Instant.now(), null, null);
    }
}
