package com.gamma.job;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Diff two Job Runs from RECORDED FACTS only ({@code DUCKLE-C2-RUN-DIFF-1}, 2026-09-16). Six kinds, each in one of
 * two states: <b>compared</b> — a list of concrete differences, every explanation line tracing to one of them —
 * or <b>not compared</b>, with the reason, when no recorded fact exists for that kind. No prose is generated
 * beyond what a difference itself says. ⛔ This is not a model summarising two receipts, on purpose.
 *
 * <ul>
 *   <li>{@code invocation} — trigger, and the parameter receipt (which layer supplied each value; layer names only,
 *       so a secret is never printed).</li>
 *   <li>{@code execution} — status, message, duration.</li>
 *   <li>{@code output} — dataset/file artifacts by name: rows, bytes, ref, watermark. <b>Absent is not zero</b>: an
 *       artifact one run produced and the other did not is reported as {@code absent}, never as {@code 0 rows}.</li>
 *   <li>{@code inputs} — not compared: no run records an input manifest (a job's reads are not receipted).</li>
 *   <li>{@code code}, {@code runtime} — not compared: no run records the job type's implementation version or
 *       the JVM/host it ran on. Stating that is the point; a diff that implied parity here would be lying.</li>
 * </ul>
 */
public final class JobRunDiff {
    private JobRunDiff() {}

    /** A parameter receipt artifact (kind {@code params}) — its {@code detail} is {@code name → {source, overrode}}. */
    static final String PARAMS = "params";

    public static Map<String, Object> diff(JobRun a, List<RunArtifact> aArts, JobRun b, List<RunArtifact> bArts) {
        Objects.requireNonNull(a, "a"); Objects.requireNonNull(b, "b");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("a", a.runId());
        out.put("b", b.runId());
        Map<String, Object> kinds = new LinkedHashMap<>();
        kinds.put("code", notCompared("no recorded fact: a run does not record its job type's implementation version"));
        kinds.put("runtime", notCompared("no recorded fact: a run does not record the JVM or host it ran on"));
        kinds.put("invocation", invocation(a, aArts, b, bArts));
        kinds.put("inputs", notCompared("no recorded fact: a run does not record an input manifest"));
        kinds.put("execution", execution(a, b));
        kinds.put("output", output(aArts, bArts));
        out.put("kinds", kinds);
        int total = 0;
        for (Object k : kinds.values())
            if (((Map<?, ?>) k).get("differences") instanceof List<?> l) total += l.size();
        out.put("differenceCount", total);
        return out;
    }

    private static Map<String, Object> notCompared(String reason) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("compared", false);
        m.put("reason", reason);
        return m;
    }

    private static Map<String, Object> compared(List<Map<String, Object>> differences) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("compared", true);
        m.put("differences", differences);
        List<String> explanation = new ArrayList<>();
        for (Map<String, Object> d : differences) explanation.add(String.valueOf(d.get("explanation")));
        m.put("explanation", explanation);
        return m;
    }

    private static Map<String, Object> difference(String field, Object aVal, Object bVal, String explanation) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("field", field);
        d.put("a", aVal);
        d.put("b", bVal);
        d.put("explanation", explanation);
        return d;
    }

    private static Map<String, Object> execution(JobRun a, JobRun b) {
        List<Map<String, Object>> diffs = new ArrayList<>();
        if (!Objects.equals(a.status(), b.status()))
            diffs.add(difference("status", a.status(), b.status(), "status " + a.status() + " → " + b.status()));
        if (!Objects.equals(nullToEmpty(a.message()), nullToEmpty(b.message())))
            diffs.add(difference("message", a.message(), b.message(), "message changed"));
        if (a.durationMs() != b.durationMs())
            diffs.add(difference("durationMs", a.durationMs(), b.durationMs(),
                    "duration " + a.durationMs() + " ms → " + b.durationMs() + " ms (" + signed(b.durationMs() - a.durationMs()) + " ms)"));
        return compared(diffs);
    }

    private static Map<String, Object> invocation(JobRun a, List<RunArtifact> aArts, JobRun b, List<RunArtifact> bArts) {
        List<Map<String, Object>> diffs = new ArrayList<>();
        if (!Objects.equals(a.trigger(), b.trigger()))
            diffs.add(difference("trigger", a.trigger(), b.trigger(), "trigger " + a.trigger() + " → " + b.trigger()));
        Map<String, Object> pa = receipt(aArts), pb = receipt(bArts);
        if (pa == null && pb == null) {
            Map<String, Object> m = compared(diffs);
            m.put("note", "neither run carries a parameter receipt (recorded since 2026-09-16); parameters not compared");
            return m;
        }
        TreeSet<String> names = new TreeSet<>();
        if (pa != null) names.addAll(pa.keySet());
        if (pb != null) names.addAll(pb.keySet());
        for (String n : names) {
            Object sa = source(pa, n), sb = source(pb, n);
            if (!Objects.equals(sa, sb))
                diffs.add(difference("params." + n + ".source", sa, sb,
                        "parameter '" + n + "' came from " + (sa == null ? "nowhere (absent)" : sa)
                                + " in a and from " + (sb == null ? "nowhere (absent)" : sb) + " in b"));
        }
        return compared(diffs);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> receipt(List<RunArtifact> arts) {
        Map<String, Object> last = null;
        for (RunArtifact r : arts) if (PARAMS.equals(r.kind()) && r.detail() != null) last = (Map<String, Object>) r.detail();
        return last;
    }

    private static Object source(Map<String, Object> receipt, String name) {
        if (receipt == null || !(receipt.get(name) instanceof Map<?, ?> m)) return null;
        return m.get("source");
    }

    private static Map<String, Object> output(List<RunArtifact> aArts, List<RunArtifact> bArts) {
        Map<String, RunArtifact> a = outputsByName(aArts), b = outputsByName(bArts);
        TreeSet<String> names = new TreeSet<>();
        names.addAll(a.keySet());
        names.addAll(b.keySet());
        List<Map<String, Object>> diffs = new ArrayList<>();
        for (String n : names) {
            RunArtifact x = a.get(n), y = b.get(n);
            if (x == null || y == null) {   // absent ≠ zero
                diffs.add(difference("output." + n, x == null ? "absent" : x.kind(), y == null ? "absent" : y.kind(),
                        "artifact '" + n + "' " + (x == null ? "not produced by a" : "not produced by b")));
                continue;
            }
            if (x.rows() != y.rows())
                diffs.add(difference("output." + n + ".rows", x.rows(), y.rows(),
                        "'" + n + "' rows " + x.rows() + " → " + y.rows() + " (" + signed(y.rows() - x.rows()) + ")"));
            if (x.bytes() != y.bytes())
                diffs.add(difference("output." + n + ".bytes", x.bytes(), y.bytes(),
                        "'" + n + "' bytes " + x.bytes() + " → " + y.bytes() + " (" + signed(y.bytes() - x.bytes()) + ")"));
            if (!Objects.equals(x.ref(), y.ref()))
                diffs.add(difference("output." + n + ".ref", x.ref(), y.ref(), "'" + n + "' written to a different ref"));
            if (!Objects.equals(x.watermark(), y.watermark()))
                diffs.add(difference("output." + n + ".watermark", x.watermark(), y.watermark(),
                        "'" + n + "' watermark " + x.watermark() + " → " + y.watermark()));
        }
        return compared(diffs);
    }

    /** dataset/file artifacts by name, highest seq winning — the receipt (kind params) is not an output. */
    private static Map<String, RunArtifact> outputsByName(List<RunArtifact> arts) {
        Map<String, RunArtifact> out = new LinkedHashMap<>();
        for (RunArtifact r : arts) if (!PARAMS.equals(r.kind())) out.put(r.name(), r);
        return out;
    }

    private static String signed(long delta) { return (delta >= 0 ? "+" : "") + delta; }
    private static String nullToEmpty(String s) { return s == null ? "" : s; }
}
