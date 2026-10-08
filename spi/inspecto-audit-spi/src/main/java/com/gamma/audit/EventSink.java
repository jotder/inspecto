package com.gamma.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Iterator;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.function.Supplier;

/**
 * The write side of the operational event log, as a CONTRACT: what a module that only <em>emits</em> needs.
 * The implementation ({@code com.gamma.event.EventLog} in {@code inspecto-event}: store, audit chain, scrubbing,
 * subscribers) implements it; modules that sit below it (the access policy's {@code AuditTrail}, the Link Analysis
 * API) emit through {@link #current()} and never name the class.
 *
 * <p>{@link #current()} finds the implementation's {@code EventSink} through {@link ServiceLoader} (the one entry
 * in {@code inspecto-event} delegates to the calling Space's log). With no implementation on the classpath (a
 * contract-only deployment) it is a no-op sink: events are dropped, never an error - but that is logged ONCE at
 * WARN. A provider that IS on the classpath yet fails to link is never swallowed into the no-op: it is logged and
 * rethrown ({@link IllegalStateException}), so the failure stays loud.
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
        private static final Logger LOG = LoggerFactory.getLogger(EventSink.class);

        private Holder() {}

        static final EventSink SINK = resolve(() -> ServiceLoader.load(EventSink.class).iterator());

        /**
         * First provider, or a no-op sink (one WARN) when there is none. A provider that fails to link
         * ({@link ServiceConfigurationError}) is logged and rethrown as {@link IllegalStateException}.
         */
        static EventSink resolve(Supplier<Iterator<EventSink>> providers) {
            try {
                Iterator<EventSink> it = providers.get();
                if (it.hasNext()) return it.next();
            } catch (ServiceConfigurationError e) {
                LOG.error("EventSink provider failed to load - refusing to fall back to a silent no-op: {}", e.toString());
                throw new IllegalStateException("EventSink provider failed to load: " + e.getMessage(), e);
            }
            LOG.warn("no EventSink provider on the class path: audit events are dropped");
            return event -> { };
        }
    }
}
