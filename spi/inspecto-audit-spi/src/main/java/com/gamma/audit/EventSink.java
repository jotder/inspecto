package com.gamma.audit;

/**
 * The write side of the operational event log, as a CONTRACT: what a module that only <em>emits</em> needs.
 * {@code EventLog} (the implementation: store, chain, scrubbing, subscribers) implements it; modules that sit
 * below the implementation (the access policy's {@code AuditTrail}, the Link Analysis API) emit through
 * {@link #current()} and never name the class.
 *
 * <p>Emitting never throws and never disturbs the caller.
 */
public interface EventSink {

    /** Record one event. Never throws. */
    void emit(Event event);

    /** Convenience: build and record a domain event from a populated builder. */
    default void emit(Event.Builder builder) {
        if (builder != null) emit(builder.build());
    }

    /** The sink for the calling Space (the Space MDC / contained scope selects it). Never {@code null}. */
    static EventSink current() {
        return EventLog.current();
    }
}
