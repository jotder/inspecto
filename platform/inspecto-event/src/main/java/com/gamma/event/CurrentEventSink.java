package com.gamma.event;

import com.gamma.audit.Event;
import com.gamma.audit.EventSink;

/**
 * The {@code ServiceLoader} entry behind {@link EventSink#current()}: every emit goes to the calling Space's
 * {@link EventLog#current()}, resolved at call time (the Space MDC / contained scope can differ per call).
 */
public final class CurrentEventSink implements EventSink {

    @Override
    public void emit(Event event) {
        EventLog.current().emit(event);
    }
}
