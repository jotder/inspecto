package com.gamma.pipeline.exec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.api.PublicApi;
import com.gamma.pipeline.PipelineRel;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * <b>X5 — the Step Receipt</b>: the record one Step leaves for one Consignment, on one lane, in one attempt
 * (GLOSSARY <i>Step Receipt</i>; design of record {@code docs/superpower/2026-11-17-x5-stepinfo-envelope-plan.md}
 * §3.2). Rows in, rows per outlet, where the data rests durably, a schema fingerprint, the outcome and bounded
 * diagnostics. Written once, never updated: a Consignment's trail is the ordered set of its receipts.
 *
 * <p>🔴 <b>The JSON form ({@link #toJson()}) is at most {@link #MAX_BYTES} bytes of UTF-8, enforced HERE, in the
 * canonical constructor</b> — not by convention at a call site. The table bounds always apply ({@code diag.msg}
 * ≤ 240 chars, ≤ 16 outlets with the surplus folded into one {@code route:*} entry carrying their sum,
 * ≤ 8 {@code diag.counts}, a {@code ref} path kept to its last 128 chars, identity fields ≤ 128 chars); if the
 * JSON still does not fit, the message is cut further, then more outlets are folded, then the counts and the ref
 * path are dropped, then the longest identity field is shortened. Every cut sets {@link #truncated}.
 *
 * <p>Outlet names are stored canonical ({@link PipelineRel#canonical}), so a receipt says {@code reject:dropped}
 * whichever spelling the Step that produced it emitted.
 *
 * <p>⚠ A public record cannot gain a component without breaking its canonical constructor: this shape is final
 * at 4.0.0 ({@code v} = 1). Growth goes into a new {@code v}, read by {@link #fromJson}.
 *
 * @param v             shape version, {@link #VERSION}
 * @param consignmentId the Consignment
 * @param pipeline      the Pipeline id (the authored id on the job lane)
 * @param lane          which lane wrote it: {@link #LANE_INGEST_FLAT}, {@link #LANE_INGEST_GRAPH}, {@link #LANE_JOB},
 *                      {@link #LANE_DRAIN}
 * @param runId         the job run (an attempt); {@code ""} on the ingest lanes, never {@code null} — a
 *                      {@code NULL} in a UNIQUE key does not hold
 * @param seq           order within (Consignment, run, lane): the walk's topological index (1-based)
 * @param step          node id (or the phase on the flat lane)
 * @param kind          node type ({@code transform.filter}), or {@code phase}
 * @param outcome       what happened to the Step
 * @param in            rows entering (the sum over inbound outlets; the seed's own count for a seed)
 * @param outlets       rows leaving per outlet
 * @param ref           where the data rests durably, or {@code null} (scratch relations get no pointer)
 * @param schema        column count + 12-hex fingerprint of the main relation, or {@code null} when unknown
 * @param diag          bounded diagnostics; never {@code null} ({@link Diagnostics#NONE})
 * @param startedAt     ISO-8601 instant the Step started, or {@code null} when it never started
 * @param ms            how long it took
 * @param simulated     a dry run's receipt
 * @param truncated     something was cut to fit
 */
@PublicApi(since = "4.0.0")
public record StepReceipt(int v, String consignmentId, String pipeline, String lane, String runId, int seq,
                          String step, String kind, Outcome outcome, long in, List<Outlet> outlets,
                          Pointer ref, Schema schema, Diagnostics diag, String startedAt, long ms,
                          boolean simulated, boolean truncated) {

    /** The shape version this build writes. */
    public static final int VERSION = 1;
    /** The bound on the UTF-8 JSON form. */
    public static final int MAX_BYTES = 1024;
    /** Bound on {@code diag.msg}, in chars. */
    public static final int MAX_MSG = 240;
    /** Bound on {@code outlets}. */
    public static final int MAX_OUTLETS = 16;
    /** Bound on {@code diag.counts}. */
    public static final int MAX_COUNTS = 8;
    /** Bound on an identity field and on the kept tail of a {@code ref} path, in chars. */
    public static final int MAX_ID = 128;
    /** The outlet the surplus outlets are folded into. */
    public static final String FOLDED_OUTLET = PipelineRel.ROUTE_PREFIX + "*";

    /** Lane: the flat ingest lane (three phases, no graph walk). */
    public static final String LANE_INGEST_FLAT = "ingest-flat";
    /** Lane: the branch-aware graph fork of ingest. */
    public static final String LANE_INGEST_GRAPH = "ingest-graph";
    /** Lane: the at-rest pipeline job. */
    public static final String LANE_JOB = "job";
    /** Lane: a drain of parked rows. */
    public static final String LANE_DRAIN = "drain";

    /** Diagnostic code: a contributed Step threw (also any Step failure with no narrower code). */
    public static final String STEP_FAILED = StepFailure.STEP_FAILED;
    /** Diagnostic code: a contributed Step ran past its deadline. */
    public static final String STEP_TIMEOUT = StepFailure.STEP_TIMEOUT;
    /** Diagnostic code: the Step kind is disabled. */
    public static final String STEP_DISABLED = StepFailure.STEP_DISABLED;
    /** Diagnostic code: the Step failed on SQL. */
    public static final String SQL_ERROR = "SQL_ERROR";
    /** Diagnostic code: the Step failed on I/O. */
    public static final String IO_ERROR = "IO_ERROR";
    /** Diagnostic code: a conserving Step put out fewer rows than it took in. */
    public static final String CONSERVATION_LOSS = "CONSERVATION_LOSS";
    /** Diagnostic code: a conserving Step put out more rows than it took in. */
    public static final String CONSERVATION_AMPLIFICATION = "CONSERVATION_AMPLIFICATION";
    /** Diagnostic code: the write stayed on the flat lane; {@code msg} says why. */
    public static final String LANE_FLAT = "LANE_FLAT";

    /** What happened to the Step. */
    public enum Outcome {
        /** It ran. */
        OK,
        /** It threw; the Consignment failed here. */
        FAILED,
        /** A disabled Step, bypassed. */
        SKIPPED,
        /** A disabled sink whose rows were parked. */
        PARKED,
        /** Downstream of a failure: never ran. */
        NOT_REACHED
    }

    /** Rows leaving a Step on one outlet ({@code data}, {@code reject:<reason>}, {@code route:<key>}). */
    public record Outlet(String outlet, long rows) {
        public Outlet {
            outlet = PipelineRel.canonical(Objects.requireNonNull(outlet, "outlet"));
        }
    }

    /**
     * Where a Step's data rests durably.
     *
     * @param kind  {@link #OUTPUTS} (the registry's rows for this Consignment), {@link #PARK}, {@link #REJECTS}
     *              (the reject sidecar) or {@link #STORE}
     * @param table the table / store name, or {@code null}
     * @param path  a path relative to the data root (park table, sidecar) or a partition, or {@code null}
     * @param files how many files (for {@link #OUTPUTS}), else 0
     */
    public record Pointer(String kind, String table, String path, long files) {
        public static final String OUTPUTS = "outputs";
        public static final String PARK = "park";
        public static final String REJECTS = "rejects";
        public static final String STORE = "store";

        public Pointer {
            Objects.requireNonNull(kind, "kind");
        }
    }

    /** The main relation's column count and a 12-hex {@code CanonicalHash} of its {@code (name, type)} list. */
    public record Schema(int columns, String fp) {
        public Schema {
            if (fp != null && fp.length() > 64) fp = fp.substring(0, 64);
        }
    }

    /**
     * Bounded diagnostics.
     *
     * @param code   one of the closed set on {@link StepReceipt} ({@link #STEP_FAILED} …), or {@code null}
     * @param msg    ≤ {@link #MAX_MSG} chars, or {@code null}
     * @param counts ≤ {@link #MAX_COUNTS} named counts, in insertion order
     */
    public record Diagnostics(String code, String msg, Map<String, Long> counts) {
        /** No diagnosis. */
        public static final Diagnostics NONE = new Diagnostics(null, null, Map.of());

        public Diagnostics {
            counts = counts == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(counts));
        }

        /**
         * The diagnosis of a Step that threw {@code t}: the {@link StepFailure} code anywhere on the cause chain,
         * else {@link #SQL_ERROR} / {@link #IO_ERROR} for a SQL or I/O cause, else {@link #STEP_FAILED}; the
         * message is the thrown one (bounded when the receipt is built).
         */
        public static Diagnostics of(Throwable t) {
            String code = null;
            for (Throwable c = t; c != null && code == null; c = c.getCause() == c ? null : c.getCause()) {
                if (c instanceof StepFailure sf) code = sf.code();
            }
            if (code == null) {
                for (Throwable c = t; c != null && code == null; c = c.getCause() == c ? null : c.getCause()) {
                    if (c instanceof SQLException) code = SQL_ERROR;
                    else if (c instanceof IOException || c instanceof UncheckedIOException) code = IO_ERROR;
                }
            }
            String msg = t == null ? null : (t.getMessage() != null ? t.getMessage() : t.getClass().getName());
            return new Diagnostics(code == null ? STEP_FAILED : code, msg, Map.of());
        }
    }

    public StepReceipt {
        Objects.requireNonNull(outcome, "outcome");
        Fit f = new Fit(consignmentId, pipeline, lane, runId == null ? "" : runId, step, kind,
                outlets == null ? List.of() : outlets, ref, diag == null ? Diagnostics.NONE : diag, startedAt);
        f.fit(v, seq, outcome, in, schema, ms, simulated);
        consignmentId = f.consignmentId;
        pipeline = f.pipeline;
        lane = f.lane;
        runId = f.runId;
        step = f.step;
        kind = f.kind;
        outlets = List.copyOf(f.outlets);
        ref = f.ref;
        diag = f.diag;
        startedAt = f.startedAt;
        truncated = truncated || f.cut;
    }

    /** The bounded JSON form — what {@code consignment_steps.receipt} stores; at most {@link #MAX_BYTES} bytes. */
    public String toJson() {
        return json(v, consignmentId, pipeline, lane, runId, seq, step, kind, outcome, in, outlets, ref, schema,
                diag, startedAt, ms, simulated, truncated);
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Read a receipt back from {@link #toJson()}'s form. */
    public static StepReceipt fromJson(String json) {
        JsonNode n;
        try {
            n = MAPPER.readTree(json);
        } catch (IOException e) {
            throw new IllegalArgumentException("not a Step Receipt: " + e.getMessage(), e);
        }
        if (n == null || !n.isObject()) throw new IllegalArgumentException("not a Step Receipt: " + json);
        List<Outlet> outlets = new ArrayList<>();
        for (JsonNode o : n.path("outlets")) outlets.add(new Outlet(o.path("outlet").asText(), o.path("rows").asLong()));
        JsonNode r = n.path("ref");
        Pointer ref = r.isObject()
                ? new Pointer(r.path("kind").asText(), text(r, "table"), text(r, "path"), r.path("files").asLong())
                : null;
        JsonNode s = n.path("schema");
        Schema schema = s.isObject() ? new Schema(s.path("columns").asInt(), text(s, "fp")) : null;
        JsonNode d = n.path("diag");
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = d.path("counts").fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            counts.put(e.getKey(), e.getValue().asLong());
        }
        Diagnostics diag = new Diagnostics(text(d, "code"), text(d, "msg"), counts);
        return new StepReceipt(n.path("v").asInt(VERSION), text(n, "consignmentId"), text(n, "pipeline"),
                text(n, "lane"), text(n, "runId"), n.path("seq").asInt(), text(n, "step"), text(n, "kind"),
                Outcome.valueOf(n.path("outcome").asText()), n.path("in").asLong(), outlets, ref, schema, diag,
                text(n, "startedAt"), n.path("ms").asLong(), n.path("simulated").asBoolean(),
                n.path("truncated").asBoolean());
    }

    private static String text(JsonNode n, String field) {
        JsonNode f = n.get(field);
        return f == null || f.isNull() ? null : f.asText();
    }

    // ── the size bound ──────────────────────────────────────────────────────────────────────────────

    /** The mutable working copy the canonical constructor cuts down until it fits. */
    private static final class Fit {
        String consignmentId, pipeline, lane, runId, step, kind, startedAt;
        List<Outlet> outlets;
        Pointer ref;
        Diagnostics diag;
        boolean cut;

        Fit(String consignmentId, String pipeline, String lane, String runId, String step, String kind,
            List<Outlet> outlets, Pointer ref, Diagnostics diag, String startedAt) {
            this.consignmentId = consignmentId;
            this.pipeline = pipeline;
            this.lane = lane;
            this.runId = runId;
            this.step = step;
            this.kind = kind;
            this.outlets = new ArrayList<>(outlets);
            this.ref = ref;
            this.diag = diag;
            this.startedAt = startedAt;
        }

        void fit(int v, int seq, Outcome outcome, long in, Schema schema, long ms, boolean simulated) {
            // 1. The table's bounds, always.
            consignmentId = head(consignmentId, MAX_ID);
            pipeline = head(pipeline, MAX_ID);
            lane = head(lane, MAX_ID);
            runId = head(runId, MAX_ID);
            step = head(step, MAX_ID);
            kind = head(kind, MAX_ID);
            startedAt = head(startedAt, 40);
            List<Outlet> named = new ArrayList<>(outlets.size());
            for (Outlet o : outlets) {
                String name = head(o.outlet(), MAX_ID);
                named.add(name.equals(o.outlet()) ? o : new Outlet(name, o.rows()));
            }
            outlets = named;
            if (outlets.size() > MAX_OUTLETS) fold(MAX_OUTLETS);
            boolean before = cut;
            cut = false;
            String code = head(diag.code(), MAX_ID);
            String msg = head(diag.msg(), MAX_MSG);
            Map<String, Long> counts = new LinkedHashMap<>();
            for (Map.Entry<String, Long> e : diag.counts().entrySet()) {
                if (counts.size() == MAX_COUNTS) { cut = true; break; }
                counts.put(head(e.getKey(), MAX_ID), e.getValue());
            }
            if (cut) diag = new Diagnostics(code, msg, counts);
            cut |= before;
            if (ref != null) {
                Pointer p = new Pointer(head(ref.kind(), 16), head(ref.table(), MAX_ID), tail(ref.path(), MAX_ID),
                        ref.files());
                if (!p.equals(ref)) ref = p;
            }

            // 2. Still too big: cut further, in the plan's order, until it fits.
            while (bytes(v, seq, outcome, in, schema, ms, simulated) > MAX_BYTES) {
                cut = true;
                if (diag.msg() != null) {
                    String m = diag.msg();
                    diag = new Diagnostics(diag.code(), m.length() <= 16 ? null : head(m, m.length() / 2), diag.counts());
                } else if (outlets.size() > 1) {
                    fold(outlets.size() - 1);
                } else if (!diag.counts().isEmpty()) {
                    diag = new Diagnostics(diag.code(), null, Map.of());
                } else if (ref != null && (ref.path() != null || ref.table() != null)) {
                    ref = new Pointer(ref.kind(), null, null, ref.files());
                } else if (!outlets.isEmpty() && outlets.get(0).outlet().length() > 16) {
                    Outlet o = outlets.get(0);
                    outlets = new ArrayList<>(List.of(new Outlet(head(o.outlet(), o.outlet().length() / 2), o.rows())));
                } else if (!shortenLongestIdentity()) {
                    break;   // nothing left to cut: unreachable with the bounds above
                }
            }
        }

        /** Fold every outlet beyond {@code keep - 1} into one {@link #FOLDED_OUTLET} carrying their sum; the
         *  data and reject outlets are kept ahead of the routes, so only routes fold while any remain. */
        private void fold(int keep) {
            List<Outlet> ordered = new ArrayList<>(outlets.size());
            for (Outlet o : outlets) if (!PipelineRel.isRoute(o.outlet())) ordered.add(o);
            for (Outlet o : outlets) if (PipelineRel.isRoute(o.outlet())) ordered.add(o);
            int head = Math.max(0, keep - 1);
            long sum = 0;
            for (int i = head; i < ordered.size(); i++) sum += ordered.get(i).rows();
            List<Outlet> out = new ArrayList<>(ordered.subList(0, head));
            out.add(new Outlet(FOLDED_OUTLET, sum));
            outlets = out;
            cut = true;
        }

        private boolean shortenLongestIdentity() {
            String[] f = {consignmentId, pipeline, runId, step, kind};
            int at = -1;
            for (int i = 0; i < f.length; i++)
                if (f[i] != null && f[i].length() > 8 && (at < 0 || f[i].length() > f[at].length())) at = i;
            if (at < 0) return false;
            String s = head(f[at], f[at].length() / 2);
            switch (at) {
                case 0 -> consignmentId = s;
                case 1 -> pipeline = s;
                case 2 -> runId = s;
                case 3 -> step = s;
                default -> kind = s;
            }
            return true;
        }

        private int bytes(int v, int seq, Outcome outcome, long in, Schema schema, long ms, boolean simulated) {
            return json(v, consignmentId, pipeline, lane, runId, seq, step, kind, outcome, in, outlets, ref, schema,
                    diag, startedAt, ms, simulated, false).getBytes(StandardCharsets.UTF_8).length;   // "false" is the longer
        }

        /** The first {@code n} chars, never splitting a surrogate pair; marks the cut. */
        private String head(String s, int n) {
            if (s == null || s.length() <= n) return s;
            cut = true;
            int end = Character.isHighSurrogate(s.charAt(n - 1)) ? n - 1 : n;
            return s.substring(0, end);
        }

        /** The last {@code n} chars, never splitting a surrogate pair; marks the cut. */
        private String tail(String s, int n) {
            if (s == null || s.length() <= n) return s;
            cut = true;
            int start = s.length() - n;
            if (Character.isLowSurrogate(s.charAt(start))) start++;
            return s.substring(start);
        }
    }

    private static String json(int v, String consignmentId, String pipeline, String lane, String runId, int seq,
                               String step, String kind, Outcome outcome, long in, List<Outlet> outlets,
                               Pointer ref, Schema schema, Diagnostics diag, String startedAt, long ms,
                               boolean simulated, boolean truncated) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("{\"v\":").append(v);
        sb.append(",\"consignmentId\":"); str(sb, consignmentId);
        sb.append(",\"pipeline\":"); str(sb, pipeline);
        sb.append(",\"lane\":"); str(sb, lane);
        sb.append(",\"runId\":"); str(sb, runId);
        sb.append(",\"seq\":").append(seq);
        sb.append(",\"step\":"); str(sb, step);
        sb.append(",\"kind\":"); str(sb, kind);
        sb.append(",\"outcome\":"); str(sb, outcome.name());
        sb.append(",\"in\":").append(in);
        sb.append(",\"outlets\":[");
        for (int i = 0; i < outlets.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append("{\"outlet\":"); str(sb, outlets.get(i).outlet());
            sb.append(",\"rows\":").append(outlets.get(i).rows()).append('}');
        }
        sb.append("],\"ref\":");
        if (ref == null) sb.append("null");
        else {
            sb.append("{\"kind\":"); str(sb, ref.kind());
            sb.append(",\"table\":"); str(sb, ref.table());
            sb.append(",\"path\":"); str(sb, ref.path());
            sb.append(",\"files\":").append(ref.files()).append('}');
        }
        sb.append(",\"schema\":");
        if (schema == null) sb.append("null");
        else {
            sb.append("{\"columns\":").append(schema.columns()).append(",\"fp\":"); str(sb, schema.fp());
            sb.append('}');
        }
        sb.append(",\"diag\":{\"code\":"); str(sb, diag.code());
        sb.append(",\"msg\":"); str(sb, diag.msg());
        sb.append(",\"counts\":{");
        int i = 0;
        for (Map.Entry<String, Long> e : diag.counts().entrySet()) {
            if (i++ > 0) sb.append(',');
            str(sb, e.getKey());
            sb.append(':').append(e.getValue());
        }
        sb.append("}}");
        sb.append(",\"startedAt\":"); str(sb, startedAt);
        sb.append(",\"ms\":").append(ms);
        sb.append(",\"simulated\":").append(simulated);
        sb.append(",\"truncated\":").append(truncated);
        return sb.append('}').toString();
    }

    private static void str(StringBuilder sb, String s) {
        if (s == null) { sb.append("null"); return; }
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20 || Character.isSurrogate(c) && !validPair(s, i)) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                        if (Character.isHighSurrogate(c)) sb.append(s.charAt(++i));
                    }
                }
            }
        }
        sb.append('"');
    }

    /** Whether the surrogate at {@code i} is the high half of a well-formed pair (a lone one is escaped). */
    private static boolean validPair(String s, int i) {
        return Character.isHighSurrogate(s.charAt(i)) && i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1));
    }
}
