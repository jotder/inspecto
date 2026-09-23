package com.gamma.geolink;

import com.gamma.control.ApiException;
import com.gamma.util.SqlIdent;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * <b>Branching pattern compiler (LA-14b, server half)</b> — compiles the SAME motif model the browser matcher
 * takes ({@code inspecto-ui/src/app/inspecto/graph/branching-pattern-engine.ts}: ordered {@code fan-in} /
 * {@code fan-out} stages, distinct-counterparty breadth, per-leg threshold band, window, LA-14a ordering) to
 * DuckDB SQL over a whole Dataset.
 *
 * <p><b>Why it exists.</b> The browser matcher only sees the projected graph, and {@code /inv/projection} caps
 * that at 2 000 folded links sorted {@code cnt DESC, source, target}. Structuring legs are small, one-off
 * deposits — they sort LAST and are cut FIRST on a large feed, so the browser can honestly report "none" over a
 * graph the ring was truncated out of. Here the threshold band, the kind and the filter are pushed into the
 * {@code WHERE} of the whole Dataset, so only legs that could belong to the motif leave DuckDB at all.
 *
 * <p><b>Split of work.</b> SQL selects each stage's ELIGIBLE legs and prunes them with necessary conditions only
 * (stage 0: an anchor with at least {@code minBranches} distinct counterparties; stage k: a leg whose tail was
 * reached by stage k-1, and — with {@code afterPrevious} — strictly after the earliest leg that reached it).
 * The windowed, per-branch search then runs over those few legs in {@link BranchingPatternEngine}, a line-for-
 * line port of the TS matcher — so the two engines cannot disagree on what a window or an arrival means. A
 * necessary-condition prune can only remove legs no match could use, which is what keeps the results identical.
 *
 * <p><b>Fences.</b> Every value (threshold bounds, kinds, breadths) is a bound parameter; every identifier is
 * checked against the relation's real columns by the caller before this runs; node keys are normalised in SQL
 * with {@code normalizeEntityKey}'s rule (lower-case, whitespace collapsed, trailing {@code [\s.,;:]} stripped)
 * so a breadth count sees the nodes the browser would draw.
 */
final class PatternQueryCompiler {

    private static final Pattern SAFE_IDENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    /** The browser's node kind for every projected entity — a stage asking for another kind matches nothing. */
    static final String ENTITY_KIND = "entity";
    /** The browser's link kind when the projection has no kind column. */
    static final String DEFAULT_LINK_KIND = "link";

    private PatternQueryCompiler() {}

    record Threshold(String attr, Double min, Double max) {}

    record Stage(boolean fanIn, int minBranches, String edgeKind, String nodeKind, Threshold threshold,
                 Double windowHours, boolean afterPrevious, Double maxGapHours) {}

    /** One statement plus its positional binds (all strings; the SQL casts each). */
    record Compiled(String sql, List<String> binds) {}

    /** Parse and validate the body's {@code stages} — the TS {@code BranchStage[]} shape. Any fault is a 422. */
    static List<Stage> parseStages(Object raw) {
        if (!(raw instanceof List<?> list) || list.isEmpty())
            throw new ApiException(422, "body must include a non-empty 'stages' array");
        if (list.size() > 8) throw new ApiException(422, "'stages' is capped at 8");
        List<Stage> out = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            if (!(list.get(i) instanceof Map<?, ?> m)) throw new ApiException(422, "stages[" + i + "] must be an object");
            Object shape = m.get("shape");
            if (!"fan-in".equals(shape) && !"fan-out".equals(shape))
                throw new ApiException(422, "stages[" + i + "].shape must be fan-in or fan-out");
            if (!(m.get("minBranches") instanceof Number mb) || mb.intValue() < 1 || mb.intValue() > 1000)
                throw new ApiException(422, "stages[" + i + "].minBranches must be an integer 1..1000");
            Threshold th = null;
            if (m.get("threshold") instanceof Map<?, ?> t) {
                Object attr = t.get("attr");
                if (!(attr instanceof String a) || !SAFE_IDENT.matcher(a).matches())
                    throw new ApiException(422, "stages[" + i + "].threshold.attr must be a column identifier");
                th = new Threshold(a, num(t.get("min"), i, "threshold.min"), num(t.get("max"), i, "threshold.max"));
            } else if (m.get("threshold") != null) {
                throw new ApiException(422, "stages[" + i + "].threshold must be an object");
            }
            out.add(new Stage("fan-in".equals(shape), mb.intValue(), text(m.get("edgeKind")), text(m.get("nodeKind")),
                    th, positive(m.get("windowHours"), i, "windowHours"), Boolean.TRUE.equals(m.get("afterPrevious")),
                    positive(m.get("maxGapHours"), i, "maxGapHours")));
        }
        return out;
    }

    /** TS {@code branchingNeedsTime}: a window anywhere, or ordering after stage 0. */
    static boolean needsTime(List<Stage> stages) {
        for (int i = 0; i < stages.size(); i++) {
            Stage st = stages.get(i);
            if ((st.windowHours() != null && st.windowHours() > 0) || (i > 0 && st.afterPrevious())) return true;
        }
        return false;
    }

    /** The distinct threshold columns, in stage order — each becomes one {@code v_i}/{@code r_i} pair. */
    static List<String> thresholdAttrs(List<Stage> stages) {
        Set<String> attrs = new LinkedHashSet<>();
        for (Stage st : stages) if (st.threshold() != null) attrs.add(st.threshold().attr());
        return new ArrayList<>(attrs);
    }

    /** {@code normalizeEntityKey} of a trimmed value, in DuckDB. Constant pattern text — no caller input. */
    private static String norm(String expr) {
        return "trim(regexp_replace(regexp_replace(lower(" + expr + "), '\\s+', ' ', 'g'), '[\\s.,;:]+$', ''))";
    }

    /**
     * The shared base relation {@code __r}: one row per Dataset row, endpoints normalised, blank endpoints and
     * rows failing {@code filterSql} dropped. Columns: {@code s,t} (keys), {@code sl,tl} (trimmed raw labels),
     * {@code k} (kind), {@code tr} (the time as text) + {@code ts} (epoch ms, NULL when unparseable), and per
     * threshold column {@code r_i} (text) + {@code v_i} (DOUBLE, NULL when not numeric).
     */
    private static String base(String datasetId, String sourceCol, String targetCol, String kindCol, String timeCol,
                               List<String> attrs, String filterSql) {
        String src = "trim(CAST(" + q(sourceCol) + " AS VARCHAR))", tgt = "trim(CAST(" + q(targetCol) + " AS VARCHAR))";
        StringBuilder sb = new StringBuilder("__r0 AS (SELECT ").append(src).append(" AS sl, ").append(tgt).append(" AS tl, ")
                .append(kindCol != null ? "CAST(" + q(kindCol) + " AS VARCHAR)" : "'" + DEFAULT_LINK_KIND + "'").append(" AS k, ")
                .append(timeCol != null ? "CAST(" + q(timeCol) + " AS VARCHAR)" : "CAST(NULL AS VARCHAR)").append(" AS tr");
        for (int i = 0; i < attrs.size(); i++) sb.append(", CAST(").append(q(attrs.get(i))).append(" AS VARCHAR) AS r_").append(i);
        sb.append(" FROM ").append(q(datasetId)).append(" WHERE ").append(q(sourceCol)).append(" IS NOT NULL AND ")
          .append(q(targetCol)).append(" IS NOT NULL AND (").append(filterSql).append(")), ")
          .append("__r AS (SELECT ").append(norm("sl")).append(" AS s, ").append(norm("tl")).append(" AS t, sl, tl, k, tr,")
          .append(" epoch_ms(TRY_CAST(tr AS TIMESTAMP)) AS ts");
        for (int i = 0; i < attrs.size(); i++) sb.append(", r_").append(i).append(", TRY_CAST(trim(r_").append(i).append(") AS DOUBLE) AS v_").append(i);
        sb.append(" FROM __r0 WHERE sl <> '' AND tl <> '')");
        return sb.toString();
    }

    /** The eligibility predicate of one stage over {@code __r} — the TS {@code eligible[]} filter. */
    private static String eligible(Stage st, List<String> attrs, List<String> binds) {
        StringBuilder w = new StringBuilder("s <> t");
        if (st.edgeKind() != null) {
            w.append(" AND k = ?");
            binds.add(st.edgeKind());
        }
        if (st.threshold() != null) {
            String v = "v_" + attrs.indexOf(st.threshold().attr());
            w.append(" AND ").append(v).append(" IS NOT NULL");
            if (st.threshold().min() != null) {
                w.append(" AND ").append(v).append(" >= CAST(? AS DOUBLE)");
                binds.add(String.valueOf(st.threshold().min()));
            }
            if (st.threshold().max() != null) {
                w.append(" AND ").append(v).append(" < CAST(? AS DOUBLE)");
                binds.add(String.valueOf(st.threshold().max()));
            }
        }
        return w.toString();
    }

    /**
     * The refusal probe: per thresholded stage, how many kind-eligible legs carry a numeric value
     * ({@code valued_i}) and how many pass the band ({@code passing_i}) — the TS matcher's two §2.6 refusals.
     */
    static Compiled refusalProbe(String datasetId, String sourceCol, String targetCol, String kindCol, String timeCol,
                                 List<Stage> stages, String filterSql) {
        List<String> attrs = thresholdAttrs(stages);
        List<String> binds = new ArrayList<>();
        StringBuilder sb = new StringBuilder("WITH ").append(base(datasetId, sourceCol, targetCol, kindCol, timeCol, attrs, filterSql))
                .append(" SELECT 0 AS __probe");
        for (int i = 0; i < stages.size(); i++) {
            Stage st = stages.get(i);
            if (st.threshold() == null) continue;
            String v = "v_" + attrs.indexOf(st.threshold().attr());
            sb.append(", count(*) FILTER (WHERE s <> t");
            if (st.edgeKind() != null) {
                sb.append(" AND k = ?");
                binds.add(st.edgeKind());
            }
            sb.append(" AND ").append(v).append(" IS NOT NULL) AS valued_").append(i);
            sb.append(", count(*) FILTER (WHERE ").append(eligible(st, attrs, binds)).append(") AS passing_").append(i);
        }
        sb.append(" FROM __r");
        return new Compiled(sb.toString(), binds);
    }

    /**
     * The legs statement: {@code (stage, s, t, sl, tl, k, tr, ts, r_i...)}, one row per DISTINCT leg per stage,
     * ordered {@code stage, s, t, ts} so a leg cap cuts deterministically. Stage 0 keeps only anchors reaching
     * their breadth; stage k keeps only legs out of a node stage k-1 reached (and, ordered, after it did).
     */
    static Compiled legs(String datasetId, String sourceCol, String targetCol, String kindCol, String timeCol,
                         List<Stage> stages, String filterSql) {
        List<String> attrs = thresholdAttrs(stages);
        List<String> binds = new ArrayList<>();
        StringBuilder rCols = new StringBuilder();
        for (int i = 0; i < attrs.size(); i++) rCols.append(", r_").append(i);
        String legCols = "s, t, k, tr" + rCols;
        StringBuilder sb = new StringBuilder("WITH ").append(base(datasetId, sourceCol, targetCol, kindCol, timeCol, attrs, filterSql));
        for (int i = 0; i < stages.size(); i++) {
            Stage st = stages.get(i);
            // One row per distinct leg — the browser folds identical (source, target, kind, attrs) into ONE edge.
            sb.append(", __e").append(i).append(" AS (SELECT ").append(legCols)
              .append(", min(sl) AS sl, min(tl) AS tl, min(ts) AS ts FROM __r WHERE ").append(eligible(st, attrs, binds))
              .append(" GROUP BY ").append(legCols).append(")");
            sb.append(", __s").append(i).append(" AS (SELECT * FROM __e").append(i).append(" e WHERE ");
            if (i == 0) {
                String anchor = st.fanIn() ? "t" : "s", other = st.fanIn() ? "s" : "t";
                sb.append(anchor).append(" IN (SELECT ").append(anchor).append(" FROM __e0 GROUP BY ").append(anchor)
                  .append(" HAVING count(DISTINCT ").append(other).append(") >= CAST(? AS INTEGER))");
                binds.add(String.valueOf(st.minBranches()));
            } else {
                // Every stage's frontier is a subset of the previous stage's leg TARGETS (collector or branches).
                String prev = "__s" + (i - 1);
                sb.append("e.s IN (SELECT t FROM ").append(prev).append(")");
                if (st.afterPrevious())
                    sb.append(" AND e.ts > (SELECT min(p.ts) FROM ").append(prev).append(" p WHERE p.t = e.s)");
            }
            sb.append(")");
        }
        sb.append(" SELECT * FROM (");
        for (int i = 0; i < stages.size(); i++) {
            if (i > 0) sb.append(" UNION ALL ");
            sb.append("SELECT ").append(i).append(" AS stage, s, t, sl, tl, k, tr, ts").append(rCols).append(" FROM __s").append(i);
        }
        sb.append(") ORDER BY stage, s, t, ts NULLS LAST, tr");
        return new Compiled(sb.toString(), binds);
    }

    private static String q(String ident) {
        return SqlIdent.q(ident);
    }

    private static String text(Object v) {
        return v instanceof String s && !s.isBlank() ? s : null;
    }

    private static Double num(Object v, int i, String key) {
        if (v == null) return null;
        if (v instanceof Number n && Double.isFinite(n.doubleValue())) return n.doubleValue();
        throw new ApiException(422, "stages[" + i + "]." + key + " must be a number");
    }

    private static Double positive(Object v, int i, String key) {
        Double d = num(v, i, key);
        if (d != null && d <= 0) throw new ApiException(422, "stages[" + i + "]." + key + " must be positive");
        return d;
    }
}
