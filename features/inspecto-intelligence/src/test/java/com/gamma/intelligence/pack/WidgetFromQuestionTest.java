package com.gamma.intelligence.pack;

import com.eoiagent.core.RunId;
import com.eoiagent.core.ToolCall;
import com.eoiagent.core.ToolResult;
import com.eoiagent.tool.Tool;
import com.gamma.pipeline.ComponentStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** AQ-0 (BI-INSIGHTS-1 P4): {@code widget_from_question} - closed vocabulary, refusals in words, draft only. */
class WidgetFromQuestionTest {

    private static final Set<String> AUTHOR = Set.of("canAuthorWorkbench");
    private static final Set<String> REVEALER = Set.of("canAuthorWorkbench", "canRevealLinkEntities");
    private static final Set<String> ACCESS = Set.of("canAuthorWorkbench", "canConfigureAccess");
    private static final String RAW = "905551234567";

    @TempDir Path dir;
    private ComponentStore store;
    private Tool tool;

    @BeforeEach
    void seed() throws Exception {
        store = new ComponentStore(dir.resolve("registry"));
        store.write("dataset", "sales", Map.of("physicalRef", "sales", "columns", List.of(
                Map.of("name", "revenue", "type", "DOUBLE", "role", "measure"),
                Map.of("name", "region", "type", "VARCHAR", "role", "dimension", "sample", RAW),
                Map.of("name", "product", "type", "VARCHAR", "role", "dimension"),
                Map.of("name", "channel", "type", "VARCHAR", "role", "dimension"),
                Map.of("name", "day", "type", "DATE", "role", "temporal"),
                Map.of("name", "msisdn", "type", "VARCHAR", "role", "dimension", "classification", "MSISDN"))));
        // a restricted Dataset: only its owner / share-listed users / an access administrator see it
        store.write("dataset", "payroll", Map.of("physicalRef", "payroll", "owner", "alice", "shares", List.of(),
                "columns", List.of(Map.of("name", "salary", "type", "DOUBLE", "role", "measure"),
                        Map.of("name", "grade", "type", "VARCHAR", "role", "dimension"))));
        tool = tool(store);
    }

    private static Tool tool(ComponentStore components) {
        return InspectoTools.tools(null, components, List::of).stream()
                .filter(t -> t.spec().name().equals(WidgetFromQuestion.TOOL)).findFirst().orElseThrow();
    }

    private static ToolResult as(Tool t, Set<String> caps, Map<String, Object> args) {
        return ToolCaller.with(ToolCaller.of(caps), () -> t.invoke(new ToolCall(WidgetFromQuestion.TOOL, args, new RunId("t"))));
    }

    private ToolResult ask(Map<String, Object> args) {
        return as(tool, AUTHOR, args);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> value(ToolResult r) {
        assertTrue(r.ok(), () -> String.valueOf(r.error()));
        return (Map<String, Object>) r.value();
    }

    private static Map<String, Object> m(String agg, String field) {
        return field == null ? Map.of("agg", agg) : Map.of("agg", agg, "field", field);
    }

    private static void refused(ToolResult r, String code, String... words) {
        assertFalse(r.ok(), "expected a refusal");
        assertEquals(code, r.meta().get("refusal"));
        for (String w : words) assertTrue(r.error().contains(w), () -> "'" + w + "' missing from: " + r.error());
    }

    // ── the vocabulary the model sees ───────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void vocabularyGoldenMapsColumnRolesToTheirAllowedAggregatesAndCarriesNoValues() {
        Map<String, Object> v = value(ask(Map.of()));
        List<Map<String, Object>> datasets = (List<Map<String, Object>>) v.get("datasets");
        assertEquals(List.of("sales"), datasets.stream().map(d -> d.get("id")).toList(),
                "the restricted payroll Dataset is not listed for this caller");
        Map<String, Map<String, Object>> cols = new java.util.LinkedHashMap<>();
        for (Map<String, Object> c : (List<Map<String, Object>>) datasets.get(0).get("columns")) cols.put((String) c.get("name"), c);

        assertEquals(List.of("count", "countDistinct", "min", "max", "sum", "avg", "median", "stddev", "variance",
                "p05", "p25", "p75", "p90", "p95", "p99"), cols.get("revenue").get("aggregates"));
        assertEquals("measure", cols.get("revenue").get("role"));
        assertEquals("number", cols.get("revenue").get("type"));
        assertEquals(List.of("count", "countDistinct"), cols.get("region").get("aggregates"), "a dimension is counted, never summed");
        assertEquals(List.of("count", "countDistinct", "min", "max"), cols.get("day").get("aggregates"));
        assertEquals("temporal", cols.get("day").get("role"));
        assertEquals(2, v.get("maxBreakdowns"));
        assertEquals(List.of("auto", "day", "week", "month"), v.get("grains"));

        // no values, no rows: the only strings are names, roles, types and fixed vocabulary
        assertFalse(v.toString().contains(RAW), "a stored sample value never reaches the model's view");
        assertFalse(cols.get("region").containsKey("sample"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aMaskedColumnIsListedByNameOnlyAndFlagged() {
        Map<String, Object> v = value(ask(Map.of()));
        Map<String, Object> msisdn = ((List<Map<String, Object>>) ((Map<String, Object>)
                ((List<?>) v.get("datasets")).get(0)).get("columns")).stream()
                .filter(c -> "msisdn".equals(c.get("name"))).findFirst().orElseThrow();
        assertEquals(Boolean.TRUE, msisdn.get("masked"));
        assertNull(msisdn.get("role"));
        assertNull(msisdn.get("type"));
        assertEquals(List.of("count", "countDistinct"), msisdn.get("aggregates"));
    }

    @Test
    void theSchemaBlockSurvivesEgressMaskingWithNoValueInIt() {
        EgressMasking egress = new EgressMasking(() -> false, () -> dir);
        Tool masked = egress.decorate(tool);
        ToolResult r = ToolCaller.with(ToolCaller.of(AUTHOR), () -> ToolCaller.modelBound(() ->
                masked.invoke(new ToolCall(WidgetFromQuestion.TOOL, Map.of(), new RunId("t")))));
        String text = String.valueOf(r.value());
        assertTrue(r.ok());
        assertFalse(text.contains(RAW), "no data value in what a hosted model is sent");
        assertFalse(text.contains("masked:"), "the vocabulary holds names, so nothing needed tokenising");
        assertTrue(text.contains("msisdn"), "the masked column is still listed by name");
    }

    // ── who may see what (ToolCaller carries capabilities, not a user id) ───────

    @Test
    void aRestrictedDatasetIsHiddenUnlessTheCallerAdministersAccess() {
        assertFalse(value(as(tool, AUTHOR, Map.of())).toString().contains("payroll"));
        assertTrue(value(as(tool, ACCESS, Map.of())).toString().contains("payroll"));
        assertTrue(value(as(tool, null, Map.of())).toString().contains("payroll"), "no Subject (Basic) sees every Dataset");
        // unbound = nothing was bound on this thread: fail closed
        ToolResult unbound = tool.invoke(new ToolCall(WidgetFromQuestion.TOOL, Map.of(), new RunId("t")));
        assertFalse(value(unbound).toString().contains("payroll"));
    }

    @Test
    void aHiddenDatasetAndAMissingOneAreRefusedIdentically() {
        Map<String, Object> hidden = Map.of("dataset", "payroll", "measures", List.of(m("sum", "salary")));
        Map<String, Object> missing = Map.of("dataset", "nothing_here", "measures", List.of(m("sum", "salary")));
        ToolResult a = ask(hidden), b = ask(missing);
        refused(a, "unknown-dataset", "I can't see a dataset called");
        refused(b, "unknown-dataset", "I can't see a dataset called");
        assertEquals(a.error().replace("payroll", "X"), b.error().replace("nothing_here", "X"), "same words either way");
    }

    @Test
    void aColumnOnlyAHiddenDatasetHasIsNeverConfirmed() {
        ToolResult r = ask(Map.of("measures", List.of(m("sum", "salary"))));
        refused(r, "unknown-column", "I can't see a column called 'salary'");
        assertFalse(r.error().contains("payroll"), "never names the hidden Dataset");
        assertFalse(r.error().contains("grade"));
    }

    // ── refusals of §6.4, in words ──────────────────────────────────────────────

    @Test
    void whyDidItMoveIsReferredToExplainThisChange() {
        refused(ask(Map.of("question", "why did revenue drop last week", "measures", List.of(m("sum", "revenue")))),
                "cause", "Explain this change");
        refused(ask(Map.of("intent", "cause", "measures", List.of(m("sum", "revenue")))), "cause");
    }

    @Test
    void aForecastIsReferredToTheForecastBand() {
        refused(ask(Map.of("question", "forecast revenue for next month", "measures", List.of(m("sum", "revenue")))),
                "forecast", "forecast band");
    }

    @Test
    void moreThanTwoBreakdownsJoinsAndCalculatedMeasuresAreRefusedWithTheVocabularyListed() {
        refused(ask(Map.of("measures", List.of(m("sum", "revenue")), "groupBy", List.of("region", "product", "channel"))),
                "unsupported", "up to 2 breakdowns", "sum");
        refused(ask(Map.of("intent", "join", "measures", List.of(m("sum", "revenue")))), "unsupported", "joins");
        refused(ask(Map.of("intent", "calculated", "measures", List.of(m("sum", "revenue")))), "unsupported", "calculated");
    }

    @Test
    void severalDatasetsFittingAskWhichRatherThanPickingOne() throws Exception {
        store.write("dataset", "sales_eu", Map.of("physicalRef", "sales_eu", "columns", List.of(
                Map.of("name", "revenue", "type", "DOUBLE", "role", "measure"),
                Map.of("name", "region", "type", "VARCHAR", "role", "dimension"))));
        refused(ask(Map.of("measures", List.of(m("sum", "revenue")), "groupBy", List.of("region"))),
                "which-dataset", "sales", "sales_eu");
        // naming it resolves it; a column only one has resolves it too
        value(ask(Map.of("dataset", "sales_eu", "measures", List.of(m("sum", "revenue")), "groupBy", List.of("region"))));
        assertEquals("sales", ((Map<?, ?>) value(ask(Map.of("measures", List.of(m("sum", "revenue")),
                "groupBy", List.of("product")))).get("query")).get("dataset"));
    }

    // ── never guessed ───────────────────────────────────────────────────────────

    @Test
    void anUnknownColumnIsRefusedWithTheNearestVisibleNamesAndNeverGuessed() {
        ToolResult r = ask(Map.of("dataset", "sales", "measures", List.of(m("sum", "revnue"))));
        refused(r, "unknown-column", "I can't see a column called 'revnue'", "revenue");
        assertFalse(r.error().contains("msisdn"), "a masked column is never offered as a suggestion");
    }

    @Test
    @SuppressWarnings("unchecked")
    void anUnknownFilterValueIsCarriedVerbatimNotCorrectedToAKnownOne() {
        Map<String, Object> out = value(ask(Map.of("dataset", "sales", "measures", List.of(m("sum", "revenue")),
                "filters", List.of(Map.of("field", "region", "op", "=", "value", "emea-ish")))));
        List<Map<String, Object>> f = (List<Map<String, Object>>) ((Map<String, Object>) out.get("query")).get("filters");
        assertEquals("emea-ish", f.get(0).get("value"));
    }

    // ── a model that leaves the vocabulary ──────────────────────────────────────

    @Test
    void aFakeModelAskingForAnAggregateOutsideTheVocabularyIsRefused() {
        refused(ask(Map.of("dataset", "sales", "measures", List.of(m("mode", "revenue")))), "unsupported-aggregate", "'mode'");
        refused(ask(Map.of("dataset", "sales", "measures", List.of(m("sumproduct", "revenue")))), "unsupported-aggregate");
        // an aggregate that exists but does not suit the column's role
        refused(ask(Map.of("dataset", "sales", "measures", List.of(m("sum", "region")))), "unsupported-aggregate", "dimension");
        refused(ask(Map.of("dataset", "sales", "measures", List.of(m("avg", "day")))), "unsupported-aggregate", "temporal");
    }

    // ── masking, as /bi/query ───────────────────────────────────────────────────

    @Test
    void aMeasureOrFilterOverAMaskedColumnIsRefusedForACallerWhoCannotReveal() {
        refused(ask(Map.of("dataset", "sales", "measures", List.of(m("sum", "msisdn")))), "masked-column", "msisdn", "canRevealLinkEntities");
        refused(ask(Map.of("dataset", "sales", "measures", List.of(m("count", null)),
                "filters", List.of(Map.of("field", "msisdn", "op", "=", "value", RAW)))), "masked-column", "filtering");
        // as /bi/query: counting and grouping a masked key stay allowed (the rows are aliased at run time)
        value(ask(Map.of("dataset", "sales", "measures", List.of(m("countDistinct", "msisdn")))));
        value(ask(Map.of("dataset", "sales", "measures", List.of(m("sum", "revenue")), "groupBy", List.of("msisdn"))));
        // a revealer may
        value(as(tool, REVEALER, Map.of("dataset", "sales", "measures", List.of(m("count", null)),
                "filters", List.of(Map.of("field", "msisdn", "op", "=", "value", RAW)))));
    }

    // ── the draft ───────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void theResultIsADraftWidgetWithATemplatedCaptionAndPersistsNothing() throws Exception {
        Map<String, Object> out = value(ask(Map.of("dataset", "sales",
                "measures", List.of(m("sum", "revenue")), "groupBy", List.of("region"),
                "window", Map.of("days", 56), "compare", "average", "topN", 5)));
        assertEquals("widget", out.get("kind"));
        assertEquals(Boolean.TRUE, out.get("clean"), () -> String.valueOf(out.get("findings")));
        assertEquals(Boolean.FALSE, out.get("persisted"));
        assertEquals("Sum of revenue by region, top 5, last 56 days, compared with the average", out.get("caption"));
        Map<String, Object> draft = (Map<String, Object>) out.get("draft");
        assertEquals("bar", draft.get("vizType"));
        assertEquals("sales", draft.get("datasetId"));
        Map<String, Object> options = (Map<String, Object>) draft.get("options");
        assertEquals("average", options.get("compareKind"));
        assertEquals(5, options.get("limit"));
        Map<String, Object> query = (Map<String, Object>) out.get("query");
        Map<String, Object> window = (Map<String, Object>) query.get("window");
        assertEquals("day", window.get("field"), "the single date column was the window's");
        assertEquals(56, window.get("days"));
        assertNotNull(window.get("asOf"), "a trailing window is anchored to today");
        assertEquals(5, query.get("limit"));
        assertFalse(out.toString().toLowerCase().contains("select "), "no SQL text for the model to see or write");

        try (Stream<Path> files = Files.walk(dir)) {
            assertTrue(files.noneMatch(p -> p.toString().contains("widget")), "nothing was written under the Space");
        }
        assertTrue(store.list("widget").isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void aDateBreakdownDrawsALineWithItsGrainAndAMeasureAloneDrawsATile() {
        Map<String, Object> line = value(ask(Map.of("dataset", "sales", "measures", List.of(m("sum", "revenue")),
                "groupBy", List.of("day"), "grain", "week", "compare", "lastYear")));
        Map<String, Object> draft = (Map<String, Object>) line.get("draft");
        assertEquals("line", draft.get("vizType"));
        assertEquals(List.of(Map.of("field", "day", "grain", "week")), ((Map<String, Object>) draft.get("controls")).get("x"));
        assertEquals("Sum of revenue by day (week), compared with the same period last year", line.get("caption"));

        Map<String, Object> tile = value(ask(Map.of("dataset", "sales", "measures", List.of(m("count", null)))));
        assertEquals("kpi", ((Map<String, Object>) tile.get("draft")).get("vizType"));
        assertEquals("Count of rows", tile.get("caption"));
    }

    @Test
    void aGrammarRefusalComesBackInWordsNotAsAnException() {
        // a grain with no date breakdown, and a compare the spec cannot hold
        refused(ask(Map.of("dataset", "sales", "measures", List.of(m("sum", "revenue")), "groupBy", List.of("region"),
                "grain", "week")), "unsupported", "date column");
        refused(ask(Map.of("dataset", "sales", "measures", List.of(m("sum", "revenue")), "compare", "previous")),
                "invalid", "time dimension");
    }

    @Test
    void withoutAWriteRootThereIsNoDatasetToSeeSoNothingIsDrafted() {
        ToolResult r = as(tool(null), AUTHOR, Map.of("measures", List.of(m("count", null))));
        refused(r, "no-visible-dataset");
        assertNotNull(tool(null), "the tool is still registered");
    }

    @Test
    void theToolIsAnAuthorToolNotAMutatingOne() {
        assertFalse(tool.spec().mutating(), "it drafts; the gated write is component_apply");
    }
}
