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
 * The {@code peers.by} cohort key (ANOMALY-DETECTION-RESIDUALS-1 (g), operator decision 2026-10-11): NULL is a cohort
 * value of its own in every {@code by} position, and the key is collision-free whatever the values contain. The cohort
 * an entity was scored in is read back from its peer baseline ({@code requested}, stored as {@code peerBaseline.cohort}):
 * the values joined with {@code '|'}, NULL shown as {@code ∅}; its member count ({@code points}) shows who shares it.
 *
 * <p>Fixture: the peer corpus's {@code traffic} Features, with the cohort taken from a two-column Dataset
 * {@code cohorts(msisdn, a, b)}: {@code p000 = ('A','B')}, {@code p001 = ('A',NULL)}, {@code p002 = (NULL,'A')},
 * {@code p003 = p008 = (NULL,NULL)}, {@code p005 = ('A|B',NULL)}, {@code p006 = ('A|B','C')},
 * {@code p007 = ('A','B|C')}, {@code p009 = ('A','')}; {@code p004} has no row. {@code minGroupSize: 2} (the floor),
 * so a cohort's member count is visible in its baseline. A second run groups on {@code b} alone.
 */
class AnomalyScoreEvaluatorTest {

    private static Map<String, AnomalyScorer.Scored> scored;
    private static Map<String, AnomalyScorer.Scored> scoredByB;

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
                    + "('p003', NULL, NULL), ('p005', 'A|B', NULL), ('p006', 'A|B', 'C'), ('p007', 'A', 'B|C'), "
                    + "('p008', NULL, NULL), ('p009', 'A', '')) t(msisdn, a, b)) TO '"
                    + out.resolve("data.parquet").toString().replace('\\', '/') + "' (FORMAT PARQUET)");
        }
        ViewStore views = new ViewStore(cfg.resolve("views"));
        scored = run(store, data, views, List.of("a", "b"));
        scoredByB = run(store, data, views, List.of("b"));
    }

    private static Map<String, AnomalyScorer.Scored> run(ComponentStore store, Path data, ViewStore views, List<String> by)
            throws Exception {
        Map<String, Object> m = new LinkedHashMap<>(AnomalyPeerCorpus.model());
        Map<String, Object> peers = new LinkedHashMap<>();
        peers.put("by", by);
        peers.put("dataset", "cohorts");
        peers.put("minGroupSize", 2);
        m.put("peers", peers);
        AnomalyModel model = AnomalyModel.fromMap("cohort_nulls", m);
        return AnomalyScoreEvaluator.evaluate(model, AnomalyPeerCorpus.AS_OF, id ->
                        DatasetRelation.relationSql(store.get("dataset", id).orElseThrow().content(), data, views))
                .scored().stream().collect(Collectors.toMap(AnomalyScorer.Scored::entityKey, s -> s));
    }

    /** The peer baseline {@code entity} was scored against on the {@code data_mb} Feature. */
    private static AnomalyScorer.FeatureResult mb(Map<String, AnomalyScorer.Scored> run, String entity) {
        return run.get(entity).features().stream().filter(f -> f.feature().equals("data_mb")).findFirst().orElseThrow();
    }

    private static AnomalyScorer.FeatureResult mb(String entity) {
        return mb(scored, entity);
    }

    @Test
    void twoNonNullColumnsJoinWithTheSeparator() {
        assertEquals("A|B", mb("p000").peerBaseline().requested());
        assertEquals(1, mb("p000").peerBaseline().points());
        assertTrue(mb("p000").peerBaseline().insufficient());
    }

    /**
     * {@code ('A', NULL)} and {@code (NULL, 'A')} are two cohorts (no column agrees between them), and neither is
     * {@code (NULL, NULL)}: each is a one-member cohort, below {@code minGroupSize} like any other.
     */
    @Test
    void aNullInEitherPositionIsItsOwnCohortValue() {
        assertEquals("A|∅", mb("p001").peerBaseline().requested());
        assertEquals("∅|A", mb("p002").peerBaseline().requested());
        assertEquals(1, mb("p001").peerBaseline().points());
        assertEquals(1, mb("p002").peerBaseline().points());
        assertTrue(mb("p001").peerBaseline().insufficient());
        assertTrue(mb("p002").peerBaseline().insufficient());
    }

    /** All {@code by} columns NULL is a cohort like any other: {@code p003} and {@code p008} share it, and it meets the floor. */
    @Test
    void allNullColumnsFormTheirOwnCohort() {
        assertEquals("∅|∅", mb("p003").peerBaseline().requested());
        assertEquals(2, mb("p003").peerBaseline().points());
        assertFalse(mb("p003").peerBaseline().insufficient());
        assertEquals("peers ∅|∅, 2 entities", mb("p003").peerBaseline().reason());
        assertEquals("∅|∅", mb("p008").peerBaseline().basis());
    }

    /** An entity with NO row in the peers Dataset is in the population only — unchanged. */
    @Test
    void anEntityWithoutAPeersRowStaysPopulationOnly() {
        assertNull(mb("p004").peerBaseline().requested());
        assertTrue(mb("p004").peerBaseline().insufficient());
        assertNull(mb(scoredByB, "p004").peerBaseline().requested());
    }

    /**
     * A value containing the separator cannot collide: {@code ('A|B', NULL)} is not {@code ('A','B')}, and
     * {@code ('A|B','C')} / {@code ('A','B|C')} stay two one-member cohorts although both READ {@code A|B|C}.
     */
    @Test
    void aSeparatorInsideAValueNeverMergesTwoCohorts() {
        assertEquals("A|B|∅", mb("p005").peerBaseline().requested());
        assertEquals(1, mb("p005").peerBaseline().points());
        assertEquals(1, mb("p000").peerBaseline().points());
        assertEquals("A|B|C", mb("p006").peerBaseline().requested());
        assertEquals("A|B|C", mb("p007").peerBaseline().requested());
        assertEquals(1, mb("p006").peerBaseline().points());
        assertEquals(1, mb("p007").peerBaseline().points());
    }

    /** NULL and the empty string are different values. */
    @Test
    void anEmptyStringIsNotNull() {
        assertEquals("A|", mb("p009").peerBaseline().requested());
        assertEquals(1, mb("p009").peerBaseline().points());
        assertEquals(1, mb("p001").peerBaseline().points());
    }

    /**
     * Single-column {@code by} (a behaviour change: a NULL value used to drop the entity to population-only): the NULL
     * value is its own cohort, here {@code p001 p003 p005 p008}; the empty string is a cohort too.
     */
    @Test
    void aSingleColumnNullIsItsOwnCohort() {
        for (String e : List.of("p001", "p003", "p005", "p008")) {
            assertEquals("∅", mb(scoredByB, e).peerBaseline().requested(), e);
            assertEquals(4, mb(scoredByB, e).peerBaseline().points(), e);
            assertFalse(mb(scoredByB, e).peerBaseline().insufficient(), e);
        }
        assertEquals("", mb(scoredByB, "p009").peerBaseline().requested());
        assertEquals(1, mb(scoredByB, "p009").peerBaseline().points());
    }
}
