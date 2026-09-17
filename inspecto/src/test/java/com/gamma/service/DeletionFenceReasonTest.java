package com.gamma.service;

import com.gamma.etl.PipelineConfig;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.event.Event;
import com.gamma.event.EventType;
import com.gamma.pipeline.PipelineGraph;
import com.gamma.pipeline.PipelineLift;
import com.gamma.pipeline.PipelineStores;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code FENCE-STORE-SILENTLY-INERT-1}: {@link CollectorService#checkDeletion} now labels the reason
 * ({@code DeletionFence.Coverage}) at the point it already logs/emits {@code STORE_DELETE_CONFLICT} —
 * check-time, no new wiring — so an operator can tell a hard {@code FENCED} conflict apart from a target
 * store the fence can never cover ({@code VIEW_ONLY} / {@code CONSUMED_ONLY} / the {@code UNMATCHED} typo
 * class). Purely additive: {@code check()}'s conflict/no-conflict outcome is unchanged either way.
 */
class DeletionFenceReasonTest {

    /** A registered single-schema CSV pipeline and the store id {@link PipelineLift} derives for it. */
    private record Registered(Path toon, String pipelineName, String store) {}

    private Registered seed(Path dir) throws Exception {
        Path toon = TestConfigs.csv(dir, PipelineConfigBatchTest.miniSchema()).write();
        PipelineConfig cfg = PipelineConfig.load(toon.toString());
        PipelineGraph graph = PipelineLift.lift(cfg);
        List<PipelineStores.Produced> produced = PipelineStores.producedStores(graph);
        assertEquals(1, produced.size(), "the fixture must lift to exactly one produced store");
        return new Registered(toon, cfg.identity().pipelineName(), produced.get(0).store());
    }

    /** Reflection seam onto the private {@code running} set — the same set a live poll cycle/manual
     *  trigger populates while a pipeline is in flight (see {@code CollectorService#runPipeline}).
     *  Simulating it directly is far cheaper than racing a real run, and exercises exactly what
     *  {@code checkDeletion} reads. */
    @SuppressWarnings("unchecked")
    private Set<String> runningSet(CollectorService svc) throws Exception {
        Field f = CollectorService.class.getDeclaredField("running");
        f.setAccessible(true);
        return (Set<String>) f.get(svc);
    }

    @Test
    void fencedConflictCarriesTheReason(@TempDir Path dir) throws Exception {
        Registered r = seed(dir);
        try (CollectorService svc = new CollectorService(List.of(r.toon()), 3600, 1)) {
            List<Event> captured = new CopyOnWriteArrayList<>();
            svc.eventLog().addSubscriber(captured::add);

            runningSet(svc).add(r.pipelineName());   // simulate an in-flight run of the store's producer

            var conflicts = svc.checkDeletion(List.of(r.store()));
            assertEquals(1, conflicts.size(), "an active producer of a resting store must conflict");

            Event conflict = captured.stream()
                    .filter(e -> EventType.STORE_DELETE_CONFLICT.equals(e.type()))
                    .findFirst().orElseThrow(() -> new AssertionError("no STORE_DELETE_CONFLICT event emitted"));
            assertEquals("FENCED", conflict.attributes().get("reason"),
                    "a conflict can only ever be raised against a FENCED store");
        }
    }

    @Test
    void unmatchedStoreIsFlaggedNotSilentlySkipped(@TempDir Path dir) throws Exception {
        Registered r = seed(dir);
        try (CollectorService svc = new CollectorService(List.of(r.toon()), 3600, 1)) {
            List<Event> captured = new CopyOnWriteArrayList<>();
            svc.eventLog().addSubscriber(captured::add);

            // No pipeline produces or consumes this name — the typo class, and the row's original complaint:
            // "no error, no warning and no event" before this change.
            var conflicts = svc.checkDeletion(List.of("no_such_store"));
            assertTrue(conflicts.isEmpty(), "an unmatched store never conflicts — behaviour is unchanged");

            Event unfenced = captured.stream()
                    .filter(e -> EventType.STORE_DELETE_UNFENCED.equals(e.type()))
                    .findFirst().orElseThrow(() -> new AssertionError("no STORE_DELETE_UNFENCED event emitted"));
            assertEquals("no_such_store", unfenced.attributes().get("store"));
            assertEquals("UNMATCHED", unfenced.attributes().get("reason"));
        }
    }
}
