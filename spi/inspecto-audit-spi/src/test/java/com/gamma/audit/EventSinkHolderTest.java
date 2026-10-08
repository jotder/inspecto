package com.gamma.audit;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.Iterator;
import java.util.List;
import java.util.ServiceConfigurationError;

import static org.junit.jupiter.api.Assertions.*;

class EventSinkHolderTest {

    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private Logger logger;

    @BeforeEach
    void attach() {
        EventSink.current(); // force the one-time class-path resolution BEFORE the appender attaches
        logger = (Logger) LoggerFactory.getLogger(EventSink.class);
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(logs);
    }

    @Test
    void absentProviderFallsBackToNoOpAndWarnsOnce() {
        EventSink sink = EventSink.Holder.resolve(() -> List.<EventSink>of().iterator());
        assertDoesNotThrow(() -> sink.emit((Event) null));
        assertEquals(1, logs.list.size());
        assertEquals(Level.WARN, logs.list.get(0).getLevel());
        assertTrue(logs.list.get(0).getFormattedMessage().contains("no EventSink provider on the class path"));
    }

    @Test
    void presentProviderIsReturnedWithoutLogging() {
        EventSink real = event -> { };
        assertSame(real, EventSink.Holder.resolve(() -> List.of(real).iterator()));
        assertTrue(logs.list.isEmpty());
    }

    @Test
    void providerThatFailsToLinkIsLoudNotSilentNoOp() {
        Iterator<EventSink> failing = new Iterator<>() {
            public boolean hasNext() { throw new ServiceConfigurationError("com.gamma.event.X: Provider not found"); }
            public EventSink next() { throw new AssertionError(); }
        };
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> EventSink.Holder.resolve(() -> failing));
        assertInstanceOf(ServiceConfigurationError.class, e.getCause());
        assertEquals(1, logs.list.size());
        assertEquals(Level.ERROR, logs.list.get(0).getLevel());
    }
}
