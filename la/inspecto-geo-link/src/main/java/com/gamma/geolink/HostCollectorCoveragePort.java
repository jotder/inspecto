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
            Path batches = Path.of(cfg.get().dirs().batchesFilePath()), lineage = Path.of(cfg.get().dirs().lineageFilePath());
            if (!Files.exists(batches) || !Files.exists(lineage)) continue;
            Set<String> mine = new HashSet<>();
            boolean owns = ownsOrigin(cfg.get(), origins);
            for (Map<String, String> b : read(batches)) {
                String table = b.get("output_table");
                if (stores.contains(table) || (owns && (table == null || table.isBlank()) && "SUCCESS".equalsIgnoreCase(b.get("status"))))
                    mine.add(b.get("consignment_id"));
            }
            if (mine.isEmpty()) continue;
            Map<String, Long> days = byCollector.computeIfAbsent(cfg.get().collector().id(), k -> new LinkedHashMap<>());
            for (Map<String, String> r : read(lineage)) {
                if (!mine.contains(r.get("consignment_id"))) continue;
                String day = day(r.get("partition"));
                if (day != null) days.merge(day, count(r.get("row_count")), Long::sum);
            }
        }
        List<Delivery> out = new ArrayList<>();
        byCollector.forEach((c, days) -> days.forEach((d, n) -> out.add(new Delivery(c, d, n))));
        return out.isEmpty() ? Optional.empty() : Optional.of(out);
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
