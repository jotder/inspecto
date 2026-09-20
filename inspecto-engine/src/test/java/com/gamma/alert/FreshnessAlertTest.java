package com.gamma.alert;

import com.gamma.catalog.ConfigSource;
import com.gamma.catalog.SemanticModel;
import com.gamma.enrich.EnrichmentConfig;
import com.gamma.etl.PipelineConfig;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.StatusStore;
import com.gamma.event.Event;
import com.gamma.event.EventLevel;
import com.gamma.event.EventLog;
import com.gamma.event.EventType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DUCKLE-C1 Dataset freshness: the three states, the fresh→stale edge, and the all-clear.
 *
 * <p>Time is passed explicitly to the package-private {@code evaluate(filter, nowMs)} rather than
 * slept for — the whole point of this rule kind is that it is driven by the CLOCK, so a test that
 * could only observe it by waiting would be testing the wrong thing (and would be slow and flaky
 * besides).
 */
class FreshnessAlertTest {

    private static final long HOUR = Duration.ofHours(1).toMillis();

    private static ConfigSource configs(PipelineConfig cfg) {
        return new ConfigSource() {
            @Override public List<PipelineConfig> pipelines() { return List.of(cfg); }
            @Override public List<EnrichmentConfig> enrichments() { return List.of(); }
            @Override public List<SemanticModel> semantics() { return List.of(); }
        };
    }

    private static StatusStore emptyStore() {
        return new StatusStore() {
            @Override public Set<String> committedBatches(PipelineConfig cfg) { return Set.of(); }
            @Override public List<Map<String, String>> batches(PipelineConfig cfg) { return List.of(); }
            @Override public List<Map<String, String>> files(PipelineConfig cfg) { return List.of(); }
            @Override public List<Map<String, String>> lineage(PipelineConfig cfg, String batchId) { return List.of(); }
            @Override public List<Map<String, String>> quarantine(PipelineConfig cfg) { return List.of(); }
        };
    }

    private static AlertRule freshnessRule(String dataset, String maximumAge) {
        return new AlertRule("sales-stale", null, null, 0, null, "WARNING", null,
                dataset, null, null, maximumAge);
    }

    private static AlertService service(Path dir, AlertRule rule) throws Exception {
        PipelineConfig cfg = PipelineConfig.load(PipelineConfigBatchTest.writePipeline(dir, "").toString());
        return new AlertService(List.of(rule), configs(cfg), emptyStore());
    }

    // ── the rule shape ───────────────────────────────────────────────────────────────

    @Test
    void aFreshnessRuleNeedsNoThresholdComparatorOrWindow() {
        AlertRule r = freshnessRule("sales_ds", "6h");
        assertTrue(r.isFreshnessRule());
        assertFalse(r.isMeasureRule(), "a freshness rule must NOT also run through the measure probe — "
                + "both are authored with dataset:, and a shared predicate would hand the measure "
                + "evaluator a null measure");
        assertEquals("gt", r.comparator(), "fixed by the rule kind, never authored");
        assertEquals(Duration.ofHours(6), r.maximumAgeDuration());
        assertEquals("6h", r.toMap().get("maximumAge"), "round-trips to GET /alerts/rules");
    }

    @Test
    void theFreshnessShapeRefusesTheLedgerMetricVocabulary() {
        // maximumAge without a dataset has nothing to be the age OF.
        assertThrows(IllegalArgumentException.class, () -> new AlertRule(
                "r", null, "gt", 0, null, "WARNING", null, null, null, null, "6h"));
        // A Nb window counts batches; a freshness limit is elapsed time. Accepting "5b" would silently
        // parse as 5 of nothing.
        assertThrows(IllegalArgumentException.class, () -> new AlertRule(
                "r", null, "gt", 0, null, "WARNING", null, "sales_ds", null, null, "5b"));
        assertThrows(IllegalArgumentException.class, () -> new AlertRule(
                "r", "error_rate", "gt", 0.5, null, "WARNING", null, "sales_ds", null, null, "6h"));
        assertThrows(IllegalArgumentException.class, () -> new AlertRule(
                "r", null, "gt", 0, null, "WARNING", null, "sales_ds", "count", null, "6h"));
        assertThrows(IllegalArgumentException.class, () -> new AlertRule(
                "r", null, "gt", 0, null, "WARNING", null, "sales_ds", null, null, "0h"));
    }

    @Test
    void fromMapParsesAFreshnessRuleWithNoThresholdPresent() {
        // ⛔ The pre-freshness fromMap demanded a threshold unconditionally; a freshness rule has none,
        // so authoring one used to fail at parse before any of this could run.
        AlertRule r = AlertRule.fromMap(Map.of(
                "name", "sales-stale", "dataset", "sales_ds", "maximumAge", "1D", "severity", "warning"));
        assertTrue(r.isFreshnessRule());
        assertEquals("1d", r.maximumAge(), "normalized like every other duration on this record");
    }

    // ── the three states ─────────────────────────────────────────────────────────────

    @Test
    void anUnknownDatasetIsNeverReportedFresh(@TempDir Path dir) throws Exception {
        AlertService svc = service(dir, freshnessRule("sales_ds", "1h"));
        // No probe wired at all: unknown.
        assertTrue(svc.evaluate(null, 0L).isEmpty());
        // Probe wired, but the Dataset has never published: still unknown — NOT fresh, and not stale
        // either (there is no stale_since to name). Silence is the only honest answer.
        svc.freshnessProbe(dataset -> OptionalLong.empty());
        assertTrue(svc.evaluate(null, 10 * HOUR).isEmpty(),
                "a Dataset with no publication history must not fire and must not read fresh");
    }

    @Test
    void aStaleDatasetFiresAndThenCoolsDown(@TempDir Path dir) throws Exception {
        AlertService svc = service(dir, freshnessRule("sales_ds", "1h"));
        AtomicLong lastWrite = new AtomicLong(0L);
        svc.freshnessProbe(dataset -> {
            assertEquals("sales_ds", dataset);
            return OptionalLong.of(lastWrite.get());
        });

        // Within the limit → fresh → silent, and nothing to clear (it was never stale).
        assertTrue(svc.evaluate(null, 30 * HOUR / 60).isEmpty());

        List<Alert> fired = svc.evaluate(null, 3 * HOUR);
        assertEquals(1, fired.size());
        Alert a = fired.get(0);
        assertEquals("sales_ds", a.pipeline(), "freshness alerts scope to their Dataset");
        assertEquals("freshness", a.metric());
        assertEquals(3 * 3600.0, a.value(), 1e-9, "the reported value is the AGE in seconds");
        assertEquals("1h", a.window(), "labelled by the limit it breached");
        assertTrue(a.message().contains("has not published"), a.message());

        // Still stale one second later: the cooldown suppresses the duplicate.
        assertTrue(svc.evaluate(null, 3 * HOUR + 1000).isEmpty(), "cooldown suppresses an immediate re-fire");
    }

    // ── the all-clear ────────────────────────────────────────────────────────────────

    @Test
    void recoveryEmitsAnAllClearThatNoCooldownCanHold(@TempDir Path dir) throws Exception {
        List<Event> seen = new CopyOnWriteArrayList<>();
        EventLog.current().addSubscriber(seen::add);

        AlertService svc = service(dir, freshnessRule("sales_ds", "1h"));
        AtomicLong lastWrite = new AtomicLong(0L);
        svc.freshnessProbe(dataset -> OptionalLong.of(lastWrite.get()));

        assertEquals(1, svc.evaluate(null, 3 * HOUR).size(), "goes stale");

        // The Dataset publishes again, WELL inside the cooldown the breach just started (a freshness
        // rule declares no window, so it takes the 10-minute default; this recovery lands 2s later).
        lastWrite.set(3 * HOUR + 1000);
        assertTrue(svc.evaluate(null, 3 * HOUR + 2000).isEmpty(), "an all-clear is not a fired Alert");

        Event cleared = seen.stream()
                .filter(e -> EventType.ALERT_CLEARED.equals(e.type()))
                .reduce((first, second) -> second).orElse(null);
        assertNotNull(cleared, "⛔ the recovery must be announced even though the breach's cooldown is "
                + "still running — suppressing it would deliver the alarm and drop the reassurance");
        assertEquals(EventLevel.INFO, cleared.level(), "a recovery is never itself critical");
        assertEquals("sales-stale", cleared.attributes().get("rule"));
        assertEquals("sales_ds", cleared.attributes().get("dataset"));
        assertEquals("1h", cleared.attributes().get("maximumAge"));

        // The correlated Signal rides alongside, at INFO.
        assertTrue(seen.stream().anyMatch(e -> EventType.SIGNAL.equals(e.type())
                        && "alert-rule.cleared".equals(e.attributes().get(com.gamma.signal.Signal.ATTR_TYPE))),
                "the all-clear is also a Signal, so triage can pair it with alert-rule.fired");
    }

    @Test
    void afterAnAllClearTheNextBreachFiresImmediately(@TempDir Path dir) throws Exception {
        // A 1-MINUTE limit, so a second outage can complete well inside the cooldown a freshness rule
        // takes (10 minutes — it declares no window). With a 1h limit the second breach could not
        // arrive until an hour later, by which point the cooldown has lapsed anyway and the test would
        // pass whether or not the all-clear reset anything.
        AlertService svc = service(dir, freshnessRule("sales_ds", "1m"));
        AtomicLong lastWrite = new AtomicLong(0L);
        svc.freshnessProbe(dataset -> OptionalLong.of(lastWrite.get()));

        assertEquals(1, svc.evaluate(null, 120_000).size(), "2 minutes with no publication ⇒ stale");
        lastWrite.set(120_000);                                     // recovers
        assertTrue(svc.evaluate(null, 121_000).isEmpty());          // all-clear

        // 🔴 Goes stale AGAIN 3 minutes after the first fire — still inside its 10-minute cooldown.
        // Without clearing the firing key, this second outage would be swallowed by the cooldown of an
        // outage that is over.
        assertEquals(1, svc.evaluate(null, 300_000).size(),
                "the all-clear resets the cooldown; a new breach must not be suppressed by an old one");
    }

    @Test
    void anAllClearFiresOnTheEdgeOnly(@TempDir Path dir) throws Exception {
        List<Event> seen = new CopyOnWriteArrayList<>();
        EventLog.current().addSubscriber(seen::add);
        AlertService svc = service(dir, freshnessRule("edge_ds", "1h"));
        svc.freshnessProbe(dataset -> OptionalLong.of(10 * HOUR));

        // Fresh from the very first sweep, and fresh on every sweep after: never stale, so there is
        // nothing to clear. A clear on every fresh sweep would be a once-a-minute all-clear for a
        // Dataset nobody ever worried about.
        svc.evaluate(null, 10 * HOUR);
        svc.evaluate(null, 10 * HOUR + 1000);
        assertTrue(seen.stream().noneMatch(e -> EventType.ALERT_CLEARED.equals(e.type())
                        && "edge_ds".equals(e.attributes().get("dataset"))),
                "a Dataset that was never stale must never be 'cleared'");
    }

    // ── the narrow (freshness-only) sweep — DUCKLE-C1's cadence residual ──────────────

    /** An {@link #emptyStore()} that counts {@code batches()} reads — the ledger pass's whole cost. */
    private static final class CountingStore implements StatusStore {
        final java.util.concurrent.atomic.AtomicInteger batchReads = new java.util.concurrent.atomic.AtomicInteger();
        @Override public Set<String> committedBatches(PipelineConfig cfg) { return Set.of(); }
        @Override public List<Map<String, String>> batches(PipelineConfig cfg) {
            batchReads.incrementAndGet();
            return List.of();
        }
        @Override public List<Map<String, String>> files(PipelineConfig cfg) { return List.of(); }
        @Override public List<Map<String, String>> lineage(PipelineConfig cfg, String batchId) { return List.of(); }
        @Override public List<Map<String, String>> quarantine(PipelineConfig cfg) { return List.of(); }
    }

    @Test
    void theFreshnessOnlySweepFiresWithoutReadingAnyPipelineLedger(@TempDir Path dir) throws Exception {
        // A ledger rule ALONGSIDE the freshness rule: the full sweep must read the ledger for it, and
        // the narrow sweep must not — otherwise "narrow" is a label, not a saving.
        AlertRule ledgerRule = new AlertRule(
                "batches-failing", "failed_batches", "gt", 1, "1h", "WARNING", null, null, null, null, null);
        PipelineConfig cfg = PipelineConfig.load(PipelineConfigBatchTest.writePipeline(dir, "").toString());
        CountingStore store = new CountingStore();
        AlertService svc = new AlertService(
                List.of(ledgerRule, freshnessRule("sales_ds", "1m")), configs(cfg), store);
        // evaluateFreshnessRules() reads the wall clock (it is the production entry point, not the
        // package-private test seam), so the probe answers relative to now: 3 hours stale.
        svc.freshnessProbe(dataset -> OptionalLong.of(System.currentTimeMillis() - 3 * HOUR));

        // ⚠ The narrow sweep goes FIRST. Running the full sweep as a "baseline" first would fire this
        // same freshness rule and start its cooldown, and the narrow call would then return empty for a
        // reason that has nothing to do with scope — a green-looking test of the wrong thing.
        List<Alert> fired = svc.evaluateFreshnessRules();
        assertEquals(0, store.batchReads.get(),
                "⛔ the freshness-only sweep must not read ANY pipeline's ledger — re-reading every "
                        + "ledger sixty times an hour for a clock comparison is the cost this entry "
                        + "point exists to remove");
        assertEquals(1, fired.size(), "the stale Dataset still fires — narrower, not gentler");
        assertEquals("freshness", fired.get(0).metric());
        assertEquals("sales_ds", fired.get(0).pipeline());

        // And the ledger IS reachable through this store — so the zero above is the narrow sweep
        // declining to read it, not a store that could never have been read.
        svc.evaluateRules();
        assertTrue(store.batchReads.get() > 0,
                "the full sweep still reads the ledger; if it stopped, the assertion above proves nothing");
    }

    @Test
    void theAlertsGrantRoutesTheNarrowSweepToTheEngine(@TempDir Path dir) throws Exception {
        // The Job reaches the engine only through AlertAccess, so a grant that quietly fell back to the
        // full sweep would make `scope: freshness` a no-op nobody could see.
        PipelineConfig cfg = PipelineConfig.load(PipelineConfigBatchTest.writePipeline(dir, "").toString());
        CountingStore store = new CountingStore();
        AlertRule ledgerRule = new AlertRule(
                "batches-failing", "failed_batches", "gt", 1, "1h", "WARNING", null, null, null, null, null);
        AlertService svc = new AlertService(
                List.of(ledgerRule, freshnessRule("sales_ds", "1m")), configs(cfg), store);
        svc.freshnessProbe(dataset -> OptionalLong.of(System.currentTimeMillis() - 3 * HOUR));

        AlertAccess grant = AlertAccess.over(() -> svc);
        assertEquals(1, grant.evaluateFreshnessRules().size());
        assertEquals(0, store.batchReads.get(), "the grant must not widen the sweep it was asked for");

        // And the default on the interface delegates to the FULL sweep, so a stand-in without a narrow
        // form (a dry-run double, a test double) does more rather than less — it can never answer the
        // narrow call with a silence it did not earn.
        java.util.concurrent.atomic.AtomicInteger fullSweeps = new java.util.concurrent.atomic.AtomicInteger();
        AlertAccess standIn = () -> {
            fullSweeps.incrementAndGet();
            return List.of();
        };
        assertTrue(standIn.evaluateFreshnessRules().isEmpty());
        assertEquals(1, fullSweeps.get(), "the default narrow sweep must route to evaluateRules()");
    }

    @Test
    void anAbsentEngineStillFailsLoudlyOnTheNarrowSweep() {
        AlertAccess grant = AlertAccess.over(() -> null);
        assertThrows(IllegalStateException.class, grant::evaluateFreshnessRules,
                "evaluation is the whole work; a missing engine must not read as 'nothing stale'");
    }
}
