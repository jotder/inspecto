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

    // ── the bound holds for ANY input (X5 L1 review) ─────────────────────────

    /** Adversarial text: control characters (6-byte escapes), 4-byte UTF-8, lone surrogates, quotes, backslashes. */
    private static String nasty(java.util.Random rnd, int maxLen) {
        int len = rnd.nextInt(maxLen + 1);
        StringBuilder sb = new StringBuilder(len);
        while (sb.length() < len) {
            switch (rnd.nextInt(7)) {
                case 0 -> sb.append((char) rnd.nextInt(0x20));              // control: a 6-byte JSON escape
                case 1 -> sb.appendCodePoint(0x1F600 + rnd.nextInt(64));   // 4-byte UTF-8
                case 2 -> sb.append((char) (0xD800 + rnd.nextInt(0x800)));  // lone surrogate: escaped
                case 3 -> sb.append(rnd.nextBoolean() ? '"' : '\\');
                case 4 -> sb.append('€');                             // 3-byte UTF-8
                default -> sb.append((char) ('a' + rnd.nextInt(26)));
            }
        }
        return sb.toString();
    }

    private static String maybeNull(java.util.Random rnd, String s) {
        return rnd.nextInt(10) == 0 ? null : s;
    }

    @Test
    void randomAdversarialTextInEveryFieldNeverBreaksTheBound() {
        java.util.Random rnd = new java.util.Random(20261117L);
        StepReceipt.Outcome[] outcomes = StepReceipt.Outcome.values();
        for (int i = 0; i < 1000; i++) {
            int big = rnd.nextBoolean() ? 2000 : 200;
            List<StepReceipt.Outlet> outlets = new ArrayList<>();
            for (int o = rnd.nextInt(40); o > 0; o--) outlets.add(new StepReceipt.Outlet(nasty(rnd, big), rnd.nextLong()));
            Map<String, Long> counts = new LinkedHashMap<>();
            for (int c = rnd.nextInt(20); c > 0; c--) counts.put(nasty(rnd, big), rnd.nextLong());
            StepReceipt r = new StepReceipt(rnd.nextInt(), maybeNull(rnd, nasty(rnd, big)), maybeNull(rnd, nasty(rnd, big)),
                    maybeNull(rnd, nasty(rnd, big)), maybeNull(rnd, nasty(rnd, big)), rnd.nextInt(),
                    maybeNull(rnd, nasty(rnd, big)), maybeNull(rnd, nasty(rnd, big)),
                    outcomes[rnd.nextInt(outcomes.length)], rnd.nextLong(), outlets,
                    rnd.nextBoolean() ? null : new StepReceipt.Pointer(nasty(rnd, big), maybeNull(rnd, nasty(rnd, big)),
                            maybeNull(rnd, nasty(rnd, big)), rnd.nextLong()),
                    rnd.nextBoolean() ? null : new StepReceipt.Schema(rnd.nextInt(), maybeNull(rnd, nasty(rnd, big))),
                    rnd.nextBoolean() ? null : new StepReceipt.Diagnostics(maybeNull(rnd, nasty(rnd, big)),
                            maybeNull(rnd, nasty(rnd, big)), counts),
                    maybeNull(rnd, nasty(rnd, big)), rnd.nextLong(), rnd.nextBoolean(), false);
            assertTrue(bytes(r) <= 1024, "case " + i + ": " + bytes(r) + " bytes");
            StepReceipt back = StepReceipt.fromJson(r.toJson());
            assertTrue(bytes(back) <= 1024, "case " + i + " read back: " + bytes(back) + " bytes");
        }
    }

    @Test
    void longFieldsAtTheirCapsInEveryFieldStillFitByTheLastFallback() {
        String ctl = "\u0001".repeat(4000);
        StepReceipt r = new StepReceipt(StepReceipt.VERSION, ctl, ctl, ctl, ctl, 1, ctl, ctl, StepReceipt.Outcome.FAILED,
                Long.MIN_VALUE, List.of(new StepReceipt.Outlet(ctl, Long.MIN_VALUE)),
                new StepReceipt.Pointer(ctl, ctl, ctl, Long.MIN_VALUE), new StepReceipt.Schema(Integer.MIN_VALUE, ctl),
                new StepReceipt.Diagnostics(ctl, ctl, Map.of(ctl, 1L)), ctl, Long.MIN_VALUE, true, false);
        assertTrue(bytes(r) <= 1024, bytes(r) + " bytes");
        assertTrue(r.truncated());
    }

    /** The reviewer's repro: a contributed Step's StepFailure code of 200 control characters. */
    @Test
    void anUnknownCodeIsMappedOntoTheClosedSetAndKeptInTheMessage() {
        StepReceipt r = receipt("flt", List.of(), null,
                new StepReceipt.Diagnostics("\u0002".repeat(200), "boom", Map.of()));
        assertTrue(bytes(r) <= 1024, bytes(r) + " bytes");
        assertEquals(StepReceipt.STEP_FAILED, r.diag().code(), "a code outside the closed set reads STEP_FAILED");
        assertTrue(r.truncated());

        StepReceipt named = receipt("flt", List.of(), null, new StepReceipt.Diagnostics("ACME_SCORE_DOWN", "boom", Map.of()));
        assertEquals(StepReceipt.STEP_FAILED, named.diag().code());
        assertEquals("[ACME_SCORE_DOWN] boom", named.diag().msg(), "the raw code survives in the message");

        StepReceipt known = receipt("flt", List.of(), null, new StepReceipt.Diagnostics(StepReceipt.SQL_ERROR, "x", Map.of()));
        assertEquals(StepReceipt.SQL_ERROR, known.diag().code());
        assertFalse(known.truncated(), "a closed-set code is untouched");
    }

    @Test
    void aLongFingerprintIsCutAndSaysSo() {
        StepReceipt r = new StepReceipt(StepReceipt.VERSION, "c", "p", StepReceipt.LANE_JOB, "", 1, "s", "k",
                StepReceipt.Outcome.OK, 1, List.of(), null, new StepReceipt.Schema(3, "f".repeat(100)), null, null, 0,
                false, false);
        assertTrue(r.schema().fp().length() <= 16, r.schema().fp());
        assertTrue(r.truncated(), "a cut fingerprint sets truncated");
    }

    /** A cause chain A → B → A: the diagnosis terminates (it runs inside the walk's failure handler). */
    @Test
    @org.junit.jupiter.api.Timeout(value = 5, unit = java.util.concurrent.TimeUnit.SECONDS,
            threadMode = org.junit.jupiter.api.Timeout.ThreadMode.SEPARATE_THREAD)
    void aCyclicCauseChainIsDiagnosedWithoutLooping() {
        RuntimeException a = new IllegalStateException("a");
        RuntimeException b = new RuntimeException("b");
        a.initCause(b);
        b.initCause(a);
        assertEquals(StepReceipt.STEP_FAILED, StepReceipt.Diagnostics.of(a).code());
        RuntimeException c = new RuntimeException("c", new java.io.UncheckedIOException(new java.io.IOException("io")));
        assertEquals(StepReceipt.IO_ERROR, StepReceipt.Diagnostics.of(c).code());
    }
}
