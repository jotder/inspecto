package com.gamma.alert;

import com.gamma.catalog.ConfigSource;
import com.gamma.catalog.SemanticModel;
import com.gamma.enrich.EnrichmentConfig;
import com.gamma.etl.PipelineConfig;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.StatusStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LA-DATA-TRUST-1 S3: a freshness Alert Rule whose {@code maximumAge} is a MULTIPLE of the feeding Collector's poll interval
 * ({@code auto} = {@value AlertRule#DEFAULT_POLL_MULTIPLE}x, or {@code Nxpoll}). It reuses the DUCKLE-C1 clock, sweep, cooldown
 * and all-clear: only the limit is resolved differently. Time is passed to {@code evaluate(filter, nowMs)}, never slept for.
 */
class PollRelativeFreshnessTest {

    private static final long MIN = 60_000L;

    private static AlertRule rule(String maximumAge, String onPipeline, String measure) {
        return new AlertRule("feed-stale", null, null, 0, null, "WARNING", onPipeline,
                "xdr", measure, null, maximumAge);
    }

    private static AlertService service(Path dir, AlertRule rule) throws Exception {
        PipelineConfig cfg = PipelineConfig.load(PipelineConfigBatchTest.writePipeline(dir, "").toString());
        ConfigSource configs = new ConfigSource() {
            @Override public List<PipelineConfig> pipelines() { return List.of(cfg); }
            @Override public List<EnrichmentConfig> enrichments() { return List.of(); }
            @Override public List<SemanticModel> semantics() { return List.of(); }
        };
        StatusStore empty = new StatusStore() {
            @Override public Set<String> committedBatches(PipelineConfig c) { return Set.of(); }
            @Override public List<Map<String, String>> batches(PipelineConfig c) { return List.of(); }
            @Override public List<Map<String, String>> files(PipelineConfig c) { return List.of(); }
            @Override public List<Map<String, String>> lineage(PipelineConfig c, String batchId) { return List.of(); }
            @Override public List<Map<String, String>> quarantine(PipelineConfig c) { return List.of(); }
        };
        return new AlertService(List.of(rule), configs, empty);
    }

    // ── the rule shape ───────────────────────────────────────────────────────────────

    @Test
    void autoTakesTheDefaultMultipleAndNxpollTakesN() {
        AlertRule auto = rule("AUTO", "daily_xdr", null);
        assertTrue(auto.isFreshnessRule());
        assertTrue(auto.followsPoll());
        assertEquals("auto", auto.maximumAge(), "normalised like every other duration");
        assertEquals("120s", auto.resolvedFor(60_000).maximumAge(), "auto = 2x the 60 s poll interval");
        assertEquals("900s", rule("3xpoll", "daily_xdr", null).resolvedFor(300_000).maximumAge());
        assertEquals("auto", auto.toMap().get("maximumAge"), "round-trips as authored, not as the resolved number");
        assertEquals("6h", rule("6h", null, null).resolvedFor(60_000).maximumAge(), "a literal limit is untouched");
    }

    @Test
    void aPollRelativeLimitNeedsThePipelineAndRefusesEventDateMode() {
        assertThrows(IllegalArgumentException.class, () -> rule("auto", null, null), "no Pipeline to take the interval of");
        assertThrows(IllegalArgumentException.class, () -> rule("auto", "  ", null));
        assertThrows(IllegalArgumentException.class, () -> rule("auto", "daily_xdr", "max(EVENT_DATE)"));
        assertThrows(IllegalArgumentException.class, () -> rule("0xpoll", "daily_xdr", null));
        assertThrows(IllegalArgumentException.class, () -> rule("xpoll", "daily_xdr", null));
        assertThrows(IllegalStateException.class, () -> rule("auto", "daily_xdr", null).maximumAgeDuration(),
                "a poll-relative limit has no duration until resolved");
    }

    // ── the verdict and the Alert are one reading ────────────────────────────────────

    @Test
    void firesOnAStoppedFeedAndClearsWhenAFileIsDelivered(@TempDir Path dir) throws Exception {
        AlertService svc = service(dir, rule("auto", "daily_xdr", null));
        AtomicLong lastWrite = new AtomicLong(0L);
        svc.freshnessProbe(d -> OptionalLong.of(lastWrite.get()));
        svc.pollIntervalProbe(p -> {
            assertEquals("daily_xdr", p);
            return OptionalLong.of(5 * MIN);                        // the Collector delivers every 5 minutes
        });

        assertTrue(svc.evaluate(null, 9 * MIN).isEmpty(), "9 min < 2 x 5 min: fresh, silent");
        assertEquals("fresh", svc.freshnessVerdicts("xdr", 9 * MIN).get(0).state());

        List<Alert> fired = svc.evaluate(null, 11 * MIN);            // the feed stopped; 11 min > 10 min
        assertEquals(1, fired.size(), "a stopped feed fires");
        assertTrue(fired.get(0).message().contains("freshness limit 600s"), fired.get(0).message());
        AlertService.FreshnessVerdict v = svc.freshnessVerdicts("xdr", 11 * MIN).get(0);
        assertEquals("stale", v.state(), "the strip reads what the Alert fired on");
        assertEquals(660L, v.ageSeconds());
        assertEquals(600L, v.limitSeconds());
        assertEquals(300L, v.pollSeconds());
        assertEquals("auto", v.maximumAge());

        lastWrite.set(11 * MIN);                                     // a File is delivered
        assertTrue(svc.evaluate(null, 11 * MIN + 1000).isEmpty());
        assertEquals("fresh", svc.freshnessVerdicts("xdr", 11 * MIN + 1000).get(0).state(), "cleared on delivery");
        assertEquals(1, svc.evaluate(null, 30 * MIN).size(), "and a second stop fires again");
    }

    @Test
    void noPollIntervalToTakeIsUnknownNeverFreshNeverStale(@TempDir Path dir) throws Exception {
        AlertService svc = service(dir, rule("2xpoll", "daily_xdr", null));
        svc.freshnessProbe(d -> OptionalLong.of(0L));
        assertTrue(svc.evaluate(null, 1000 * MIN).isEmpty(), "probe not wired: silent");
        svc.pollIntervalProbe(p -> OptionalLong.empty());            // cron / manual / unknown Pipeline
        assertTrue(svc.evaluate(null, 1000 * MIN).isEmpty(), "no interval: silent, not a guessed limit");
        AlertService.FreshnessVerdict v = svc.freshnessVerdicts("xdr", 1000 * MIN).get(0);
        assertEquals("unknown", v.state());
        assertNull(v.ageSeconds());
        assertNull(v.limitSeconds());
    }

    @Test
    void neverPublishedIsUnknownAndAnotherDatasetHasNoVerdict(@TempDir Path dir) throws Exception {
        AlertService svc = service(dir, rule("auto", "daily_xdr", null));
        svc.freshnessProbe(d -> OptionalLong.empty());
        svc.pollIntervalProbe(p -> OptionalLong.of(MIN));
        assertEquals("unknown", svc.freshnessVerdicts("xdr", 99 * MIN).get(0).state());
        assertEquals(120L, svc.freshnessVerdicts("xdr", 99 * MIN).get(0).limitSeconds());
        assertTrue(svc.freshnessVerdicts("other", 99 * MIN).isEmpty());
        assertFalse(svc.evaluate(null, 99 * MIN).iterator().hasNext());
    }
}
