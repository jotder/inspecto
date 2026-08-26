package com.gamma.job;

import com.gamma.event.EventLog;
import com.gamma.event.InMemoryEventStore;
import com.gamma.signal.Signal;
import com.gamma.signal.Signals;
import com.gamma.signal.Severity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The causation THREAD, end to end at the producing side.
 *
 * <p>{@link Signals#assembleTree} was correct and well-tested from the day it shipped — but every
 * producer passed {@code null} for {@code causationId}, so in production the forest was permanently
 * FLAT: N roots, no nesting, and {@code GET /signals/tree} could never answer "what did this signal
 * cause?". A tree assembler with no threaded input is the shape this repo calls dead config.
 *
 * <p>These pin the two halves of the fix at the endpoint that matters — the signal a Run actually
 * emits — plus the deliberate ROOT case, so a future change cannot quietly re-flatten the tree.
 */
class RunContextCausationTest {

    private static RunContext ctx(String correlationId, String causationId) {
        return new RunContext("r1", EventLog.DEFAULT_SPACE_ID, "loader", "signal:pipeline.commit",
                correlationId, causationId, 1, Map.of(), null, 100, null);
    }

    @Test
    void aSignalTriggeredRunStampsItsTriggerAsTheCauseOfEverythingItEmits() {
        InMemoryEventStore store = new InMemoryEventStore();
        EventLog.current().installStore(store);

        ctx("corr-1", "sig-parent").signals()
                .emit("job.run.started", Severity.INFO, Map.of("job", "loader"));

        List<Signal> emitted = store.query(com.gamma.event.EventQuery.recent(100)).stream()
                .map(Signal::fromEvent).filter(s -> "job.run.started".equals(s.type())).toList();
        assertEquals(1, emitted.size(), "the run emitted its signal");
        Signal s = emitted.get(0);
        assertEquals("sig-parent", s.causationId(),
                "🔴 the triggering signal is the CAUSE — without this /signals/tree is flat");
        assertEquals("corr-1", s.correlationId(),
                "…and correlation is a DIFFERENT axis, still carried independently");

        // The payoff: the emitted signal nests under its cause instead of standing as a second root.
        Signal parent = new Signal("sig-parent", "pipeline.commit", s.at().minusMillis(10),
                Severity.INFO, s.source(), null, "corr-1", null, null, null, "m", Map.of(), 1);
        List<Signals.SignalNode> forest = Signals.assembleTree(List.of(parent, s));
        assertEquals(1, forest.size(), "one root, not two — the tree is no longer flat");
        assertEquals("sig-parent", forest.get(0).signal().signalId());
        assertEquals(List.of(s.signalId()),
                forest.get(0).children().stream().map(n -> n.signal().signalId()).toList(),
                "the run's fact hangs off the signal that caused it");
    }

    @Test
    void aCronOrManualRunIsARootBecauseNoSignalCausedIt() {
        InMemoryEventStore store = new InMemoryEventStore();
        EventLog.current().installStore(store);

        ctx("r1", null).signals().emit("job.run.started", Severity.INFO, Map.of("job", "loader"));

        Signal s = store.query(com.gamma.event.EventQuery.recent(100)).stream()
                .map(Signal::fromEvent).filter(x -> "job.run.started".equals(x.type()))
                .findFirst().orElseThrow();
        assertNull(s.causationId(),
                "a cron/manual run is caused by no SIGNAL — a null here is the honest answer, "
                        + "and assembleTree surfaces it as a root");
    }
}
