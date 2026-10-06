package com.gamma.geolink;

import com.gamma.control.ApiContext;
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
        var service = HostContext.of(api).service();
        Map<String, Map<String, Long>> byCollector = new TreeMap<>();   // collector -> day -> rows
        for (var pv : service.pipelines()) {
            Optional<PipelineConfig> cfg = service.configFor(pv.name());
            if (cfg.isEmpty() || cfg.get().dirs().batchesFilePath() == null || cfg.get().dirs().lineageFilePath() == null) continue;
            Path batches = Path.of(cfg.get().dirs().batchesFilePath()), lineage = Path.of(cfg.get().dirs().lineageFilePath());
            if (!Files.exists(batches) || !Files.exists(lineage)) continue;
            Set<String> mine = new HashSet<>();
            for (Map<String, String> b : read(batches)) if (stores.contains(b.get("output_table"))) mine.add(b.get("consignment_id"));
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
