package com.gamma.pipeline;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link DeletionFence} (T25, §3.8 rule 4): a delete races only an <em>active</em> producer/consumer of a
 * <em>resting</em> store; an idle store (quiet window) or a non-resting {@code sink.view} is never a conflict.
 */
class DeletionFenceTest {

    /** A pipeline that produces {@code store} via a sink of the given subtype. */
    private static PipelineGraph producer(String name, String store, String sinkType) {
        return new PipelineGraph(name, true,
                List.of(PipelineNode.of("acq", "acquisition"),
                        new PipelineNode("sink", sinkType, store, null, Map.of(PipelineStores.CONFIG_STORE, store), null)),
                List.of(PipelineEdge.data("acq", "sink")));
    }

    /** A pipeline that consumes {@code store} at rest. */
    private static PipelineGraph consumer(String name, String store) {
        return new PipelineGraph(name, true,
                List.of(new PipelineNode("src", "transform.sql", "read", null,
                        Map.of(PipelineStores.CONFIG_SOURCE_STORE, store), null)),
                List.of());
    }

    @Test
    void conflictWhenAProducerIsRunning() {
        List<PipelineGraph> flows = List.of(producer("orders_etl", "orders", "sink.persistent"));
        List<DeletionFence.Conflict> c = DeletionFence.check(List.of("orders"), flows, Set.of("orders_etl"));
        assertEquals(1, c.size());
        assertEquals("orders", c.get(0).store());
        assertEquals(List.of("orders_etl"), c.get(0).activeProducers());
        assertTrue(c.get(0).activeConsumers().isEmpty());
    }

    @Test
    void conflictWhenOnlyAConsumerIsRunning() {
        List<PipelineGraph> flows = List.of(
                producer("orders_etl", "orders", "sink.persistent"),
                consumer("orders_rollup", "orders"));
        // the producer is idle, but a consumer is reading → still a conflict
        List<DeletionFence.Conflict> c = DeletionFence.check(List.of("orders"), flows, Set.of("orders_rollup"));
        assertEquals(1, c.size());
        assertEquals(List.of("orders_rollup"), c.get(0).activeConsumers());
        assertTrue(c.get(0).activeProducers().isEmpty());
    }

    @Test
    void clearInAQuietWindow() {
        List<PipelineGraph> flows = List.of(
                producer("orders_etl", "orders", "sink.persistent"),
                consumer("orders_rollup", "orders"));
        // nothing running → the delete is in a quiet window → no conflict
        assertTrue(DeletionFence.check(List.of("orders"), flows, Set.of()).isEmpty());
    }

    @Test
    void viewStoreIsNeverADeletionHazard() {
        // a sink.view persists nothing on disk, so there is nothing to delete and no race
        List<PipelineGraph> flows = List.of(producer("kpi_flow", "active_subs", "sink.view"));
        assertTrue(DeletionFence.check(List.of("active_subs"), flows, Set.of("kpi_flow")).isEmpty());
    }

    @Test
    void unknownStoreIsClear() {
        List<PipelineGraph> flows = List.of(producer("orders_etl", "orders", "sink.persistent"));
        assertTrue(DeletionFence.check(List.of("nonexistent"), flows, Set.of("orders_etl")).isEmpty());
    }

    // ---- coverage: the distinction check() deliberately does not draw -------------------------------
    // A miscopied store id made the shipped compact_job.toon's fence inert for two months (ffeb95dc →
    // 819e597b) because check() returns "clear" both for a store that is genuinely quiet and for one that
    // names nothing at all. These pin that coverage() separates the two without changing check().

    /**
     * 🔴 The load-bearing case. A store whose pipeline is configured but has <b>never run</b> (no bytes rest
     * yet) must NOT look like a typo: the fence is derived from the authored topology, never the filesystem,
     * so a not-yet-produced store is still {@code FENCED}. A store no pipeline mentions is {@code UNMATCHED}.
     * {@code check} cannot tell them apart — both are "clear" — which is exactly the false negative.
     */
    @Test
    void aNeverRunStoreIsFencedWhileATypoIsUnmatched() {
        // 'orders' is produced by a configured pipeline that is NOT running and may never have run.
        List<PipelineGraph> flows = List.of(producer("orders_etl", "orders", "sink.persistent"));

        // check() conflates them: both delete targets come back clear.
        assertTrue(DeletionFence.check(List.of("orders"), flows, Set.of()).isEmpty());
        assertTrue(DeletionFence.check(List.of("odrers"), flows, Set.of()).isEmpty());

        // coverage() separates them.
        assertEquals(DeletionFence.Coverage.FENCED,
                DeletionFence.coverage(List.of("orders"), flows).get("orders"),
                "a configured producer arms the fence even before the pipeline has ever run");
        assertEquals(DeletionFence.Coverage.UNMATCHED,
                DeletionFence.coverage(List.of("odrers"), flows).get("odrers"),
                "a store no pipeline produces or consumes can never be flagged — the typo class");
    }

    /** A {@code sink.view} target is skipped by design, and must not be reported as a typo. */
    @Test
    void viewOnlyStoreIsCoveredNotUnmatched() {
        List<PipelineGraph> flows = List.of(producer("kpi_flow", "active_subs", "sink.view"));
        assertEquals(DeletionFence.Coverage.VIEW_ONLY,
                DeletionFence.coverage(List.of("active_subs"), flows).get("active_subs"));
    }

    /** A store read but not produced here (the producer lives elsewhere) is attested — not a typo. */
    @Test
    void consumedOnlyStoreIsCoveredNotUnmatched() {
        List<PipelineGraph> flows = List.of(consumer("orders_rollup", "orders"));
        assertEquals(DeletionFence.Coverage.CONSUMED_ONLY,
                DeletionFence.coverage(List.of("orders"), flows).get("orders"));
    }

    /** The real regression: the value the shipped job actually carried, against the pipeline it sits beside. */
    @Test
    void theShippedMiscopiedValueWouldHaveBeenReportedUnmatched() {
        List<PipelineGraph> flows = List.of(producer("sales_pipeline", "sales", "sink.persistent"));
        Map<String, DeletionFence.Coverage> c =
                DeletionFence.coverage(List.of("out/database", "sales"), flows);
        assertEquals(DeletionFence.Coverage.UNMATCHED, c.get("out/database"),
                "'store: out/database' was the dir: value copied one line down — the fence was dead");
        assertEquals(DeletionFence.Coverage.FENCED, c.get("sales"),
                "positive control: the corrected value does arm the fence");
    }

    /** coverage() must not disturb check(): same topology, conflicts still reported. */
    @Test
    void coverageDoesNotChangeCheck() {
        List<PipelineGraph> flows = List.of(
                producer("orders_etl", "orders", "sink.persistent"),
                consumer("orders_rollup", "orders"));
        DeletionFence.coverage(List.of("orders", "ghost"), flows);
        List<DeletionFence.Conflict> c = DeletionFence.check(List.of("orders"), flows, Set.of("orders_etl"));
        assertEquals(1, c.size());
        assertEquals(List.of("orders_etl"), c.get(0).activeProducers());
    }

    /** Duplicate and repeated targets collapse to one entry each, in encounter order. */
    @Test
    void coverageIsKeyedByDistinctStoreInEncounterOrder() {
        List<PipelineGraph> flows = List.of(producer("orders_etl", "orders", "sink.persistent"));
        Map<String, DeletionFence.Coverage> c =
                DeletionFence.coverage(List.of("ghost", "orders", "ghost"), flows);
        assertEquals(List.of("ghost", "orders"), List.copyOf(c.keySet()));
        assertEquals(2, c.size());
    }
}
