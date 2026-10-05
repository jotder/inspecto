package com.gamma.job;

import com.gamma.linkindex.LinkIndexAccess;
import com.gamma.signal.Severity;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code la.index.build} Job Type - builds or refreshes the Link Analysis Index of one configured Dataset
 * mapping after a daily partition lands (LA-DAILY-INGEST-1, T5; decisions D-ING2 / D-ING5). Triggered like
 * {@code orders_summary_followup_job}: {@code on_signal: job.dataset.produced} with a {@code when} guard.
 *
 * <p>It is a clock over a build that already exists, and nothing more. The Link Analysis side
 * ({@link LinkIndexAccess}) re-decides the delegated principal's authority, reads the index {@code plan} advice
 * (none | append | full | compact), and runs ONLY the mode the server itself recommends; it never forces a mode
 * the server refuses, and a refusal is recorded and reported, never retried in a loop. {@code full} (a first
 * build, a rewritten or removed input) is expensive, so it runs only when the Job states {@code allow_full}.
 *
 * <p>Fails CLOSED: with the {@code link-index} Platform Service absent (or the Link Analysis module missing, where
 * the service throws) the Run fails, and so does every refusal, failure and unfinished build - an index that was
 * not verified current must never report health. Dry run builds nothing and says so.
 *
 * <p>Aggregate-only output: the Signal and the Run message carry the mode, a refusal code and counts - never a
 * column name, an entity id or a row.
 */
final class LaIndexBuildJob implements Job {

    static final String TYPE = "la.index.build";
    static final long DEFAULT_TIMEOUT_SECONDS = 3600;

    private final JobConfig cfg;

    LaIndexBuildJob(JobConfig cfg) {
        this.cfg = cfg;
    }

    @Override public String name() { return cfg.name(); }
    @Override public String type() { return TYPE; }

    @Override public JobResult run() {
        throw new UnsupportedOperationException("la.index.build requires a JobContext");
    }

    @Override
    public JobResult run(JobContext ctx) {
        long t0 = System.nanoTime();
        if (ctx.dryRun()) {
            ctx.log().info("dry run: the Link Analysis Index was NOT built (a build writes a new index version, "
                    + "so it has no preview form)");
            return JobResult.ok("dry run: nothing built - trigger for real to refresh the index",
                    (System.nanoTime() - t0) / 1_000_000L);
        }
        LinkIndexAccess index = ctx.services().find(LinkIndexAccess.class)
                .orElseThrow(() -> new IllegalStateException("la.index.build needs the 'link-index' Platform "
                        + "Service, which is not available in this build"));

        Map<String, String> p = ctx.params();
        LinkIndexAccess.Request req = new LinkIndexAccess.Request(cfg.name(), need(p, "dataset"),
                need(p, "source_col"), need(p, "target_col"), opt(p, "kind_col"), opt(p, "time_col"),
                opt(p, "time_col_zone"), opt(p, "weight_col"), list(opt(p, "attr_cols")), need(cfg.params(), "owner"),   // the AUTHORED, save-stamped owner - never the args / bind / manual-trigger layers
                "true".equalsIgnoreCase(String.valueOf(p.get("allow_full")).trim()),
                timeoutSeconds(p.get("timeout_seconds")) * 1000L);

        LinkIndexAccess.Outcome o = index.build(req);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("job", cfg.name());
        payload.put("result", o.result());
        payload.put("mode", o.mode());
        if (o.code() != null) payload.put("code", o.code());
        payload.put("edges", o.edges());
        payload.put("nodes", o.nodes());
        payload.put("deltas", o.deltas());
        ctx.signals().emit("la.index.build.completed", o.ok() ? Severity.INFO : Severity.WARN, payload);
        ctx.log().info("link index build", "result", o.result(), "mode", o.mode(), "code", o.code());

        long ms = (System.nanoTime() - t0) / 1_000_000L;
        String msg = "la.index.build: " + o.result().toLowerCase(java.util.Locale.ROOT)
                + (o.mode() == null ? "" : " (" + o.mode() + ")") + " - " + o.message();
        return o.ok() ? JobResult.ok(msg, ms) : JobResult.failed(msg, ms);
    }

    private static String need(Map<String, String> p, String key) {
        String v = p.get(key);
        if (v == null || v.isBlank())
            throw new IllegalArgumentException("la.index.build requires param '" + key + "'");
        return v.trim();
    }

    private static String opt(Map<String, String> p, String key) {
        String v = p.get(key);
        return v == null || v.isBlank() ? null : v.trim();
    }

    private static List<String> list(String csv) {
        return csv == null ? List.of() : Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private static long timeoutSeconds(String raw) {
        if (raw == null || raw.isBlank()) return DEFAULT_TIMEOUT_SECONDS;
        try {
            long v = Long.parseLong(raw.trim());
            return v < 1 ? DEFAULT_TIMEOUT_SECONDS : v;
        } catch (NumberFormatException bad) {
            return DEFAULT_TIMEOUT_SECONDS;
        }
    }
}
