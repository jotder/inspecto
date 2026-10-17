package com.gamma.anomaly;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.entitylist.WatchListFeed;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.ViewStore;
import com.gamma.query.DatasetRelation;
import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TimeZone;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Golden pins of everything an {@code anomaly.score} run writes or hands on (ANALYTICS-CORE-1 M7), over the golden
 * corpus: the history and {@code _latest} directories (file names, ownership marker, column names and types, every
 * row — a SHA-256 of all 306, plus the planted rows in full), the model version hash, the band counts,
 * recompute-from-explanation, the watch-list feed's arguments and its refusal, and the entity cap. Written against
 * the pre-M7 code and kept unchanged when the score output contract moved to the Analytics Kernel.
 */
class AnomalyScoreOutputGoldenTest {

    private static final Instant AT = Instant.parse("2026-10-01T02:03:04.005Z");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static TimeZone saved;
    private static AnomalyModel model;
    private static Map<String, Object> content;
    private static AnomalyScoreEvaluator.Run run;

    /** TIMESTAMP columns are written through the JVM zone: pin it so the golden reads the same on every host. */
    @BeforeAll
    static void score(@TempDir Path dir) throws Exception {
        saved = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        DuckDbUtil.loadDriver();
        Path cfg = dir.resolve("config"), data = dir.resolve("data");
        content = AnomalyCorpus.plant(cfg, data);
        model = AnomalyModel.fromMap(AnomalyCorpus.MODEL, content);
        ComponentStore store = new ComponentStore(cfg.resolve("registry"));
        ViewStore views = new ViewStore(cfg.resolve("views"));
        run = AnomalyScoreEvaluator.evaluate(model, AnomalyCorpus.AS_OF, id ->
                DatasetRelation.relationSql(store.get("dataset", id).orElseThrow().content(), data, views));
    }

    @AfterAll
    static void restore() { TimeZone.setDefault(saved); }

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
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement()) {
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

    private static String sha(String s) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    }

    private static final String HEAD = """
            file .anomaly-score-output = usage
            file scores-1790820184005-run_1.parquet
            col model VARCHAR
            col entity_type VARCHAR
            col entity_key VARCHAR
            col period_start TIMESTAMP
            col score DOUBLE
            col band VARCHAR
            col raw DOUBLE
            col features VARCHAR
            col insufficient_count INTEGER
            col model_version VARCHAR
            col run_id VARCHAR
            col scored_at TIMESTAMP
            """;

    private static final String SPIKE = "row | usage | subscriber | spike | 2026-09-30 00:00:00.0 | 95.8040465877309 | high | 9.513148795220223 | [{\"feature\":\"data_mb\",\"label\":\"Data volume (MB)\",\"bucket\":\"2026-09-30\",\"observed\":3000.0,\"baseline\":{\"kind\":\"self\",\"median\":299.0,\"mad\":7.412999999999999,\"points\":28,\"season\":\"none\",\"basis\":\"none\",\"fellBack\":false},\"unit\":1.0,\"zSelf\":180.6688963210702,\"deviation\":10.0,\"direction\":\"up\",\"weight\":1.0,\"contribution\":50.0,\"share\":0.5524861878453039,\"insufficient\":false,\"reason\":\"Data volume (MB) 3000 vs a usual 299 (28 days) — 181 MADs above\"},{\"feature\":\"sessions\",\"label\":\"Sessions\",\"bucket\":\"2026-09-30\",\"observed\":10.0,\"baseline\":{\"kind\":\"self\",\"median\":1.0,\"mad\":0.0,\"points\":28,\"season\":\"none\",\"basis\":\"none\",\"fellBack\":false},\"unit\":1.0,\"zSelf\":9.0,\"deviation\":9.0,\"direction\":\"up\",\"weight\":1.0,\"contribution\":40.5,\"share\":0.44751381215469616,\"insufficient\":false,\"reason\":\"Sessions 10 vs a usual 1 (28 days) — 9 MADs above\"}] | 0 | 0123456789ab | run/1 | 2026-10-01 02:03:04.005";
    private static final String MASKED = "row | usage | subscriber | masked | 2026-09-30 00:00:00.0 | 90.52982314766173 | high | 7.0710678118654755 | [{\"feature\":\"data_mb\",\"label\":\"Data volume (MB)\",\"bucket\":\"2026-09-30\",\"observed\":900.0,\"baseline\":{\"kind\":\"self\",\"median\":199.0,\"mad\":8.1543,\"points\":28,\"season\":\"none\",\"basis\":\"none\",\"fellBack\":false},\"unit\":1.0,\"zSelf\":70.45226130653266,\"deviation\":10.0,\"direction\":\"up\",\"weight\":1.0,\"contribution\":50.0,\"share\":1.0,\"insufficient\":false,\"reason\":\"Data volume (MB) 900 vs a usual 199 (28 days) — 70.5 MADs above\"},{\"feature\":\"sessions\",\"label\":\"Sessions\",\"bucket\":\"2026-09-30\",\"observed\":1.0,\"baseline\":{\"kind\":\"self\",\"median\":1.0,\"mad\":0.0,\"points\":28,\"season\":\"none\",\"basis\":\"none\",\"fellBack\":false},\"unit\":1.0,\"zSelf\":0.0,\"deviation\":0.0,\"direction\":\"up\",\"weight\":1.0,\"contribution\":0.0,\"share\":0.0,\"insufficient\":false,\"reason\":\"Sessions 1 vs a usual 1 (28 days) — 0 MADs above\"}] | 0 | 0123456789ab | run/1 | 2026-10-01 02:03:04.005";
    private static final String ALL_ROWS_SHA = "f942e42d3e8bdc88f221a93000d88a4285ec832d4eaa8c9ea0032d1ab3a064d7";

    @Test
    void theHistoryAndLatestDatasetsAreUnchanged(@TempDir Path out) throws Exception {
        Path history = AnomalyScoreEvaluator.write(out, model, "0123456789ab", "run/1", AT, run);
        assertEquals(out.resolve("anomaly_scores_usage"), history);
        String dump = dump(history);
        assertTrue(dump.startsWith(HEAD), dump.substring(0, Math.min(dump.length(), 2000)));
        List<String> rows = dump.lines().filter(l -> l.startsWith("row")).toList();
        assertEquals(306, rows.size());
        assertEquals(SPIKE, rows.stream().filter(l -> l.startsWith("row | usage | subscriber | spike |")).findFirst().orElseThrow());
        assertEquals(MASKED, rows.stream().filter(l -> l.startsWith("row | usage | subscriber | masked |")).findFirst().orElseThrow());
        assertEquals(ALL_ROWS_SHA, sha(dump));
        assertEquals(dump, dump(out.resolve("anomaly_scores_usage_latest")), "_latest holds this run alone, same shape");
    }

    @Test
    void theLatestSwapKeepsOnlyTheNewestRunAndTheHistoryKeepsBoth(@TempDir Path out) throws Exception {
        AnomalyScoreEvaluator.write(out, model, "v", "a", AT, run);
        AnomalyScoreEvaluator.write(out, model, "v", "b", AT.plusSeconds(60), run);
        assertEquals(List.of(".anomaly-score-output", "scores-1790820184005-a.parquet", "scores-1790820244005-b.parquet"),
                names(out.resolve("anomaly_scores_usage")));
        assertEquals(List.of(".anomaly-score-output", "scores-1790820244005-b.parquet"),
                names(out.resolve("anomaly_scores_usage_latest")));
    }

    @Test
    void aDirectoryAnotherModelOwnsIsRefusedWithTheSameMessage(@TempDir Path out) throws Exception {
        Files.createDirectories(out.resolve("anomaly_scores_usage"));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> AnomalyScoreEvaluator.write(out, model, "v", "r", AT, run));
        assertEquals("anomaly-model 'usage' refuses to write into 'anomaly_scores_usage': the directory exists and was "
                + "not created by this model", e.getMessage());
    }

    @Test
    void theModelVersionHashIsUnchanged() {
        Map<String, Object> c = new LinkedHashMap<>(AnomalyCorpus.model());
        assertEquals("19918787637d", AnomalyScoreEvaluator.version(c));
        c.put("owner", "someone");
        c.put("name", "usage");
        assertEquals("19918787637d", AnomalyScoreEvaluator.version(c), "the envelope is left out of the hash");
    }

    @Test
    void bandCountsAndTheScoreScaleAreUnchanged() {
        assertEquals("{elevated=0, high=2, normal=304}", AnomalyScoreEvaluator.bands(run.scored()).toString());
        assertEquals("{elevated=0, high=0, normal=0}", AnomalyScoreEvaluator.bands(List.of()).toString());
        assertEquals(100 * (1 - Math.exp(-1.0)), AnomalyScorer.score(3, 3), 0.0);
        assertEquals("normal", model.band(59.999));
        assertEquals("elevated", model.band(60));
        assertEquals("high", model.band(80));
    }

    @Test
    void recomputeFromTheStoredExplanationGivesTheStoredScore() throws Exception {
        for (AnomalyScorer.Scored s : run.scored()) {
            List<Map<String, Object>> features = JSON.readValue(AnomalyScoreEvaluator.featuresJson(s.features()),
                    new TypeReference<>() {});
            double[] r = AnomalyScorer.recompute(features, model.zCap(), model.scale());
            assertEquals(s.raw(), r[0], 1e-9, s.entityKey());
            assertEquals(s.score(), r[1], 1e-9, s.entityKey());
        }
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
        AnomalyLists lists = AnomalyLists.of(Map.of("watchList", Map.of("list", "usage-watch", "ttlHours", 6)));
        Recording rec = new Recording();
        assertEquals(2, AnomalyScoreJobType.feedWatchList(Optional.of(rec), dir, dir, model, lists, "r7", AT, run.scored()));
        assertEquals(List.of("usage-watch [masked, spike] 2026-10-01T08:03:04.005Z job:anomaly.score:usage anomaly.score "
                + "usage run r7: band high (score >= 80.0)"), rec.calls);
        IllegalStateException none = assertThrows(IllegalStateException.class, () -> AnomalyScoreJobType.feedWatchList(
                Optional.empty(), dir, dir, model, lists, "r7", AT, run.scored()));
        assertEquals("anomaly-model 'usage' feeds watch list 'usage-watch', but Entity Lists are not installed in this "
                + "edition", none.getMessage());
        assertEquals(0, AnomalyScoreJobType.feedWatchList(Optional.empty(), dir, dir, model, AnomalyLists.of(Map.of()),
                "r7", AT, run.scored()), "no watchList: no-op");
    }

    @Test
    void theEntityCapResolvesTheSameWay() {
        assertEquals(200_000, AnomalyScoreEvaluator.MAX_ENTITIES);
        assertEquals("anomaly.score.maxEntities", AnomalyScoreEvaluator.MAX_ENTITIES_PROPERTY);
        assertEquals(2_000_000, AnomalyModel.MAX_ENTITIES_CEILING);
        for (String bad : new String[]{null, "", "x", "0", "-4", "1.5"})
            assertEquals(200_000, AnomalyScoreEvaluator.defaultMaxEntities(bad), String.valueOf(bad));
        assertEquals(7, AnomalyScoreEvaluator.defaultMaxEntities(" 7 "));
        Map<String, Object> m = new LinkedHashMap<>(AnomalyCorpus.model());
        m.put("maxEntities", 9);
        assertEquals(9, AnomalyScoreEvaluator.maxEntities(AnomalyModel.fromMap("usage", m)));
    }

    private static List<String> names(Path dir) throws Exception {
        try (Stream<Path> s = Files.list(dir)) { return s.map(p -> p.getFileName().toString()).sorted().toList(); }
    }
}
