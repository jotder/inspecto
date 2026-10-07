package com.gamma.event;

import com.gamma.audit.Event;
import com.gamma.audit.EventSink;
import com.gamma.audit.EventType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/** The contract's {@link EventSink#current()} finds this module's implementation through ServiceLoader. */
class CurrentEventSinkTest {

    @Test
    void currentSinkIsFoundAndEmitsIntoTheCallingSpacesLog() {
        assertInstanceOf(CurrentEventSink.class, EventSink.current());
        List<Event> seen = new ArrayList<>();
        Consumer<Event> sub = seen::add;
        EventLog.current().addSubscriber(sub);
        try {
            EventSink.current().emit(Event.builder(EventType.LOG).source("t").message("via the contract"));
        } finally {
            EventLog.current().removeSubscriber(sub);
        }
        assertEquals(1, seen.size());
        assertEquals("via the contract", seen.get(0).message());
    }
}
