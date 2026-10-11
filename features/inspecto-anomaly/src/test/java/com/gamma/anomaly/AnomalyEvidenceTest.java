package com.gamma.anomaly;

import com.gamma.mask.EvidenceMasker;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.ViewStore;
import com.gamma.query.DatasetRelation;
import com.gamma.sql.SqlSandboxPolicy;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Evidence rows (ANOMALY-DETECTION-RESIDUALS-1 (c), design section 5): up to 3 origin rows of the scored day for an
 * entity's TOP feature, masked at write time like Risk Score evidence. Fixture: the golden corpus with a unique
 * {@code txn} per row in Dataset {@code usage2(msisdn, event_time, mb, txn)}, the {@code msisdn} column classified
 * MSISDN; the evidence columns are {@code txn} (raw), {@code msisdn} (classified, so a token) and {@code mb}.
 */
class AnomalyEvidenceTest {

    private static Path cfg;
    private static Path data;
    private static ComponentStore store;
    private static ViewStore views;
    private static Function<String, String> relation;

    @BeforeAll
    static void plant(@TempDir Path dir) throws Exception {
        cfg = dir.resolve("config");
        data = dir.resolve("data");
        AnomalyCorpus.plant(cfg, data);
        store = new ComponentStore(cfg.resolve("registry"));
        views = new ViewStore(cfg.resolve("views"));
        Map<String, Object> ds = new LinkedHashMap<>();
        ds.put("physicalRef", "usage2");
        ds.put("columns", List.of(Map.of("name", "msisdn", "classification", "MSISDN")));
        store.write("dataset", "usage2", ds);
        Path out = data.resolve("usage2");
        Files.createDirectories(out);
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement()) {
            st.execute("COPY (SELECT msisdn, event_time, mb, row_number() OVER () AS txn FROM (" + AnomalyCorpus.ROWS
                    + ") u) TO '" + out.resolve("data.parquet").toString().replace('\\', '/') + "' (FORMAT PARQUET)");
        }
        relation = id -> {
            try {
                return DatasetRelation.relationSql(store.get("dataset", id).orElseThrow().content(), data, views);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };
    }

    private static AnomalyModel model(List<String> evidence) {
        Map<String, Object> m = AnomalyCorpus.model();
        List<Map<String, Object>> features = new ArrayList<>();
        for (Object o : (List<?>) m.get("features")) {
            @SuppressWarnings("unchecked") Map<String, Object> f = new LinkedHashMap<>((Map<String, Object>) o);
            f.put("dataset", "usage2");
            if (evidence != null) f.put("evidence", evidence);
            features.add(f);
        }
        m.put("features", features);
        return AnomalyModel.fromMap("usage_ev", m);
    }

    private static EvidenceMasker masker(AnomalyModel model) {
        return EvidenceMasker.of(store, cfg, model.datasetIds());
    }

    private static Map<String, AnomalyScorer.Scored> byKey(AnomalyScoreEvaluator.Run run) {
        return run.scored().stream().collect(Collectors.toMap(AnomalyScorer.Scored::entityKey, s -> s));
    }

    @Test
    void theTopFeatureCarriesUpToThreeMaskedOriginRows() throws Exception {
        AnomalyModel model = model(List.of("txn", "msisdn", "mb"));
        AnomalyScoreEvaluator.Run run = AnomalyScoreEvaluator.evaluate(model, AnomalyCorpus.AS_OF, relation,
                AnomalyScoreEvaluator.NONE, masker(model));
        assertFalse(run.evidenceTruncated());
        AnomalyScorer.Scored spike = byKey(run).get("spike");
        List<Map<String, Object>> evidence = spike.features().get(0).evidence();
        assertEquals(3, evidence.size(), "10 origin rows on the scored day, 3 kept");
        for (Map<String, Object> row : evidence) {
            assertEquals(List.of("txn", "msisdn", "mb"), List.copyOf(row.keySet()));
            assertTrue(String.valueOf(row.get("msisdn")).startsWith(EvidenceMasker.TOKEN_PREFIX),
                    "a classified column is stored as a token, never raw");
            assertNotEquals("spike", row.get("msisdn"));
            assertNotNull(row.get("txn"), "an unclassified column stays as it is");
        }
        for (int i = 1; i < spike.features().size(); i++)
            assertTrue(spike.features().get(i).evidence().isEmpty(), "only the top feature carries evidence");
        String stored = AnomalyScoreEvaluator.featuresJson(spike.features());
        assertFalse(stored.contains("\"spike\""), "the raw key is not in the explanation");
        assertTrue(stored.contains("\"evidence\""));
    }

    @Test
    void anEntityWhoseTopFeatureCarriesNoSignalGetsNoEvidenceAndAModelWithoutEvidenceGetsNone() throws Exception {
        AnomalyModel plain = model(null);
        AnomalyScoreEvaluator.Run run = AnomalyScoreEvaluator.evaluate(plain, AnomalyCorpus.AS_OF, relation);
        assertTrue(run.scored().stream().allMatch(s -> s.features().stream().allMatch(f -> f.evidence().isEmpty())));
        assertFalse(AnomalyScoreEvaluator.featuresJson(run.scored().get(0).features()).contains("evidence"),
                "no evidence key at all when none is declared (stored explanations are unchanged)");
        // drop: nothing on the scored day, so its top feature is insufficient or flat - it has no evidence rows to show
        AnomalyModel ev = model(List.of("txn"));
        AnomalyScorer.Scored drop = byKey(AnomalyScoreEvaluator.evaluate(ev, AnomalyCorpus.AS_OF, relation,
                AnomalyScoreEvaluator.NONE, masker(ev))).get("drop");
        assertTrue(drop.features().get(0).evidence().isEmpty());
    }

    @Test
    void evidenceWithoutAMaskerIsRefusedNotStoredRaw() {
        AnomalyModel model = model(List.of("msisdn"));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> AnomalyScoreEvaluator.evaluate(model, AnomalyCorpus.AS_OF, relation));
        assertTrue(e.getMessage().contains("unmasked"), e.getMessage());
    }

    @Test
    void aReadPastTheCapIsReportedAndScoresAreUnchanged() throws Exception {
        AnomalyModel model = model(List.of("txn"));
        AnomalyScoreEvaluator.Run capped = AnomalyScoreEvaluator.evaluate(model, AnomalyCorpus.AS_OF, relation,
                masker(model), 5);
        assertTrue(capped.evidenceTruncated());
        AnomalyScoreEvaluator.Run full = AnomalyScoreEvaluator.evaluate(model, AnomalyCorpus.AS_OF, relation,
                AnomalyScoreEvaluator.NONE, masker(model));
        assertEquals(full.scored().stream().map(s -> s.entityKey() + s.score()).toList(),
                capped.scored().stream().map(s -> s.entityKey() + s.score()).toList());
    }

    @Test
    void thePreviewCarriesTheSameMaskedEvidence() throws Exception {
        AnomalyModel model = model(List.of("txn", "msisdn"));
        AnomalyScoreEvaluator.Run p = AnomalyScoreEvaluator.preview(model, AnomalyCorpus.AS_OF, relation, "spike",
                SqlSandboxPolicy.withCaps("512MB", 2, 10), masker(model));
        List<Map<String, Object>> evidence = p.scored().get(0).features().get(0).evidence();
        assertEquals(3, evidence.size());
        assertTrue(String.valueOf(evidence.get(0).get("msisdn")).startsWith(EvidenceMasker.TOKEN_PREFIX));
        assertEquals(java.util.Set.of("data_mb", "sessions"), p.series().keySet(), "a per-day series per feature");
    }

    @Test
    void evidenceColumnsAreValidatedAtParse() {
        Map<String, Object> m = AnomalyCorpus.model();
        @SuppressWarnings("unchecked") Map<String, Object> f = new LinkedHashMap<>((Map<String, Object>) ((List<?>) m.get("features")).get(0));
        f.put("evidence", List.of("a b"));
        m.put("features", List.of(f));
        assertThrows(IllegalArgumentException.class, () -> AnomalyModel.fromMap("x", m));
        f.put("evidence", List.of("c1", "c2", "c3", "c4", "c5", "c6", "c7", "c8", "c9"));
        assertThrows(IllegalArgumentException.class, () -> AnomalyModel.fromMap("x", m));
        f.put("evidence", List.of("txn"));
        assertTrue(AnomalyModel.fromMap("x", m).referencedColumns().get("usage").contains("txn"));
    }
}
