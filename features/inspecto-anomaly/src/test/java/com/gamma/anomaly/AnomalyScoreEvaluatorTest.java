package com.gamma.anomaly;

import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.ViewStore;
import com.gamma.query.DatasetRelation;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CHARACTERISATION of the multi-column {@code peers.by} cohort key (ANOMALY-DETECTION-RESIDUALS-1 (g)): the key is
 * {@code max(concat_ws('|', by...))}, and DuckDB's {@code concat_ws} SKIPS NULL arguments. These tests pin what the
 * evaluator does TODAY; they are not the intended policy, which is the operator's call. The cohort an entity was
 * scored in is read back from its peer baseline ({@code requested}, stored as {@code peerBaseline.cohort}).
 *
 * <p>Fixture: the peer corpus's {@code traffic} Features, with the cohort taken from a two-column Dataset
 * {@code cohorts(msisdn, a, b)}: {@code p000 = ('A','B')}, {@code p001 = ('A',NULL)}, {@code p002 = (NULL,'A')},
 * {@code p003 = (NULL,NULL)}; {@code p004} has no row. {@code minGroupSize: 2} (the floor), so a cohort's member count is visible in its baseline.
 */
class AnomalyScoreEvaluatorTest {

    private static Map<String, AnomalyScorer.Scored> scored;

    @BeforeAll
    static void score(@TempDir Path dir) throws Exception {
        Path cfg = dir.resolve("config"), data = dir.resolve("data");
        AnomalyPeerCorpus.plant(cfg, data);
        ComponentStore store = new ComponentStore(cfg.resolve("registry"));
        store.write("dataset", "cohorts", Map.of("physicalRef", "cohorts"));
        Path out = data.resolve("cohorts");
        Files.createDirectories(out);
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement()) {
            st.execute("COPY (SELECT * FROM (VALUES ('p000', 'A', 'B'), ('p001', 'A', NULL), ('p002', NULL, 'A'), "
                    + "('p003', NULL, NULL)) t(msisdn, a, b)) TO '"
                    + out.resolve("data.parquet").toString().replace('\\', '/') + "' (FORMAT PARQUET)");
        }
        Map<String, Object> m = new LinkedHashMap<>(AnomalyPeerCorpus.model());
        Map<String, Object> peers = new LinkedHashMap<>();
        peers.put("by", List.of("a", "b"));
        peers.put("dataset", "cohorts");
        peers.put("minGroupSize", 2);
        m.put("peers", peers);
        AnomalyModel model = AnomalyModel.fromMap("cohort_nulls", m);
        ViewStore views = new ViewStore(cfg.resolve("views"));
        scored = AnomalyScoreEvaluator.evaluate(model, AnomalyPeerCorpus.AS_OF, id ->
                        DatasetRelation.relationSql(store.get("dataset", id).orElseThrow().content(), data, views))
                .scored().stream().collect(Collectors.toMap(AnomalyScorer.Scored::entityKey, s -> s));
    }

    /** The peer baseline {@code entity} was scored against on the {@code data_mb} Feature. */
    private static AnomalyScorer.FeatureResult mb(String entity) {
        return scored.get(entity).features().stream().filter(f -> f.feature().equals("data_mb")).findFirst().orElseThrow();
    }

    @Test
    void twoNonNullColumnsJoinWithTheSeparator() {
        assertEquals("A|B", mb("p000").peerBaseline().requested());
        assertEquals(1, mb("p000").peerBaseline().points());
        assertTrue(mb("p000").peerBaseline().insufficient());
    }

    /**
     * CURRENT BEHAVIOUR, a cohort collision (ANOMALY-DETECTION-RESIDUALS-1 (g), not fixed: the policy is the
     * operator's): {@code ('A', NULL)} and {@code (NULL, 'A')} both key as {@code 'A'}, so the two entities share one
     * two-member cohort although no column agrees between them.
     */
    @Test
    void aNullInEitherPositionIsSkippedSoTheTwoKeysCollideToday() {
        assertEquals("A", mb("p001").peerBaseline().requested());
        assertEquals("A", mb("p002").peerBaseline().requested());
        assertEquals(2, mb("p001").peerBaseline().points());
        assertEquals(2, mb("p002").peerBaseline().points());
        assertFalse(mb("p001").peerBaseline().insufficient());
    }

    /**
     * All {@code by} columns NULL: {@code concat_ws} yields the empty string, which the evaluator drops, so the entity
     * is in the population only, exactly like an entity with no row in the peers Dataset.
     */
    @Test
    void allNullColumnsLeaveTheEntityWithoutACohortLikeAMissingRow() {
        assertNull(mb("p003").peerBaseline().requested());
        assertTrue(mb("p003").peerBaseline().insufficient());
        assertNull(mb("p004").peerBaseline().requested());
        assertTrue(mb("p004").peerBaseline().insufficient());
    }
}
