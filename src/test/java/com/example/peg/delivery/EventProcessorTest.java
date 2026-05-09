package com.example.peg.delivery;

import com.example.peg.query.EventRepository;
import com.example.peg.shared.EventType;
import com.example.peg.shared.PartnerEventMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EventProcessorTest {

    private EventRepository repo;
    private DownstreamCallService downstream;
    private PlatformTransactionManager txManager;
    private TransactionStatus txStatus;
    private EventProcessor processor;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        repo = mock(EventRepository.class);
        downstream = mock(DownstreamCallService.class);
        txManager = mock(PlatformTransactionManager.class);
        txStatus = mock(TransactionStatus.class);
        when(txManager.getTransaction(any())).thenReturn(txStatus);
        processor = new EventProcessor(repo, downstream, txManager);
    }

    @Test
    void process_skipsAndReturns_whenAlreadyTerminal() {
        PartnerEventMessage msg = msg(EventType.ORDER_CREATED);
        when(repo.tryMarkProcessing(eq("p"), eq(msg.eventId()), anyString())).thenReturn(false);

        processor.process(msg, "actor");

        verify(downstream, never()).notify(any());
        verify(repo, never()).markProcessed(any(), any(), any());
        // Claim tx ran (begin + commit), no second tx for finalize.
        verify(txManager, times(1)).getTransaction(any());
        verify(txManager, times(1)).commit(txStatus);
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
    void process_commitsClaimBeforeDownstreamCall_andFinalizeAfter() {
        PartnerEventMessage msg = msg(EventType.ORDER_CREATED);
        when(repo.tryMarkProcessing(eq("p"), eq(msg.eventId()), anyString())).thenReturn(true);

        processor.process(msg, "actor");

        // Two distinct transactions bracket the downstream HTTP call.
        InOrder order = inOrder(txManager, repo, downstream);
        order.verify(txManager).getTransaction(any());                          // tx1 begin
        order.verify(repo).tryMarkProcessing("p", msg.eventId(), "actor");
        order.verify(txManager).commit(txStatus);                               // tx1 commit
        order.verify(downstream).notify(msg);                                   // outside tx
        order.verify(txManager).getTransaction(any());                          // tx2 begin
        order.verify(repo).markProcessed("p", msg.eventId(), "actor");
        order.verify(txManager).commit(txStatus);                               // tx2 commit
    }

    @Test
    void process_propagatesDownstreamException_leavesRowInProcessing() {
        PartnerEventMessage msg = msg(EventType.ORDER_CREATED);
        when(repo.tryMarkProcessing(any(), any(), any())).thenReturn(true);
        org.mockito.Mockito.doThrow(new RuntimeException("boom")).when(downstream).notify(msg);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> processor.process(msg, "actor"))
                .isInstanceOf(RuntimeException.class).hasMessageContaining("boom");

        // Claim tx committed before the failure; finalize tx never began. The row
        // is left in PROCESSING so pgmq redelivery + the PROCESSING→PROCESSING
        // rule on tryMarkProcessing can recover.
        verify(repo).tryMarkProcessing("p", msg.eventId(), "actor");
        verify(txManager, times(1)).commit(txStatus);
        verify(repo, never()).markProcessed(any(), any(), any());
    }

    private PartnerEventMessage msg(EventType type) {
        return new PartnerEventMessage(
                UUID.randomUUID(), "p", type, "ref",
                mapper.nullNode(), Instant.now(), null, null);
    }
}
