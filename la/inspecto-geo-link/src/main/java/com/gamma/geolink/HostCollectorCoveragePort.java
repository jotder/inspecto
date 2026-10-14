package com.gamma.geolink;

import com.gamma.spi.http.ApiContext;
import com.gamma.control.HostContext;
import com.gamma.etl.PipelineConfig;
import com.gamma.la.core.CollectorCoveragePort;
import com.gamma.mask.EvidenceMasker;
import com.gamma.util.Csv;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The BRIDGE's {@link CollectorCoveragePort}: for every registered Pipeline, join its {@code batches} audit CSV
 * (Consignment to output store) to its {@code lineage} CSV (Consignment to event-day partition and rows), keep the
 * batches that wrote one of the Dataset's stores ({@link EvidenceMasker#datasetStores}), and attribute each to the
 * Pipeline's Collector id. The same audit files {@code GET /lineage} reads; best-effort per file.
 */
public final class HostCollectorCoveragePort implements CollectorCoveragePort {

    /** {@code year=2026/month=09/day=03} or {@code dt=2026-09-03}: the event day the Pipeline cut the partition at. */
    private static final Pattern HIVE = Pattern.compile("year=(\\d{4})/month=(\\d{1,2})/day=(\\d{1,2})");
    private static final Pattern DT = Pattern.compile("dt=(\\d{4}-\\d{2}-\\d{2})");

    @Override
    public Optional<List<Delivery>> deliveries(ApiContext api, Map<String, Object> dataset) {
        Set<String> stores = EvidenceMasker.datasetStores(dataset);
        if (stores.isEmpty()) return Optional.empty();
        Set<String> origins = origins(stores);
        var service = HostContext.of(api).service();
        Map<String, Map<String, Long>> byCollector = new TreeMap<>();   // collector -> day -> rows
        for (var pv : service.pipelines()) {
            Optional<PipelineConfig> cfg = service.configFor(pv.name());
            if (cfg.isEmpty() || cfg.get().dirs().batchesFilePath() == null || cfg.get().dirs().lineageFilePath() == null) continue;
            Map<String, Long> days = null;
            // The configured path is for the run that LOADED the config; every earlier run left its own timestamped pair.
            for (Path[] run : runs(Path.of(cfg.get().dirs().batchesFilePath()), Path.of(cfg.get().dirs().lineageFilePath()))) {
                Set<String> mine = new HashSet<>();
                boolean owns = ownsOrigin(cfg.get(), origins);
                for (Map<String, String> b : read(run[0])) {
                    String table = b.get("output_table");
                    if (stores.contains(table) || (owns && (table == null || table.isBlank()) && "SUCCESS".equalsIgnoreCase(b.get("status"))))
                        mine.add(b.get("consignment_id"));
                }
                if (mine.isEmpty()) continue;
                if (days == null) days = byCollector.computeIfAbsent(cfg.get().collector().id(), k -> new LinkedHashMap<>());
                for (Map<String, String> r : read(run[1])) {
                    if (!mine.contains(r.get("consignment_id"))) continue;
                    String day = day(r.get("partition"));
                    if (day != null) days.merge(day, count(r.get("row_count")), Long::sum);
                }
            }
        }
        List<Delivery> out = new ArrayList<>();
        byCollector.forEach((c, days) -> days.forEach((d, n) -> out.add(new Delivery(c, d, n))));
        return out.isEmpty() ? Optional.empty() : Optional.of(out);
    }

    // ── LA-DATA-TRUST-1 S1: one row per File ─────────────────────────────────────────────────────────────

    @Override
    public Optional<List<FileEntry>> files(ApiContext api, Map<String, Object> dataset) {
        Set<String> stores = EvidenceMasker.datasetStores(dataset);
        if (stores.isEmpty()) return Optional.empty();
        Set<String> origins = origins(stores);
        var service = HostContext.of(api).service();
        List<FileEntry> out = new ArrayList<>();
        for (var pv : service.pipelines()) {
            Optional<PipelineConfig> cfg = service.configFor(pv.name());
            if (cfg.isEmpty() || cfg.get().dirs().batchesFilePath() == null || cfg.get().dirs().lineageFilePath() == null) continue;
            String collector = cfg.get().collector().id();
            boolean matched = false;
            List<FileEntry> mine = new ArrayList<>(), problems = new ArrayList<>();
            boolean owns = ownsOrigin(cfg.get(), origins);
            for (Path[] run : runs(Path.of(cfg.get().dirs().batchesFilePath()), Path.of(cfg.get().dirs().lineageFilePath()))) {
                matched |= collectFiles(run, stores, owns, collector, pv.name(), mine, problems);
            }
            if (!matched) continue;                                  // no batch of this Pipeline wrote the Dataset: its Files are not this Dataset's
            out.addAll(mine);
            out.addAll(problems);
        }
        return out.isEmpty() ? Optional.empty() : Optional.of(out);
    }

    /**
     * One run's (batches, lineage, status) triple: the Files of the Consignments that wrote the Dataset go to {@code mine}; a
     * File that did not succeed goes to {@code problems} (kept only when the Pipeline matched in some run). Returns whether a
     * Consignment of this run wrote the Dataset.
     */
    static boolean collectFiles(Path[] run, Set<String> stores, boolean owns, String collector, String pipeline,
                                List<FileEntry> mine, List<FileEntry> problems) {
        Set<String> consignments = new HashSet<>();
        for (Map<String, String> b : read(run[0])) {
            String table = b.get("output_table");
            if (stores.contains(table) || (owns && (table == null || table.isBlank()) && "SUCCESS".equalsIgnoreCase(b.get("status"))))
                consignments.add(b.get("consignment_id"));
        }
        Path status = run[0].resolveSibling(run[0].getFileName().toString().replaceFirst("_batches_", "_status_"));
        if (Files.exists(status)) {
            List<Map<String, String>> lineage = read(run[1]);
            for (Map<String, String> s : read(status)) {
                boolean in = consignments.contains(s.get("consignment_id"));
                String outcome = outcome(s.get("status"), count(s.get("error_rows")));
                if (!in && "SUCCESS".equals(outcome)) continue;
                (in ? mine : problems).add(entry(s, outcome, lineage, in, collector, pipeline));
            }
        }
        return !consignments.isEmpty();
    }

    private static FileEntry entry(Map<String, String> s, String outcome, List<Map<String, String>> lineage, boolean joinLineage,
                                   String collector, String pipeline) {
        String name = s.get("filename") == null ? "" : s.get("filename");
        String cid = blankToNull(s.get("consignment_id"));
        String day = null;
        Set<String> outputs = new java.util.LinkedHashSet<>();
        long undated = 0;   // rows the Pipeline could not give an event day: they sit in the NULL partition, on no day of the strip
        if (joinLineage) {
            String base = baseName(name);
            for (Map<String, String> r : lineage) {
                if (!java.util.Objects.equals(cid, blankToNull(r.get("consignment_id"))) || !base.equals(baseName(r.get("input_file")))) continue;
                String d = day(r.get("partition"));
                if (d != null && (day == null || d.compareTo(day) < 0)) day = d;
                String part = r.get("partition");
                if (part != null && part.contains("__HIVE_DEFAULT_PARTITION__")) undated += count(r.get("row_count"));
                String o = r.get("output_file");
                if (o != null && !o.isBlank()) outputs.add(o);
            }
        }
        String errors = s.get("error_rows");
        long errorRows = errors == null || errors.isBlank() ? -1L : count(errors);
        // S10 live drive: 20 garbage rows (an unparseable event date) were loaded under the NULL partition and the File read
        // SUCCESS with 0 error rows. They are not rejected, but they are not on any day: count them with the error rows and
        // read the File as PARTIAL so the analyst sees that part of it was not usable.
        if (undated > 0) {
            errorRows = Math.max(errorRows, 0) + undated;
            if ("SUCCESS".equals(outcome)) outcome = "PARTIAL";
        }
        return new FileEntry(name, day, count(s.get("parsed_rows")), errorRows,
                blankToNull(s.get("end_time")), outcome, collector, pipeline, cid, new ArrayList<>(outputs));
    }

    /** The status ledger's word as one of SUCCESS / PARTIAL / QUARANTINED / FAILED; a word it does not know (PARKED) passes through. */
    static String outcome(String status, long errorRows) {
        String st = status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
        if (st.equals("SUCCESS")) return errorRows > 0 ? "PARTIAL" : "SUCCESS";
        if (st.startsWith("QUARANTINED") || st.equals("SKIPPED_UNREADABLE")) return "QUARANTINED";
        return st.isEmpty() ? "FAILED" : st;
    }

    private static String baseName(String p) {
        if (p == null) return "";
        String n = p.replace('\\', '/');
        return n.substring(n.lastIndexOf('/') + 1);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    /** The Catalog's origin names for a Dataset: its {@code sourceName} and the head segment of its {@code physicalRef}, lower-cased. */
    static Set<String> origins(Set<String> stores) {
        Set<String> out = new HashSet<>();
        for (String s : stores) {
            int slash = s.indexOf('/');
            out.add((slash < 0 ? s : s.substring(0, slash)).trim().toLowerCase(Locale.ROOT));
        }
        return out;
    }

    /**
     * LA-COVERAGE-NOT-ASSESSED-1: a single-schema or segmented Pipeline writes no {@code output_table} (it has no selector
     * table to name), so its batches cannot join on a store name. Its identity is the Catalog's origin rule instead: the
     * Pipeline IS the origin when its Stream (default its own name) is the Dataset's origin. A selector Pipeline never takes
     * this path: its batches name their table, and a blank one is a batch that wrote nothing.
     */
    static boolean ownsOrigin(PipelineConfig cfg, Set<String> origins) {
        if (cfg.schemas().selector() != null && cfg.schemas().selector().hasSchemas()) return false;
        for (String name : new String[]{cfg.identity().pipelineName(), cfg.stream()})
            if (name != null && origins.contains(name.trim().toLowerCase(Locale.ROOT))) return true;
        return false;
    }

    /**
     * Every (batches, lineage) pair of one Pipeline: {@code <name>_batches_<runTimestamp>.csv} with the lineage CSV of the
     * SAME run (consignment ids repeat across runs, so a pair never crosses runs). The configured pair is included when it
     * exists; a missing sibling skips that run.
     */
    static List<Path[]> runs(Path configuredBatches, Path configuredLineage) {
        List<Path[]> out = new ArrayList<>();
        Path dir = configuredBatches.toAbsolutePath().getParent();
        String file = configuredBatches.getFileName().toString();
        int at = file.lastIndexOf("_batches_");
        if (dir == null || at < 0 || !Files.isDirectory(dir)) {
            if (Files.exists(configuredBatches) && Files.exists(configuredLineage)) out.add(new Path[]{configuredBatches, configuredLineage});
            return out;
        }
        String prefix = file.substring(0, at + "_batches_".length());
        try (var ls = Files.list(dir)) {
            ls.filter(f -> f.getFileName().toString().startsWith(prefix) && f.getFileName().toString().endsWith(".csv"))
                    .sorted().forEach(b -> {
                        Path l = b.resolveSibling(b.getFileName().toString().replaceFirst("_batches_", "_lineage_"));
                        if (Files.exists(l)) out.add(new Path[]{b, l});
                    });
        } catch (Exception ignored) {
            // best-effort, like read()
        }
        return out;
    }

    static String day(String partition) {
        if (partition == null) return null;
        Matcher h = HIVE.matcher(partition);
        if (h.find()) return String.format("%s-%02d-%02d", h.group(1), Integer.parseInt(h.group(2)), Integer.parseInt(h.group(3)));
        Matcher d = DT.matcher(partition);
        return d.find() ? d.group(1) : null;
    }

    private static long count(String s) {
        try {
            return s == null || s.isBlank() ? 0L : Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /** A partially written or locked audit CSV must not fail the read: return what parsed. */
    private static List<Map<String, String>> read(Path csv) {
        List<Map<String, String>> out = new ArrayList<>();
        try {
            Csv.readInto(csv, out);
        } catch (Exception ignored) {
            // tolerate a missing/locked/partial audit CSV
        }
        return out;
    }
}
