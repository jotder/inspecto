package com.gamma.job;

import com.gamma.job.StorageSeries.AxisTrend;
import com.gamma.signal.Severity;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The {@code storage_trend} maintenance task (System Maintenance COULD tier — growth-trend analysis +
 * archive recommendations). Read-only analysis over the {@code maintenance_storage} sample series that
 * {@code storage_report} accumulates (one row per axis per run): fits a simple two-point growth rate
 * (bytes/day) per axis and for the whole space over the most recent {@code window_days} (default 30),
 * projects when the total will cross {@code warn_bytes}, and surfaces the fastest-growing axes as
 * archive candidates. Emits a {@code maintenance.storage.trend} WARNING when the projected breach is
 * within {@code warn_days} (default 14), or the threshold is already exceeded. Nothing is mutated —
 * findings go to the Run Log message and, on a projected breach, one signal.
 *
 * <p>A two-point (earliest→latest in-window) slope, not a regression: cheap, honest, and adequate for a
 * housekeeping heuristic. A short sample span yields a noisy rate — {@code window_days} plus roughly
 * daily {@code storage_report} sampling is the intended cadence. Fewer than two samples in the window is
 * reported as insufficient history (fail-soft SUCCESS), so a freshly-enabled space is never an error.
 */
final class StorageTrendTask {

    private StorageTrendTask() {}

    static JobResult run(JobConfig cfg, String dataDir, JobContext ctx) throws Exception {
        long t0 = System.nanoTime();
        int windowDays = Integer.parseInt(cfg.opt("window_days", "30"));
        long warnBytes = Long.parseLong(cfg.opt("warn_bytes", "0"));   // 0 = no threshold
        int warnDays = Integer.parseInt(cfg.opt("warn_days", "14"));
        int top = Integer.parseInt(cfg.opt("top", "5"));

        if (dataDir == null || dataDir.isBlank())
            return JobResult.ok("storage_trend: no data root configured — no sample history to analyse", 0L);
        Path storeDir = StorageSeries.storeDir(Path.of(dataDir));
        if (!StorageSeries.hasHistory(storeDir))
            return JobResult.ok("storage_trend: no storage_report history yet (" + storeDir
                    + ") — run storage_report first", (System.nanoTime() - t0) / 1_000_000L);

        // The series reader is shared with space.comparison — one slope calculation, never two.
        StorageSeries.Window w = StorageSeries.read(storeDir, windowDays);
        List<long[]> totals = w.totals();
        if (totals.size() < 2)
            return JobResult.ok("storage_trend: insufficient history (" + totals.size()
                    + " sample(s) in the last " + windowDays + "d); need >= 2 to project a trend",
                    (System.nanoTime() - t0) / 1_000_000L);
        List<AxisTrend> axisTrends = new ArrayList<>(w.axes());

        double spanDays = w.spanDays();
        long earliestTotal = totals.get(0)[1];
        long latestTotal = totals.get(totals.size() - 1)[1];
        double totalPerDay = (latestTotal - earliestTotal) / spanDays;
        axisTrends.sort(Comparator.comparingDouble(AxisTrend::bytesPerDay).reversed());

        // Projection of the total against warn_bytes.
        boolean breach = false;
        long etaDays = -1;
        if (warnBytes > 0) {
            if (latestTotal >= warnBytes) { breach = true; etaDays = 0; }
            else if (totalPerDay > 0) {
                etaDays = Math.round((warnBytes - latestTotal) / totalPerDay);
                breach = etaDays <= warnDays;
            }
        }

        // Fastest-growing axes = archive candidates (positive growth only, top N).
        StringBuilder growth = new StringBuilder();
        int shown = 0;
        for (AxisTrend a : axisTrends) {
            if (a.bytesPerDay() <= 0 || shown >= top) break;
            growth.append(shown == 0 ? "" : ", ").append(a.axis())
                    .append("(+").append(Math.round(a.bytesPerDay())).append("b/day)");
            shown++;
        }
        String recommend = shown == 0 ? "no axis is growing" : growth.toString();

        String projection = warnBytes <= 0 ? "no warn_bytes threshold set"
                : latestTotal >= warnBytes ? "ALREADY OVER warn_bytes=" + warnBytes
                : totalPerDay <= 0 ? "not growing — no projected breach of warn_bytes=" + warnBytes
                : "projected to reach warn_bytes=" + warnBytes + " in ~" + etaDays + "d"
                        + (etaDays <= warnDays ? " (within warn_days=" + warnDays + ")" : "");

        if (ctx != null && breach)
            ctx.signals().emit("maintenance.storage.trend", Severity.WARN, Map.of(
                    "currentBytes", latestTotal, "bytesPerDay", Math.round(totalPerDay),
                    "etaDays", etaDays, "warnBytes", warnBytes,
                    "topAxis", axisTrends.isEmpty() ? "-" : axisTrends.get(0).axis()));

        String msg = "storage_trend: total " + latestTotal + "b, " + (totalPerDay >= 0 ? "+" : "")
                + Math.round(totalPerDay) + "b/day over "
                + String.format(Locale.ROOT, "%.1f", spanDays) + "d window (" + totals.size()
                + " samples); archive candidates: " + recommend + "; " + projection;
        return JobResult.ok(msg, (System.nanoTime() - t0) / 1_000_000L);
    }
}
