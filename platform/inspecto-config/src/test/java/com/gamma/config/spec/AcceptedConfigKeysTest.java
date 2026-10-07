package com.gamma.config.spec;

import com.gamma.config.io.ConfigCodec;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The dead-property census (`DUCKLE-C3-DEAD-PROPERTY-1`): a block no component reads must produce a
 * coded ERROR, a near name must be suggested when there is one, and — the half that is easy to get
 * wrong — <b>nothing must be suggested when nothing is close</b>.
 */
class AcceptedConfigKeysTest {

    private static Map<String, Object> pipelineDraft() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("name", "orders");
        raw.put("dirs", new LinkedHashMap<>(Map.of("poll", "in", "database", "db")));
        raw.put("processing", new LinkedHashMap<>(Map.of("threads", 4)));
        return raw;
    }

    private static List<Finding> check(Map<String, Object> raw) {
        return AcceptedConfigKeys.unknownKeyFindings("pipeline", raw, Severity.ERROR);
    }

    @Test
    void aCleanDraftIsNotFlagged() {
        assertTrue(check(pipelineDraft()).isEmpty(), "a draft of declared blocks must produce no finding");
    }

    @Test
    void everyParserOnlyBlockIsAccepted() {
        // The engine-honoured, spec-undeclared blocks are read by SOMETHING, so none is dead.
        Map<String, Object> raw = pipelineDraft();
        raw.put("active", true);
        raw.put("steps", List.of());
        raw.put("trigger", new LinkedHashMap<>(Map.of("every", "30s")));
        @SuppressWarnings("unchecked")
        Map<String, Object> proc = (Map<String, Object>) raw.get("processing");
        proc.put("summarize", new LinkedHashMap<>(Map.of("group_by", List.of("region"))));
        proc.put("disabled_steps", List.of("dedup"));
        assertTrue(check(raw).isEmpty(), "a parser-only block is read by the engine and must not be flagged");
    }

    @Test
    void aDeadTopLevelBlockIsAnErrorWithAStableCode() {
        Map<String, Object> raw = pipelineDraft();
        raw.put("banana", "yellow");
        List<Finding> findings = check(raw);
        assertEquals(1, findings.size(), "exactly one finding for one dead block: " + findings);
        Finding f = findings.getFirst();
        assertEquals(Severity.ERROR, f.severity());
        assertEquals("banana", f.fieldPath());
        assertEquals(FindingCodes.ERR_UNKNOWN_CONFIG_KEY, f.code());
    }

    @Test
    void suggestsTheNearName() {
        Map<String, Object> raw = pipelineDraft();
        raw.put("procesing", new LinkedHashMap<>());   // one deletion away from `processing`
        Finding f = check(raw).stream().filter(x -> x.fieldPath().equals("procesing")).findFirst().orElseThrow();
        assertTrue(f.guidance().contains("did you mean 'processing'"),
                "a one-edit typo must name the key the author meant; got: " + f.guidance());
    }

    /**
     * 🔴 The row's explicit requirement. A message that volunteers an unrelated name for a word that is
     * not a typo of anything sends the author to the wrong key — worse than saying nothing.
     */
    @Test
    void offersNoSuggestionWhenNothingIsClose() {
        Map<String, Object> raw = pipelineDraft();
        raw.put("banana", "yellow");
        Finding f = check(raw).getFirst();
        assertFalse(f.guidance().contains("did you mean"),
                "nothing accepted is close to 'banana', so no name may be guessed; got: " + f.guidance());
        assertTrue(f.guidance().contains("no accepted key is close"));
        assertTrue(AcceptedConfigKeys.nearestName("banana", AcceptedConfigKeys.acceptedBlocks("pipeline")).isEmpty());
    }

    @Test
    void suggestionsStayAtTheSameDepth() {
        // `procesing` must never be told to try `processing.dedup`: that is not a legal top-level key.
        Finding f = check(new LinkedHashMap<>(Map.of("procesing", Map.of()))).getFirst();
        assertFalse(f.guidance().contains("processing.dedup"), f.guidance());
    }

    @Test
    void aDeadSubBlockUnderAnAcceptedBlockIsFlagged() {
        Map<String, Object> raw = pipelineDraft();
        @SuppressWarnings("unchecked")
        Map<String, Object> proc = (Map<String, Object>) raw.get("processing");
        proc.put("dedupe", Map.of());   // the block is `processing.dedup`
        Finding f = check(raw).stream().filter(x -> x.fieldPath().equals("processing.dedupe"))
                .findFirst().orElseThrow();
        assertTrue(f.guidance().contains("did you mean 'processing.dedup'"),
                "a nested suggestion must be written as a full path the author can use; got: " + f.guidance());
    }

    /**
     * 🔴 `dirs` is accepted WHOLE even though it HAS declared leaves. Its other leaves
     * ({@code errors}, {@code quarantine}, {@code markers}, {@code log_dir}) are engine-read and carry
     * no {@code FieldSpec}, so a checker that descended on "this block has a declared sub-block" would
     * refuse four keys that work today. Only {@code processing} is censused at leaf level.
     */
    @Test
    void aBlockThatIsNotCensusedAtLeafLevelIsNotDescendedInto() {
        Map<String, Object> raw = pipelineDraft();
        @SuppressWarnings("unchecked")
        Map<String, Object> dirs = (Map<String, Object>) raw.get("dirs");
        dirs.put("quarantine", "q");
        dirs.put("markers", "m");
        raw.put("collector", new LinkedHashMap<>(Map.of("etag_header", "x-amz-meta-etag")));
        assertTrue(check(raw).isEmpty(),
                "engine-read leaves inside a whole-accepted block must not be flagged: " + check(raw));
    }

    @Test
    void xPrefixedKeysAreAccepted() {
        Map<String, Object> raw = pipelineDraft();
        raw.put("x-owner", "platform-team");
        @SuppressWarnings("unchecked")
        Map<String, Object> proc = (Map<String, Object>) raw.get("processing");
        proc.put("x-note", "tuned 2026-09");
        assertTrue(check(raw).isEmpty(), "an x- block is the author's own annotation and is never dead");
    }

    /** An `x-` key is only a safe escape hatch if the codec actually carries it through a save. */
    @Test
    void xPrefixedKeysRoundTripThroughTheCodecUntouched() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("name", "orders");
        raw.put("x-owner", "platform-team");
        Map<String, Object> back = ConfigCodec.toMap(ConfigCodec.toToon(raw));
        assertEquals("platform-team", back.get("x-owner"), "x- keys must survive encode/decode verbatim");
    }

    @Test
    void aTypeWithNoCensusIsCheckedNotAtAll() {
        // Fail-open BY OMISSION and stated: `widget` has no parser census, so nothing is known to be
        // dead. (This test used `alert` as its example until alert GAINED a census — see below.)
        assertFalse(AcceptedConfigKeys.hasCensus("widget"));
        assertTrue(AcceptedConfigKeys.unknownKeyFindings("widget",
                new LinkedHashMap<>(Map.of("banana", "yellow")), Severity.ERROR).isEmpty());
    }

    // ── the `alert` census (the second type to get one) ──────────────────────────

    private static Map<String, Object> alertDraft(Map<String, Object> alertBlock) {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("alert", new LinkedHashMap<>(alertBlock));
        return raw;
    }

    @Test
    void theAlertTypeHasACensus() {
        assertTrue(AcceptedConfigKeys.hasCensus("alert"));
    }

    @Test
    void aLiveLedgerMetricAlertIsAccepted() {
        // spaces/demo/config/registry/alert-rules/orders_stream_failures.toon, verbatim.
        Map<String, Object> raw = alertDraft(Map.of(
                "name", "orders_stream_failures", "metric", "rejected_files", "comparator", "gt",
                "threshold", 3, "window", "1h", "onPipeline", "orders", "severity", "CRITICAL"));
        assertTrue(AcceptedConfigKeys.unknownKeyFindings("alert", raw, Severity.ERROR).isEmpty(),
                "a shipped sample alert config must not be refused by its own census");
    }

    @Test
    void aLiveMeasureAlertIsAccepted() {
        // spaces/demo/config/registry/alert-rules/orders_low_volume.toon — the BI-5 shape, whose `dataset:` and
        // `measure:` keys the spec does NOT declare. This is the case a spec-only census would break.
        Map<String, Object> raw = alertDraft(Map.of(
                "name", "orders_low_volume", "dataset", "orders_dataset", "measure", "count",
                "comparator", "lt", "threshold", 10, "severity", "WARNING"));
        assertTrue(AcceptedConfigKeys.unknownKeyFindings("alert", raw, Severity.ERROR).isEmpty(),
                "alert.dataset/alert.measure are parser-only but LIVE — refusing them would break "
                        + "every measure rule authored today");
    }

    @Test
    void aDeadAlertKeyIsRefusedWithASuggestion() {
        Map<String, Object> raw = alertDraft(Map.of(
                "name", "x", "metric", "error_rate", "comparator", "gt", "threshold", 1,
                "window", "1h", "severity", "WARNING", "thresold", 5));
        List<Finding> findings = AcceptedConfigKeys.unknownKeyFindings("alert", raw, Severity.ERROR);
        assertEquals(1, findings.size(), "exactly the one dead key is refused: " + findings);
        Finding f = findings.get(0);
        assertEquals("alert.thresold", f.fieldPath());
        assertEquals(FindingCodes.ERR_UNKNOWN_CONFIG_KEY, f.code());
        assertTrue(f.guidance().contains("alert.threshold"),
                "a near-name typo must be suggested at the right depth, got: " + f.guidance());
    }

    @Test
    void anExtensionKeyInsideTheAlertBlockRoundTrips() {
        Map<String, Object> raw = alertDraft(Map.of(
                "name", "x", "metric", "error_rate", "comparator", "gt", "threshold", 1,
                "window", "1h", "severity", "WARNING", "x-owner", "platform-team"));
        assertTrue(AcceptedConfigKeys.unknownKeyFindings("alert", raw, Severity.ERROR).isEmpty(),
                "the x- escape hatch must work one level down, not only at the top level");
    }

    @Test
    void theAlertCensusDoesNotLeakIntoPipelineConfigs() {
        // A pipeline carrying a top-level `alert:` block is dead-key territory for the PIPELINE census;
        // the alert census must not quietly accept it. Guards the per-type censused-parent switch.
        Map<String, Object> raw = pipelineDraft();
        raw.put("alert", new LinkedHashMap<>(Map.of("name", "x")));
        List<Finding> findings = AcceptedConfigKeys.unknownKeyFindings("pipeline", raw, Severity.ERROR);
        assertTrue(findings.stream().anyMatch(f -> "alert".equals(f.fieldPath())),
                "a top-level alert: block in a pipeline must still be refused, got: " + findings);
    }

    // ── the `meta` census (the third type to get one) ────────────────────────────

    /** {@code spaces/demo/config/orders/orders_meta.toon}'s top level, verbatim. */
    private static Map<String, Object> metaDraft() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("name", "orders_semantics");
        raw.put("version", 1);
        raw.put("tables", new LinkedHashMap<>(Map.of("orders", Map.of("description", "d", "grain", "g"))));
        raw.put("kpis", new LinkedHashMap<>(Map.of("gross_revenue", Map.of("definition", "sum(amount)"))));
        raw.put("reports", new LinkedHashMap<>(Map.of("daily_orders", Map.of("description", "d"))));
        raw.put("domain", new LinkedHashMap<>(Map.of("currency", "USD")));
        return raw;
    }

    @Test
    void theMetaTypeHasACensus() {
        assertTrue(AcceptedConfigKeys.hasCensus("meta"));
    }

    @Test
    void aShippedMetaConfigIsAccepted() {
        assertTrue(AcceptedConfigKeys.unknownKeyFindings("meta", metaDraft(), Severity.ERROR).isEmpty(),
                "both committed *_meta.toon files carry exactly these six keys — the census must not "
                        + "refuse a config that runs today");
    }

    @Test
    void aDeadMetaKeyIsRefusedWithASuggestion() {
        Map<String, Object> raw = metaDraft();
        raw.put("kpi", Map.of("x", "y"));
        List<Finding> findings = AcceptedConfigKeys.unknownKeyFindings("meta", raw, Severity.ERROR);
        assertEquals(1, findings.size(), "exactly the one dead key is refused: " + findings);
        Finding f = findings.getFirst();
        assertEquals("kpi", f.fieldPath());
        assertEquals(FindingCodes.ERR_UNKNOWN_CONFIG_KEY, f.code());
        assertTrue(f.guidance().contains("kpis"),
                "a near-name typo must be suggested, got: " + f.guidance());
    }

    @Test
    void authorChosenKpiAndReportNamesAreNeverDescendedInto() {
        // 🔴 The load-bearing constraint for `meta`: `SemanticModel.load` iterates entrySet() over
        // `tables`/`kpis`/`reports`, whose keys are names the AUTHOR invents. Descending one level
        // would refuse every one of them. `censusedParents("meta")` is empty for exactly this reason.
        // ⚠ Honest scope, mutation-checked: adding `meta` to `censusedParents` ALONE does NOT turn this
        // red, because the checker skips a parent with no accepted sub-blocks. It goes red as soon as
        // that is paired with a declared `kpis.*` leaf — which is precisely the change this guards.
        Map<String, Object> raw = metaDraft();
        raw.put("kpis", new LinkedHashMap<>(Map.of("a_kpi_nobody_could_predict", Map.of("definition", "1"))));
        raw.put("tables", new LinkedHashMap<>(Map.of("some_table_ref", Map.of("grain", "day"))));
        assertTrue(AcceptedConfigKeys.unknownKeyFindings("meta", raw, Severity.ERROR).isEmpty(),
                "author-chosen KPI/table names must not be flagged");
    }

    @Test
    void theWarningSeverityCarriesTheWarnCode() {
        Map<String, Object> raw = pipelineDraft();
        raw.put("banana", "yellow");
        Finding f = AcceptedConfigKeys.unknownKeyFindings("pipeline", raw, Severity.WARNING).getFirst();
        assertEquals(Severity.WARNING, f.severity());
        assertEquals(FindingCodes.WARN_UNKNOWN_CONFIG_KEY, f.code());
    }
}
