package com.gamma.job;

import com.gamma.event.Event;
import com.gamma.event.EventLog;
import com.gamma.event.EventType;
import com.gamma.signal.Severity;
import com.gamma.signal.Signal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A Run's Signal emitter: the chain depth on every emitted Signal is the SYSTEM value (a payload
 * {@code chainDepth} cannot reset a loop count, §8.4), and the {@code exchange.*} namespace (written only by the
 * Exchange when it delivers another Space's Signal) cannot be emitted by a Job.
 */
class RunSignalEmitterTest {

    private static RunContext ctx(Path dir, int depth) {
        return new RunContext("r-1", "default", "loop_job", "signal:x", "corr-1", "sig-0", depth, Map.of(),
                new RunLogStore(dir.toString()), 100, new RunArtifactStore(dir.toString()));
    }

    @Test
    void aPayloadChainDepthCannotOverrideTheRunsDepth(@TempDir Path dir) {
        List<Event> seen = new CopyOnWriteArrayList<>();
        Consumer<Event> probe = e -> {
            if (EventType.SIGNAL.equals(e.type()) && "loop.ping".equals(e.attributes().get(Signal.ATTR_TYPE))) seen.add(e);
        };
        EventLog.global().addSubscriber(probe);
        try {
            ctx(dir, 5).signals().emit("loop.ping", Severity.INFO, Map.of("chainDepth", 0, "k", "v"));
        } finally {
            EventLog.global().removeSubscriber(probe);
        }
        assertEquals(1, seen.size());
        Signal s = Signal.fromEvent(seen.getFirst());
        assertEquals(5, ((Number) s.payload().get("chainDepth")).intValue(), "the system depth wins");
        assertEquals("v", s.payload().get("k"));
    }

    @Test
    void aJobCannotEmitTheExchangeNamespace(@TempDir Path dir) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ctx(dir, 0).signals().emit("exchange.opco.fraud.alert", Severity.WARN, Map.of()));
        assertTrue(e.getMessage().contains("exchange.*"), e.getMessage());
    }
}
