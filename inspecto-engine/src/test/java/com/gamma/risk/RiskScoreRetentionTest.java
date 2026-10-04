package com.gamma.risk;

import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Opt-in history retention: off by default, whole run files only, never {@code _latest} or the new run. */
class RiskScoreRetentionTest {

    private static RiskScoreModel model(Map<String, Object> extra) {
        Map<String, Object> m = new LinkedHashMap<>(RiskCorpus.model());
        m.putAll(extra);
        return RiskScoreModel.fromMap("subs", m);
    }

    private static List<RiskScorer.Scored> one(RiskScoreModel m) {
        return List.of(RiskScorer.score(m, "m1", Map.of("sim_swaps", 1.0), Map.of()));
    }

    private static long count(Path dir) throws Exception {
        try (Stream<Path> s = Files.list(dir)) { return s.filter(p -> p.toString().endsWith(".parquet")).count(); }
    }

    private static void runs(Path data, RiskScoreModel m, Instant base, int n) throws Exception {
        for (int i = 0; i < n; i++)
            RiskScoreEvaluator.write(data, m, "v", "r" + i, base.plus(Duration.ofDays(i)), one(m));
    }

    @Test
    void defaultOffLeavesEveryRunInTheHistory(@TempDir Path data) throws Exception {
        DuckDbUtil.loadDriver();
        RiskScoreModel m = model(Map.of());
        assertNull(m.retainDays());
        assertNull(m.retainRuns());
        runs(data, m, Instant.parse("2020-01-01T00:00:00Z"), 4);
        assertEquals(4, count(data.resolve("risk_scores_subs")));
    }

    @Test
    void retainRunsKeepsTheNewestAndNeverTouchesLatest(@TempDir Path data) throws Exception {
        DuckDbUtil.loadDriver();
        RiskScoreModel m = model(Map.of("retainRuns", 2));
        runs(data, m, Instant.parse("2020-01-01T00:00:00Z"), 5);
        assertEquals(2, count(data.resolve("risk_scores_subs")));
        assertEquals(1, count(data.resolve("risk_scores_subs_latest")), "_latest is never pruned");
        try (Stream<Path> s = Files.list(data.resolve("risk_scores_subs"))) {
            assertTrue(s.anyMatch(p -> p.getFileName().toString().contains("-r4.parquet")), "newest run kept");
        }
    }

    @Test
    void retainDaysDropsOnlyRunsOlderThanTheWindowAndKeepsTheNewRun(@TempDir Path data) throws Exception {
        DuckDbUtil.loadDriver();
        Instant base = Instant.parse("2020-01-01T00:00:00Z");
        runs(data, model(Map.of()), base, 3); // days 0,1,2 written with retention off
        RiskScoreModel m = model(Map.of("retainDays", 1));
        RiskScoreEvaluator.write(data, m, "v", "r9", base.plus(Duration.ofDays(10)), one(m));
        assertEquals(1, count(data.resolve("risk_scores_subs")), "old runs pruned, the just-written run kept");
        assertEquals(1, count(data.resolve("risk_scores_subs_latest")));
    }

    @Test
    void retentionKeysFailClosed() {
        for (Object bad : new Object[]{0, -1, 1.5, "x"})
            assertThrows(IllegalArgumentException.class, () -> model(Map.of("retainDays", bad)), String.valueOf(bad));
        assertThrows(IllegalArgumentException.class, () -> model(Map.of("retainRuns", 0)));
        assertThrows(IllegalArgumentException.class, () -> model(Map.of("retainDays", 5, "retainRuns", 5)),
                "both set is ambiguous");
        assertEquals(7, model(Map.of("retainDays", 7)).retainDays());
    }
}
