package com.gamma.pipeline;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link PipelineDocument} (ELT amendment §5.1) — the document is a projection of config that a
 * business reviewer signs off on, so the gates are: nothing configured is silently dropped, the
 * field table really joins Schema to Mapping, secrets never render, and the same input renders
 * byte-identically every time (the fingerprint in the header is worthless otherwise).
 */
class PipelineDocumentTest {

    private static final String FP = "abc123";

    // ── fixtures ─────────────────────────────────────────────────────────────────

    private static Map<String, Object> step(String verb, Map<String, Object> cfg) {
        return new LinkedHashMap<>(Map.of(verb, cfg));
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    /** A recipe exercising every verb §5.1 gives a section to. */
    private static Map<String, Object> fullRecipe() {
        return map(
                "name", "subscriber_etl",
                "active", true,
                "trigger", map("cron", "0 * * * *"),
                "steps", List.of(
                        step("collect", map("connection", "connections/sftp_main", "files", "*.csv",
                                "password", "hunter2")),
                        step("parse", map("grammar", "grammars/subscriber", "format", "CSV")),
                        step("map", map("schema", "schemas/subscriber", "mapping", "mappings/subscriber")),
                        step("transform", map("join", "references/lookup", "on", "MSISDN")),
                        step("transform", map("filter", "AMOUNT > 0")),
                        step("dedup", map("key", List.of("MSISDN", "EVENT_DATE"), "order_by", "ts DESC")),
                        step("summarize", map("group_by", List.of("REGION"), "measures", List.of("sum(AMOUNT)"))),
                        step("sink", map("database", "warehouse", "format", "PARQUET"))),
                "guarantees", map(
                        "file_dedup", map("mode", "hash"),
                        "quarantine", "dirs/quarantine",
                        "retention", 30));
    }

    private static Map<String, Map<String, Object>> fullComponents() {
        Map<String, Map<String, Object>> c = new LinkedHashMap<>();
        c.put("grammars/subscriber", map("name", "subscriber"));
        c.put("schemas/subscriber", map("raw", map("name", "SUBSCRIBER", "format", "CSV", "fields", List.of(
                map("name", "MSISDN", "selector", "0", "type", "VARCHAR",
                        "description", "Subscriber number", "classification", "PII"),
                map("name", "AMOUNT", "selector", "1", "type", "DOUBLE", "unit", "EUR"),
                map("name", "REGION", "selector", "2", "type", "VARCHAR")))));
        c.put("mappings/subscriber", map("rules", List.of(
                map("targetColumn", "MSISDN", "sourceExpression", "MSISDN", "transformType", "DIRECT"),
                map("targetColumn", "AMOUNT", "sourceExpression", "CAST(AMOUNT AS DOUBLE)", "transformType", "EXPR"))));
        return c;
    }

    // ── header + binding ─────────────────────────────────────────────────────────

    @Test
    void headerCarriesTheNameAndTheFingerprintThatBindsTheDocumentToConfig() {
        String md = PipelineDocument.render("subscriber_etl", fullRecipe(), fullComponents(), FP);
        assertTrue(md.startsWith("# Pipeline: subscriber_etl\n"), "title names the pipeline");
        assertTrue(md.contains("| Config fingerprint | `abc123` |"), "fingerprint is in the header table");
        assertTrue(md.contains("sign-off is stale"), "states what a fingerprint mismatch means");
        assertTrue(md.contains("| Status | Active |"));
    }

    @Test
    void anInactivePipelineSaysSo() {
        Map<String, Object> r = fullRecipe();
        r.put("active", false);
        assertTrue(PipelineDocument.render("p", r, Map.of(), FP).contains("| Status | Inactive |"));
    }

    @Test
    void thereIsNoTimestampSoTheSameConfigAlwaysRendersIdentically() {
        String a = PipelineDocument.render("subscriber_etl", fullRecipe(), fullComponents(), FP);
        String b = PipelineDocument.render("subscriber_etl", fullRecipe(), fullComponents(), FP);
        assertEquals(a, b, "render must be deterministic — the contract test and the fingerprint depend on it");
    }

    // ── the field table (§5.1's verification feature) ────────────────────────────

    @Test
    void theMapFieldTableJoinsMappingRulesToTheirSchemaField() {
        String md = PipelineDocument.render("subscriber_etl", fullRecipe(), fullComponents(), FP);
        assertTrue(md.contains("| Target | Source | Kind | Type | Unit | Description | Classification |"));
        // MSISDN: rule supplies target/source/kind, the schema field supplies type/description/classification
        assertTrue(md.contains("| MSISDN | MSISDN | DIRECT | VARCHAR |  | Subscriber number | PII |"),
                "mapping rule joined to its schema field");
        assertTrue(md.contains("| AMOUNT | CAST(AMOUNT AS DOUBLE) | EXPR | DOUBLE | EUR |  |  |"),
                "an EXPR rule keeps its expression verbatim");
    }

    @Test
    void aSchemaFieldWithNoMappingRuleStillAppearsSoTheOutputShapeIsComplete() {
        String md = PipelineDocument.render("subscriber_etl", fullRecipe(), fullComponents(), FP);
        assertTrue(md.contains("| REGION | 2 |  | VARCHAR |"),
                "unmapped schema field is listed with its selector, no kind");
    }

    @Test
    void anUnresolvedRefDegradesToANoteRatherThanFailingTheWholeDocument() {
        String md = PipelineDocument.render("subscriber_etl", fullRecipe(), Map.of("schemas/subscriber", Map.of()), FP);
        assertTrue(md.contains("**not resolved**"), "a dangling ref is reported, not thrown");
    }

    // ── secrets ──────────────────────────────────────────────────────────────────

    @Test
    void secretShapedKeysNeverRenderTheirValue() {
        String md = PipelineDocument.render("subscriber_etl", fullRecipe(), fullComponents(), FP);
        assertFalse(md.contains("hunter2"), "a password must never reach the document");
        assertTrue(md.contains("••••"), "it renders masked, not omitted");
    }

    @Test
    void maskingIsBySubstringSoTokenAndAccessKeyAreCoveredToo() {
        Map<String, Object> r = map("name", "p", "steps", List.of(
                step("collect", map("api_token", "t0ps3cret", "aws_access_key", "AKIA", "region", "eu"))));
        String md = PipelineDocument.render("p", r, Map.of(), FP);
        assertFalse(md.contains("t0ps3cret"));
        assertFalse(md.contains("AKIA"));
        assertTrue(md.contains("| `region` | eu |"), "non-secret keys are unaffected");
    }

    // ── nothing dropped ──────────────────────────────────────────────────────────

    @Test
    void everyStepKeyRendersEvenWithoutADedicatedTable() {
        String md = PipelineDocument.render("subscriber_etl", fullRecipe(), fullComponents(), FP);
        assertTrue(md.contains("| `on` | MSISDN |"), "join's on key");
        assertTrue(md.contains("| `order_by` | ts DESC |"), "dedup's winner policy");
        assertTrue(md.contains("| `measures` | sum(AMOUNT) |"), "summarize measures");
        assertTrue(md.contains("| `format` | PARQUET |"), "sink format");
    }

    @Test
    void guaranteesRenderAsTheirOwnSectionNotAsSteps() {
        String md = PipelineDocument.render("subscriber_etl", fullRecipe(), fullComponents(), FP);
        assertTrue(md.contains("## Guarantees"));
        assertTrue(md.contains("| File dedup | mode=hash |"));
        assertTrue(md.contains("| Retention | 30 |"));
        assertTrue(md.indexOf("## Guarantees") > md.indexOf("## Steps"), "Guarantees follow the chain");
    }

    @Test
    void aRecipeWithNoGuaranteesSaysNoneRatherThanOmittingTheSection() {
        String md = PipelineDocument.render("p", map("name", "p", "steps", List.of()), Map.of(), FP);
        assertTrue(md.contains("## Guarantees\n\n_None configured._"));
        assertTrue(md.contains("_No steps._"));
    }

    // ── route ────────────────────────────────────────────────────────────────────

    @Test
    void routeRendersABranchTableAndThenEachBranchesOwnChain() {
        Map<String, Object> r = map("name", "p", "steps", List.of(step("route", map(
                "mode", "case",
                "branches", map(
                        "hi", map("when", "AMOUNT > 100", "default", true,
                                "steps", List.of(step("sink", map("database", "hot")))),
                        "lo", map("when", "AMOUNT <= 100",
                                "steps", List.of(step("sink", map("database", "cold")))))))));
        String md = PipelineDocument.render("p", r, Map.of(), FP);
        assertTrue(md.contains("| Branch | Condition | Mode | Default | Destination |"));
        assertTrue(md.contains("| hi | AMOUNT > 100 | case | yes | Sink → hot |"));
        assertTrue(md.contains("| lo | AMOUNT <= 100 | case |  | Sink → cold |"));
        assertTrue(md.contains("Branch: hi"), "each branch gets its own nested section");
        assertTrue(md.contains("| `database` | cold |"), "a nested sink's config still renders");
    }

    // ── table safety ─────────────────────────────────────────────────────────────

    @Test
    void aPipeInAValueIsEscapedSoItCannotBreakTheTableRow() {
        Map<String, Object> r = map("name", "p", "steps", List.of(
                step("transform", map("filter", "A = 'x' OR B = 'y' | z"))));
        String md = PipelineDocument.render("p", r, Map.of(), FP);
        assertTrue(md.contains("A = 'x' OR B = 'y' \\| z"), "pipe escaped");
    }

    @Test
    void theStepOverviewTableListsTheChainInOrder() {
        String md = PipelineDocument.render("subscriber_etl", fullRecipe(), fullComponents(), FP);
        int collect = md.indexOf("| 1 | Collect |");
        int sink = md.indexOf("| 8 | Sink |");
        assertTrue(collect > 0 && sink > collect, "overview numbers the chain in pipeline order");
    }

    // ── the determinism contract (golden file) ───────────────────────────────────

    /**
     * Byte-compare against a checked-in golden document — the Phase 5 DoD ("the document regenerates
     * deterministically from fixtures"). Regenerate with {@code -Dpipeline.document.write=true} after an
     * intentional format change, exactly like the step-types / node-attributes contracts.
     */
    @Test
    void documentRegeneratesDeterministicallyFromTheFixture() throws IOException {
        Path golden = Path.of("src/test/resources/pipeline-document.golden.md").toAbsolutePath().normalize();
        String actual = PipelineDocument.render("subscriber_etl", fullRecipe(), fullComponents(), FP).replace("\r\n", "\n").trim();

        if (Boolean.getBoolean("pipeline.document.write")) {
            Files.createDirectories(golden.getParent());
            Files.writeString(golden, actual + "\n", StandardCharsets.UTF_8);
            return;
        }
        assertTrue(Files.exists(golden), "missing golden file — regenerate with -Dpipeline.document.write=true");
        String expected = Files.readString(golden, StandardCharsets.UTF_8).replace("\r\n", "\n").trim();
        assertEquals(expected, actual, "document format changed — regenerate with -Dpipeline.document.write=true");
    }
}
