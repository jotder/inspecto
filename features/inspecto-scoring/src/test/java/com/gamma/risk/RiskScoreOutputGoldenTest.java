package com.gamma.risk;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.entitylist.WatchListFeed;
import com.gamma.mask.EvidenceMasker;
import com.gamma.pipeline.ComponentStore;
import com.gamma.query.DatasetRelation;
import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TimeZone;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Golden pins of everything a {@code risk.score} run writes or hands on (ANALYTICS-CORE-1 M7): the history and
 * {@code _latest} directories (file names, ownership marker, column names and types, every row), the model version
 * hash, the {@code factors} explanation JSON, recompute-from-explanation, the watch-list feed's arguments and its
 * refusal, and retention. Written against the pre-M7 code and kept unchanged when the score output contract moved to
 * the Analytics Kernel, so a moved line that changes any written byte of content turns this red.
 */
class RiskScoreOutputGoldenTest {

    private static final Instant AT = Instant.parse("2026-10-01T02:03:04.005Z");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static TimeZone saved;

    /** TIMESTAMP columns are written through the JVM zone: pin it so the golden reads the same on every host. */
    @BeforeAll
    static void utc() throws Exception {
        saved = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        DuckDbUtil.loadDriver();
    }

    @AfterAll
    static void restore() { TimeZone.setDefault(saved); }

    private static final EvidenceMasker NO_MASK = EvidenceMasker.of(
            new ComponentStore(Path.of("does-not-exist", "registry")), Path.of("does-not-exist", "cfg"), List.of("d"));

    private static RiskScoreEvaluator.Run corpusRun(Path dir) throws Exception {
        Path cfg = dir.resolve("config"), data = dir.resolve("data");
        RiskCorpus.plant(cfg, data, false);
        RiskScoreModel model = RiskScoreModel.fromMap(RiskCorpus.MODEL, RiskCorpus.model());
        return RiskScoreEvaluator.evaluate(model, id -> DatasetRelation.relationSql(Map.of("physicalRef", id), data, null),
                NO_MASK);
    }

    /** One directory, rendered: its files and marker, the Parquet schema, and every row ordered by entity key. */
    static String dump(Path dir) throws Exception {
        StringBuilder out = new StringBuilder();
        try (Stream<Path> s = Files.list(dir)) {
            for (Path p : s.sorted().toList()) {
                out.append("file ").append(p.getFileName());
                if (!p.getFileName().toString().endsWith(".parquet")) out.append(" = ").append(Files.readString(p));
                out.append('\n');
            }
        }
        String glob = "'" + dir.toString().replace('\\', '/') + "/*.parquet'";
        try (Connection c = java.sql.DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery("DESCRIBE SELECT * FROM read_parquet(" + glob + ")")) {
                while (rs.next()) out.append("col ").append(rs.getString(1)).append(' ').append(rs.getString(2)).append('\n');
            }
            try (ResultSet rs = st.executeQuery("SELECT * FROM read_parquet(" + glob + ") ORDER BY entity_key, run_id")) {
                int n = rs.getMetaData().getColumnCount();
                while (rs.next()) {
                    out.append("row");
                    for (int i = 1; i <= n; i++) out.append(" | ").append(rs.getString(i));
                    out.append('\n');
                }
            }
        }
        return out.toString();
    }

    private static final String HISTORY = """
            file .risk-score-output = subs
            file scores-1790820184005-run_1.parquet
            col model VARCHAR
            col entity_type VARCHAR
            col entity_key VARCHAR
            col score DOUBLE
            col high BOOLEAN
            col factors VARCHAR
            col model_version VARCHAR
            col run_id VARCHAR
            col scored_at TIMESTAMP
            row | subs | subscriber | m1 | 65.6 | true | [{"indicator":"failed_topups","label":"Failed top-ups","value":3.0,"missing":false,"weight":10.0,"cap":25.0,"capped":true,"contribution":25.0,"evidence":[{"topup_id":"t1","amount":10.0},{"topup_id":"t2","amount":20.0},{"topup_id":"t3","amount":30.0}]},{"indicator":"sim_swaps","value":2.0,"missing":false,"weight":20.0,"capped":false,"contribution":40.0,"evidence":[{"swap_id":"s1"},{"swap_id":"s2"}]},{"indicator":"spend","value":60.0,"missing":false,"weight":0.01,"capped":false,"contribution":0.6,"evidence":[]}] | 0123456789ab | run/1 | 2026-10-01 02:03:04.005
            row | subs | subscriber | m2 | 10.05 | false | [{"indicator":"failed_topups","label":"Failed top-ups","value":1.0,"missing":false,"weight":10.0,"cap":25.0,"capped":false,"contribution":10.0,"evidence":[{"topup_id":"t4","amount":5.0}]},{"indicator":"sim_swaps","value":null,"missing":true,"weight":20.0,"capped":false,"contribution":0.0,"evidence":[]},{"indicator":"spend","value":5.0,"missing":false,"weight":0.01,"capped":false,"contribution":0.05,"evidence":[]}] | 0123456789ab | run/1 | 2026-10-01 02:03:04.005
            row | subs | subscriber | m3 | 1.0 | false | [{"indicator":"failed_topups","label":"Failed top-ups","value":null,"missing":true,"weight":10.0,"cap":25.0,"capped":false,"contribution":0.0,"evidence":[]},{"indicator":"sim_swaps","value":null,"missing":true,"weight":20.0,"capped":false,"contribution":0.0,"evidence":[]},{"indicator":"spend","value":100.0,"missing":false,"weight":0.01,"capped":false,"contribution":1.0,"evidence":[]}] | 0123456789ab | run/1 | 2026-10-01 02:03:04.005
            row | subs | subscriber | m4 | 20.0 | false | [{"indicator":"failed_topups","label":"Failed top-ups","value":null,"missing":true,"weight":10.0,"cap":25.0,"capped":false,"contribution":0.0,"evidence":[]},{"indicator":"sim_swaps","value":1.0,"missing":false,"weight":20.0,"capped":false,"contribution":20.0,"evidence":[{"swap_id":"s3"}]},{"indicator":"spend","value":null,"missing":true,"weight":0.01,"capped":false,"contribution":0.0,"evidence":[]}] | 0123456789ab | run/1 | 2026-10-01 02:03:04.005
            """;

    @Test
    void theHistoryAndLatestDatasetsAreUnchanged(@TempDir Path dir) throws Exception {
        RiskScoreEvaluator.Run run = corpusRun(dir);
        RiskScoreModel model = RiskScoreModel.fromMap(RiskCorpus.MODEL, RiskCorpus.model());
        Path out = dir.resolve("out");
        Path history = RiskScoreEvaluator.write(out, model, "0123456789ab", "run/1", AT, run.scored());
        assertEquals(out.resolve("risk_scores_subs"), history);
        assertEquals(HISTORY, dump(history));
        assertEquals(HISTORY, dump(out.resolve("risk_scores_subs_latest")), "_latest holds this run alone, same shape");
    }

    @Test
    void theLatestSwapKeepsOnlyTheNewestRunAndTheHistoryKeepsBoth(@TempDir Path dir) throws Exception {
        RiskScoreEvaluator.Run run = corpusRun(dir);
        RiskScoreModel model = RiskScoreModel.fromMap(RiskCorpus.MODEL, RiskCorpus.model());
        Path out = dir.resolve("out");
        RiskScoreEvaluator.write(out, model, "v", "a", AT, run.scored());
        RiskScoreEvaluator.write(out, model, "v", "b", AT.plusSeconds(60), run.scored());
        assertEquals(List.of(".risk-score-output", "scores-1790820184005-a.parquet", "scores-1790820244005-b.parquet"),
                names(out.resolve("risk_scores_subs")));
        assertEquals(List.of(".risk-score-output", "scores-1790820244005-b.parquet"), names(out.resolve("risk_scores_subs_latest")));
    }

    @Test
    void aDirectoryAnotherModelOwnsIsRefusedWithTheSameMessage(@TempDir Path dir) throws Exception {
        RiskScoreModel model = RiskScoreModel.fromMap(RiskCorpus.MODEL, RiskCorpus.model());
        Files.createDirectories(dir.resolve("risk_scores_subs"));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> RiskScoreEvaluator.write(dir, model, "v", "r", AT, List.of()));
        assertEquals("risk-score 'subs' refuses to write into 'risk_scores_subs': the directory exists and was not "
                + "created by this model", e.getMessage());
    }

    @Test
    void theModelVersionHashIsUnchanged() {
        Map<String, Object> content = new LinkedHashMap<>(RiskCorpus.model());
        assertEquals("c50b609c54a8", RiskScoreEvaluator.version(content));
        content.put("owner", "someone");
        content.put("shares", List.of("x"));
        assertEquals("c50b609c54a8", RiskScoreEvaluator.version(content), "the envelope is left out of the hash");
    }

    @Test
    void recomputeFromTheStoredExplanationGivesTheStoredScore(@TempDir Path dir) throws Exception {
        for (RiskScorer.Scored s : corpusRun(dir).scored()) {
            List<Map<String, Object>> factors = JSON.readValue(RiskScoreEvaluator.factorsJson(s.factors()),
                    new TypeReference<>() {});
            assertEquals(s.score(), RiskScorer.recompute(factors), 0.0, s.entityKey());
        }
        assertEquals(100.0, RiskScorer.recompute(List.of(Map.of("weight", 300, "value", 1))), 0.0, "clamped to 100");
        assertEquals(0.0, RiskScorer.recompute(List.of(Map.of("weight", -3, "value", 1))), 0.0, "clamped to 0");
        assertEquals(40.0, RiskScorer.recompute(List.of(Map.of("weight", 30, "value", 2, "cap", 40))), 0.0, "capped");
    }

    /** A feed that records what it was handed. */
    static final class Recording implements WatchListFeed {
        final List<String> calls = new ArrayList<>();
        @Override public java.util.function.Predicate<String> check(Path writeRoot, String listId, String purpose) {
            return k -> false;
        }
        @Override public int feed(Path writeRoot, Path dataDir, String listId, Collection<String> keys, Instant expiresAt,
                                  String actor, String reason) {
            calls.add(listId + " " + keys + " " + expiresAt + " " + actor + " " + reason);
            return keys.size();
        }
    }

    @Test
    void theWatchListFeedIsHandedTheSameArguments(@TempDir Path dir) throws Exception {
        Map<String, Object> content = new LinkedHashMap<>(RiskCorpus.model());
        content.put("watchList", Map.of("list", "mule-watch", "ttlHours", 12));
        RiskScoreModel model = RiskScoreModel.fromMap(RiskCorpus.MODEL, content);
        List<RiskScorer.Scored> scored = corpusRun(dir).scored();
        Recording rec = new Recording();
        assertEquals(1, RiskScoreJobType.feedWatchList(Optional.of(rec), dir, dir, model, "r7", AT, scored));
        assertEquals(List.of("mule-watch [m1] 2026-10-01T14:03:04.005Z job:risk.score:subs risk.score subs run r7: score >= 50.0"),
                rec.calls);
        IllegalStateException none = assertThrows(IllegalStateException.class,
                () -> RiskScoreJobType.feedWatchList(Optional.empty(), dir, dir, model, "r7", AT, scored));
        assertEquals("risk-score 'subs' feeds watch list 'mule-watch', but Entity Lists are not installed in this edition",
                none.getMessage());
        assertEquals(0, RiskScoreJobType.feedWatchList(Optional.empty(), dir, dir,
                RiskScoreModel.fromMap(RiskCorpus.MODEL, RiskCorpus.model()), "r7", AT, scored), "no watchList: no-op");
    }

    @Test
    void retentionKeepsTheSameFiles(@TempDir Path dir) throws Exception {
        Map<String, Object> runs = new LinkedHashMap<>(RiskCorpus.model());
        runs.put("retainRuns", 2);
        RiskScoreModel byRuns = RiskScoreModel.fromMap("subs", runs);
        List<RiskScorer.Scored> one = List.of(RiskScorer.score(byRuns, "m1", Map.of("sim_swaps", 1.0), Map.of()));
        Path a = dir.resolve("a");
        for (int i = 0; i < 5; i++) RiskScoreEvaluator.write(a, byRuns, "v", "r" + i, AT.plus(Duration.ofDays(i)), one);
        assertEquals(List.of(".risk-score-output", "scores-1791079384005-r3.parquet", "scores-1791165784005-r4.parquet"),
                names(a.resolve("risk_scores_subs")));

        Map<String, Object> days = new LinkedHashMap<>(RiskCorpus.model());
        days.put("retainDays", 2);
        RiskScoreModel byDays = RiskScoreModel.fromMap("subs", days);
        Path b = dir.resolve("b");
        RiskScoreModel off = RiskScoreModel.fromMap("subs", RiskCorpus.model());
        for (int i = 0; i < 4; i++) RiskScoreEvaluator.write(b, off, "v", "r" + i, AT.plus(Duration.ofDays(i)), one);
        RiskScoreEvaluator.write(b, byDays, "v", "r9", AT.plus(Duration.ofDays(4)), one);
        assertEquals(List.of(".risk-score-output", "scores-1790992984005-r2.parquet", "scores-1791079384005-r3.parquet",
                "scores-1791165784005-r9.parquet"), names(b.resolve("risk_scores_subs")),
                "older than 2 days by file stamp is pruned; exactly 2 days old is kept");
    }

    @Test
    void theEntityCapResolvesTheSameWay() {
        assertEquals(200_000, RiskScoreEvaluator.MAX_ENTITIES);
        assertEquals("risk.score.maxEntities", RiskScoreEvaluator.MAX_ENTITIES_PROPERTY);
        assertEquals(2_000_000, RiskScoreModel.MAX_ENTITIES_CEILING);
        for (String bad : new String[]{null, "", "x", "0", "-4", "1.5"})
            assertEquals(200_000, RiskScoreEvaluator.defaultMaxEntities(bad), String.valueOf(bad));
        assertEquals(7, RiskScoreEvaluator.defaultMaxEntities(" 7 "));
        Map<String, Object> m = new LinkedHashMap<>(RiskCorpus.model());
        m.put("maxEntities", 9);
        assertEquals(9, RiskScoreEvaluator.maxEntities(RiskScoreModel.fromMap("subs", m)));
    }

    private static List<String> names(Path dir) throws Exception {
        try (Stream<Path> s = Files.list(dir)) { return s.map(p -> p.getFileName().toString()).sorted().toList(); }
    }
}
