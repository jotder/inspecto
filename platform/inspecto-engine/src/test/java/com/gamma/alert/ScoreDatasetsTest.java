package com.gamma.alert;

import com.gamma.analytics.score.ScoreColumns;
import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A model's outputs as system Datasets (`BI-INSIGHTS-1` P0, I-14). */
class ScoreDatasetsTest {

    private static final List<ScoreColumns.Column> KIND_COLUMNS = List.of(ScoreColumns.Column.measure("score", "DOUBLE"));
    private static final List<Map<String, Object>> FINANCE_SHARES = List.of(Map.of("subjectType", "role", "subjectId", "finance"));
    private static final Map<String, Object> FINANCE = Map.of("owner", "alice", "shares", FINANCE_SHARES);
    private static final List<ScoreDatasets.Input> INPUTS = List.of(new ScoreDatasets.Input("calls", "msisdn"));

    @TempDir Path data;
    @TempDir Path space;
    private ComponentStore store;

    @BeforeEach
    void written() throws Exception {
        store = new ComponentStore(space.resolve("config").resolve("registry"));
        calls(Map.of());
        for (String dir : List.of("risk_scores_fraud", "risk_scores_fraud_latest"))
            ScoreOutputDirs.claim(data.resolve(dir), RiskScoreOutputs.SCORE_KIND.ownerMarker(), "risk-score", "fraud");
    }

    /** {@code calls}, whose msisdn column is classified MSISDN, with {@code envelope} on top. */
    private void calls(Map<String, Object> envelope) throws Exception {
        Map<String, Object> ds = new LinkedHashMap<>(Map.of("physicalRef", "calls", "columns", List.of(
                Map.of("name", "msisdn", "classification", "MSISDN"), Map.of("name", "minutes"))));
        ds.putAll(envelope);
        store.write("dataset", "calls", ds);
    }

    private String register(String entityType, Map<String, Object> model) {
        return ScoreDatasets.register(store, data, RiskScoreOutputs.SCORE_KIND, "fraud", entityType, KIND_COLUMNS,
                model, INPUTS);
    }

    private Map<String, Object> dataset(String id) {
        return store.get("dataset", id).map(ComponentRegistry.Component::content).orElse(null);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> entityKey(String id) {
        return ((List<Map<String, Object>>) dataset(id).get("columns")).get(2);
    }

    @Test
    void bothOutputsAreRegisteredWithEveryColumnAndItsRole() {
        assertNull(register("subscriber", Map.of()));

        for (String id : List.of("risk_scores_fraud", "risk_scores_fraud_latest")) {
            Map<String, Object> ds = dataset(id);
            assertEquals(id, ds.get("physicalRef"));
            assertEquals("system", ds.get("origin"));
            @SuppressWarnings("unchecked") List<Map<String, Object>> cols = (List<Map<String, Object>>) ds.get("columns");
            assertEquals(List.of("model", "entity_type", "entity_key", "score", "model_version", "run_id", "scored_at"),
                    cols.stream().map(c -> c.get("name")).toList());
            assertEquals("entity", cols.get(2).get("role"));
            assertEquals("measure", cols.get(3).get("role"));
            assertEquals("temporal", cols.get(6).get("role"));
        }
    }

    /** The telco-bypass template: entityType sim over an msisdn key classified MSISDN — the key is an MSISDN. */
    @Test
    void theEntityKeyTakesTheStricterOfTheEntityTypeAndTheInputKeysClass() {
        assertNull(register("sim", Map.of()));
        assertEquals("MSISDN", entityKey("risk_scores_fraud_latest").get("classification"));
    }

    @Test
    void anUnclassifiedKeyKeepsTheModelsEntityType() throws Exception {
        store.write("dataset", "calls", Map.of("physicalRef", "calls", "columns", List.of(Map.of("name", "msisdn"))));
        assertNull(register("subscriber", Map.of()));
        assertEquals("subscriber", entityKey("risk_scores_fraud").get("classification"));
    }

    @Test
    void aKeyWhoseDatasetCannotBeTracedRegistersNothing() {
        String reason = ScoreDatasets.register(store, data, RiskScoreOutputs.SCORE_KIND, "fraud", "subscriber",
                KIND_COLUMNS, Map.of(), List.of(new ScoreDatasets.Input("gone", "msisdn")));

        assertNotNull(reason);
        assertNull(dataset("risk_scores_fraud"));
        assertNull(dataset("risk_scores_fraud_latest"));
    }

    @Test
    void sourcesSharedDifferentlyNarrowWhatAnEarlierRunRegistered() throws Exception {
        assertNull(register("subscriber", FINANCE));
        assertEquals(FINANCE_SHARES, dataset("risk_scores_fraud_latest").get("shares"));

        calls(Map.of("owner", "alice", "shares", List.of(Map.of("subjectType", "role", "subjectId", "sales"))));
        String reason = register("subscriber", FINANCE);

        assertNotNull(reason);
        assertTrue(reason.contains("shared differently"), reason);
        for (String id : List.of("risk_scores_fraud", "risk_scores_fraud_latest")) {
            assertEquals(List.of(), dataset(id).get("shares"), id + " narrowed to the owner");
            assertEquals("alice", dataset(id).get("owner"));
        }
    }

    /** The model's owner keeps the narrowed outputs only if they may see every input; otherwise no one owns them. */
    @Test
    void narrowingDropsAnOwnerWhoCannotSeeAnInput() throws Exception {
        assertNull(register("subscriber", FINANCE));
        calls(Map.of("owner", "bob", "shares", List.of(Map.of("subjectType", "role", "subjectId", "sales"))));

        assertNotNull(register("subscriber", FINANCE));

        assertEquals(List.of(), dataset("risk_scores_fraud_latest").get("shares"));
        assertFalse(dataset("risk_scores_fraud_latest").containsKey("owner"), "alice cannot read calls: not the owner");
    }

    @Test
    void anUntraceableKeyNarrowsWhatAnEarlierRunRegistered() throws Exception {
        assertNull(register("subscriber", FINANCE));
        store.delete("dataset", "calls");   // the key's Dataset is gone: its class cannot be traced

        String reason = register("subscriber", FINANCE);

        assertNotNull(reason);
        assertEquals(List.of(), dataset("risk_scores_fraud").get("shares"));
    }

    @Test
    void anUnsharedModelOverAnUnsharedDatasetIsOpenAndASharedOneNarrowsItNextRun() {
        assertNull(register("subscriber", Map.of()));
        assertFalse(dataset("risk_scores_fraud").containsKey("shares"));

        assertNull(register("subscriber", FINANCE));
        assertEquals(FINANCE_SHARES, dataset("risk_scores_fraud").get("shares"), "re-enveloped");
    }

    @Test
    void aMappingInputsBaseJoinsTheEnvelope() throws Exception {
        store.write("dataset", "base", Map.of("physicalRef", "calls", "owner", "alice", "shares", FINANCE_SHARES));
        store.write("dataset", "calls", Map.of("origin", "mapping", "basedOn", "base", "physicalRef", "calls",
                "columns", List.of(Map.of("name", "msisdn", "classification", "MSISDN"))));

        assertNull(register("subscriber", Map.of()));

        assertEquals(FINANCE_SHARES, dataset("risk_scores_fraud_latest").get("shares"));
    }

    /** BI-INSIGHTS-1 loose ends A: the base two Mapping hops down still bounds the outputs. */
    @Test
    void aMappingChainsBaseSharedDifferentlyRefuses() throws Exception {
        store.write("dataset", "base", Map.of("physicalRef", "calls", "owner", "alice",
                "shares", List.of(Map.of("subjectType", "role", "subjectId", "sales"))));
        store.write("dataset", "mid", Map.of("origin", "mapping", "basedOn", "base", "physicalRef", "calls"));
        store.write("dataset", "calls", Map.of("origin", "mapping", "basedOn", "mid", "physicalRef", "calls",
                "columns", List.of(Map.of("name", "msisdn", "classification", "MSISDN"))));

        String reason = register("subscriber", FINANCE);

        assertNotNull(reason);
        assertTrue(reason.contains("shared differently"), reason);
    }

    @Test
    void aBareLatestFromATemplateIsAdopted() throws Exception {
        store.write("dataset", "risk_scores_fraud_latest", Map.of("physicalRef", "risk_scores_fraud_latest"));

        assertNull(register("subscriber", FINANCE));

        assertEquals("system", dataset("risk_scores_fraud_latest").get("origin"));
        assertEquals(FINANCE_SHARES, dataset("risk_scores_fraud_latest").get("shares"));
    }

    @Test
    void aHandDeclaredDatasetIsNeverTouched() throws Exception {
        store.write("dataset", "risk_scores_fraud_latest", Map.of("physicalRef", "risk_scores_fraud_latest",
                "description", "mine"));

        assertNull(register("subscriber", FINANCE));

        assertEquals("mine", dataset("risk_scores_fraud_latest").get("description"));
        assertFalse(dataset("risk_scores_fraud_latest").containsKey("origin"));
    }

    @Test
    void aDatasetOfThatIdOverAnotherStoreIsReportedAndLeftAlone() throws Exception {
        store.write("dataset", "risk_scores_fraud", Map.of("physicalRef", "calls"));

        String reason = register("subscriber", Map.of());

        assertNotNull(reason);
        assertTrue(reason.contains("reads another store"), reason);
        assertEquals("calls", dataset("risk_scores_fraud").get("physicalRef"));
        assertEquals("system", dataset("risk_scores_fraud_latest").get("origin"), "the other one still registers");
    }

    @Test
    void anOutputTheModelDidNotWriteIsNotRegistered(@TempDir Path empty) {
        String reason = ScoreDatasets.register(store, empty, RiskScoreOutputs.SCORE_KIND, "fraud", "subscriber",
                KIND_COLUMNS, Map.of(), INPUTS);

        assertNotNull(reason);
        assertNull(dataset("risk_scores_fraud"));
        assertNull(dataset("risk_scores_fraud_latest"));
    }
}
