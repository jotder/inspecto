package com.gamma.etl;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ConsignmentEventBusTest {

    private static ConsignmentEvent evt(String batchId) {
        return new ConsignmentEvent("p", batchId, "SUCCESS", List.of("year=2020/month=04/day=03"), 10, 5L, 0);
    }

    @Test
    void fansOutToAllSubscribers() {
        ConsignmentEventBus bus = new ConsignmentEventBus();
        List<String> a = new ArrayList<>(), b = new ArrayList<>();
        bus.subscribe(e -> a.add(e.batchId()));
        bus.subscribe(e -> b.add(e.batchId()));
        assertEquals(2, bus.listenerCount());

        bus.publish(evt("b1"));
        assertEquals(List.of("b1"), a);
        assertEquals(List.of("b1"), b);
    }

    @Test
    void aThrowingListenerDoesNotDropTheEventForOthers() {
        ConsignmentEventBus bus = new ConsignmentEventBus();
        List<String> received = new ArrayList<>();
        bus.subscribe(e -> { throw new RuntimeException("boom"); });
        bus.subscribe(e -> received.add(e.batchId()));

        assertDoesNotThrow(() -> bus.publish(evt("b2")));
        assertEquals(List.of("b2"), received, "second listener still receives despite first throwing");
    }

    @Test
    void sinkPublishes() {
        ConsignmentEventBus bus = new ConsignmentEventBus();
        List<String> received = new ArrayList<>();
        bus.subscribe(e -> received.add(e.batchId()));
        bus.sink().accept(evt("b3"));
        assertEquals(List.of("b3"), received);
    }
}
