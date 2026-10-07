package com.gamma.audit;

import java.util.ServiceLoader;

/**
 * The write side of the operational event log, as a CONTRACT: what a module that only <em>emits</em> needs.
 * The implementation ({@code com.gamma.event.EventLog} in {@code inspecto-event}: store, audit chain, scrubbing,
 * subscribers) implements it; modules that sit below it (the access policy's {@code AuditTrail}, the Link Analysis
 * API) emit through {@link #current()} and never name the class.
 *
 * <p>{@link #current()} finds the implementation's {@code EventSink} through {@link ServiceLoader} (the one entry
 * in {@code inspecto-event} delegates to the calling Space's log). With no implementation on the classpath (a
 * contract-only deployment) it is a no-op sink: events are dropped, never an error.
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
        return Holder.SINK;
    }

    /** Lazy holder: the provider is looked up once, on first use. */
    final class Holder {
        private Holder() {}

        static final EventSink SINK = ServiceLoader.load(EventSink.class).findFirst().orElse(event -> { });
    }
}
