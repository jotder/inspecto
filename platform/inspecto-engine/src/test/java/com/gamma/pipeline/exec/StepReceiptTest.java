package com.gamma.pipeline.exec;

import com.gamma.pipeline.PipelineRel;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * X5 — the {@link StepReceipt} shape and its 1024-byte bound (plan §3.2), which the record enforces when it is
 * built, not the caller; plus {@link PipelineRel#canonical}, which makes every receipt name an outlet one way.
 */
class StepReceiptTest {

    private static int bytes(StepReceipt r) {
        return r.toJson().getBytes(StandardCharsets.UTF_8).length;
    }

    private static StepReceipt receipt(String step, List<StepReceipt.Outlet> outlets, StepReceipt.Pointer ref,
                                       StepReceipt.Diagnostics diag) {
        return new StepReceipt(StepReceipt.VERSION, "20261117T101500_cdr_voice_0007", "cdr_voice",
                StepReceipt.LANE_INGEST_GRAPH, "", 4, step, "transform.filter", StepReceipt.Outcome.OK, 120_000,
                outlets, ref, new StepReceipt.Schema(31, "9c1e4f0a2b7d"), diag, "2026-11-17T10:15:02.114Z", 41,
                false, false);
    }

    @Test
    void theWorstCaseStaysWithinTheBoundAndSaysItWasCut() {
        List<StepReceipt.Outlet> outlets = new ArrayList<>();
        outlets.add(new StepReceipt.Outlet(PipelineRel.DATA, 100));
        outlets.add(new StepReceipt.Outlet("dropped", 7));
        for (int i = 0; i < 500; i++) outlets.add(new StepReceipt.Outlet(PipelineRel.route("branch_" + i), i));
        Map<String, Long> counts = new LinkedHashMap<>();
        for (int i = 0; i < 50; i++) counts.put("count_" + i, (long) i);
        StepReceipt r = receipt("flt_valid", outlets,
                new StepReceipt.Pointer(StepReceipt.Pointer.PARK, "t", "p/".repeat(400), 0),
                new StepReceipt.Diagnostics(StepReceipt.STEP_FAILED, "x".repeat(10_240), counts));

        assertTrue(bytes(r) <= 1024, "a 10 KB message and 500 routes fit: " + bytes(r));
        assertTrue(r.truncated(), "a cut receipt says so");
        assertTrue(r.outlets().size() <= StepReceipt.MAX_OUTLETS);
        assertEquals(StepReceipt.FOLDED_OUTLET, r.outlets().get(r.outlets().size() - 1).outlet(),
                "the surplus is folded into one route:* entry");
        long total = r.outlets().stream().mapToLong(StepReceipt.Outlet::rows).sum();
        assertEquals(100 + 7 + (499L * 500 / 2), total, "folding keeps every row counted");
        assertEquals(PipelineRel.DATA, r.outlets().get(0).outlet(), "the data outlet is kept, not folded");
        assertEquals("reject:dropped", r.outlets().get(1).outlet(), "and so is the reject outlet, canonical");
        assertTrue(r.diag().msg() == null || r.diag().msg().length() <= StepReceipt.MAX_MSG);
        assertEquals(StepReceipt.STEP_FAILED, r.diag().code(), "the code survives every cut");
    }

    @Test
    void wideCharactersInEveryFieldStillFit() {
        String wide = "€".repeat(400);   // 3 UTF-8 bytes each
        StepReceipt r = new StepReceipt(StepReceipt.VERSION, wide, wide, StepReceipt.LANE_JOB, wide, 1, wide, wide,
                StepReceipt.Outcome.FAILED, 1, List.of(new StepReceipt.Outlet(wide, 1)),
                new StepReceipt.Pointer(StepReceipt.Pointer.OUTPUTS, wide, wide, 3),
                null, new StepReceipt.Diagnostics(StepReceipt.SQL_ERROR, "\u0001".repeat(300), Map.of(wide, 1L)),
                null, 0, false, false);
        assertTrue(bytes(r) <= 1024, "identity fields are cut last but cut: " + bytes(r));
        assertTrue(r.truncated());
    }

    @Test
    void aSmallReceiptIsUntouchedAndRoundTrips() {
        StepReceipt r = receipt("flt_valid",
                List.of(new StepReceipt.Outlet(PipelineRel.DATA, 119_412), new StepReceipt.Outlet("dropped", 588)),
                null, StepReceipt.Diagnostics.NONE);

        assertFalse(r.truncated(), "nothing was cut");
        assertTrue(bytes(r) < 512, "the plan's example is about 420 bytes: " + bytes(r));
        StepReceipt back = StepReceipt.fromJson(r.toJson());
        assertEquals(r, back, "the stored form reads back as the same receipt");
        assertEquals("reject:dropped", back.outlets().get(1).outlet());
        assertEquals("", back.runId(), "an ingest receipt's run id is '' — never null");
    }

    @Test
    void aStepFailureCodeIsFoundAnywhereOnTheCauseChain() {
        Exception wrapped = new RuntimeException("walk failed",
                new StepFailure(StepFailure.STEP_TIMEOUT, "too slow", null));
        assertEquals(StepReceipt.STEP_TIMEOUT, StepReceipt.Diagnostics.of(wrapped).code());
        assertEquals(StepReceipt.SQL_ERROR,
                StepReceipt.Diagnostics.of(new IllegalStateException(new java.sql.SQLException("bad"))).code());
        assertEquals(StepReceipt.STEP_FAILED, StepReceipt.Diagnostics.of(new IllegalArgumentException("x")).code());
    }

    @Test
    void canonicalMapsEveryLegacyRejectSpellingAndNothingElse() {
        assertEquals("reject:dropped", PipelineRel.canonical("dropped"));
        assertEquals("reject:invalid", PipelineRel.canonical("invalid"));
        assertEquals("reject:duplicate", PipelineRel.canonical("duplicate"));
        assertEquals("reject:unmatched", PipelineRel.canonical("unmatched"));
        assertEquals("reject:dropped", PipelineRel.canonical("reject:dropped"), "already canonical: unchanged");
        assertEquals(PipelineRel.DATA, PipelineRel.canonical(PipelineRel.DATA));
        assertEquals("route:emea", PipelineRel.canonical("route:emea"));
        assertNull(PipelineRel.canonical(null));
    }
}
