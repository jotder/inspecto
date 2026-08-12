package com.gamma.etl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** {@link StepProgress}: the live per-pipeline "which step is the Consignment at" snapshot (G6/S7). */
class StepProgressTest {

    @Test
    void tracksReplacesAndClearsPerPipeline() {
        String pipe = "sp_test_" + System.nanoTime();
        assertNull(StepProgress.current(pipe), "nothing tracked yet");

        StepProgress.track(pipe, "C1", "parse", 1, 3);
        StepProgress.Snapshot s = StepProgress.current(pipe);
        assertEquals("C1", s.consignmentId());
        assertEquals("parse", s.step());
        assertEquals(1, s.index());
        assertEquals(3, s.total());
        assertNotNull(s.startedAt());

        StepProgress.track(pipe, "C1", "transform", 2, 3);   // a later step replaces the snapshot
        assertEquals("transform", StepProgress.current(pipe).step());
        assertEquals(2, StepProgress.current(pipe).index());

        StepProgress.clear(pipe);
        assertNull(StepProgress.current(pipe), "cleared when the batch/run finishes");
    }

    @Test
    void pipelinesAreIndependent() {
        String a = "sp_a_" + System.nanoTime();
        String b = "sp_b_" + System.nanoTime();
        StepProgress.track(a, "CA", "parse", 1, 3);
        StepProgress.track(b, "CB", "sink", 3, 3);
        assertEquals("parse", StepProgress.current(a).step());
        assertEquals("sink", StepProgress.current(b).step());
        StepProgress.clear(a);
        assertNull(StepProgress.current(a));
        assertNotNull(StepProgress.current(b), "clearing one pipeline leaves the other");
        StepProgress.clear(b);
    }

    @Test
    void blankAndNullPipelinesAreNoOps() {
        StepProgress.track(null, "C", "parse", 1, 1);
        StepProgress.track("  ", "C", "parse", 1, 1);
        StepProgress.clear(null);
        assertNull(StepProgress.current(null));
    }
}
