package com.gamma.config.safety;

import com.gamma.api.PublicApi;
import com.gamma.config.spec.Finding;
import com.gamma.config.spec.RawConfig;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The hard-fail config safety gate (M5 / v3.5.0; security guardrail R6). A config can pass
 * {@code ConfigSpecs} validation — be structurally <em>valid</em> — and still be <em>unsafe</em>:
 * write outside the workspace, oversubscribe the box, delete its own dedup markers, or target an
 * unknown output sink. This validator catches exactly that class, returning {@code ERROR}-severity
 * {@link Finding}s for any violation so an agent draft is rejected (and repaired) before it is ever
 * surfaced to a human.
 *
 * <p>It is intentionally <b>core</b> (not in the optional agent): the security boundary must hold
 * regardless of who produced the draft. It is pure JDK ({@code nio}), zero-dependency, and stateless.
 *
 * <h3>What it enforces</h3>
 * <ul>
 *   <li><b>Path jail</b> — every path-bearing field must resolve under an {@link SafetyPolicy#allowedRoots}
 *       root: reject UNC/network paths, {@code ..} escapes, anything outside the roots, and symlink
 *       escapes (the nearest existing ancestor's real path is re-checked).</li>
 *   <li><b>Numeric bounds</b> — threads, duckdb-threads, batch caps within policy; {@code skip_*} ≥ 0;
 *       and {@code retention_days} ≥ 1 when duplicate-check is on (else the first cleanup wipes every
 *       marker — a data-loss footgun the advisory validator only warns about).</li>
 *   <li><b>Output allow-list</b> — {@code output.format} and {@code output.compression} restricted to
 *       known-safe values; DuckLake targets require their connection fields when enabled.</li>
 *   <li><b>Enrichment references</b> — each {@code references.<name>} entry mirrors the hard-fails
 *       {@code EnrichmentConfig.fromMap} applies at load ({@code ref} XOR {@code path}, SQL-identifier
 *       names, ISO {@code as_of} requiring a by-name {@code ref}), so a bad reference is refused at
 *       the write gate instead of only at registration.</li>
 * </ul>
 *
 * <p>Only the path-bearing config types ({@code pipeline}, {@code enrichment}) have a surface to gate;
 * {@code job}/{@code schema}/{@code meta} return no safety findings.
 *
 * @since 3.5.0
 */
@PublicApi(since = "4.0.0")
public final class ConfigSafetyValidator {

    private ConfigSafetyValidator() {}

    /** Same rule as the ETL layer's {@code Identifiers.validate} — values interpolated into SQL. */
    private static final Pattern SQL_IDENTIFIER = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");

    private static final String[] PIPELINE_DIRS = {
            "dirs.poll", "dirs.database", "dirs.backup", "dirs.temp", "dirs.errors",
            "dirs.quarantine", "dirs.markers", "dirs.status_dir", "dirs.log_dir"
    };
    private static final String[] PIPELINE_SKIPS = {
            "processing.csv_settings.skip_header_lines", "processing.csv_settings.skip_junk_lines",
            "processing.csv_settings.skip_tail_lines", "processing.csv_settings.skip_tail_columns"
    };

    /**
     * Check {@code raw} (a decoded config map) against {@code policy}. Returns every safety violation
     * as an {@code ERROR} {@link Finding}; an empty list means the draft is safe. Never throws.
     */
    public static List<Finding> check(String configType, Map<String, Object> raw, SafetyPolicy policy) {
        List<Finding> out = new ArrayList<>();
        if (raw == null) return out;
        SafetyPolicy p = (policy == null) ? SafetyPolicy.defaultPolicy() : policy;
        String type = (configType == null) ? "" : configType.toLowerCase();
        switch (type) {
            case "pipeline" -> checkPipeline(raw, p, out);
            case "enrichment" -> checkEnrichment(raw, p, out);
            default -> { /* job / schema / meta: no path/numeric/output surface to gate */ }
        }
        return out;
    }

    // ── pipeline ─────────────────────────────────────────────────────────────────────

    private static void checkPipeline(Map<String, Object> raw, SafetyPolicy p, List<Finding> out) {
        for (String f : PIPELINE_DIRS) checkPath(raw, f, p, out);
        checkPath(raw, "output.ducklake.data_path", p, out);

        checkIntBound(raw, "processing.threads", 1, p.maxThreads(), out);
        checkIntBound(raw, "processing.duckdb_threads", -1, p.maxThreads(), out);
        checkIntBound(raw, "processing.batch.max_files", 1, p.maxBatchFiles(), out);

        Object maxBytes = RawConfig.at(raw, "processing.batch.max_bytes");
        if (maxBytes != null) {
            long v = longOr(raw, "processing.batch.max_bytes", 1);
            if (v <= 0) {
                out.add(Finding.error("processing.batch.max_bytes",
                        "batch.max_bytes must be > 0 (got " + v + ")"));
            } else if (v > p.maxBatchBytes()) {
                out.add(Finding.error("processing.batch.max_bytes",
                        "batch.max_bytes " + v + " exceeds the safety cap " + p.maxBatchBytes()));
            }
        }

        for (String f : PIPELINE_SKIPS) {
            if (RawConfig.at(raw, f) != null && RawConfig.intOr(raw, f, 0) < 0) {
                out.add(Finding.error(f, f + " must be >= 0"));
            }
        }

        if (RawConfig.boolOr(raw, "processing.duplicate_check.enabled", false)
                && RawConfig.at(raw, "processing.duplicate_check.retention_days") != null
                && RawConfig.intOr(raw, "processing.duplicate_check.retention_days", 90) <= 0) {
            out.add(Finding.error("processing.duplicate_check.retention_days",
                    "retention_days must be >= 1 when duplicate_check is enabled "
                            + "(else every dedup marker is wiped on the first cleanup)"));
        }

        checkOutput(raw, "output.format", "output.compression", p, out);
        checkDuckLake(raw, out);

        // ── sinks (plural destinations) ──
        // Each entry is a {database, format, compression, ducklake} tuple; validate the same path-jail /
        // allow-list surface the single output: has. (Multi-destination ingest is executable; the one
        // unsupported combination — a versioned reference store with >1 destination — is refused at load,
        // PipelineConfig.prepare().)
        if (raw.get("sinks") instanceof List<?> sinks) {
            for (int i = 0; i < sinks.size(); i++) {
                if (sinks.get(i) instanceof Map<?, ?> sink) {
                    checkSink(i, sink, p, out);
                } else {
                    out.add(Finding.error("sinks[" + i + "]", "each sinks[] entry must be a map"));
                }
            }
        }
    }

    /** Path-jail + format/compression/ducklake allow-list for one {@code sinks[i]} destination entry. */
    private static void checkSink(int i, Map<?, ?> sink, SafetyPolicy p, List<Finding> out) {
        String prefix = "sinks[" + i + "]";
        Object db = sink.get("database");
        if (db != null && !db.toString().isBlank()) checkPathValue(prefix + ".database", db.toString(), p, out);

        Object fmt = sink.get("format");
        if (fmt != null && !fmt.toString().isBlank()
                && !p.allowedFormats().contains(fmt.toString().trim().toUpperCase())) {
            out.add(Finding.error(prefix + ".format", "output format '" + fmt
                    + "' is not in the allow-list " + p.allowedFormats()));
        }
        Object comp = sink.get("compression");
        if (comp != null && !comp.toString().isBlank()
                && !p.allowedCompression().contains(comp.toString().trim().toLowerCase())) {
            out.add(Finding.error(prefix + ".compression", "compression '" + comp
                    + "' is not in the allow-list " + p.allowedCompression()));
        }
        if (sink.get("ducklake") instanceof Map<?, ?> dl) {
            Object dp = dl.get("data_path");
            if (dp != null && !dp.toString().isBlank()) {
                checkPathValue(prefix + ".ducklake.data_path", dp.toString(), p, out);
            }
            if (Boolean.TRUE.equals(dl.get("enabled"))) {
                for (String k : new String[]{"catalog_url", "data_path", "table"}) {
                    Object v = dl.get(k);
                    if (v == null || v.toString().isBlank()) {
                        out.add(Finding.error(prefix + ".ducklake." + k,
                                prefix + ".ducklake." + k + " is required when ducklake is enabled"));
                    }
                }
            }
        }
    }

    // ── enrichment ───────────────────────────────────────────────────────────────────

    private static void checkEnrichment(Map<String, Object> raw, SafetyPolicy p, List<Finding> out) {
        checkPath(raw, "input.database", p, out);
        checkPath(raw, "output.database", p, out);
        checkPath(raw, "transform_file", p, out);

        Object refs = RawConfig.at(raw, "references");
        if (refs instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (e.getValue() instanceof Map<?, ?> ref) {
                    checkReference(String.valueOf(e.getKey()), ref, p, out);
                } else {
                    out.add(Finding.error("references." + e.getKey(),
                            "each references.<name> entry must be a map"));
                }
            }
        }
        checkOutput(raw, "output.format", "output.compression", p, out);
    }

    /**
     * Per-entry gate for one {@code references.<name>} map entry, mirroring the hard-fails
     * {@code EnrichmentConfig.fromMap} applies at load — so a hand-authored or API-written config with
     * a bad reference is refused at the write-time 422 gate instead of only failing at registration.
     * The identifier and {@code as_of} checks are safety-proper: both values are spliced into the
     * as-of view's SQL downstream.
     */
    private static void checkReference(String name, Map<?, ?> ref, SafetyPolicy p, List<Finding> out) {
        String prefix = "references." + name;
        if (!SQL_IDENTIFIER.matcher(name).matches()) {
            out.add(Finding.error(prefix, "reference name '" + name
                    + "' is not a valid SQL identifier ([A-Za-z_][A-Za-z0-9_]*)"));
        }
        Object pathV = ref.get("path");
        Object refV = ref.get("ref");
        boolean hasPath = pathV != null && !pathV.toString().isBlank();
        boolean hasRef = refV != null && !refV.toString().isBlank();
        if (hasPath == hasRef) {
            out.add(Finding.error(prefix, prefix + " needs exactly one of 'path' or 'ref'"));
        }
        if (hasPath) checkPathValue(prefix + ".path", pathV.toString(), p, out);
        if (hasRef && !SQL_IDENTIFIER.matcher(refV.toString().trim()).matches()) {
            out.add(Finding.error(prefix + ".ref", "reference id '" + refV.toString().trim()
                    + "' is not a valid SQL identifier ([A-Za-z_][A-Za-z0-9_]*)"));
        }
        Object asOf = ref.get("as_of");
        if (asOf != null && !asOf.toString().isBlank()) {
            String s = asOf.toString().trim();
            try {
                if (s.length() == 10) LocalDate.parse(s);
                else LocalDateTime.parse(s.replace(' ', 'T'));
            } catch (DateTimeParseException ex) {
                out.add(Finding.error(prefix + ".as_of", prefix + ".as_of must be an ISO-8601 "
                        + "date or date-time (2026-07-24 or 2026-07-24T10:00:00), got: '" + s + "'"));
            }
            if (!hasRef) {
                out.add(Finding.error(prefix + ".as_of", prefix
                        + ".as_of needs a by-name 'ref' — a plain 'path' file carries no version history"));
            }
        }
    }

    // ── path jail ────────────────────────────────────────────────────────────────────

    private static void checkPath(Map<String, Object> raw, String field, SafetyPolicy p, List<Finding> out) {
        String v = RawConfig.str(raw, field);
        if (v != null && !v.isBlank()) checkPathValue(field, v, p, out);
    }

    private static void checkPathValue(String field, String value, SafetyPolicy p, List<Finding> out) {
        String s = value.trim();
        if (s.startsWith("\\\\") || s.startsWith("//")) {
            out.add(Finding.error(field, "path '" + s + "' is a UNC/network path, which is not allowed"));
            return;
        }
        Path norm;
        try {
            norm = Paths.get(s).toAbsolutePath().normalize();
        } catch (RuntimeException ex) {
            out.add(Finding.error(field, "path '" + s + "' is not a valid path: " + ex.getMessage()));
            return;
        }
        if (underAnyRoot(norm, p.allowedRoots())) return;

        // Say WHICH failure it was: a path that looks contained but is not has escaped through a link,
        // and telling an operator "outside the allowed roots" for a path that visibly is not would
        // send them hunting the wrong thing.
        boolean looksContained = p.allowedRoots().stream().anyMatch(norm::startsWith);
        out.add(looksContained
                ? Finding.error(field, "path '" + s
                        + "' escapes the allowed roots via a symlink (resolves to " + norm + ")")
                : Finding.error(field, "path '" + s + "' resolves to " + norm
                        + ", outside the allowed roots " + p.allowedRoots()));
    }

    /**
     * Containment against any allowed root, delegated to {@link PathJail#contains} — which also
     * performs the symlink-escape re-check this method used to open-code.
     *
     * <p>Sharing the verdict with the enforcing surface is the point: an advisory gate that disagrees
     * with the jail it is supposed to pre-empt lets a draft pass authoring and then fail at load, or
     * worse, the reverse. The <em>resolution</em> policy stays here (config values are CWD-relative);
     * only the containment decision is shared.
     */
    private static boolean underAnyRoot(Path candidate, List<Path> roots) {
        for (Path root : roots) {
            if (PathJail.contains(root, candidate)) return true;
        }
        return false;
    }

    // ── numeric + output ───────────────────────────────────────────────────────────────

    private static void checkIntBound(Map<String, Object> raw, String field, int min, int max, List<Finding> out) {
        if (RawConfig.at(raw, field) == null) return;
        int n = RawConfig.intOr(raw, field, min); // unparseable -> in-bounds (a spec concern, not safety)
        if (n < min || n > max) {
            out.add(Finding.error(field, field + " must be in [" + min + ", " + max + "] (got " + n + ")"));
        }
    }

    private static void checkOutput(Map<String, Object> raw, String fmtField, String compField,
                                    SafetyPolicy p, List<Finding> out) {
        String fmt = RawConfig.str(raw, fmtField);
        if (fmt != null && !fmt.isBlank() && !p.allowedFormats().contains(fmt.trim().toUpperCase())) {
            out.add(Finding.error(fmtField, "output format '" + fmt + "' is not in the allow-list "
                    + p.allowedFormats()));
        }
        String comp = RawConfig.str(raw, compField);
        if (comp != null && !comp.isBlank() && !p.allowedCompression().contains(comp.trim().toLowerCase())) {
            out.add(Finding.error(compField, "compression '" + comp + "' is not in the allow-list "
                    + p.allowedCompression()));
        }
    }

    private static void checkDuckLake(Map<String, Object> raw, List<Finding> out) {
        if (!RawConfig.boolOr(raw, "output.ducklake.enabled", false)) return;
        for (String k : new String[]{"output.ducklake.catalog_url", "output.ducklake.data_path",
                "output.ducklake.table"}) {
            if (!RawConfig.present(raw, k)) {
                out.add(Finding.error(k, k + " is required when output.ducklake.enabled is true"));
            }
        }
    }

    private static long longOr(Map<String, Object> raw, String field, long def) {
        Object v = RawConfig.at(raw, field);
        if (v == null) return def;
        try {
            return Long.parseLong(v.toString().trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
