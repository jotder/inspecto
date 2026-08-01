package com.gamma.pipeline;

import com.gamma.api.PublicApi;
import com.gamma.config.io.ConfigCodec;
import com.gamma.etl.PipelineConfig;
import com.gamma.etl.SchemaSelector;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Compile-back: recovers, from a lifted {@link PipelineGraph}, the execution inputs the existing engine
 * consumes — the inverse of {@link PipelineLift}. Because the lift is lossless (it carries the typed
 * {@code PipelineConfig} sub-records / schema maps / {@code SchemaSelector} verbatim as node config
 * values), {@code compile} simply groups the nodes back by role; every original input is recoverable
 * unchanged. A round-trip ({@code lift → compile}) that returns the same inputs is the <b>Phase-1
 * parity gate</b> (the IR loses nothing).
 *
 * <p><b>Scope (Phase 1):</b> this recovers the inputs; it does not yet <em>invoke</em>
 * {@code CollectorProcessor} from them — driving the engine from a {@code PipelineGraph} (so the existing
 * suite literally runs through the lifted path) needs the branch-aware executor scheduled for Phase 3
 * (doc §13 R3 / §14 T12). Until then the lossless round-trip below is the gate.
 */
@PublicApi(since = "4.3.0")
public final class PipelineCompiler {

    private PipelineCompiler() {}

    /**
     * The execution inputs recovered from a {@link PipelineGraph}, grouped by role.
     *
     * @param name        the pipeline id ({@link PipelineGraph#name()})
     * @param active      the poll gate ({@link PipelineGraph#active()})
     * @param acquisition the single entry {@code acquisition} node (the engine's {@code source:} + {@code dirs.poll})
     * @param parser      the single {@code parser} node (csv/grammar/fixedwidth + schema(s)/selector/segments)
     * @param dedups      the dedup nodes ({@code marker} and/or {@code fingerprint}), in chain order
     * @param sinks       every {@code sink} node (per-schema outputs + any quarantine)
     * @param gap         the optional {@code gap} reporting node
     */
    public record Compiled(String name, boolean active,
                           Optional<PipelineNode> acquisition, Optional<PipelineNode> parser,
                           List<PipelineNode> dedups, List<PipelineNode> sinks, Optional<PipelineNode> gap) {}

    /** Recover the engine inputs from {@code g} by grouping its nodes by role. */
    public static Compiled compile(PipelineGraph g) {
        PipelineNode acq = null, parser = null, gap = null;
        List<PipelineNode> dedups = new ArrayList<>();
        List<PipelineNode> sinks = new ArrayList<>();
        for (PipelineNode n : g.nodes()) {
            String t = n.type();
            if (BuiltinNodeType.ACQUISITION.type().equals(t)) acq = n;
            else if (BuiltinNodeType.PARSER.type().equals(t)) parser = n;
            else if (BuiltinNodeType.GAP.type().equals(t)) gap = n;
            else if (PipelineNodeTypes.isCategory(t, NodeCategory.SINK)) sinks.add(n);   // any sink subtype
            else if (BuiltinNodeType.TRANSFORM_DEDUP_MARKER.type().equals(t)
                    || BuiltinNodeType.TRANSFORM_DEDUP_FINGERPRINT.type().equals(t)) dedups.add(n);
        }
        return new Compiled(g.name(), g.active(),
                Optional.ofNullable(acq), Optional.ofNullable(parser),
                List.copyOf(dedups), List.copyOf(sinks), Optional.ofNullable(gap));
    }

    /**
     * <b>T5b — compile a lifted graph back to a runnable {@code PipelineConfig.fromMap}-shaped map.</b>
     * Reconstructs the raw config map from a lifted {@link PipelineGraph}, writing the lift's stored schema
     * map to {@code schemaDir} as a {@code .toon} file ({@code fromMap} re-reads schema files from disk).
     * Round-tripping {@code lift → toConfigMap → fromMap → run} reproduces today's <b>data output</b> —
     * the execution-through-lift parity gate (proven for single-schema in {@code PipelineExecutionParityTest}).
     *
     * <p>Scope: <b>single-schema</b>, <b>selector</b> (column-count dispatch), <b>segments</b> (plugin
     * ingester — schemas written to {@code schemaDir} as {@code .toon} files), and <b>fixed-width</b>
     * (text frontend; augments {@code csv_settings} with {@code frontend} + slice layout). Operational,
     * non-data knobs the IR does not model ({@code status_dir}/{@code errors}/{@code log_dir}) are
     * intentionally omitted — status is simply disabled in the rebuilt config, which does not affect the
     * data output.
     */
    public static Map<String, Object> toConfigMap(PipelineGraph g, Path schemaDir) throws IOException {
        Compiled c = compile(g);
        PipelineNode acq = c.acquisition().orElseThrow(() -> new IllegalArgumentException("graph has no acquisition node"));
        PipelineNode parser = c.parser().orElseThrow(() -> new IllegalArgumentException("graph has no parser node"));
        PipelineNode sink = c.sinks().stream().filter(s -> s.cfg("database") != null).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("graph has no persistent sink with a database dir"));

        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("name", g.name());
        raw.put("active", g.active());
        putIfPresent(raw, "trigger", acq.cfg("trigger"));   // T13: entry-node trigger round-trips (§3.6)

        // ── collector (W0): reconstruct the source: block the parser reads (PipelineConfigParser:390-500).
        // The lift carries the acquisition node's typed sub-records verbatim (Stability/Fetch/Retry/…), plus
        // Duplicate + Incremental on the fingerprint node and GapDetection on the gap node; this emits their
        // inverse in the raw map shape fromMap re-parses. Absent ⇒ the parser's implicit LOCAL default, so
        // a plain local pipeline still emits a block that re-parses to the identical Collector record. ──
        Map<String, Object> collector = collectorBlock(g, acq, c);
        if (!collector.isEmpty()) raw.put("collector", collector);

        // ── dirs (data-relevant only; status_dir/errors/log_dir intentionally omitted) ──
        Map<String, Object> dirs = new LinkedHashMap<>();
        putIfPresent(dirs, "poll", acq.cfg("poll"));
        putIfPresent(dirs, "database", sink.cfg("database"));
        putIfPresent(dirs, "backup", sink.cfg("backup"));
        putIfPresent(dirs, "temp", sink.cfg("temp"));
        c.dedups().stream().filter(d -> BuiltinNodeType.TRANSFORM_DEDUP_MARKER.type().equals(d.type())).findFirst()
                .ifPresent(d -> putIfPresent(dirs, "markers", d.cfg("markers_dir")));
        c.sinks().stream().filter(s -> s.cfg("dir") != null).findFirst()
                .ifPresent(qs -> putIfPresent(dirs, "quarantine", qs.cfg("dir")));
        raw.put("dirs", dirs);

        // ── output ──
        Map<String, Object> output = new LinkedHashMap<>();
        putIfPresent(output, "format", sink.cfg("format"));
        putIfPresent(output, "compression", sink.cfg("compression"));
        putIfPresent(output, "ducklake", sink.cfg("ducklake"));
        raw.put("output", output);

        // ── processing ──
        Map<String, Object> proc = new LinkedHashMap<>();
        putIfPresent(proc, "threads", sink.cfg("threads"));
        putIfPresent(proc, "duckdb_threads", sink.cfg("duckdb_threads"));
        putIfPresent(proc, "file_pattern", acq.cfg("file_pattern"));   // present once the lift carries it; else default glob
        c.dedups().stream().filter(d -> BuiltinNodeType.TRANSFORM_DEDUP_MARKER.type().equals(d.type())).findFirst()
                .ifPresent(d -> {
                    Map<String, Object> dc = new LinkedHashMap<>();
                    dc.put("enabled", true);
                    putIfPresent(dc, "marker_extension", d.cfg("marker_extension"));
                    putIfPresent(dc, "retention_days", d.cfg("retention_days"));
                    proc.put("duplicate_check", dc);
                });
        if (parser.cfg("csv") instanceof PipelineConfig.CsvSettings csv) proc.put("csv_settings", csvSettingsToMap(csv));

        Files.createDirectories(schemaDir);
        if (parser.cfg("selector") instanceof SchemaSelector selector && selector.hasSchemas()) {
            // multi-schema selector → processing.schemas[] (column-count dispatch; one schema file per entry)
            List<Map<String, Object>> schemas = new ArrayList<>();
            int i = 0;
            for (SchemaSelector.Descriptor d : selector.descriptors()) {
                Path sf = schemaDir.resolve(g.name() + "_schema_" + (i++) + ".toon");
                Files.writeString(sf, ConfigCodec.toToon(d.schema()));
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("column_count", d.columnCount());
                entry.put("schema_file", sf.toString().replace('\\', '/'));
                if (d.table() != null && !d.table().isBlank()) entry.put("table", d.table());
                schemas.add(entry);
            }
            proc.put("schemas", schemas);
        } else if (parser.cfg("schema") instanceof Map<?, ?> schemaMap) {
            // single legacy schema → processing.schema_file
            Path sf = schemaDir.resolve(g.name() + "_schema.toon");
            Files.writeString(sf, ConfigCodec.toToon(schemaMap));
            proc.put("schema_file", sf.toString().replace('\\', '/'));
        } else if (parser.cfg("segments") instanceof Map<?, ?> segMap && !segMap.isEmpty()) {
            // segments: write each in-memory schema map to a .toon file; fromMap re-reads from disk
            putIfPresent(proc, "ingester", parser.cfg("ingester"));
            if (parser.cfg("ingester_config") instanceof Map<?, ?> icfg && !icfg.isEmpty())
                proc.put("ingester_config", icfg);
            Map<String, String> segPaths = new LinkedHashMap<>();
            int i = 0;
            for (Map.Entry<?, ?> e : segMap.entrySet()) {
                Path sf = schemaDir.resolve(g.name() + "_seg_" + (i++) + ".toon");
                Files.writeString(sf, ConfigCodec.toToon((Map<?, ?>) e.getValue()));
                segPaths.put((String) e.getKey(), sf.toString().replace('\\', '/'));
            }
            proc.put("segments", segPaths);
        } else {
            throw new UnsupportedOperationException("toConfigMap: no schema / selector / segments in parser node");
        }

        // fixed-width: augments csv_settings (additive — accompanies any schema shape)
        if (parser.cfg("fixedwidth") instanceof PipelineConfig.FixedWidth fw) {
            @SuppressWarnings("unchecked")
            Map<String, Object> csvMap = (Map<String, Object>)
                    proc.computeIfAbsent("csv_settings", k -> new LinkedHashMap<String, Object>());
            csvMap.put("frontend", "fixedwidth");
            csvMap.put("fixedwidth", fixedWidthToMap(fw));
        }

        // json frontend: augments csv_settings (additive — same shape as fixed-width)
        if (parser.cfg("json") instanceof PipelineConfig.Json j) {
            @SuppressWarnings("unchecked")
            Map<String, Object> csvMap = (Map<String, Object>)
                    proc.computeIfAbsent("csv_settings", k -> new LinkedHashMap<String, Object>());
            csvMap.put("frontend", "json");
            Map<String, Object> jm = new LinkedHashMap<>();
            jm.put("format", j.format());
            csvMap.put("json", jm);
        }

        // text_regex frontend: augments csv_settings (additive — same shape as fixed-width)
        if (parser.cfg("text_regex") instanceof PipelineConfig.TextRegex tr) {
            @SuppressWarnings("unchecked")
            Map<String, Object> csvMap = (Map<String, Object>)
                    proc.computeIfAbsent("csv_settings", k -> new LinkedHashMap<String, Object>());
            csvMap.put("frontend", "text_regex");
            Map<String, Object> tm = new LinkedHashMap<>();
            tm.put("pattern", tr.pattern());
            csvMap.put("text_regex", tm);
        }

        raw.put("processing", proc);
        return raw;
    }

    /**
     * Reconstruct the {@code collector:} block the parser reads ({@link com.gamma.etl.PipelineConfigParser}
     * lines 390-500) from the lifted acquisition node's typed sub-records (plus {@code duplicate}/
     * {@code incremental} on the fingerprint dedup node and {@code gap_detection} on the gap node). Only keys
     * that differ from the parser's implicit LOCAL defaults are emitted, so a plain local pipeline stays terse
     * while still re-parsing to the identical {@code Collector} record. Durations are emitted in seconds
     * ({@code "<n>s"}) — every source duration here is second-granular by construction.
     */
    private static Map<String, Object> collectorBlock(PipelineGraph g, PipelineNode acq, Compiled c) {
        Map<String, Object> src = new LinkedHashMap<>();

        // ── identity / discovery (emit only when != the parser's implicit LOCAL defaults) ──
        Object id = acq.cfg("id");
        if (id != null && !id.equals(g.name())) src.put("id", id);
        Object connector = acq.cfg("connector");
        if (connector != null && !"local".equals(connector)) src.put("connector", connector);
        if (acq.use() != null && acq.use().startsWith("connection/"))
            src.put("connection", acq.use().substring("connection/".length()));
        if (acq.cfg("includes") instanceof List<?> inc && !inc.isEmpty()) src.put("include", inc);
        if (acq.cfg("excludes") instanceof List<?> exc && !exc.isEmpty()) src.put("exclude", exc);
        if (acq.cfg("recursive_depth") instanceof Integer d && d != -1) src.put("recursive_depth", d);
        Object discovery = acq.cfg("discovery");
        if (discovery != null && !"poll".equals(discovery)) src.put("discovery", discovery);

        // ── stability (Phase B) — absent ⇒ DISABLED ──
        if (acq.cfg("stability") instanceof PipelineConfig.Stability st && st.enabled()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("window", durationSeconds(st.windowMillis()));
            m.put("size_checks", st.sizeChecks());
            if (st.readyMarker() != null) m.put("ready_marker", st.readyMarker());
            m.put("exclude_temp_files", st.excludeTempFiles());
            if (!st.tempPatterns().isEmpty()) m.put("exclude_temp_patterns", st.tempPatterns());
            src.put("stability", m);
        }

        // ── guarantee (Phase D) — absent ⇒ BEST_EFFORT ──
        if (acq.cfg("guarantee") instanceof PipelineConfig.Guarantee guar
                && guar != PipelineConfig.Guarantee.BEST_EFFORT)
            src.put("guarantee", guar.name());

        // ── fetch (Phase E/F) — absent ⇒ DEFAULT ──
        if (acq.cfg("fetch") instanceof PipelineConfig.Fetch f && !f.equals(PipelineConfig.Fetch.DEFAULT)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("mode", f.mode());
            if (f.stagingDir() != null) m.put("staging_dir", f.stagingDir());
            m.put("parallel_fetch", f.parallelFetch());
            if (f.rateLimitBytesPerSec() > 0) m.put("rate_limit", f.rateLimitBytesPerSec());
            src.put("fetch", m);
        }

        // ── retry (Phase F) — absent ⇒ a single attempt ──
        if (acq.cfg("retry") instanceof PipelineConfig.Retry r && r.enabled()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("count", r.count());
            m.put("backoff", r.backoff());
            m.put("initial_delay", durationSeconds(r.initialDelayMillis()));
            m.put("max_delay", durationSeconds(r.maxDelayMillis()));
            src.put("retry", m);
        }

        // ── circuit breaker (Phase F) — absent ⇒ never trips ──
        if (acq.cfg("circuit_breaker") instanceof PipelineConfig.CircuitBreaker cb && cb.enabled()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("failure_threshold", cb.failureThreshold());
            m.put("cooldown", durationSeconds(cb.cooldownMillis()));
            src.put("circuit_breaker", m);
        }

        // ── post_action (Phase F) — absent ⇒ RETAIN ──
        if (acq.cfg("post_action") instanceof PipelineConfig.PostActionConfig pa && pa.active()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("on_success", pa.onSuccess());
            if (pa.archivePath() != null) m.put("archive_path", pa.archivePath());
            if (!pa.tags().isEmpty()) m.put("tags", pa.tags());
            m.put("on_unsupported", pa.onUnsupported());
            src.put("post_action", m);
        }

        // ── duplicate + incremental live on the fingerprint dedup node (content-based dedup only) ──
        c.dedups().stream()
                .filter(d -> BuiltinNodeType.TRANSFORM_DEDUP_FINGERPRINT.type().equals(d.type()))
                .findFirst().ifPresent(fp -> {
            if (fp.cfg("duplicate") instanceof PipelineConfig.Duplicate dup && dup.contentBased()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("mode", dup.mode());
                m.put("algorithm", dup.algorithm());
                m.put("on_change", dup.onChange());
                src.put("duplicate", m);
            }
            if (fp.cfg("incremental") instanceof PipelineConfig.Incremental in && in.enabled()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("watermark", in.watermark());
                src.put("incremental", m);
            }
        });

        // ── gap_detection lives on the gap node (Phase D) — absent ⇒ off ──
        c.gap().ifPresent(gapNode -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("enabled", true);
            putIfPresent(m, "sequence", gapNode.cfg("sequence"));
            src.put("gap_detection", m);
        });

        return src;
    }

    /** Emit a millisecond duration as a whole-second string ({@code "30s"}) the parser's {@code toMillis} reads. */
    private static String durationSeconds(long millis) {
        return (millis / 1000L) + "s";
    }

    private static Map<String, Object> csvSettingsToMap(PipelineConfig.CsvSettings c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("delimiter", c.delimiter());
        m.put("has_header", c.hasHeader());
        m.put("skip_header_lines", c.skipHeaderLines());
        m.put("skip_junk_lines", c.skipJunkLines());
        m.put("skip_tail_lines", c.skipTailLines());
        m.put("skip_tail_columns", c.skipTailCols());
        if (c.engine() != null && !"auto".equalsIgnoreCase(c.engine())) m.put("engine", c.engine());
        if (!c.dateFormats().isEmpty()) m.put("date_formats", c.dateFormats());
        if (!c.tsFormats().isEmpty()) m.put("timestamp_formats", c.tsFormats());
        if (c.encoding() != null) m.put("encoding", c.encoding());
        if (c.inputCompression() != null) m.put("compression", c.inputCompression());
        if (c.strictMode() != null) m.put("strict_mode", c.strictMode());
        if (!c.nullStrings().isEmpty()) m.put("null_strings", c.nullStrings());
        if (!c.includePrefixes().isEmpty()) m.put("include_prefixes", c.includePrefixes());
        if (!c.includeRegex().isEmpty()) m.put("include_regex", c.includeRegex());
        if (!c.excludePrefixes().isEmpty()) m.put("exclude_prefixes", c.excludePrefixes());
        if (!c.excludeRegex().isEmpty()) m.put("exclude_regex", c.excludeRegex());
        if (c.filterTargetColumn() != 0) m.put("filter_target_column", c.filterTargetColumn());
        return m;
    }

    private static Map<String, Object> fixedWidthToMap(PipelineConfig.FixedWidth fw) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("record", fw.binary() ? "bytes" : "line");
        if (fw.binary()) m.put("record_length", fw.recordLength());
        if (fw.trim() != PipelineConfig.FixedWidth.Trim.BOTH) m.put("trim", fw.trim().name().toLowerCase());
        m.put("min_record_length", fw.minRecordLength());
        List<Map<String, Object>> fields = new ArrayList<>();
        for (PipelineConfig.FixedWidth.Slice s : fw.slices()) {
            Map<String, Object> f = new LinkedHashMap<>();
            if (s.name() != null) f.put("name", s.name());
            f.put("start", s.start());
            f.put("length", s.length());
            fields.add(f);
        }
        m.put("fields", fields);
        return m;
    }

    private static void putIfPresent(Map<String, Object> m, String key, Object v) {
        if (v != null) m.put(key, v);
    }
}
