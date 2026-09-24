package com.gamma.job;

import com.gamma.signal.Severity;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The {@code space.comparison} Job Type: compares the <b>storage growth</b> of two or more Spaces — the
 * {@code maintenance_storage} sample series {@code storage_report} accumulates and {@code storage_trend}
 * reads — per axis: each Space's latest bytes and bytes/day slope, the spread (max − min latest bytes) and
 * the fastest grower. ⛔ Not a config or Pipeline diff (space-comparison design §1).
 *
 * <p><b>Read-only, across a boundary, through a grant only.</b> The run reads a Space only when its
 * {@link SpaceStorageAccess} grant names it, and it checks EVERY requested Space against the grant before
 * reading ANY of them — a refused Space fails the whole run, never "compares the rest", because a partial
 * answer to an unauthorized question is still an answer. Nothing is written anywhere: the result is the
 * Run message plus one {@code space.comparison.completed} signal in the running Space (design §5 Q3 —
 * no catalog rows, no Run Artifacts).
 *
 * <p><b>Fail-soft per Space, not per run</b> (design §3): a granted Space with no history or fewer than two
 * in-window samples is reported "not comparable" and the others still compare. A run needs at least two
 * comparable Spaces to report any axis.
 *
 * <p>The series reader is {@link StorageSeries} — the one {@code storage_trend} uses, so the two reports'
 * slopes cannot disagree.
 */
final class SpaceComparisonJob implements Job {

    static final String TYPE = "space.comparison";
    static final String SIGNAL = "space.comparison.completed";

    private final JobConfig cfg;
    private final SpaceStorageAccess access;

    SpaceComparisonJob(JobConfig cfg, SpaceStorageAccess access) {
        this.cfg = cfg;
        this.access = access;
    }

    @Override public String name() { return cfg.name(); }
    @Override public String type() { return TYPE; }

    @Override
    public JobResult run() throws Exception {
        return run(null);
    }

    /** One Space's comparable series. */
    private record SpaceSeries(String space, Map<String, StorageSeries.AxisTrend> axes, int samples) {}

    @Override
    public JobResult run(JobContext ctx) throws Exception {
        long t0 = System.nanoTime();
        List<String> spaces = csv(cfg.opt("spaces", ""));
        if (new LinkedHashSet<>(spaces).size() != spaces.size())
            throw new IllegalArgumentException("space.comparison: 'spaces' names a Space twice: " + spaces);
        if (spaces.size() < 2)
            throw new IllegalArgumentException("space.comparison: 'spaces' must name at least two Spaces, got "
                    + spaces);
        int windowDays = Integer.parseInt(cfg.opt("window_days", "30"));
        int top = Integer.parseInt(cfg.opt("top", "5"));
        if (windowDays < 1 || top < 1)
            throw new IllegalArgumentException("space.comparison: window_days and top must be >= 1");
        Set<String> axisFilter = new LinkedHashSet<>(csv(cfg.opt("axes", "")));

        // ⛔ Authorize ALL before reading ANY — see the class note.
        Map<String, Path> roots = new LinkedHashMap<>();
        List<String> refused = new ArrayList<>();
        for (String s : spaces) access.dataRoot(s).ifPresentOrElse(p -> roots.put(s, p), () -> refused.add(s));
        if (!refused.isEmpty())
            throw new SecurityException("space.comparison: not authorized to read Space(s) " + refused
                    + " — a cross-Space read needs the canAdminister-gated POST /space-comparisons; an authored "
                    + "or scheduled run may read only its own Space");

        List<SpaceSeries> comparable = new ArrayList<>();
        Map<String, String> notComparable = new LinkedHashMap<>();
        for (Map.Entry<String, Path> e : roots.entrySet()) {
            Path storeDir = StorageSeries.storeDir(e.getValue());
            if (!StorageSeries.hasHistory(storeDir)) { notComparable.put(e.getKey(), "no storage_report history"); continue; }
            StorageSeries.Window w = StorageSeries.read(storeDir, windowDays);
            if (w.samples() < 2) {
                notComparable.put(e.getKey(), w.samples() + " sample(s) in " + windowDays + "d, need >= 2");
                continue;
            }
            Map<String, StorageSeries.AxisTrend> byAxis = new LinkedHashMap<>();
            for (StorageSeries.AxisTrend a : w.axes())
                if (axisFilter.isEmpty() || axisFilter.contains(a.axis())) byAxis.put(a.axis(), a);
            comparable.add(new SpaceSeries(e.getKey(), byAxis, w.samples()));
        }

        List<Map<String, Object>> axisRows = new ArrayList<>();
        if (comparable.size() >= 2) {
            Set<String> axes = new TreeSet<>();
            comparable.forEach(s -> axes.addAll(s.axes().keySet()));
            for (String axis : axes) {
                Map<String, Object> perSpace = new LinkedHashMap<>();
                long max = Long.MIN_VALUE, min = Long.MAX_VALUE;
                String fastest = null;
                double fastestRate = Double.NEGATIVE_INFINITY;
                for (SpaceSeries s : comparable) {
                    StorageSeries.AxisTrend a = s.axes().get(axis);
                    long bytes = a == null ? 0L : a.currentBytes();   // an axis a Space lacks holds 0 bytes there
                    double rate = a == null ? 0.0 : a.bytesPerDay();
                    perSpace.put(s.space(), Map.of("bytes", bytes, "bytesPerDay", Math.round(rate)));
                    max = Math.max(max, bytes);
                    min = Math.min(min, bytes);
                    if (rate > fastestRate) { fastestRate = rate; fastest = s.space(); }
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("axis", axis);
                row.put("spreadBytes", max - min);
                row.put("fastest", fastest);
                row.put("spaces", perSpace);
                axisRows.add(row);
            }
            axisRows.sort(Comparator.comparingLong((Map<String, Object> r) -> (Long) r.get("spreadBytes")).reversed());
            if (axisRows.size() > top) axisRows = new ArrayList<>(axisRows.subList(0, top));
        }

        if (ctx != null) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("spaces", spaces);
            payload.put("windowDays", windowDays);
            payload.put("comparable", comparable.stream().map(SpaceSeries::space).toList());
            payload.put("notComparable", notComparable);
            payload.put("axes", axisRows);
            ctx.signals().emit(SIGNAL, Severity.INFO, payload);
        }

        StringBuilder msg = new StringBuilder("space.comparison: ").append(spaces.size()).append(" space(s), ")
                .append(comparable.size()).append(" comparable over ").append(windowDays).append("d");
        if (comparable.size() < 2) msg.append(" — need >= 2 comparable Spaces to compare any axis");
        for (Map<String, Object> r : axisRows) {
            msg.append("; ").append(r.get("axis")).append(":");
            @SuppressWarnings("unchecked")
            Map<String, Map<String, Object>> per = (Map<String, Map<String, Object>>) r.get("spaces");
            per.forEach((s, v) -> msg.append(' ').append(s).append('=').append(v.get("bytes")).append("b(")
                    .append(signed((Long) v.get("bytesPerDay"))).append("b/day)"));
            msg.append(" spread=").append(r.get("spreadBytes")).append("b fastest=").append(r.get("fastest"));
        }
        if (!notComparable.isEmpty()) {
            msg.append("; not comparable:");
            notComparable.forEach((s, why) -> msg.append(' ').append(s).append(" (").append(why).append(')'));
        }
        return JobResult.ok(msg.toString(), (System.nanoTime() - t0) / 1_000_000L);
    }

    private static String signed(long v) {
        return String.format(Locale.ROOT, "%+d", v);
    }

    /** The house convention for list-valued job params: CSV, trimmed, blanks dropped. */
    private static List<String> csv(String raw) {
        return Arrays.stream(raw.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
