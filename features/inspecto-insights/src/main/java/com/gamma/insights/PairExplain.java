package com.gamma.insights;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.analytics.drivers.Drivers;
import com.gamma.control.BiSpecRunner;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * "Explain this change" by pairs of dimensions (depth 2, {@code BI-EXPLAIN-DEPTH2-1}, plan §11.12), computed by the
 * {@code insight.scan} Job for a Profile with {@code pairs: true} and read back by {@code POST /insights/explain}
 * {@code depth: 2}. For every pair of the Profile's dimensions, one statement grouped by both columns over the last two
 * complete buckets, through the Job's {@link InsightScan.Fetcher} (no SQL built here); {@link Drivers} over the value
 * pairs. A pair with more than {@code maxPairValues} value pairs is refused in words, never cut. Stored as
 * {@link #FILE} beside the Insights Parquet, keyed by the explained bucket.
 */
final class PairExplain {

    private PairExplain() {}

    static final String FILE = "pairs.json";
    static final String JOIN = " × ";
    static final double COVER = 0.8;
    private static final ObjectMapper JSON = new ObjectMapper();

    /** The entry for the last bucket of {@code b} against the one before it. */
    static Map<String, Object> compute(InsightProfile p, InsightScan.Buckets b, InsightScan.Fetcher f, Instant at)
            throws Exception {
        int n = b.size();
        InsightScan.Buckets two = new InsightScan.Buckets(b.grain(), b.starts().subList(n - 2, n));
        String prev = two.label(0), cur = two.label(1);
        List<Drivers.Dimension> scored = new ArrayList<>();
        List<Map<String, Object>> refused = new ArrayList<>();
        double before = Double.NaN, after = Double.NaN;
        List<String> dims = p.dimensions();
        for (int i = 0; i < dims.size(); i++) {
            for (int j = i + 1; j < dims.size(); j++) {
                String d1 = dims.get(i), d2 = dims.get(j), name = d1 + JOIN + d2;
                BiSpecRunner.Run r = f.fetch(InsightScan.body(p, List.of(d1, d2, p.field()), two, 2 * p.maxPairValues()));
                Map<String, double[]> acc = new LinkedHashMap<>();
                for (Map<String, Object> row : r.rows()) {
                    String bucket = String.valueOf(row.get(p.field()));
                    int k = bucket.equals(prev) ? 0 : bucket.equals(cur) ? 1 : -1;
                    if (k < 0) continue;
                    String key = text(row.get(d1)) + JOIN + text(row.get(d2));
                    acc.computeIfAbsent(key, x -> new double[2])[k] += number(row.get(p.measureId()), p.measureId());
                }
                if (r.truncated() || acc.size() > p.maxPairValues()) {
                    refused.add(Map.of("dimension", name, "reason", "more than " + p.maxPairValues()
                            + " value pairs (maxPairValues); pair coarser columns or raise maxPairValues"));
                    continue;
                }
                List<Drivers.Element> els = new ArrayList<>();
                double b0 = 0, b1 = 0;
                for (Map.Entry<String, double[]> e : acc.entrySet()) {
                    els.add(new Drivers.Element(e.getKey(), e.getValue()[0], e.getValue()[1]));
                    b0 += e.getValue()[0];
                    b1 += e.getValue()[1];
                }
                before = b0;
                after = b1;
                scored.add(Drivers.explain(name, els, COVER));
            }
        }
        List<Map<String, Object>> drivers = new ArrayList<>();
        for (Drivers.Dimension d : Drivers.rank(scored)) drivers.add(ExplainRoutes.dimension(d));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("profile", p.id());
        out.put("computedAt", at.toString());
        out.put("measure", p.measureId());
        out.put("grain", p.grain());
        out.put("previous", prev);
        out.put("current", cur);
        out.put("before", Double.isNaN(before) ? null : before);
        out.put("after", Double.isNaN(after) ? null : after);
        out.put("cover", COVER);
        out.put("drivers", drivers);
        out.put("refused", refused);
        return out;
    }

    private static String text(Object v) {
        return v == null ? "(empty)" : String.valueOf(v);
    }

    private static double number(Object v, String id) {
        if (v == null) return 0;
        if (v instanceof Number n) return n.doubleValue();
        throw new SecurityException("the Measure '" + id + "' reaches the scan masked; pairs never store a masked "
                + "column's values — refused");
    }

    /** bucket label → entry; empty when no run stored pairs. */
    static Map<String, Map<String, Object>> read(Path dir) throws IOException {
        Path f = dir.resolve(FILE);
        if (!Files.isRegularFile(f)) return new LinkedHashMap<>();
        return JSON.readValue(f.toFile(), new TypeReference<LinkedHashMap<String, Map<String, Object>>>() {});
    }

    /** Stores {@code entry} under its bucket (replacing that bucket's), dropping entries older than the Insights' retention. */
    static void write(Path dir, Map<String, Object> entry, Instant at) throws IOException {
        Instant keepFrom = at.minus(InsightStore.RETENTION_DAYS, ChronoUnit.DAYS);
        Map<String, Map<String, Object>> all = read(dir);
        all.values().removeIf(e -> Instant.parse(String.valueOf(e.get("computedAt"))).isBefore(keepFrom));
        all.put(String.valueOf(entry.get("current")), entry);
        Path tmp = dir.resolve(FILE + ".tmp");
        JSON.writeValue(tmp.toFile(), all);
        Files.move(tmp, dir.resolve(FILE), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
