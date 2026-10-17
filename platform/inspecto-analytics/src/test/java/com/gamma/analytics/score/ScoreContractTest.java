package com.gamma.analytics.score;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The score output contract's pure parts: names, scale and bands, the entity cap, columns, run files and retention. */
class ScoreContractTest {

    private static final Instant T = Instant.parse("2026-10-01T00:00:00Z");

    @Test
    void outputNamesDeriveFromThePrefix() {
        ScoreKind k = new ScoreKind("risk-score", "risk_scores_", ".risk-score-output");
        assertEquals("risk_scores_subs", k.scores("subs"));
        assertEquals("risk_scores_subs_latest", k.latest("subs"));
    }

    @Test
    void theScaleClampsSaturatesAndBands() {
        assertEquals(0.0, ScoreScale.clamp(-3), 0.0);
        assertEquals(100.0, ScoreScale.clamp(180), 0.0);
        assertEquals(42.5, ScoreScale.clamp(42.5), 0.0);
        assertEquals(0.0, ScoreScale.saturating(0, 3), 0.0);
        assertEquals(100 * (1 - Math.exp(-1)), ScoreScale.saturating(3, 3), 1e-12);
        assertTrue(ScoreScale.saturating(1e9, 3) <= 100.0);
        assertTrue(ScoreScale.isHigh(70, 70), "inclusive");
        assertFalse(ScoreScale.isHigh(69.999, 70));
        assertEquals("normal", ScoreScale.band(59.9, 60, 80));
        assertEquals("elevated", ScoreScale.band(60, 60, 80));
        assertEquals("high", ScoreScale.band(80, 60, 80));
        assertEquals("{elevated=0, high=2, normal=1}", ScoreScale.bandCounts(List.of("high", "normal", "high")).toString());
        assertEquals("{elevated=0, high=0, normal=0}", ScoreScale.bandCounts(List.of()).toString());
    }

    @Test
    void theEntityCapKeepsItsDefaultOnABadProperty() {
        for (String bad : new String[]{null, "", " ", "x", "0", "-1", "2.5", "99999999999"})
            assertEquals(EntityCap.DEFAULT, EntityCap.systemDefault(bad), String.valueOf(bad));
        assertEquals(12, EntityCap.systemDefault(" 12 "));
        assertEquals(5, EntityCap.resolve(5, "no.such.property"));
        assertEquals(EntityCap.DEFAULT, EntityCap.resolve(null, "no.such.property"));
        assertEquals(10 * EntityCap.DEFAULT, EntityCap.CEILING);
    }

    @Test
    void columnsAreHeadKindTail() {
        List<ScoreColumns.Column> t = ScoreColumns.table(List.of(ScoreColumns.Column.measure("score", "DOUBLE")));
        assertEquals(List.of("model", "entity_type", "entity_key", "score", "model_version", "run_id", "scored_at"),
                t.stream().map(ScoreColumns.Column::name).toList());
        assertEquals(ScoreColumns.Role.TEMPORAL, t.get(6).role());
        assertEquals(ScoreColumns.Role.MEASURE, t.get(3).role());
    }

    @Test
    void runFilesAreStampedAndSanitised() {
        assertEquals("scores-1790812800000-run_1.parquet", ScoreRunFiles.name(T, "run/1"));
        assertEquals(1790812800000L, ScoreRunFiles.stampOf("scores-1790812800000-run_1.parquet"));
        assertEquals(Long.MAX_VALUE, ScoreRunFiles.stampOf("scores-x.parquet"));
    }

    @Test
    void retentionByRunsKeepsTheNewestAndNeverTheJustWritten() {
        List<String> runs = List.of("scores-3-c.parquet", "scores-1-a.parquet", "scores-2-b.parquet", "scores-4-d.parquet");
        assertEquals(List.of("scores-1-a.parquet", "scores-2-b.parquet"),
                new ScoreRunFiles.Retention(null, 2).expired(runs, T, "scores-4-d.parquet"));
        assertEquals(List.of("scores-2-b.parquet"),
                new ScoreRunFiles.Retention(null, 2).expired(runs, T, "scores-1-a.parquet"), "the just-written run is never deleted, though it holds its place in the order");
        assertEquals(List.of(), ScoreRunFiles.Retention.OFF.expired(runs, T, "x"));
    }

    @Test
    void retentionByDaysDropsOnlyOlderStamps() {
        long day = 86_400_000L, now = T.toEpochMilli();
        String old = "scores-" + (now - 3 * day) + "-a.parquet", edge = "scores-" + (now - 2 * day) + "-b.parquet";
        String fresh = "scores-" + now + "-c.parquet", unstamped = "scores-x.parquet";
        assertEquals(List.of(old), new ScoreRunFiles.Retention(2, null).expired(List.of(fresh, edge, old, unstamped), T, fresh),
                "exactly the window's age is kept; an unstamped file never expires");
    }
}
