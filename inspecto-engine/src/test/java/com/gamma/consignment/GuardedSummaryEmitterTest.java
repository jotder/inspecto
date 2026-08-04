package com.gamma.consignment;

import com.gamma.consignment.Measure.Composability;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * §14.4 step 4 — the §7.2 guardrails. The plan's instruction for this step is explicit: <b>verify RED first</b>,
 * because a guard trusted while green is a guard that was never tested. So the refusals lead, and each names the
 * silent-wrong-number it prevents; the acceptance cases come last, only to show the guard is not simply
 * refusing everything.
 */
class GuardedSummaryEmitterTest {

    private static SummaryRow row(Measure... measures) {
        return new SummaryRow("cdr", Map.of("record_day", "2026-07-01"), List.of(measures));
    }

    private static IllegalArgumentException refused(SummaryRow row) {
        return assertThrows(IllegalArgumentException.class, () -> new GuardedSummaryEmitter().emit(row));
    }

    // ── RED: the two refusals the plan names ─────────────────────────────────────

    /** §7.2 makes {@code count} mandatory — it is what makes an aggregate reconcilable against detail. */
    @Test
    void refusesASummaryWithoutCount() {
        IllegalArgumentException ex = refused(row(Measure.additive("bytes", 4096)));
        assertTrue(ex.getMessage().contains("'count'"), ex.getMessage());
        assertTrue(ex.getMessage().contains("mandatory"), ex.getMessage());
    }

    /** A bare AVG: declaring an average additive is arithmetically meaningless and nothing downstream flags it. */
    @Test
    void refusesABareAverageMeasure() {
        IllegalArgumentException ex = refused(row(
                Measure.additive(SummaryEmitter.COUNT, 10),
                Measure.additive("avg_duration", 12.5)));
        assertTrue(ex.getMessage().contains("avg_duration"), ex.getMessage());
        assertTrue(ex.getMessage().contains("ADDITIVE"), ex.getMessage());
    }

    // ── RED: the rest of the §7.2 surface ────────────────────────────────────────

    /** The guard's core stance: an undeclared measure is refused, never assumed additive. */
    @Test
    void refusesAMeasureThatDeclaresNoComposability() {
        IllegalArgumentException ex = refused(row(
                Measure.additive(SummaryEmitter.COUNT, 10),
                new Measure("revenue", null, 99.0)));
        assertTrue(ex.getMessage().contains("declares no composability"), ex.getMessage());
        assertTrue(ex.getMessage().contains("never inferred"), ex.getMessage());
    }

    /** Every name that signals non-additivity must be refused when declared ADDITIVE, not just "avg". */
    @Test
    void refusesEveryNonAdditiveNameDeclaredAdditive() {
        for (String name : List.of("avg", "average", "mean", "median", "ratio", "rate", "pct", "percent",
                "percentile", "p95", "stddev", "variance", "distinct_users", "unique_msisdn",
                "min_latency", "max_latency", "call_duration_avg")) {
            IllegalArgumentException ex = refused(row(
                    Measure.additive(SummaryEmitter.COUNT, 1),
                    Measure.additive(name, 1.0)));
            assertTrue(ex.getMessage().contains(name), "must refuse ADDITIVE '" + name + "': " + ex.getMessage());
        }
    }

    /** min/max merge cleanly but NOT by addition, and Composability has no member for that. */
    @Test
    void acceptsMinMaxOnlyWhenNotDeclaredAdditive() {
        assertDoesNotThrow(() -> new GuardedSummaryEmitter().emit(row(
                Measure.additive(SummaryEmitter.COUNT, 5),
                new Measure("max_latency", Composability.COMPUTED_FROM_DETAIL, 900))));
        assertDoesNotThrow(() -> new GuardedSummaryEmitter().emit(row(
                Measure.additive(SummaryEmitter.COUNT, 5),
                new Measure("p95_latency", Composability.BUCKETED, 880))));
    }

    /** A NaN would propagate through every merge that touches it, turning one bad row into a bad total. */
    @Test
    void refusesNonFiniteValues() {
        assertTrue(refused(row(Measure.additive(SummaryEmitter.COUNT, 1),
                Measure.additive("bytes", Double.NaN))).getMessage().contains("non-finite"));
        assertTrue(refused(row(Measure.additive(SummaryEmitter.COUNT, 1),
                Measure.additive("bytes", Double.POSITIVE_INFINITY))).getMessage().contains("non-finite"));
    }

    @Test
    void refusesCountDeclaredNonAdditive() {
        assertTrue(refused(row(new Measure(SummaryEmitter.COUNT, Composability.BUCKETED, 10)))
                .getMessage().contains("must be ADDITIVE"));
    }

    @Test
    void refusesStructurallyBrokenRows() {
        assertTrue(refused(new SummaryRow("", Map.of(), List.of(Measure.additive("count", 1))))
                .getMessage().contains("no target"));
        assertTrue(refused(new SummaryRow("cdr", Map.of(), List.of())).getMessage().contains("no measures"));
        assertTrue(refused(row(Measure.additive(SummaryEmitter.COUNT, 1),
                Measure.additive("bytes", 1), Measure.additive("bytes", 2)))
                .getMessage().contains("declared twice"));
        assertTrue(refused(row(Measure.additive(SummaryEmitter.COUNT, -1))).getMessage().contains("negative"));
    }

    /** Every violation at once, so a refusal costs one repair round rather than several. */
    @Test
    void reportsEveryViolationNotJustTheFirst() {
        String msg = refused(new SummaryRow("", Map.of(), List.of(
                Measure.additive("avg_x", 1), new Measure("y", null, 2)))).getMessage();
        assertTrue(msg.contains("no target"), msg);
        assertTrue(msg.contains("avg_x"), msg);
        assertTrue(msg.contains("'y'"), msg);
        assertTrue(msg.contains("'count'"), msg);
    }

    // ── GREEN: what a correct summary looks like ─────────────────────────────────

    @Test
    void acceptsAndCollectsAWellDeclaredSummary() {
        GuardedSummaryEmitter emitter = new GuardedSummaryEmitter();
        emitter.emit(row(Measure.additive(SummaryEmitter.COUNT, 10), Measure.additive("bytes", 2048)));
        emitter.emit(row(Measure.additive(SummaryEmitter.COUNT, 5)));

        assertEquals(2, emitter.emitted().size());
        assertEquals(10.0, emitter.emitted().get(0).count());
    }

    // ── §7.2's free reconciliation ───────────────────────────────────────────────

    private static ConsignmentOutput detail(String table, long rows) {
        return new ConsignmentOutput("c1", null, table, "p", null, "/w/" + table + "/f.parquet",
                rows, 10L, "2026-08-04T10:00:00Z", 0, ConsignmentOutput.State.LIVE);
    }

    @Test
    void reconcilesSummedCountAgainstTheRegistrysDetailRows() {
        GuardedSummaryEmitter emitter = new GuardedSummaryEmitter();
        emitter.emit(row(Measure.additive(SummaryEmitter.COUNT, 4)));
        emitter.emit(row(Measure.additive(SummaryEmitter.COUNT, 6)));

        assertTrue(emitter.reconcile(List.of(detail("cdr", 7), detail("cdr", 3))).isEmpty(),
                "4 + 6 summarised == 7 + 3 detail rows");

        var mismatch = emitter.reconcile(List.of(detail("cdr", 99)));
        assertTrue(mismatch.isPresent());
        assertTrue(mismatch.get().contains("summarised 10"), mismatch.get());
        assertTrue(mismatch.get().contains("99"), mismatch.get());
    }

    /** A non-LIVE file's rows are held elsewhere now, so counting them would fake a mismatch. */
    @Test
    void reconciliationIgnoresNonLiveOutputs() {
        GuardedSummaryEmitter emitter = new GuardedSummaryEmitter();
        emitter.emit(row(Measure.additive(SummaryEmitter.COUNT, 5)));

        ConsignmentOutput compacted = new ConsignmentOutput("c1", null, "cdr", "p", null, "/w/old.parquet",
                500L, 10L, "2026-08-04T10:00:00Z", 0, ConsignmentOutput.State.COMPACTED_AWAY);
        assertTrue(emitter.reconcile(List.of(detail("cdr", 5), compacted)).isEmpty());
    }
}
