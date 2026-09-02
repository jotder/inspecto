package com.gamma.config.safety;

import com.gamma.config.spec.Finding;
import com.gamma.config.spec.Severity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Adversarial tests for the hard-fail config safety gate (R6). This is the security core of M5, so
 * the cases are the attacks: path traversal, UNC, escape-the-workspace, symlink escape, and
 * out-of-bounds numerics / unknown output sinks. A clean draft under-root must pass with no findings.
 */
class ConfigSafetyValidatorTest {

    // ── config refs resolve config-relative first (W1b) ──────────────────────

    /** A pipeline whose only path surface is a `processing.schema_file` ref. */
    private static Map<String, Object> pipelineWithSchemaRef(String ref) {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("name", "TEST");
        Map<String, Object> processing = new LinkedHashMap<>();
        processing.put("schema_file", ref);
        raw.put("processing", processing);
        return raw;
    }

    /**
     * 🔴 The defect this pins. The Parse drawer deliberately writes the PORTABLE bare `<name>.toon`
     * beside its pipeline (W1b: a config ref resolves config-relative first, working-directory
     * second). This gate resolved it against the working directory instead and raised an ERROR —
     * "outside the allowed roots" for a file sitting right next to the config — which 422'd the save
     * before the two checks that resolve correctly could run. Creating a pipeline from the UI could
     * not be completed at all.
     */
    @Test
    void aPortableSchemaRefBesideItsConfigIsAccepted(@TempDir Path root) throws IOException {
        Path configDir = java.nio.file.Files.createDirectories(root.resolve("config"));
        java.nio.file.Files.writeString(configDir.resolve("e2e_schema.toon"), "raw:\n  name: e2e\n");

        List<Finding> f = ConfigSafetyValidator.check("pipeline", pipelineWithSchemaRef("e2e_schema.toon"),
                SafetyPolicy.withRoots(root), configDir);

        assertTrue(f.isEmpty(), "a ref beside its own config is contained, not an escape: " + f);
    }

    /** Without the dir the old behaviour stands — the caller that cannot supply one is unchanged. */
    @Test
    void withoutAConfigDirTheRefStillResolvesFromTheWorkingDirectory(@TempDir Path root) {
        List<Finding> f = ConfigSafetyValidator.check("pipeline", pipelineWithSchemaRef("e2e_schema.toon"),
                SafetyPolicy.withRoots(root));

        assertFalse(f.isEmpty(), "no config dir ⇒ working-directory resolution ⇒ outside the root");
    }

    /**
     * 🔴 Falsified in the other direction, because a resolution change is exactly where a jail gets
     * weakened by accident: config-relative resolution must not launder a traversal INTO the config
     * directory. `../../etc/passwd` normalises out of the base and is still refused.
     */
    @Test
    void configRelativeResolutionDoesNotLaunderATraversal(@TempDir Path root) throws IOException {
        Path configDir = java.nio.file.Files.createDirectories(root.resolve("config"));

        List<Finding> f = ConfigSafetyValidator.check("pipeline",
                pipelineWithSchemaRef("../../etc/passwd"), SafetyPolicy.withRoots(root), configDir);

        assertFalse(f.isEmpty(), "a traversal must still be refused when a config dir is supplied");
        assertEquals(Severity.ERROR, f.get(0).severity());
    }

    /**
     * ⚠ The existence half of the loader's rule. A ref that does NOT exist config-relative keeps
     * resolving from the working directory — otherwise every legacy config, whose refs resolve that
     * way and which is the form every config in this repo uses, would start failing.
     */
    @Test
    void aRefThatDoesNotExistBesideTheConfigKeepsTheWorkingDirectoryMeaning(@TempDir Path root)
            throws IOException {
        Path configDir = java.nio.file.Files.createDirectories(root.resolve("config"));

        List<Finding> f = ConfigSafetyValidator.check("pipeline", pipelineWithSchemaRef("absent.toon"),
                SafetyPolicy.withRoots(root), configDir);

        assertFalse(f.isEmpty(), "nothing beside the config ⇒ the as-authored form is judged, as before");
    }

    /** A minimal pipeline map with the given dirs map + optional processing/output overlays. */
    private static Map<String, Object> pipeline(Map<String, Object> dirs) {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("name", "TEST");
        raw.put("dirs", dirs);
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("format", "PARQUET");
        raw.put("output", output);
        return raw;
    }

    private static Map<String, Object> safeDirs(Path root) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("poll", root.resolve("inbox").toString());
        d.put("database", root.resolve("db").toString());
        d.put("backup", root.resolve("backup").toString());
        return d;
    }

    private static boolean hasError(List<Finding> findings, String fieldContains) {
        return findings.stream().anyMatch(f -> f.severity() == Severity.ERROR
                && f.fieldPath().contains(fieldContains));
    }

    @Test
    void cleanDraftUnderRootPasses(@TempDir Path root) {
        List<Finding> f = ConfigSafetyValidator.check("pipeline", pipeline(safeDirs(root)),
                SafetyPolicy.withRoots(root));
        assertTrue(f.isEmpty(), "a draft fully under the allowed root is safe: " + f);
    }

    @Test
    void dotDotEscapeIsRejected(@TempDir Path root) {
        Map<String, Object> dirs = safeDirs(root);
        dirs.put("database", root.resolve("sub").resolve("..").resolve("..").resolve("escape").toString());
        List<Finding> f = ConfigSafetyValidator.check("pipeline", pipeline(dirs), SafetyPolicy.withRoots(root));
        assertTrue(hasError(f, "dirs.database"), "`..` escape must be rejected: " + f);
    }

    @Test
    void absolutePathOutsideRootIsRejected(@TempDir Path root) {
        Map<String, Object> dirs = safeDirs(root);
        dirs.put("database", Path.of("/etc/secret-db").toAbsolutePath().toString());
        List<Finding> f = ConfigSafetyValidator.check("pipeline", pipeline(dirs), SafetyPolicy.withRoots(root));
        assertTrue(hasError(f, "dirs.database"), "an absolute path outside the root must be rejected: " + f);
    }

    // ── S5: the load-time refs the 422 gate had been blind to ───────────────────

    /** Overlay a {@code processing} block onto a clean pipeline. */
    private static Map<String, Object> withProcessing(Path root, String key, String value) {
        Map<String, Object> raw = pipeline(safeDirs(root));
        Map<String, Object> proc = new LinkedHashMap<>();
        proc.put(key, value);
        raw.put("processing", proc);
        return raw;
    }

    @Test
    void schemaFileOutsideRootIsRejected(@TempDir Path root) {
        List<Finding> f = ConfigSafetyValidator.check("pipeline",
                withProcessing(root, "schema_file", Path.of("/etc/evil_schema.toon").toAbsolutePath().toString()),
                SafetyPolicy.withRoots(root));
        assertTrue(hasError(f, "processing.schema_file"),
                "the write gate must refuse at authoring what the loader refuses at load: " + f);
    }

    @Test
    void grammarOutsideRootIsRejectedUnderBothSpellings(@TempDir Path root) {
        String evil = Path.of("/etc/evil.asn").toAbsolutePath().toString();
        List<Finding> legacy = ConfigSafetyValidator.check("pipeline",
                withProcessing(root, "grammar", evil), SafetyPolicy.withRoots(root));
        assertTrue(hasError(legacy, "processing.grammar"), "legacy spelling must be gated: " + legacy);

        Map<String, Object> raw = pipeline(safeDirs(root));
        Map<String, Object> parsing = new LinkedHashMap<>();
        parsing.put("grammar", evil);
        raw.put("parsing", parsing);
        List<Finding> preferred = ConfigSafetyValidator.check("pipeline", raw, SafetyPolicy.withRoots(root));
        assertTrue(hasError(preferred, "parsing.grammar"),
                "parsing.grammar is the design-of-record spelling and WINS over the legacy one, "
                        + "so gating only the legacy key leaves the preferred one open: " + preferred);
    }

    /**
     * ⚠ A registry reference is an <b>id</b>, not a path. Jailing it resolves {@code grammar/<id>}
     * against the working directory and reports an escape whenever the roots are not the CWD — which
     * refused a valid config at the 422 gate until {@code checkConfigRef} learned the difference.
     */
    @Test
    void aRegistryReferenceIsNotTreatedAsAPath(@TempDir Path root) {
        for (String ref : List.of("grammar/pipe-delimited", "schema/orders", "mapping/orders")) {
            String key = ref.startsWith("grammar/") ? "grammar"
                    : ref.startsWith("schema/") ? "schema_file" : "mapping_file";
            List<Finding> f = ConfigSafetyValidator.check("pipeline",
                    withProcessing(root, key, ref), SafetyPolicy.withRoots(root));
            assertFalse(hasError(f, "processing." + key),
                    "'" + ref + "' is a registry id, not a path — it must not be jailed: " + f);
        }
    }

    /** The multi-schema table form carries schema_file as a COLUMN; a scalar-only check misses it. */
    @Test
    void schemaFileInTheMultiSchemaTableFormIsAlsoRejected(@TempDir Path root) {
        Map<String, Object> raw = pipeline(safeDirs(root));
        Map<String, Object> proc = new LinkedHashMap<>();
        Map<String, Object> good = new LinkedHashMap<>();
        good.put("schema_file", root.resolve("ok_schema.toon").toString());
        Map<String, Object> bad = new LinkedHashMap<>();
        bad.put("schema_file", Path.of("/etc/evil_schema.toon").toAbsolutePath().toString());
        proc.put("schemas", List.of(good, bad));
        raw.put("processing", proc);
        List<Finding> f = ConfigSafetyValidator.check("pipeline", raw, SafetyPolicy.withRoots(root));
        assertTrue(hasError(f, "processing.schemas[1].schema_file"),
                "the escaping row must be named by index, not silently skipped: " + f);
        assertFalse(hasError(f, "processing.schemas[0].schema_file"), "the contained row must pass: " + f);
    }

    @Test
    void uncPathIsRejected(@TempDir Path root) {
        Map<String, Object> dirs = safeDirs(root);
        dirs.put("backup", "\\\\fileserver\\share\\exfil");
        List<Finding> f = ConfigSafetyValidator.check("pipeline", pipeline(dirs), SafetyPolicy.withRoots(root));
        assertTrue(hasError(f, "dirs.backup"), "UNC/network path must be rejected: " + f);
    }

    @Test
    void symlinkEscapeIsRejected(@TempDir Path root, @TempDir Path outside) throws IOException {
        // ⚠ This used to assumeTrue(false) when the OS refused a symlink, so it never actually ran on
        // Windows — the one test covering the real-path re-check, silently skipped in every green run.
        Path link = TestLinks.linkDirectory(root.resolve("sneaky"), outside);
        Map<String, Object> dirs = safeDirs(root);
        // Normalised path is under root (root/sneaky/db), but the real path resolves into `outside`.
        dirs.put("database", link.resolve("db").toString());
        List<Finding> f = ConfigSafetyValidator.check("pipeline", pipeline(dirs), SafetyPolicy.withRoots(root));
        assertTrue(hasError(f, "dirs.database"), "symlink escape must be rejected: " + f);
    }

    @Test
    void numericBoundsAreEnforced(@TempDir Path root) {
        Map<String, Object> raw = pipeline(safeDirs(root));
        Map<String, Object> proc = new LinkedHashMap<>();
        proc.put("threads", 0);                    // below min
        Map<String, Object> batch = new LinkedHashMap<>();
        batch.put("max_files", 0);                 // below min
        proc.put("batch", batch);
        Map<String, Object> dup = new LinkedHashMap<>();
        dup.put("enabled", true);
        dup.put("retention_days", 0);              // data-loss footgun
        proc.put("duplicate_check", dup);
        raw.put("processing", proc);

        List<Finding> f = ConfigSafetyValidator.check("pipeline", raw, SafetyPolicy.withRoots(root));
        assertTrue(hasError(f, "processing.threads"));
        assertTrue(hasError(f, "processing.batch.max_files"), "the legacy spelling stays bounded");
        assertTrue(hasError(f, "retention_days"));
    }

    /** CONSIGNMENT-HOME-1: the canonical home collector.consignment.* is bounded like the legacy one. */
    @Test
    void collectorConsignmentCapsAreBounded(@TempDir Path root) throws Exception {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("name", "caps");
        Map<String, Object> collector = new LinkedHashMap<>();
        Map<String, Object> consignment = new LinkedHashMap<>();
        consignment.put("max_files", 0);
        consignment.put("max_bytes", -5);
        collector.put("consignment", consignment);
        raw.put("collector", collector);
        List<Finding> f = ConfigSafetyValidator.check("pipeline", raw, SafetyPolicy.withRoots(root));
        assertTrue(hasError(f, "collector.consignment.max_files"), f.toString());
        assertTrue(hasError(f, "collector.consignment.max_bytes"), f.toString());
    }

    @Test
    void threadsAboveCpuCapIsRejected(@TempDir Path root) {
        Map<String, Object> raw = pipeline(safeDirs(root));
        Map<String, Object> proc = new LinkedHashMap<>();
        proc.put("threads", 99999);
        raw.put("processing", proc);
        List<Finding> f = ConfigSafetyValidator.check("pipeline", raw, SafetyPolicy.withRoots(root));
        assertTrue(hasError(f, "processing.threads"), "absurd thread count must be rejected: " + f);
    }

    @Test
    void unknownOutputFormatAndCompressionRejected(@TempDir Path root) {
        Map<String, Object> raw = pipeline(safeDirs(root));
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("format", "EXE");          // not in allow-list
        output.put("compression", "rar");     // not in allow-list
        raw.put("output", output);
        List<Finding> f = ConfigSafetyValidator.check("pipeline", raw, SafetyPolicy.withRoots(root));
        assertTrue(hasError(f, "output.format"));
        assertTrue(hasError(f, "output.compression"));
    }

    @Test
    void sinkDatabaseOutsideRootIsRejected(@TempDir Path root) {
        Map<String, Object> raw = pipeline(safeDirs(root));
        raw.put("sinks", List.of(
                Map.of("database", Path.of("/etc/exfil-sink").toAbsolutePath().toString(), "format", "PARQUET")));
        List<Finding> f = ConfigSafetyValidator.check("pipeline", raw, SafetyPolicy.withRoots(root));
        assertTrue(hasError(f, "sinks[0].database"), "a sink database escaping the root must be rejected: " + f);
    }

    @Test
    void sinkFormatAndCompressionAllowListEnforced(@TempDir Path root) {
        Map<String, Object> raw = pipeline(safeDirs(root));
        raw.put("sinks", List.of(new LinkedHashMap<>(Map.of(
                "database", root.resolve("sink").toString(), "format", "EXE", "compression", "rar"))));
        List<Finding> f = ConfigSafetyValidator.check("pipeline", raw, SafetyPolicy.withRoots(root));
        assertTrue(hasError(f, "sinks[0].format"));
        assertTrue(hasError(f, "sinks[0].compression"));
    }

    @Test
    void multipleValidSinksUnderRootPassCleanly(@TempDir Path root) {
        Map<String, Object> raw = pipeline(safeDirs(root));
        raw.put("sinks", List.of(
                Map.of("database", root.resolve("hot").toString(), "format", "PARQUET"),
                Map.of("database", root.resolve("cold").toString(), "format", "CSV")));
        List<Finding> f = ConfigSafetyValidator.check("pipeline", raw, SafetyPolicy.withRoots(root));
        assertTrue(f.isEmpty(), "two valid under-root destinations are safe (multi-destination is executable): " + f);
    }

    @Test
    void enrichmentOutsideRootIsRejected(@TempDir Path root) {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("name", "ENR");
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("database", root.resolve("events").toString());
        raw.put("input", in);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("database", Path.of("/var/exfil").toAbsolutePath().toString()); // escapes
        out.put("format", "PARQUET");
        raw.put("output", out);
        List<Finding> f = ConfigSafetyValidator.check("enrichment", raw, SafetyPolicy.withRoots(root));
        assertTrue(hasError(f, "output.database"), "enrichment output escaping the root must be rejected: " + f);
    }

    // ── enrichment references.<name> entries (mirrors EnrichmentConfig.fromMap's load-time hard-fails) ──

    /** A minimal valid enrichment map under {@code root} with the given references block. */
    private static Map<String, Object> enrichment(Path root, Map<String, Object> references) {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("name", "ENR");
        raw.put("input", new LinkedHashMap<>(Map.of("database", root.resolve("events").toString())));
        raw.put("output", new LinkedHashMap<>(Map.of(
                "database", root.resolve("enriched").toString(), "format", "PARQUET")));
        raw.put("references", references);
        return raw;
    }

    @Test
    void referenceEntriesWithOneOriginEachAreSafe(@TempDir Path root) {
        Map<String, Object> refs = new LinkedHashMap<>();
        refs.put("rates", Map.of("ref", "fx_rates", "as_of", "2026-07-24"));
        refs.put("plans", Map.of("ref", "plans", "as_of", "2026-07-24T10:00:00"));
        refs.put("lut", Map.of("path", root.resolve("lut.parquet").toString(), "format", "PARQUET"));
        List<Finding> f = ConfigSafetyValidator.check("enrichment", enrichment(root, refs),
                SafetyPolicy.withRoots(root));
        assertTrue(f.isEmpty(), "by-name refs (with both as_of forms) and an under-root path are safe: " + f);
    }

    @Test
    void referenceWithBothPathAndRefIsRejected(@TempDir Path root) {
        Map<String, Object> refs = new LinkedHashMap<>();
        refs.put("rates", Map.of("ref", "fx_rates", "path", root.resolve("lut.parquet").toString()));
        List<Finding> f = ConfigSafetyValidator.check("enrichment", enrichment(root, refs),
                SafetyPolicy.withRoots(root));
        assertTrue(hasError(f, "references.rates"), "both 'path' and 'ref' must be rejected: " + f);
    }

    @Test
    void referenceWithNeitherPathNorRefIsRejected(@TempDir Path root) {
        Map<String, Object> refs = new LinkedHashMap<>();
        refs.put("rates", Map.of("format", "PARQUET"));
        List<Finding> f = ConfigSafetyValidator.check("enrichment", enrichment(root, refs),
                SafetyPolicy.withRoots(root));
        assertTrue(hasError(f, "references.rates"), "an entry with neither origin must be rejected: " + f);
    }

    @Test
    void referenceIdMustBeSqlIdentifier(@TempDir Path root) {
        Map<String, Object> refs = new LinkedHashMap<>();
        refs.put("rates", Map.of("ref", "fx-rates; DROP TABLE x"));
        List<Finding> f = ConfigSafetyValidator.check("enrichment", enrichment(root, refs),
                SafetyPolicy.withRoots(root));
        assertTrue(hasError(f, "references.rates.ref"), "a non-identifier ref must be rejected: " + f);
    }

    @Test
    void referenceNameMustBeSqlIdentifier(@TempDir Path root) {
        Map<String, Object> refs = new LinkedHashMap<>();
        refs.put("bad name", Map.of("ref", "fx_rates"));
        List<Finding> f = ConfigSafetyValidator.check("enrichment", enrichment(root, refs),
                SafetyPolicy.withRoots(root));
        assertTrue(hasError(f, "references.bad name"), "a non-identifier entry name must be rejected: " + f);
    }

    @Test
    void asOfOnPathReferenceIsRejected(@TempDir Path root) {
        Map<String, Object> refs = new LinkedHashMap<>();
        refs.put("lut", Map.of("path", root.resolve("lut.parquet").toString(), "as_of", "2026-07-24"));
        List<Finding> f = ConfigSafetyValidator.check("enrichment", enrichment(root, refs),
                SafetyPolicy.withRoots(root));
        assertTrue(hasError(f, "references.lut.as_of"),
                "as_of on a plain path file must be rejected (no version history): " + f);
    }

    @Test
    void malformedAsOfIsRejected(@TempDir Path root) {
        Map<String, Object> refs = new LinkedHashMap<>();
        refs.put("rates", Map.of("ref", "fx_rates", "as_of", "1' OR '1'='1"));
        List<Finding> f = ConfigSafetyValidator.check("enrichment", enrichment(root, refs),
                SafetyPolicy.withRoots(root));
        assertTrue(hasError(f, "references.rates.as_of"),
                "a non-ISO as_of must be rejected (the value reaches SQL): " + f);
    }

    @Test
    void nonMapReferenceEntryIsRejected(@TempDir Path root) {
        Map<String, Object> refs = new LinkedHashMap<>();
        refs.put("rates", "fx_rates"); // scalar, not a {ref/path,…} map — silently dropped at load
        List<Finding> f = ConfigSafetyValidator.check("enrichment", enrichment(root, refs),
                SafetyPolicy.withRoots(root));
        assertTrue(hasError(f, "references.rates"), "a scalar entry must be rejected, not dropped: " + f);
    }

    @Test
    void referencePathEscapingRootIsRejected(@TempDir Path root) {
        Map<String, Object> refs = new LinkedHashMap<>();
        refs.put("lut", Map.of("path", Path.of("/var/exfil/lut.parquet").toAbsolutePath().toString()));
        List<Finding> f = ConfigSafetyValidator.check("enrichment", enrichment(root, refs),
                SafetyPolicy.withRoots(root));
        assertTrue(hasError(f, "references.lut.path"), "a reference path escaping the root must be rejected: " + f);
    }

    @Test
    void nonPathTypesHaveNoSafetySurface(@TempDir Path root) {
        Map<String, Object> job = Map.of("job", Map.of("name", "j", "cron", "0 2 * * *"));
        assertTrue(ConfigSafetyValidator.check("job", job, SafetyPolicy.withRoots(root)).isEmpty());
        assertTrue(ConfigSafetyValidator.check("schema", Map.of(), SafetyPolicy.withRoots(root)).isEmpty());
    }

    @Test
    void nullRawAndDefaultPolicyAreSafe() {
        assertTrue(ConfigSafetyValidator.check("pipeline", null, null).isEmpty());
        assertNotNull(SafetyPolicy.defaultPolicy().allowedRoots());
        assertFalse(SafetyPolicy.defaultPolicy().allowedRoots().isEmpty(), "defaults to user.dir");
    }
}
