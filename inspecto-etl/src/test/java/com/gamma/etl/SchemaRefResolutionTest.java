package com.gamma.etl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileNotFoundException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * How a {@code schema_file} / {@code segments} reference turns into the file actually read — unification
 * W1b (see {@code docs/superpower/onboarding-pipeline-unification.md}).
 *
 * <p>The defect: every schema reference on disk is written <b>working-directory</b>-relative
 * ({@code spaces/<space>/config/x_schema.toon}; the space template even ships a literal
 * {@code spaces/${SPACE}/...} placeholder). So the server had to be launched from the base directory or the
 * pipeline would not load, and a space directory could not be moved, renamed, or imported under a new name
 * without rewriting every reference inside it — the "least changes to promote to another instance" goal.
 *
 * <p>The fix is resolution order, not a format change: <b>config-relative first, working-directory second</b>.
 * A bare {@code x_schema.toon} beside its pipeline is portable, and every legacy config keeps loading
 * byte-identically because its form is still tried. Nothing on disk needs migrating.
 */
class SchemaRefResolutionTest {

    private static final String SCHEMA = """
            partitionKey: EVENT_DATE
            raw:
              name: ev
              format: CSV
              fields[2]{name,selector,type}:
                ACCOUNT_NUMBER,"account",VARCHAR
                EVENT_DATE,"event_date",DATE
            mapping:
              canonicalName: ev
              rawName: ev
              rules[2]{targetColumn,sourceExpression,transformType}:
                ACCOUNT_NUMBER,ACCOUNT_NUMBER,DIRECT
                EVENT_DATE,EVENT_DATE,DIRECT
            """;

    // ── the new capability: a reference relative to the config's own directory ──────────

    @Test
    void bareSchemaNameBesideTheConfigResolves(@TempDir Path dir) throws Exception {
        writeSchema(dir.resolve("orders_schema.toon"));
        PipelineConfig cfg = load(dir, "orders_schema.toon");
        assertEquals(List.of("ACCOUNT_NUMBER", "EVENT_DATE"), columns(cfg),
                "a bare basename must resolve against the config's own directory");
    }

    @Test
    void aSubdirectoryReferenceResolvesRelativeToTheConfigToo(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("schemas"));
        writeSchema(dir.resolve("schemas/orders_schema.toon"));
        assertEquals(List.of("ACCOUNT_NUMBER", "EVENT_DATE"),
                columns(load(dir, "schemas/orders_schema.toon")));
    }

    /**
     * The actual portability claim, not a proxy for it: author the pair in one directory, move BOTH to a
     * differently-named directory, and load from the new location without editing the config. This is what
     * "export a stream and import it into another instance" needs, and what the working-directory-relative
     * form cannot do.
     */
    @Test
    void thePairStillLoadsAfterTheWholeDirectoryIsRenamed(@TempDir Path tmp) throws Exception {
        Path authored = Files.createDirectories(tmp.resolve("space_a/config"));
        writeSchema(authored.resolve("orders_schema.toon"));
        Path pipeline = writePipeline(authored, "orders_schema.toon");

        Path promoted = tmp.resolve("space_b/config");
        Files.createDirectories(promoted.getParent());
        Files.move(authored, promoted);

        PipelineConfig cfg = PipelineConfig.load(promoted.resolve(pipeline.getFileName()).toString());
        assertEquals(List.of("ACCOUNT_NUMBER", "EVENT_DATE"), columns(cfg),
                "a relocated space must load with zero edits to its config");
    }

    // ── what must NOT change ───────────────────────────────────────────────────────────

    /**
     * The legacy form — resolved from the working directory, NOT from the config's directory. Written under
     * {@code target/} (build output, and a real relative path from the process CWD) because that is the only
     * way to exercise the fallback honestly; a temp dir is absolute and would take the absolute branch.
     */
    @Test
    void aWorkingDirectoryRelativeReferenceStillLoads(@TempDir Path dir) throws Exception {
        Path legacyDir = Path.of("target", "w1b-legacy");
        Files.createDirectories(legacyDir);
        Path legacySchema = legacyDir.resolve("legacy_schema.toon");
        writeSchema(legacySchema);
        try {
            // Deliberately NOT under `dir`: the config-relative candidate cannot exist, so only the
            // working-directory branch can satisfy this — exactly the pre-W1b path.
            PipelineConfig cfg = load(dir, "target/w1b-legacy/legacy_schema.toon");
            assertEquals(List.of("ACCOUNT_NUMBER", "EVENT_DATE"), columns(cfg));
        } finally {
            Files.deleteIfExists(legacySchema);
            Files.deleteIfExists(legacyDir);
        }
    }

    @Test
    void anAbsoluteReferenceIsUsedVerbatim(@TempDir Path tmp) throws Exception {
        Path elsewhere = Files.createDirectories(tmp.resolve("elsewhere"));
        writeSchema(elsewhere.resolve("abs_schema.toon"));
        Path configDir = Files.createDirectories(tmp.resolve("cfg"));
        PipelineConfig cfg = load(configDir, fwd(elsewhere.resolve("abs_schema.toon")));
        assertEquals(List.of("ACCOUNT_NUMBER", "EVENT_DATE"), columns(cfg));
    }

    /**
     * A reference that climbs out of the config's directory must not be satisfied by the new
     * config-relative branch — otherwise W1b would have turned every config into a reader of arbitrary
     * files above its own tree. It falls through to the legacy branch (which resolves from the working
     * directory and finds nothing here), so the load fails rather than silently escaping.
     *
     * <p>⚠ This is containment for the branch W1b introduces, NOT a security boundary: the legacy branch
     * remains unjailed, which is the systemic pass tracked in {@code BACKLOG.md} §6.
     */
    @Test
    void aParentEscapingReferenceIsNotResolvedAgainstTheConfigDirectory(@TempDir Path tmp) throws Exception {
        writeSchema(tmp.resolve("outside_schema.toon"));            // one level ABOVE the config dir
        Path configDir = Files.createDirectories(tmp.resolve("cfg"));
        FileNotFoundException e = assertThrows(FileNotFoundException.class,
                () -> load(configDir, "../outside_schema.toon"),
                "a ../ reference must not be resolved against the config directory");
        assertTrue(e.getMessage().contains("outside_schema.toon"), e.getMessage());
    }

    // ── the other two reference sites ──────────────────────────────────────────────────

    @Test
    void multiSchemaEntriesResolveConfigRelativeToo(@TempDir Path dir) throws Exception {
        writeSchema(dir.resolve("by_count_schema.toon"));
        Path pipeline = dir.resolve("multi.toon");
        Files.writeString(pipeline, """
                name: MULTI_ETL
                version: 1
                %s
                output:
                  format: PARQUET
                processing:
                  threads: 1
                  file_pattern: "glob:**/*.csv"
                  schemas[1]{column_count,schema_file}:
                    2,by_count_schema.toon
                """.formatted(dirsBlock(dir)), StandardCharsets.UTF_8);
        PipelineConfig cfg = PipelineConfig.load(pipeline.toString());
        assertTrue(cfg.schemas().selector().hasSchemas(),
                "a schemas[] entry must resolve config-relative like schema_file does");
    }

    @Test
    void segmentSchemasResolveConfigRelativeToo(@TempDir Path dir) throws Exception {
        writeSchema(dir.resolve("call_schema.toon"));
        Path pipeline = dir.resolve("plugin.toon");
        Files.writeString(pipeline, """
                name: PLUGIN_ETL
                version: 1
                %s
                output:
                  format: PARQUET
                processing:
                  threads: 1
                  file_pattern: "glob:**/*.bin"
                  ingester: com.acme.SomeIngester
                  segments:
                    CALL: call_schema.toon
                """.formatted(dirsBlock(dir)), StandardCharsets.UTF_8);
        PipelineConfig cfg = PipelineConfig.load(pipeline.toString());
        assertEquals(java.util.Set.of("CALL"), cfg.schemas().segments().keySet(),
                "a segments value must resolve config-relative like schema_file does");
    }

    // ── helpers ────────────────────────────────────────────────────────────────────────

    private static void writeSchema(Path at) throws Exception {
        Files.createDirectories(at.getParent());
        Files.writeString(at, SCHEMA, StandardCharsets.UTF_8);
    }

    private static List<String> columns(PipelineConfig cfg) {
        return cfg.schemas().single().get("raw") instanceof java.util.Map<?, ?> raw
                && raw.get("fields") instanceof List<?> fields
                ? fields.stream().map(f -> String.valueOf(((java.util.Map<?, ?>) f).get("name"))).toList()
                : List.of();
    }

    private static String fwd(Path p) { return p.toString().replace('\\', '/'); }

    private static String dirsBlock(Path dir) {
        String d = fwd(dir);
        return """
                dirs:
                  poll: %s/inbox
                  database: %s/db
                  backup: %s/backup
                  temp: %s/temp
                  errors: %s/errors
                  quarantine: %s/quarantine
                  status_dir: %s/status""".formatted(d, d, d, d, d, d, d);
    }

    /** Write a single-schema pipeline into {@code configDir} referencing {@code ref}, and load it. */
    private static PipelineConfig load(Path configDir, String ref) throws Exception {
        return PipelineConfig.load(writePipeline(configDir, ref).toString());
    }

    private static Path writePipeline(Path configDir, String ref) throws Exception {
        Files.createDirectories(configDir);
        Path pipeline = configDir.resolve("orders_pipeline.toon");
        Files.writeString(pipeline, """
                name: ORDERS_ETL
                version: 1
                %s
                output:
                  format: PARQUET
                processing:
                  threads: 1
                  file_pattern: "glob:**/*.csv"
                  schema_file: %s
                """.formatted(dirsBlock(configDir), ref), StandardCharsets.UTF_8);
        return pipeline;
    }
}
