package com.gamma.config.spec;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the declarative config specs (P0): every authored {@link ConfigSpec} resolves, the
 * record types enforce their null-safety / defensive-copy contracts, and each {@link CrossFieldRule}
 * fires (or stays silent) exactly where the imperative loaders/{@code ConfigValidator} would —
 * proving the spec and the existing code agree.
 */
class ConfigSpecsTest {

    // ── spec resolution ──────────────────────────────────────────────────────────

    @Test
    void forTypeResolvesEveryKnownTypeAndRejectsUnknown() {
        for (String t : ConfigSpecs.TYPES) {
            ConfigSpec spec = ConfigSpecs.forType(t);
            assertNotNull(spec, "spec for " + t);
            assertEquals(t, spec.type());
            assertFalse(spec.fields().isEmpty(), t + " should declare fields");
        }
        assertNull(ConfigSpecs.forType("nope"));
        assertNull(ConfigSpecs.forType(null));
    }

    @Test
    void forTypeIsCaseInsensitive() {
        assertNotNull(ConfigSpecs.forType("PIPELINE"));
        assertEquals("pipeline", ConfigSpecs.forType("Pipeline").type());
    }

    // ── record contracts ──────────────────────────────────────────────────────────

    @Test
    void fieldSpecNormalisesNullsAndCopiesEnumValues() {
        FieldSpec f = new FieldSpec(null, null, null, null, false, null, null, null, null, null);
        assertEquals("", f.path());
        assertEquals(FieldType.STRING, f.type());
        assertNotNull(f.enumValues());
        assertTrue(f.enumValues().isEmpty());

        FieldSpec e = FieldSpec.enumField("p", "L", List.of("a", "b"), "a", "d");
        assertThrows(UnsupportedOperationException.class, () -> e.enumValues().add("c"));
    }

    @Test
    void configSpecFieldLookupWorks() {
        ConfigSpec p = ConfigSpecs.pipeline();
        Optional<FieldSpec> threads = p.field("processing.threads");
        assertTrue(threads.isPresent());
        assertEquals(4, threads.get().defaultValue());
        assertTrue(p.field("does.not.exist").isEmpty());
    }

    @Test
    void crossFieldRuleCheckReportsFindingOnViolationOnly() {
        CrossFieldRule rule = new CrossFieldRule("r", "must hold", Severity.ERROR,
                List.of("a.b"), raw -> RawConfig.present(raw, "a.b"));
        assertTrue(rule.check(Map.of("a", Map.of("b", "x"))).isEmpty(), "satisfied → no finding");

        Optional<Finding> f = rule.check(Map.of());
        assertTrue(f.isPresent());
        assertEquals(Severity.ERROR, f.get().severity());
        assertEquals("a.b", f.get().fieldPath());
        assertEquals("must hold", f.get().message());
    }

    // ── pipeline cross-field rules (mirror PipelineConfig.load + ConfigValidator) ──

    private Optional<Finding> fire(ConfigSpec spec, String ruleId, Map<String, Object> raw) {
        CrossFieldRule rule = spec.rules().stream().filter(r -> r.id().equals(ruleId)).findFirst()
                .orElseThrow(() -> new AssertionError("no rule " + ruleId));
        return rule.check(raw);
    }

    /**
     * The authoring-time half of the engine's fail-closed source-zone refusal. ⚠ Filed as a real gap:
     * the offline mock refused a bad zone on the pipeline write while NO Java route did, so the mock
     * was ahead of the server rather than mirroring it. This rule is what makes it true.
     */
    @Test
    void sourceTimezoneMustBeAZoneTheQueryEngineAccepts() {
        ConfigSpec p = ConfigSpecs.pipeline();
        String id = "parsing-source-timezone-resolvable";

        // absent, and blank, are legal — no zone declared is the default wall-clock behaviour
        assertTrue(fire(p, id, Map.of("parsing", Map.of("frontend", "delimited"))).isEmpty());
        assertTrue(fire(p, id, Map.of("parsing", Map.of("source_timezone", ""))).isEmpty());

        assertTrue(fire(p, id, Map.of("parsing", Map.of("source_timezone", "Asia/Kolkata"))).isEmpty());
        assertTrue(fire(p, id, Map.of("parsing", Map.of("source_timezone", "UTC"))).isEmpty());

        assertTrue(fire(p, id, Map.of("parsing", Map.of("source_timezone", "Not/AZone"))).isPresent());
        // 🔴 the offset forms ZoneId.of would accept but DuckDB rejects — the whole reason this gate
        // is available-ids membership rather than ZoneId.of
        assertTrue(fire(p, id, Map.of("parsing", Map.of("source_timezone", "+05:30"))).isPresent());
        assertTrue(fire(p, id, Map.of("parsing", Map.of("source_timezone", "Z"))).isPresent());
        // ...and the lower-case spelling DuckDB takes but no config key allows
        assertTrue(fire(p, id, Map.of("parsing", Map.of("source_timezone", "utc"))).isPresent());
    }

    /**
     * The authoring-time half of the %z/%Z refusal. Both directives, both lists, and an escaped
     * literal percent must still pass.
     */
    @Test
    void temporalFormatsMayNotCarryAZoneDirective() {
        ConfigSpec p = ConfigSpecs.pipeline();
        String id = "parsing-formats-carry-no-zone-directive";

        assertTrue(fire(p, id, delimited("timestamp_formats", List.of("%Y-%m-%d %H:%M:%S"))).isEmpty());
        assertTrue(fire(p, id, Map.of("parsing", Map.of("frontend", "delimited"))).isEmpty());

        assertTrue(fire(p, id, delimited("timestamp_formats", List.of("%Y-%m-%d %H:%M:%S%z"))).isPresent());
        assertTrue(fire(p, id, delimited("timestamp_formats", List.of("%Y-%m-%d %H:%M:%S %Z"))).isPresent());
        assertTrue(fire(p, id, delimited("date_formats", List.of("%Y-%m-%d%z"))).isPresent());
        // a clean format beside a dirty one is still refused
        assertTrue(fire(p, id, delimited("timestamp_formats",
                List.of("%Y-%m-%d %H:%M:%S", "%Y-%m-%d %H:%M:%S%z"))).isPresent());

        // ⚠ %% is an escaped literal percent — '%%z' is naive text, not a directive
        assertTrue(fire(p, id, delimited("timestamp_formats", List.of("%Y-%m-%d %H:%M:%S%%z"))).isEmpty());
        assertTrue(fire(p, id, delimited("timestamp_formats", List.of("%Y-%m-%d %H:%M:%S%%%z"))).isPresent());

        // ⚠ a list authored at parsing: level is not read by mergeParsing at all, so refusing one
        // there would 422 on something the engine ignores
        assertTrue(fire(p, id, Map.of("parsing",
                Map.of("timestamp_formats", List.of("%Y-%m-%d %H:%M:%S%z")))).isEmpty());
    }

    private static Map<String, Object> delimited(String key, List<String> formats) {
        return Map.of("parsing", Map.of("frontend", "delimited", "delimited", Map.of(key, formats)));
    }

    @Test
    void pluginIngesterRequiresNonEmptySegments() {
        ConfigSpec p = ConfigSpecs.pipeline();
        // ingester set, segments missing → ERROR (matches PipelineConfig.load throw)
        Map<String, Object> bad = Map.of("processing", Map.of("ingester", "com.x.Plugin"));
        assertTrue(fire(p, "plugin-ingester-requires-segments", bad).isPresent());

        // ingester set, segments present → ok
        Map<String, Object> good = Map.of("processing",
                Map.of("ingester", "com.x.Plugin", "segments", Map.of("CALL", "call_schema.toon")));
        assertTrue(fire(p, "plugin-ingester-requires-segments", good).isEmpty());

        // no ingester → ok regardless of segments
        assertTrue(fire(p, "plugin-ingester-requires-segments", Map.of("processing", Map.of())).isEmpty());
    }

    @Test
    void threadsTimesDuckdbOversubscriptionWarns() {
        ConfigSpec p = ConfigSpecs.pipeline();
        int cores = Runtime.getRuntime().availableProcessors();
        Map<String, Object> over = Map.of("processing",
                Map.of("threads", cores + 1, "duckdb_threads", 2));
        assertEquals(Severity.WARNING,
                fire(p, "threads-x-duckdb-threads-oversubscription", over).orElseThrow().severity());

        // duckdb_threads=0 (default) → never warns
        Map<String, Object> off = Map.of("processing", Map.of("threads", 999, "duckdb_threads", 0));
        assertTrue(fire(p, "threads-x-duckdb-threads-oversubscription", off).isEmpty());
    }

    @Test
    void duckdbEngineWithSkipTailColumnsWarns() {
        ConfigSpec p = ConfigSpecs.pipeline();
        Map<String, Object> bad = Map.of("processing",
                Map.of("csv_settings", Map.of("engine", "duckdb", "skip_tail_columns", 1)));
        assertTrue(fire(p, "duckdb-engine-x-skip-tail-columns", bad).isPresent());

        Map<String, Object> javaEngine = Map.of("processing",
                Map.of("csv_settings", Map.of("engine", "java", "skip_tail_columns", 3)));
        assertTrue(fire(p, "duckdb-engine-x-skip-tail-columns", javaEngine).isEmpty());
    }

    @Test
    void threadsVsBatchMaxFilesWarns() {
        ConfigSpec p = ConfigSpecs.pipeline();
        Map<String, Object> bad = Map.of("processing",
                Map.of("threads", 4, "batch", Map.of("max_files", 1)));
        assertTrue(fire(p, "threads-vs-batch-max-files", bad).isPresent());

        Map<String, Object> ok = Map.of("processing",
                Map.of("threads", 4, "batch", Map.of("max_files", 8)));
        assertTrue(fire(p, "threads-vs-batch-max-files", ok).isEmpty());
    }

    @Test
    void duplicateCheckRetentionWarns() {
        ConfigSpec p = ConfigSpecs.pipeline();
        Map<String, Object> bad = Map.of("processing",
                Map.of("duplicate_check", Map.of("enabled", true, "retention_days", 0)));
        assertTrue(fire(p, "duplicate-check-retention", bad).isPresent());

        Map<String, Object> ok = Map.of("processing",
                Map.of("duplicate_check", Map.of("enabled", true, "retention_days", 30)));
        assertTrue(fire(p, "duplicate-check-retention", ok).isEmpty());

        // disabled → never warns even with retention 0
        Map<String, Object> disabled = Map.of("processing",
                Map.of("duplicate_check", Map.of("enabled", false, "retention_days", 0)));
        assertTrue(fire(p, "duplicate-check-retention", disabled).isEmpty());
    }

    @Test
    void referenceUpsertRequiresKey() {
        ConfigSpec p = ConfigSpecs.pipeline();
        // upsert / scd2 without a key → ERROR (mirrors the PipelineConfig parser throw)
        assertTrue(fire(p, "reference-upsert-requires-key",
                Map.of("reference", Map.of("load", "upsert"))).isPresent());
        assertEquals(Severity.ERROR, fire(p, "reference-upsert-requires-key",
                Map.of("reference", Map.of("load", "scd2"))).orElseThrow().severity());

        // upsert / scd2 with a non-empty key → ok
        assertTrue(fire(p, "reference-upsert-requires-key",
                Map.of("reference", Map.of("load", "upsert", "key", List.of("customer_id")))).isEmpty());

        // replace (or absent reference) never needs a key
        assertTrue(fire(p, "reference-upsert-requires-key",
                Map.of("reference", Map.of("load", "replace"))).isEmpty());
        assertTrue(fire(p, "reference-upsert-requires-key", Map.of()).isEmpty());

        // an empty key list under upsert is still a violation
        assertTrue(fire(p, "reference-upsert-requires-key",
                Map.of("reference", Map.of("load", "upsert", "key", List.of()))).isPresent());
    }

    // ── enrichment + job rules ──────────────────────────────────────────────────

    @Test
    void enrichmentRequiresTransformOrFile() {
        ConfigSpec e = ConfigSpecs.enrichment();
        assertTrue(fire(e, "transform-or-transform-file", Map.of()).isPresent());
        assertTrue(fire(e, "transform-or-transform-file", Map.of("transform", "SELECT 1")).isEmpty());
        assertTrue(fire(e, "transform-or-transform-file",
                Map.of("transform_file", "x.sql")).isEmpty());
    }

    @Test
    void jobTypeAndCronRules() {
        ConfigSpec j = ConfigSpecs.job();
        assertTrue(fire(j, "job-type-required", Map.of("job", Map.of("type", "enrich"))).isEmpty());
        // ingest is no longer a job type (T23 / §3.8 — ingest is pipeline-exclusive)
        assertTrue(fire(j, "job-type-required", Map.of("job", Map.of("type", "ingest"))).isPresent());
        assertTrue(fire(j, "job-type-required", Map.of("job", Map.of("type", "bogus"))).isPresent());
        assertTrue(fire(j, "job-type-required", Map.of("job", Map.of())).isPresent());

        // absent cron → ok; 5 fields → ok; 6 fields → ok; 3 fields → error
        assertTrue(fire(j, "cron-field-count", Map.of("job", Map.of())).isEmpty());
        assertTrue(fire(j, "cron-field-count", Map.of("job", Map.of("cron", "0 2 * * *"))).isEmpty());
        assertTrue(fire(j, "cron-field-count", Map.of("job", Map.of("cron", "0 0 2 * * *"))).isEmpty());
        assertTrue(fire(j, "cron-field-count", Map.of("job", Map.of("cron", "0 2 *"))).isPresent());
    }

    // ── widget / dashboard cross-field rules (kpi_report_builder, 2026-07-22) ──────

    @Test
    void widgetMustBindADatasetOrAView() {
        ConfigSpec w = ConfigSpecs.widget();
        assertTrue(fire(w, "binds-a-dataset-or-a-view", Map.of("vizType", "kpi", "datasetId", "orders")).isEmpty(),
                "a dataset binding satisfies the rule");
        assertTrue(fire(w, "binds-a-dataset-or-a-view", Map.of("vizType", "geo-map", "viewId", "v1")).isEmpty(),
                "a view binding satisfies the rule (a view-bound widget has no datasetId)");
        assertTrue(fire(w, "binds-a-dataset-or-a-view", Map.of("vizType", "kpi")).isPresent(),
                "neither dataset nor view → ERROR");
    }

    @Test
    void dashboardNeedsAtLeastOneTile() {
        ConfigSpec d = ConfigSpecs.dashboard();
        assertTrue(fire(d, "at-least-one-tile",
                Map.of("tiles", List.of(Map.of("widgetId", "w1", "span", 1)))).isEmpty(), "one tile → ok");
        assertTrue(fire(d, "at-least-one-tile", Map.of("tiles", List.of())).isPresent(),
                "an empty tiles list → ERROR (required alone would accept [])");
    }

    // ── meta cross-field rules ─────────────────────────────────────────────

    /**
     * {@code domain.timezone} took any string at all before 2026-08-14 — the value is served to
     * {@code /catalog/kpis} and read by {@code ExplainEntitySkill}, so a typo travelled as fact.
     *
     * <p>⚠ An abbreviation is the case worth pinning, not gibberish: {@code IST} looks like a zone and
     * is what an author reaches for, but {@link java.time.ZoneId#of} rejects it (it is a
     * {@code SHORT_IDS} alias, and {@code ZoneId.of} does not consult that map. {@code EST} goes the
     * same way, while {@code EST5EDT} <b>is</b> a real id — which is exactly why the rule asks
     * {@code ZoneId.of} instead of reasoning about a value's shape. (This test was written asserting
     * the opposite for {@code EST}; the JVM corrected it.)
     */
    @Test
    void metaDomainTimezoneMustResolveToARealZone() {
        ConfigSpec m = ConfigSpecs.meta();
        String rule = "domain-timezone-resolvable";

        assertTrue(fire(m, rule, Map.of("domain", Map.of("timezone", "Asia/Kolkata"))).isEmpty(),
                "an IANA id is the canonical spelling");
        assertTrue(fire(m, rule, Map.of("domain", Map.of("timezone", "UTC"))).isEmpty());
        assertTrue(fire(m, rule, Map.of("domain", Map.of("timezone", "+05:30"))).isEmpty(),
                "a fixed offset resolves, so the rule must not demand a region id");

        // absent / blank: legal. The field is optional and the row's ⛔ stands — nothing forces a zone.
        assertTrue(fire(m, rule, Map.of()).isEmpty(), "no domain block at all is legal");
        assertTrue(fire(m, rule, Map.of("domain", Map.of("currency", "INR"))).isEmpty());
        assertTrue(fire(m, rule, Map.of("domain", Map.of("timezone", "  "))).isEmpty(),
                "blank is unset, not a violation");

        Optional<Finding> bad = fire(m, rule, Map.of("domain", Map.of("timezone", "IST")));
        assertTrue(bad.isPresent(), "IST is an abbreviation ZoneId.of rejects");
        assertEquals(Severity.ERROR, bad.get().severity());
        assertEquals("domain.timezone", bad.get().fieldPath(), "the finding anchors on the field the author typed");

        assertTrue(fire(m, rule, Map.of("domain", Map.of("timezone", "Asia/Kolkatta"))).isPresent(),
                "a misspelt region id is the other half of the gap");
        assertTrue(fire(m, rule, Map.of("domain", Map.of("timezone", "EST"))).isPresent(),
                "EST is rejected too — it lives in ZoneId.SHORT_IDS, which ZoneId.of does NOT consult");
        assertTrue(fire(m, rule, Map.of("domain", Map.of("timezone", "EST5EDT"))).isEmpty(),
                "EST5EDT, by contrast, IS a real zone id — so the rule asks ZoneId.of rather than "
                        + "guessing from a value's shape");
    }
}
