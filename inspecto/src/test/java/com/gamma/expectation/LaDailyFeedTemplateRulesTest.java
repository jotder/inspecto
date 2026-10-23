package com.gamma.expectation;

import com.gamma.alert.AlertRule;
import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LA-DATA-TRUST-1 S3, S4, S7 / S8: the rules the shipped {@code spaces/_templates/la-daily-feed} template carries load through
 * the same ComponentStore a Space registry uses and mean what the operator asked - a freshness Alert Rule that follows the
 * poll interval, an index-lag Alert Rule, and a trailing-days row-count band. Reads the template tree itself, not a copy.
 */
class LaDailyFeedTemplateRulesTest {

    private static ComponentStore registry() {
        return new ComponentStore(Path.of("..", "spaces", "_templates", "la-daily-feed", "config", "registry").toAbsolutePath().normalize());
    }

    private static AlertRule rule(String name) {
        ComponentRegistry.Component c = registry().get("alert-rule", name).orElseThrow(() -> new AssertionError("template ships no Alert Rule " + name));
        return AlertRule.fromMap(c.content());
    }

    /**
     * A raw {@code #} line in a .toon file TRUNCATES the file at that line (project memory, TOON traps): the la-showcase
     * {@code mule_transfers} Pipeline had one above {@code collector:}, so its whole collector block - gap_detection
     * and file_template included - was dropped and it never wrote a day-manifest (LA-DATA-TRUST-1 live drive, 2026-10-14).
     */
    @Test
    void noShippedTemplateToonFileHasARawCommentLine() throws Exception {
        Path templates = Path.of("..", "spaces", "_templates").toAbsolutePath().normalize();
        List<String> bad = new java.util.ArrayList<>();
        try (var files = java.nio.file.Files.walk(templates)) {
            for (Path f : (Iterable<Path>) files.filter(x -> x.toString().endsWith(".toon"))::iterator)
                for (String line : java.nio.file.Files.readAllLines(f))
                    if (line.stripLeading().startsWith("#")) bad.add(templates.relativize(f) + ": " + line.strip());
        }
        assertTrue(bad.isEmpty(), "a raw # line truncates a TOON file: " + bad);
    }

    @Test
    void theFreshnessRuleFollowsThePollIntervalOfTheFeedingPipeline() {
        AlertRule r = rule("daily_xdr_freshness");
        assertEquals("daily_xdr_dataset", r.dataset());
        assertTrue(r.isFreshnessRule());
        assertTrue(r.followsPoll(), "maximumAge: auto, not a hand-picked number");
        assertEquals("daily_xdr", r.onPipeline());
        assertFalse(r.indexLag());
    }

    @Test
    void theIndexLagRuleTakesALiteralLimit() {
        AlertRule r = rule("daily_xdr_index_lag");
        assertEquals("daily_xdr_dataset", r.dataset());
        assertTrue(r.indexLag());
        assertEquals("2h", r.maximumAge(), "an index-lag rule takes a literal limit");
    }

    @Test
    void theVolumeBandMeansWhatTheOperatorAsked() {
        List<ComponentRegistry.Component> found = registry().list("expectation");
        assertEquals(1, found.size());
        Expectation e = Expectation.fromMap(found.get(0).content());
        assertEquals("baseline", e.kind());
        assertEquals("daily_xdr/database", e.target());
        var b = e.baseline();
        assertEquals(List.of("EVENT_DATE"), b.groupBy());
        assertEquals(7, b.trailingDays());
        assertEquals(3, b.minPriorDays());
        assertEquals(50.0, b.maxDecrease());
        assertEquals(100.0, b.maxIncrease());
        assertEquals("percent", b.limitUnit());
    }

    /** LA-DAILY-INGEST-1 owner call: the shipped Files-from-an-inbox feeds make a corrected same-name day take effect. */
    @Test
    void theShippedInboxFeedsReprocessAChangedSameNameFile() throws Exception {
        for (String rel : List.of("la-daily-feed/config/daily_xdr/daily_xdr_pipeline.toon",
                "la-showcase/config/mule/mule_transfers_pipeline.toon")) {
            Path toon = Path.of("..", "spaces", "_templates").resolve(rel).toAbsolutePath().normalize();
            var d = com.gamma.etl.PipelineConfig.load(toon.toString()).collector().duplicate();
            assertEquals("checksum", d.mode(), rel);
            assertEquals("reprocess", d.onChange(), rel);
        }
    }

    /** S10 live drive: a fresh Space must index itself and run its volume band on the arrival of a File, with no Job authored by hand. */
    @Test
    void theTemplateShipsTheJobsThatIndexAndEvaluateOnEveryCommit() throws Exception {
        Path jobs = Path.of("..", "spaces", "_templates", "la-daily-feed", "config", "jobs").toAbsolutePath().normalize();
        com.gamma.job.JobConfig index = com.gamma.job.JobConfig.load(jobs.resolve("la_index_build_job.toon").toString());
        assertEquals("la.index.build", index.type());
        assertEquals("pipeline.commit", index.onSignal());
        assertEquals("daily_xdr_dataset", index.params().get("dataset"));
        assertEquals("true", index.params().get("allow_full"), "the first build is a full one");
        // LA-DAILY-INGEST-1 roadmap residual: the T5 / scale parameters reach the Job from the .toon, not only from a map
        assertEquals("admin", index.params().get("owner"), "the seed gate re-stamps it to the applier on an authenticated server");
        assertEquals("600", index.params().get("timeout_seconds"));
        assertEquals("300", index.params().get("wait_seconds"), "a time budget, not the six-wait bound");
        assertTrue(Long.parseLong(index.params().get("wait_seconds")) <= Long.parseLong(index.params().get("timeout_seconds")),
                "a wait longer than the timeout is clamped to it, so the shipped wait must fit inside it");
        com.gamma.job.JobConfig band = com.gamma.job.JobConfig.load(jobs.resolve("la_rows_band_evaluate_job.toon").toString());
        assertEquals("expectation.evaluate", band.type());
        assertEquals("pipeline.commit", band.onSignal());
        assertTrue(band.when().contains("daily_xdr"), "only this feed's commits");
    }
}
