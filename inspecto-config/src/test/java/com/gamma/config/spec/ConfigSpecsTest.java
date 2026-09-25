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
        assertEquals(Runtime.getRuntime().availableProcessors(), threads.get().defaultValue());
        assertTrue(p.field("does.not.exist").isEmpty());
    }

    @Test
    void everyOutputDucklakeKeyTheEngineReadsIsDeclared() {
        // DuckLakeRegistrar.registerOne reads these five leaves of output.ducklake; ConfigSafetyValidator
        // requires catalog_url/data_path/table when enabled and jails data_path.
        ConfigSpec p = ConfigSpecs.pipeline();
        for (String k : List.of("output.ducklake.enabled", "output.ducklake.catalog_url",
                "output.ducklake.data_path", "output.ducklake.schema", "output.ducklake.table"))
            assertTrue(p.field(k).isPresent(), k + " is read by the engine but not declared in ConfigSpecs");
        assertEquals(FieldType.BOOL, p.field("output.ducklake.enabled").get().type());
        assertEquals("main", p.field("output.ducklake.schema").get().defaultValue());
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
     * Pipeline spec Wave 0, item 4 — one concept, two spellings. A WARNING and never an ERROR: the
     * legacy key still READS, so refusing it would break deployed configs to make a naming point.
     */
    @Test
    void theLegacyGrammarSpellingWarnsButIsStillAccepted() {
        ConfigSpec p = ConfigSpecs.pipeline();
        String id = "parsing-grammar-is-canonical";

        // the canonical spelling alone is silent
        assertTrue(fire(p, id, Map.of("parsing", Map.of("grammar", "grammar/cdr"))).isEmpty());
        assertTrue(fire(p, id, Map.of()).isEmpty());

        Optional<Finding> f = fire(p, id, Map.of("processing", Map.of("grammar", "cdr.grammar.toon")));
        assertTrue(f.isPresent(), "the deprecated spelling must be reported");
        assertEquals(Severity.WARNING, f.get().severity(),
                "a legacy key that still reads must never be an ERROR — that would 422 a deployed config");

        // both present is still only a warning; parsing.grammar wins in the parser
        assertTrue(fire(p, id, Map.of("parsing", Map.of("grammar", "grammar/cdr"),
                "processing", Map.of("grammar", "cdr.grammar.toon"))).isPresent());

        // both spellings are declared — the point of the item was that only the legacy one was
        assertTrue(p.field("parsing.grammar").isPresent(), "the canonical key must be declared");
        assertTrue(p.field("processing.grammar").isPresent(), "the legacy key stays declared, as read-only");
    }

    /**
     * Pipeline spec Wave 1, gap 8 — {@code output_store:} arming was undiscoverable. This mirrors the
     * FOUR refusals in {@code PipelineConfig.prepare()} (summarize / dedup / join / steps), which fire
     * at registration; the rule moves the same answer to authoring time.
     */
    @Test
    void stageTwoBlocksRequireAnOutputStoreOnlyWhenActive() {
        ConfigSpec p = ConfigSpecs.pipeline();
        String id = "stage-two-blocks-require-output-store";

        // ⚠ an INACTIVE draft may be incomplete — refusing one would break authoring in progress
        assertTrue(fire(p, id, Map.of("processing", Map.of("dedup", Map.of("keys", List.of("a"))))).isEmpty());
        assertTrue(fire(p, id, Map.of("active", false,
                "processing", Map.of("dedup", Map.of("keys", List.of("a"))))).isEmpty());

        // each of the four blocks, active and unaccompanied by output_store
        assertTrue(fire(p, id, Map.of("active", true, "steps", List.of(Map.of("kind", "dedup")))).isPresent());
        for (String block : List.of("dedup", "summarize", "join"))
            assertTrue(fire(p, id, Map.of("active", true,
                            "processing", Map.of(block, Map.of("keys", List.of("a"))))).isPresent(),
                    block + " must require output_store when active");

        // ...and each is satisfied by declaring one
        assertTrue(fire(p, id, Map.of("active", true, "output_store", "landed",
                "steps", List.of(Map.of("kind", "dedup")))).isEmpty());

        // an ACTIVE pipeline carrying none of the four is fine without output_store
        assertTrue(fire(p, id, Map.of("active", true, "processing", Map.of("threads", 4))).isEmpty());

        // 🔴 an EMPTY block is not an authored one — the engine tests the parsed field, which an empty
        // section leaves null, so refusing here would refuse a pipeline the engine arms happily
        assertTrue(fire(p, id, Map.of("active", true, "steps", List.of())).isEmpty());
        assertTrue(fire(p, id, Map.of("active", true, "processing", Map.of("dedup", Map.of()))).isEmpty());

        assertTrue(p.field("output_store").isPresent(), "the arming condition must be declared to be discoverable");
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

    /** D5-ref: a delete marker is a column AND the value(s) meaning delete — one without the other is an ERROR. */
    @Test
    void referenceDeleteNeedsBothColumnAndValues() {
        ConfigSpec p = ConfigSpecs.pipeline();
        String id = "reference-delete-needs-column-and-values";
        Map<String, Object> noValues = Map.of("reference", Map.of("load", "upsert", "key", List.of("id"),
                "delete", Map.of("column", "op")));
        assertEquals(Severity.ERROR, fire(p, id, noValues).orElseThrow().severity());
        assertTrue(fire(p, id, Map.of("reference", Map.of("load", "upsert", "key", List.of("id"),
                "delete", Map.of("column", "op", "values", List.of())))).isPresent(), "empty values list");
        assertTrue(fire(p, id, Map.of("reference", Map.of("load", "upsert", "key", List.of("id"),
                "delete", Map.of("values", List.of("D"))))).isPresent(), "values without a column");
        assertTrue(fire(p, id, Map.of("reference", Map.of("load", "upsert", "key", List.of("id"),
                "delete", Map.of("column", "op", "values", List.of("D"))))).isEmpty());
        assertTrue(fire(p, id, Map.of()).isEmpty(), "absent delete block needs nothing");
    }

    /** D5-ref/D6-ref only mean something on the versioned store — on `replace` they would be silently inert. */
    @Test
    void referenceDeleteAndOrderByRequireAVersionedLoad() {
        ConfigSpec p = ConfigSpecs.pipeline();
        String id = "reference-delete-order-by-require-versioned-load";
        Map<String, Object> del = Map.of("column", "op", "values", List.of("D"));
        assertEquals(Severity.ERROR, fire(p, id, Map.of("reference", Map.of("delete", del))).orElseThrow().severity(),
                "delete on an absent (replace) load");
        assertTrue(fire(p, id, Map.of("reference", Map.of("load", "replace", "order_by", "updated_at"))).isPresent());
        assertTrue(fire(p, id, Map.of("reference", Map.of("load", "upsert", "key", List.of("id"),
                "delete", del, "order_by", "updated_at"))).isEmpty());
        assertTrue(fire(p, id, Map.of("reference", Map.of("load", "scd2", "key", List.of("id"),
                "order_by", "updated_at"))).isEmpty());
        assertTrue(fire(p, id, Map.of("reference", Map.of("load", "replace"))).isEmpty());
    }

    /** The marker is dropped before hashing — a marker that is also a key column would change the key identity. */
    @Test
    void referenceDeleteColumnMayNotBeAKeyColumn() {
        ConfigSpec p = ConfigSpecs.pipeline();
        String id = "reference-delete-column-not-a-key";
        assertEquals(Severity.ERROR, fire(p, id, Map.of("reference", Map.of("load", "upsert", "key", List.of("id", "op"),
                "delete", Map.of("column", "op", "values", List.of("D"))))).orElseThrow().severity());
        assertTrue(fire(p, id, Map.of("reference", Map.of("load", "upsert", "key", List.of("id"),
                "delete", Map.of("column", "op", "values", List.of("D"))))).isEmpty());
    }

    /** A delete feed without order_by resolves a same-key upsert+delete pair in one batch arbitrarily — WARN. */
    @Test
    void referenceDeleteWithoutOrderByWarns() {
        ConfigSpec p = ConfigSpecs.pipeline();
        String id = "reference-delete-without-order-by";
        Map<String, Object> del = Map.of("column", "op", "values", List.of("D"));
        assertEquals(Severity.WARNING, fire(p, id, Map.of("reference", Map.of("load", "upsert", "key", List.of("id"),
                "delete", del))).orElseThrow().severity());
        assertTrue(fire(p, id, Map.of("reference", Map.of("load", "upsert", "key", List.of("id"),
                "delete", del, "order_by", "updated_at"))).isEmpty());
        assertTrue(fire(p, id, Map.of()).isEmpty());
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
        // JOB-SPEC-1 (2026-09-06): the stale `job-type-required` rule (enrich|report|maintenance) is GONE —
        // now that POST/PUT /jobs runs this spec at save it would have refused every `type: pipeline` job.
        // The job type is owned by JobRoutes/JobTypeRegistry, not by the spec.
        assertTrue(j.rules().stream().noneMatch(r -> r.id().equals("job-type-required")),
                "the spec must not re-grow a type whitelist that the job registry already owns");

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

    /** LA-21: a working-set binding must carry what the tile renders (the pin) and a mode it can state. */
    @Test
    void aWorkingSetBindingNeedsItsInvestigationRelationModeAndPin() {
        ConfigSpec w = ConfigSpecs.widget();
        Map<String, Object> pin = Map.of("step", 4, "workingSetHash", "sha256:ab");
        Map<String, Object> ok = Map.of("vizType", "working-set", "viewId", "case-a",
                "workingSet", Map.of("relation", "entities", "mode", "frozen", "pin", pin));
        assertTrue(fire(w, "working-set-binding", ok).isEmpty(), "a complete Frozen binding");
        assertTrue(fire(w, "working-set-binding", Map.of("vizType", "kpi", "datasetId", "orders")).isEmpty(),
                "no binding → the rule does not apply");
        assertTrue(fire(w, "working-set-binding", Map.of("vizType", "working-set", "viewId", "case-a",
                "workingSet", Map.of("relation", "entities", "mode", "sometimes", "pin", pin))).isPresent(),
                "a mode the tile cannot state → ERROR");
        assertTrue(fire(w, "working-set-binding", Map.of("vizType", "working-set", "viewId", "case-a",
                "workingSet", Map.of("relation", "nodes", "mode", "live", "pin", pin))).isPresent(),
                "an unknown relation → ERROR");
        assertTrue(fire(w, "working-set-binding", Map.of("vizType", "working-set", "viewId", "case-a",
                "workingSet", Map.of("relation", "entities", "mode", "live"))).isPresent(),
                "no pin — a Live tile has nothing to measure drift from → ERROR");
        assertTrue(fire(w, "working-set-binding", Map.of("vizType", "working-set",
                "workingSet", Map.of("relation", "entities", "mode", "frozen", "pin", pin))).isPresent(),
                "no Investigation (viewId) → ERROR");
    }

    @Test
    void dashboardNeedsAtLeastOneTile() {
        ConfigSpec d = ConfigSpecs.dashboard();
        assertTrue(fire(d, "at-least-one-tile",
                Map.of("tiles", List.of(Map.of("widgetId", "w1", "span", 1)))).isEmpty(), "one tile → ok");
        assertTrue(fire(d, "at-least-one-tile", Map.of("tiles", List.of())).isPresent(),
                "an empty tiles list → ERROR (required alone would accept [])");
    }

    /** UIE-5: the viewer header keys are declared (so the component-route key census accepts them), and
     *  {@code asOf} must be a real calendar date. */
    @Test
    void dashboardDeclaresTheHeaderAndAsOfIsARealDate() {
        ConfigSpec d = ConfigSpecs.dashboard();
        java.util.Set<String> paths = d.fields().stream().map(FieldSpec::path)
                .collect(java.util.stream.Collectors.toSet());
        assertTrue(paths.containsAll(List.of("description", "asOf", "illustrative")), paths.toString());
        assertTrue(fire(d, "as-of-is-a-date", Map.of("tiles", List.of())).isEmpty(), "no asOf → the rule does not apply");
        assertTrue(fire(d, "as-of-is-a-date", Map.of("asOf", "2026-09-23")).isEmpty(), "an ISO date → ok");
        assertTrue(fire(d, "as-of-is-a-date", Map.of("asOf", "23 Sep 2026")).isPresent(), "not YYYY-MM-DD → ERROR");
        assertTrue(fire(d, "as-of-is-a-date", Map.of("asOf", "2026-02-30")).isPresent(), "no such day → ERROR");
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

    // ── expectation: the 'condition' kind (promoted 2026-07-18) ───────────────────

    /**
     * The real {@code condition}-kind save shape, validated through the very loop
     * {@code POST /validate} and {@code POST /config/write} run ({@code ConfigLoader.validate}).
     *
     * <p>🔴 This is not hypothetical reachability: both routes take {@code type} from the request
     * body and {@code ConfigSpecs.forType("expectation")} is non-null, so before this spec was
     * brought forward a condition expectation drew TWO errors from a spec that had never heard of
     * its kind — {@code kind} not in the enum, and {@code column} "missing" although
     * {@code Expectation} deliberately exempts this one kind from it. The {@code /expectations}
     * route is unaffected only because it runs the spec's field NAMES and not its value rules.
     */
    @Test
    void expectationSpecAcceptsTheConditionKindsRealSaveShape() {
        Map<String, Object> draft = new java.util.LinkedHashMap<>();
        draft.put("name", "e_cond");
        draft.put("targetType", "pipeline");
        draft.put("target", "orders");
        draft.put("kind", "condition");
        draft.put("when", Map.of("kind", "group", "op", "AND", "items",
                List.of(Map.of("kind", "condition", "field", "ID", "operator", "is_null"))));
        draft.put("severity", "MINOR");
        draft.put("enabled", true);

        List<Finding> errors = com.gamma.config.io.ConfigLoader.filesystem()
                .validate(ConfigSpecs.expectation(), draft).stream()
                .filter(f -> f.severity() == Severity.ERROR).toList();
        assertEquals(List.of(), errors,
                "a condition expectation the engine evaluates must not be refused by its own spec");
    }

    /**
     * The Studio sends {@code when: null} on EVERY save ({@code expectation-form.dialog.ts}), the four
     * kinds that ignore it included — so declaring {@code when} must not turn every non-condition save
     * into a type error. {@code RawConfig.present} treats null as absent, which is what makes the
     * declaration safe; this pins that rather than trusting it.
     */
    @Test
    void expectationWhenIsAnOptionalMapAndANullWhenIsNotAFinding() {
        FieldSpec when = ConfigSpecs.expectation().field("when").orElseThrow(
                () -> new AssertionError("the 'condition' kind's predicate tree must be declared"));
        assertEquals(FieldType.MAP, when.type(), "a when-clause is a condition TREE, not a scalar");
        assertFalse(when.required(), "the other four kinds carry no when");

        Map<String, Object> studioSave = new java.util.LinkedHashMap<>();
        studioSave.put("name", "e_ok");
        studioSave.put("target", "orders");
        studioSave.put("column", "ID");
        studioSave.put("kind", "range");
        studioSave.put("min", 1);
        studioSave.put("max", 9);
        studioSave.put("when", null);          // ← the Studio's literal every-save shape
        studioSave.put("severity", "MINOR");

        List<Finding> errors = com.gamma.config.io.ConfigLoader.filesystem()
                .validate(ConfigSpecs.expectation(), studioSave).stream()
                .filter(f -> f.severity() == Severity.ERROR).toList();
        assertEquals(List.of(), errors, "when: null is absent, not a malformed map");
    }

    /** {@code condition} and {@code baseline} are live kinds — the spec's enum must offer all six the record accepts. */
    @Test
    void expectationKindEnumOffersEveryKindTheRecordAccepts() {
        FieldSpec kind = ConfigSpecs.expectation().field("kind").orElseThrow();
        assertEquals(List.of("non_null", "range", "regex", "referential", "condition", "baseline"),
                kind.enumValues());
    }

    /** DUCKLE-C8: a baseline profiles its own {@code columns}, and one with no limit could never fail. */
    @Test
    void expectationBaselineNeedsNoColumnButNeedsALimit() {
        ConfigSpec e = ConfigSpecs.expectation();
        assertTrue(fire(e, "column-needed-unless-condition", Map.of("kind", "baseline")).isEmpty());
        assertTrue(fire(e, "baseline-needs-a-limit", Map.of("kind", "baseline")).isPresent());
        assertTrue(fire(e, "baseline-needs-a-limit", Map.of("kind", "baseline", "maxDecrease", 5)).isEmpty());
        assertTrue(fire(e, "baseline-needs-a-limit", Map.of("kind", "non_null")).isEmpty());
    }

    /**
     * {@code column} is required for the four column-checking kinds and irrelevant to {@code condition},
     * whose tree names its own field(s). {@link FieldSpec} has no conditional-required, so the
     * requirement lives where the other three kind rules already live — a {@link CrossFieldRule}.
     */
    @Test
    void expectationColumnIsRequiredForEveryKindExceptCondition() {
        ConfigSpec e = ConfigSpecs.expectation();
        String rule = "column-needed-unless-condition";
        assertFalse(e.field("column").orElseThrow().required(),
                "a flat required would refuse every condition expectation");

        for (String k : List.of("non_null", "range", "regex", "referential")) {
            assertTrue(fire(e, rule, Map.of("kind", k)).isPresent(), k + " checks one column");
            assertTrue(fire(e, rule, Map.of("kind", k, "column", "ID")).isEmpty(), k + " with a column");
        }
        assertTrue(fire(e, rule, Map.of("kind", "condition")).isEmpty(),
                "the condition kind's tree names its own field(s)");
        assertTrue(fire(e, rule, Map.of("kind", "CONDITION")).isEmpty(), "kind is matched case-insensitively");
    }

    /** The fourth kind rule, mirroring range/regex/referential: a condition with no tree has no predicate. */
    @Test
    void expectationConditionNeedsAWhenTree() {
        ConfigSpec e = ConfigSpecs.expectation();
        String rule = "condition-needs-a-when";
        assertTrue(fire(e, rule, Map.of("kind", "condition")).isPresent());
        assertTrue(fire(e, rule, Map.of("kind", "condition", "when", Map.of("kind", "group"))).isEmpty());
        assertTrue(fire(e, rule, Map.of("kind", "non_null", "column", "ID")).isEmpty(),
                "the other four kinds ignore when");
    }

    // -- alert shapes (DUCKLE-C1) ------------------------------------------------------------------

    private static List<String> alertErrors(Map<String, Object> alert) {
        return com.gamma.config.io.ConfigLoader.filesystem()
                .validate(ConfigSpecs.alert(), Map.of("alert", alert)).stream()
                .filter(f -> f.severity() == Severity.ERROR).map(Finding::fieldPath).toList();
    }

    /**
     * A freshness rule takes no metric, threshold or window - {@code AlertRule} REFUSES a metric or a
     * window on one - so a spec that required them refused, through {@code /config/write}, the exact
     * shape the engine accepts.
     */
    @Test
    void aFreshnessRuleIsNotRefusedForOmittingTheLedgerFields() {
        assertEquals(List.of(), alertErrors(Map.of("name", "sales-fresh", "dataset", "sales_ds",
                "maximumAge", "6h", "severity", "WARNING")));
        for (String f : List.of("alert.metric", "alert.threshold", "alert.window"))
            assertFalse(ConfigSpecs.alert().field(f).orElseThrow().required(),
                    f + " is required per shape, never flatly");
    }

    /** ...while the ledger rule keeps every requirement it had, now stated per shape. */
    @Test
    void aLedgerRuleStillNeedsMetricThresholdAndWindow() {
        assertEquals(List.of("alert.metric", "alert.window", "alert.threshold"),
                alertErrors(Map.of("name", "ledger-bare", "severity", "WARNING")));
        assertEquals(List.of(), alertErrors(Map.of("name", "ledger-ok", "metric", "failed_batches",
                "threshold", "3", "window", "1h", "severity", "WARNING")));
        assertEquals(List.of("alert.window"), alertErrors(Map.of("name", "ledger-badwin",
                "metric", "failed_batches", "threshold", "3", "window", "soon", "severity", "WARNING")));
    }

    /** A measure rule (dataset, no maximumAge) takes a threshold but no metric or window. */
    @Test
    void aMeasureRuleNeedsAThresholdButNoWindow() {
        assertEquals(List.of(), alertErrors(Map.of("name", "m-ok", "dataset", "sales_ds",
                "measure", "count", "threshold", "10", "severity", "WARNING")));
        assertEquals(List.of("alert.threshold"), alertErrors(Map.of("name", "m-bare", "dataset", "sales_ds",
                "measure", "count", "severity", "WARNING")));
        // a blank maximumAge is absent (AlertRule reads it so), which makes this a measure rule again
        assertEquals(List.of("alert.threshold"), alertErrors(Map.of("name", "m-blank", "dataset", "sales_ds",
                "measure", "count", "maximumAge", " ", "severity", "WARNING")));
    }
}
