package com.gamma.control;

import com.gamma.job.JobRun;
import com.gamma.job.JobService;
import com.gamma.signal.Ref;
import com.gamma.signal.Signal;
import com.gamma.signal.SignalType;
import com.gamma.signal.SignalTypeCatalog;
import com.gamma.signal.SignalTypeDecl;
import com.gamma.signal.Signals;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * The Signal Board of one Space (SIGNAL-BOARD-1 SB-3, {@code GET /signals/board}): each Signal Type (the catalog plus
 * any type seen on the ledger in the window), what posts it, which Jobs listen, when it was last posted, and how the
 * listening went over the window: Runs started, {@code when} guards that said no, refusals ({@code job.signal.refused})
 * and chain cuts ({@code job.chain.cut}). Two lists matter most: {@code unheard} (a type posted in the window that no
 * enabled Job hears, decided per posted type, so a family member nobody listens to is listed even when another member
 * of its family is heard) and {@code unposted} (a Job whose {@code on_signal} matches no catalogued type and nothing
 * posted in the window).
 *
 * <p>Where each count comes from:
 * <ul>
 *   <li>posted, lastPosted, postedBy, runsStarted ({@code job.run.started} with {@code trigger = signal:<type>}, or
 *       {@code event:<pipeline>} for {@code pipeline.commit}), refused and chainCuts: the Signal ledger page of the
 *       window. When that page is full ({@code capped}) every ledger count is a floor.</li>
 *   <li>guardsSaidNo: the Job's Run history in memory (a {@code SKIPPED} Run with the message {@code when guard false}),
 *       because a guard that says no posts no Signal. That history keeps {@link #RUN_HISTORY} Runs per Job and starts
 *       empty at each server start, so {@code guardsCapped} says the figure is a floor when the server started inside
 *       the window or a Job's history is full and begins inside it.</li>
 *   <li>missed: {@code null} until SB-2b (catch-up at boot) records Signals too old to replay; {@code missedTracked}
 *       is false until then. SB-2b fills both through {@link Inputs#missedByType}.</li>
 * </ul>
 * A row is keyed by the catalog: a posted type a family covers ({@code exchange.*}) counts under the family; a posted
 * type the catalog does not know gets its own row with {@code catalogued: false}. {@code on_pipeline} is short for
 * {@code on_signal: pipeline.commit} (D-SB2), so such a Job is listed as a listener of {@code pipeline.commit}.
 */
final class SignalBoard {

    /** Bounds on every list the board returns (house rule: a truncated flag with the true total). */
    static final int MAX_TYPES = 500;
    static final int MAX_FINDINGS = 100;
    static final int MAX_LISTENERS = 50;
    static final int MAX_POSTERS = 10;
    /** Runs kept per Job in the in-memory history the guard count reads (mirrors {@code JobRunLedger.MAX_HISTORY}). */
    static final int RUN_HISTORY = 50;

    static final String WHEN_GUARD_FALSE = "when guard false";
    private static final String SIGNAL_TRIGGER = "signal:";
    private static final String PIPELINE_TRIGGER = "event:";
    private static final DateTimeFormatter RUN_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * What the board is built from. {@code window} is the Signal page of {@code [sinceMs, untilMs]} and {@code capped}
     * says it was full. {@code historySinceMs} is when the in-memory Run history began (the server's start).
     * {@code missedByType} is the SB-2b hook: {@code null} while missed Signals are not recorded.
     */
    record Inputs(SignalTypeCatalog catalog, List<JobService.JobView> jobs, Function<String, List<JobRun>> runsFor,
                  List<Signal> window, boolean capped, long sinceMs, long untilMs, long historySinceMs,
                  Map<String, Integer> missedByType) {}

    /** One posted concrete type inside a row. */
    private static final class Posted {
        int count;
        Instant last;
        final Set<String> postedBy = new LinkedHashSet<>();
    }

    /** One row while it is being counted. */
    private static final class Row {
        final String type;
        final SignalTypeCatalog.Entry entry;   // null for a type the catalog does not know
        int runsStarted, guardsSaidNo, refused, chainCuts;
        final Map<String, Posted> posted = new LinkedHashMap<>();   // concrete type -> its postings
        final List<Map<String, Object>> listeners = new ArrayList<>();

        Row(String type, SignalTypeCatalog.Entry entry) {
            this.type = type;
            this.entry = entry;
        }

        int postedCount() {
            int n = 0;
            for (Posted p : posted.values()) n += p.count;
            return n;
        }
    }

    private SignalBoard() {}

    static Map<String, Object> build(Inputs in) {
        Map<String, Row> rows = new LinkedHashMap<>();
        for (SignalTypeCatalog.Entry e : in.catalog().entries())
            rows.put(e.decl().type(), new Row(e.decl().type(), e));

        // ── the ledger page: postings, Runs started, refusals, chain cuts ─────────────────────────────────
        for (Signal s : in.window()) {
            Posted p = row(rows, in.catalog(), s.type()).posted.computeIfAbsent(s.type(), k -> new Posted());
            p.count++;
            if (s.at() != null && (p.last == null || s.at().isAfter(p.last))) p.last = s.at();
            if (s.source() != null) p.postedBy.add(compact(s.source()));
            Map<String, Object> payload = s.payload() == null ? Map.of() : s.payload();
            switch (s.type()) {
                case SignalType.JOB_RUN_STARTED -> {
                    String trigger = str(payload.get("trigger"));
                    if (trigger != null && trigger.startsWith(SIGNAL_TRIGGER))
                        row(rows, in.catalog(), trigger.substring(SIGNAL_TRIGGER.length())).runsStarted++;
                    else if (trigger != null && trigger.startsWith(PIPELINE_TRIGGER))   // on_pipeline (D-SB2)
                        row(rows, in.catalog(), SignalType.PIPELINE_COMMIT).runsStarted++;
                }
                case SignalType.JOB_SIGNAL_REFUSED -> {
                    String t = str(payload.get("refusedType"));
                    if (t != null) row(rows, in.catalog(), t).refused++;
                }
                case SignalType.JOB_CHAIN_CUT -> {
                    String t = str(payload.get("signalType"));
                    if (t != null) row(rows, in.catalog(), t).chainCuts++;
                }
                default -> { }
            }
        }

        // ── guards that said no (may add a row, so before the listeners are matched) ──────────────────────
        boolean guardsCapped = in.historySinceMs() > in.sinceMs();   // the history began inside the window
        for (JobService.JobView j : in.jobs()) {
            if (j.onSignal() == null || j.onSignal().isBlank()) continue;
            List<JobRun> runs = in.runsFor().apply(j.name());
            if (runs == null) continue;
            long oldest = Long.MAX_VALUE;
            for (JobRun run : runs) {
                long at = epochMs(run.startTime());
                if (at >= 0) oldest = Math.min(oldest, at);
                if (at < in.sinceMs() || at > in.untilMs()) continue;
                if (!"SKIPPED".equals(run.status()) || !WHEN_GUARD_FALSE.equals(run.message())) continue;
                String trigger = run.trigger();
                if (trigger == null || !trigger.startsWith(SIGNAL_TRIGGER)) continue;
                row(rows, in.catalog(), trigger.substring(SIGNAL_TRIGGER.length())).guardsSaidNo++;
            }
            if (runs.size() >= RUN_HISTORY && oldest != Long.MAX_VALUE && oldest >= in.sinceMs()) guardsCapped = true;
        }

        // ── the Jobs: who listens, and Jobs nothing posts to ────────────────────────────────────────────────
        List<Map<String, Object>> unposted = new ArrayList<>();
        for (JobService.JobView j : in.jobs()) {
            if (j.onPipeline() != null && !j.onPipeline().isBlank()) {
                Row commit = rows.computeIfAbsent(SignalType.PIPELINE_COMMIT,
                        k -> new Row(k, in.catalog().find(k).orElse(null)));
                listen(commit, j, "on_pipeline", j.onPipeline());
            }
            if (j.onSignal() == null || j.onSignal().isBlank()) continue;
            String on = j.onSignal().trim();
            boolean anyPoster = false;
            for (Row r : rows.values()) {
                if (!overlaps(r, on)) continue;
                listen(r, j, "on_signal", on);
                if (r.entry != null || !r.posted.isEmpty()) anyPoster = true;
            }
            if (!anyPoster) {
                Map<String, Object> u = new LinkedHashMap<>();
                u.put("job", j.name());
                u.put("onSignal", on);
                u.put("enabled", j.enabled());
                unposted.add(u);
            }
        }

        // ── order, project, bound ───────────────────────────────────────────────────────────────────────────
        List<Row> ordered = new ArrayList<>(rows.values());
        ordered.sort(Comparator.comparingInt((Row r) -> -r.postedCount()).thenComparing(r -> r.type));
        List<Map<String, Object>> types = new ArrayList<>();
        List<Map<String, Object>> unheard = new ArrayList<>();
        for (Row r : ordered) {
            Integer missed = in.missedByType() == null ? null : in.missedByType().getOrDefault(r.type, 0);
            types.add(view(r, missed));
            for (Map.Entry<String, Posted> e : r.posted.entrySet()) {
                if (heard(r, e.getKey())) continue;
                Posted p = e.getValue();
                Map<String, Object> u = new LinkedHashMap<>();
                u.put("type", e.getKey());
                u.put("posted", p.count);
                u.put("lastPosted", p.last == null ? null : p.last.toString());
                putBounded(u, "postedBy", new ArrayList<>(p.postedBy), MAX_POSTERS);
                unheard.add(u);
            }
        }
        unheard.sort(Comparator.comparingInt((Map<String, Object> u) -> -(int) u.get("posted"))
                .thenComparing(u -> (String) u.get("type")));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("since", Instant.ofEpochMilli(in.sinceMs()).toString());
        out.put("until", Instant.ofEpochMilli(in.untilMs()).toString());
        out.put("capped", in.capped());
        out.put("guardsCapped", guardsCapped);
        out.put("missedTracked", in.missedByType() != null);
        putBounded(out, "types", types, MAX_TYPES);
        putBounded(out, "unheard", unheard, MAX_FINDINGS);
        putBounded(out, "unposted", unposted, MAX_FINDINGS);
        return out;
    }

    /** {@code key}, {@code keyTotal} and {@code keyTruncated}: at most {@code max} items and the true count. */
    private static <T> void putBounded(Map<String, Object> out, String key, List<T> items, int max) {
        out.put(key, items.size() > max ? List.copyOf(items.subList(0, max)) : items);
        out.put(key + "Total", items.size());
        out.put(key + "Truncated", items.size() > max);
    }

    private static Map<String, Object> view(Row r, Integer missed) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", r.type);
        m.put("title", r.entry == null ? null : r.entry.decl().title());
        m.put("poster", r.entry == null ? null : r.entry.decl().poster());
        m.put("source", r.entry == null ? null : r.entry.source());
        m.put("catalogued", r.entry != null);
        m.put("family", r.entry != null && r.entry.decl().family());
        m.put("posted", r.postedCount());
        Instant last = null;
        Set<String> by = new LinkedHashSet<>();
        for (Posted p : r.posted.values()) {
            if (p.last != null && (last == null || p.last.isAfter(last))) last = p.last;
            by.addAll(p.postedBy);
        }
        m.put("lastPosted", last == null ? null : last.toString());
        putBounded(m, "postedBy", new ArrayList<>(by), MAX_POSTERS);
        putBounded(m, "listeners", r.listeners, MAX_LISTENERS);
        m.put("runsStarted", r.runsStarted);
        m.put("guardsSaidNo", r.guardsSaidNo);
        m.put("refused", r.refused);
        m.put("chainCuts", r.chainCuts);
        m.put("missed", missed);
        return m;
    }

    private static void listen(Row r, JobService.JobView j, String key, String value) {
        Map<String, Object> l = new LinkedHashMap<>();
        l.put("job", j.name());
        l.put("key", key);
        l.put("value", value);
        l.put("enabled", j.enabled());
        r.listeners.add(l);
    }

    /** Does an enabled listener of this row hear the concrete posted {@code type}? The Job service's own match. */
    private static boolean heard(Row r, String type) {
        for (Map<String, Object> l : r.listeners) {
            if (!Boolean.TRUE.equals(l.get("enabled"))) continue;
            if ("on_pipeline".equals(l.get("key"))) {
                if (SignalType.PIPELINE_COMMIT.equals(type)) return true;
            } else if (Signals.matchesType(type, (String) l.get("value"))) {
                return true;
            }
        }
        return false;
    }

    /** The row a posted type counts under: its own catalog entry, else the family that covers it, else its own
     *  uncatalogued row. */
    private static Row row(Map<String, Row> rows, SignalTypeCatalog catalog, String type) {
        Row exact = rows.get(type);
        if (exact != null) return exact;
        SignalTypeCatalog.Entry e = catalog.find(type).orElse(null);
        if (e != null) return rows.computeIfAbsent(e.decl().type(), k -> new Row(k, e));
        return rows.computeIfAbsent(type, k -> new Row(k, null));
    }

    /**
     * Does a Job with {@code on_signal: on} hear some Signal of this row? A concrete row: the Job service's own match
     * ({@link Signals#matchesType}). A family row {@code p.*}: the Job names a member, the family itself, or a glob
     * that is wider or narrower than it.
     */
    private static boolean overlaps(Row r, String on) {
        boolean family = r.entry != null && r.entry.decl().family();
        if (!family) return Signals.matchesType(r.type, on);
        SignalTypeDecl decl = r.entry.decl();
        if (!on.endsWith(".*")) return decl.covers(on);
        String rowPrefix = r.type.substring(0, r.type.length() - 1);   // "p."
        String onPrefix = on.substring(0, on.length() - 1);             // "q."
        return rowPrefix.startsWith(onPrefix) || onPrefix.startsWith(rowPrefix);
    }

    private static String compact(Ref r) {
        if (r.kind() == null) return r.id();
        return r.id() == null ? r.kind() : r.kind() + ":" + r.id();
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    /** A Run's {@code yyyy-MM-dd HH:mm:ss} start (written in the server's zone) as epoch millis; -1 when unreadable. */
    private static long epochMs(String ts) {
        if (ts == null || ts.isBlank()) return -1;
        try {
            return LocalDateTime.parse(ts.trim(), RUN_TS).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (DateTimeParseException e) {
            return -1;
        }
    }
}
