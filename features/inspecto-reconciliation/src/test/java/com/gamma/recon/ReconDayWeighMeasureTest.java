package com.gamma.recon;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Measurement, not a gate (RECON-UPLIFT-RESIDUALS-1 5a): {@link ReconDay.Cache#weigh} against the real retained heap of
 * a 200k-key, 3-way day shaped like {@code ReconService.shapeRow}. Runs only with {@code RECON_PERF=1}; prints the
 * estimate, the retained bytes after a full GC and the {@code GC.class_histogram} total for the same live set.
 */
@EnabledIfEnvironmentVariable(named = "RECON_PERF", matches = "1")
class ReconDayWeighMeasureTest {

    static final int KEYS = 200_000;
    static final String[] SIDES = {"a", "b", "c"};

    private static long used() {
        for (int i = 0; i < 4; i++) System.gc();
        Runtime rt = Runtime.getRuntime();
        return rt.totalMemory() - rt.freeMemory();
    }

    private static List<Map<String, Object>> rows() {
        List<Map<String, Object>> rows = new ArrayList<>(KEYS);
        for (int i = 0; i < KEYS; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            Map<String, Object> key = new LinkedHashMap<>();
            key.put("msisdn", new String(("9" + (1_000_000_000L + i)).toCharArray()));
            row.put("key", key);
            for (int s = 0; s < 3; s++) {
                Map<String, Object> side = new LinkedHashMap<>();
                side.put("balance", Double.valueOf(i * 1.01 + s));
                side.put("minutes", Double.valueOf(i * 0.5 + s));
                side.put(ReconService.RECORDS, Long.valueOf(1));
                row.put(SIDES[s], side);
            }
            row.put("inA", Boolean.TRUE);
            row.put("inB", Boolean.TRUE);
            row.put("inC", Boolean.TRUE);
            rows.add(row);
        }
        return rows;
    }

    /** The in-process {@code GC.class_histogram} (jcmd attaching to its own JVM hung on this box). */
    private static long histogramTotal() throws Exception {
        javax.management.ObjectName n = new javax.management.ObjectName("com.sun.management:type=DiagnosticCommand");
        String out = (String) java.lang.management.ManagementFactory.getPlatformMBeanServer()
                .invoke(n, "gcClassHistogram", new Object[]{new String[0]}, new String[]{String[].class.getName()});
        long total = -1;
        for (String line : out.split("\n")) if (line.startsWith("Total")) total = Long.parseLong(line.trim().split(" +")[2]);
        return total;
    }

    @Test
    void weighAgainstTheHeap() throws Exception {
        long before = used();
        long histBefore = histogramTotal();
        List<Map<String, Object>> rows = rows();
        ReconService.DayResult day = new ReconService.DayResult(rows, new int[KEYS], KEYS, Map.of(), Map.of(), null, 1);
        long after = used();
        long histAfter = histogramTotal();
        long estimate = ReconDay.Cache.weigh(day);
        long retained = after - before;
        long hist = histAfter - histBefore;
        System.out.printf("RECON-WEIGH keys=%d estimate=%d retained=%d histogram=%d estimate/retained=%.2f estimate/histogram=%.2f%n",
                KEYS, estimate, retained, hist, estimate / (double) retained, estimate / (double) hist);
        if (rows.size() != KEYS) throw new AssertionError();   // keeps the live set reachable until here
    }
}
