package com.gamma.config.safety;

import com.gamma.config.spec.Finding;
import com.gamma.config.spec.Severity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Adversarial tests for the hard-fail config safety gate (R6). This is the security core of M5, so
 * the cases are the attacks: path traversal, UNC, escape-the-workspace, symlink escape, and
 * out-of-bounds numerics / unknown output sinks. A clean draft under-root must pass with no findings.
 */
class ConfigSafetyValidatorTest {

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

    @Test
    void uncPathIsRejected(@TempDir Path root) {
        Map<String, Object> dirs = safeDirs(root);
        dirs.put("backup", "\\\\fileserver\\share\\exfil");
        List<Finding> f = ConfigSafetyValidator.check("pipeline", pipeline(dirs), SafetyPolicy.withRoots(root));
        assertTrue(hasError(f, "dirs.backup"), "UNC/network path must be rejected: " + f);
    }

    @Test
    void symlinkEscapeIsRejected(@TempDir Path root, @TempDir Path outside) throws IOException {
        Path link = root.resolve("sneaky");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (IOException | UnsupportedOperationException e) {
            assumeTrue(false, "OS cannot create symlinks here; skipping symlink-escape case");
        }
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
        assertTrue(hasError(f, "processing.batch.max_files"));
        assertTrue(hasError(f, "retention_days"));
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
