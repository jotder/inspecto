package com.gamma.geolink;

import com.gamma.la.api.InvestigationRoutes;
import com.gamma.la.api.StandingDetection;
import com.gamma.la.core.LinkEventTypes;
import com.gamma.audit.Event;
import com.gamma.event.EventLog;
import com.gamma.la.api.ValueMeasures;
import com.gamma.la.api.WorkingSetRoutes;
import com.gamma.la.core.InvestigationEvaluator;
import com.gamma.la.core.InvestigationStore;
import com.gamma.la.core.InvestigationStores;
import com.gamma.la.core.SnapshotStore;
import com.gamma.alert.AlertRule;
import com.gamma.alert.InvestigationMeasureProbe;
import com.gamma.spi.http.ApiContext;
import com.gamma.query.DatasetMeasureProbe;
import com.gamma.query.MeasureCompiler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.gamma.util.DuckDbUtil;
import com.gamma.util.SqlIdent;
import org.duckdb.DuckDBAppender;
import org.duckdb.DuckDBConnection;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.TreeMap;

/**
 * <b>Measures over the Working Set</b> (LA-23): a scalar Measure over one relation of an Investigation's derived
 * relation (LA-20's {@code entities · links · excluded}), and — as the {@link InvestigationMeasureProbe} this
 * module contributes by {@code ServiceLoader} — the evaluator behind an Alert Rule that watches one.
 *
 * <p><b>One grammar, not two.</b> A Measure here is the BI Measure shorthand — {@code count} | {@code agg(field)},
 * agg ∈ count/countDistinct/sum/avg/min/max — validated by {@link DatasetMeasureProbe#validMeasure} and split by
 * {@link MeasureCompiler#splitShorthand}, the grammar's one home — and EVALUATED there too (ANALYTICS-CORE-1 M9):
 * the relation is a list of rows derived in-JVM from the sealed log (D-E3), never a Dataset a SQL sandbox could
 * address, so {@link #evaluate} loads the measured column into a sealed in-memory DuckDB table and runs
 * {@link MeasureCompiler#compile}'s SELECT over it (SQL's own null rules: nulls ignored; an aggregate over no values
 * is NULL, i.e. empty). {@code sum}/{@code avg}/{@code min}/{@code max} take a numeric column only ({@link #NUMERIC}).
 *
 * <p><b>The alert gate.</b> A sweep has no caller, so the owner-only / PDP gate cannot be re-run when the rule is
 * evaluated. {@link #value} therefore answers only for a rule that {@code InvestigationMeasureRoutes} BOUND — it
 * applies that gate — and that is unchanged since: the binding beside the Investigation must carry this rule's
 * canonical hash and the Investigation's owner. A rule written around that route never evaluates.
 */
public final class WorkingSetMeasures implements InvestigationMeasureProbe {

    private static final Logger log = LoggerFactory.getLogger(WorkingSetMeasures.class);

    /**
     * The aggregates the Working Set answers — a SUBSET of {@link MeasureCompiler}'s grammar. The statistical
     * aggregates (median, spread, percentiles; D-AN5) pass {@link DatasetMeasureProbe#validMeasure} but stay refused
     * here: the Working Set's declared set and its Alert Rules never offered them, and widening the surface is a
     * product decision, not part of moving the evaluation into {@link MeasureCompiler}.
     */
    static final List<String> AGGS = List.of("count", "countDistinct", "sum", "avg", "min", "max");

    /** The columns {@code sum}/{@code avg}/{@code min}/{@code max} may aggregate. */
    static final Set<String> NUMERIC = Set.of("hop", "opSeq", "count", "contacts");

    /** The declared set every Investigation answers: {@code {name, relation, measure}}. */
    static final List<List<String>> DECLARED = List.of(
            List.of("entities", "entities", "count"),
            List.of("links", "links", "count"),
            List.of("events", "links", "sum(count)"),
            List.of("excluded", "excluded", "count"),
            List.of("maxHop", "entities", "max(hop)"),
            // LA-17 slice 2: distinct identities — a merged node counts once; equals `entities` with no resolve in force.
            List.of("identities", "entities", "countDistinct(identity)"));

    /**
     * One Measure over one relation's rows. Throws {@link IllegalArgumentException} for a measure outside the
     * grammar, a field the relation does not have, or a numeric aggregate over a non-numeric column.
     */
    static OptionalDouble compute(List<Map<String, Object>> rows, String relation, String measure) {
        List<String> columns = WorkingSetRoutes.COLUMNS.get(relation);
        if (columns == null) throw new IllegalArgumentException("relation must be one of "
                + WorkingSetRoutes.COLUMNS.keySet() + ", got '" + relation + "'");
        if (!DatasetMeasureProbe.validMeasure(measure))
            throw new IllegalArgumentException("measure must be count or agg(field) with agg ∈ "
                    + "count/countDistinct/sum/avg/min/max, got '" + measure + "'");
        Map<String, Object> m = MeasureCompiler.splitShorthand(List.of(measure), null).get(0);
        String agg = String.valueOf(m.get("agg"));
        if (!AGGS.contains(agg))
            throw new IllegalArgumentException("measure '" + measure + "': the Working Set evaluates only "
                    + AGGS + " — the statistical aggregates run in SQL over a Dataset");
        Object f = m.get("field");
        if (f == null) return OptionalDouble.of(rows.size());   // bare count: COUNT(*) of a list is its size, no SQL needed
        String field = String.valueOf(f);
        if (!columns.contains(field))
            throw new IllegalArgumentException("'" + field + "' is not a column of the " + relation + " relation "
                    + columns);
        if (!"count".equals(agg) && !"countDistinct".equals(agg) && !NUMERIC.contains(field))
            throw new IllegalArgumentException(agg + "(" + field + ") needs a numeric column " + NUMERIC);
        return evaluate(rows, agg, field);
    }

    /** The one-column relation {@link #evaluate} loads the rows into. */
    private static final String TABLE = "working_set";

    /**
     * Evaluates one validated Measure through {@link MeasureCompiler} (ANALYTICS-CORE-1 M9: the grammar has ONE
     * evaluator) over a sealed in-memory DuckDB table holding only the measured column of these rows — a numeric
     * column as {@code DOUBLE} (as the retired in-JVM loop summed them), any other as its text. {@code count(field)}
     * is {@code COUNT(*)} over the rows whose field is not null, i.e. SQL's {@code COUNT(field)}. Nothing here
     * reaches a file: the connection is sealed and its only relation is the rows the caller already holds.
     */
    private static OptionalDouble evaluate(List<Map<String, Object>> rows, String agg, String field) {
        boolean numeric = NUMERIC.contains(field);
        List<MeasureCompiler.Filter> filters = "count".equals(agg)
                ? List.of(new MeasureCompiler.Filter(field, "notNull", null)) : List.of();
        MeasureCompiler.Spec spec = new MeasureCompiler.Spec(TABLE, List.of(new MeasureCompiler.Measure(agg, field)),
                List.of(), Map.of(), filters, List.of(), 1);
        try (Connection conn = DuckDbUtil.openInMemory(null)) {
            try (Statement st = conn.createStatement()) {
                st.execute("CREATE TABLE " + SqlIdent.q(TABLE) + " (" + SqlIdent.q(field)
                        + (numeric ? " DOUBLE)" : " VARCHAR)"));
            }
            try (DuckDBAppender app = conn.unwrap(DuckDBConnection.class).createAppender("", TABLE)) {
                for (Map<String, Object> r : rows) {
                    app.beginRow();
                    Object v = r.get(field);
                    if (v == null) app.appendNull();
                    else if (numeric) app.append(((Number) v).doubleValue());
                    else app.append(String.valueOf(v));
                    app.endRow();
                }
            }
            MeasureCompiler.Compiled c = MeasureCompiler.compile(spec);
            try (PreparedStatement ps = conn.prepareStatement(c.sql())) {
                for (int i = 0; i < c.params().size(); i++) ps.setObject(i + 1, c.params().get(i));
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    double d = rs.getDouble(1);
                    return rs.wasNull() ? OptionalDouble.empty() : OptionalDouble.of(d);   // SQL: an aggregate over nothing is NULL
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("working-set measure " + agg + "(" + field + ") could not be evaluated: "
                    + e.getMessage(), e);
        }
    }

    /** The declared set over one evaluated relation, plus the links counted by kind. */
    static Map<String, Object> declared(WorkingSetRoutes.Relation rel) {
        List<Map<String, Object>> measures = new ArrayList<>();
        for (List<String> d : DECLARED) {
            OptionalDouble v = compute(rel.tables().get(d.get(1)), d.get(1), d.get(2));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", d.get(0));
            m.put("relation", d.get(1));
            m.put("measure", d.get(2));
            m.put("value", v.isPresent() ? v.getAsDouble() : null);
            measures.add(m);
        }
        TreeMap<String, long[]> byKind = new TreeMap<>(java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder()));
        for (Map<String, Object> l : rel.tables().get("links")) {
            long[] acc = byKind.computeIfAbsent((String) l.get("kind"), k -> new long[2]);
            acc[0]++;
            acc[1] += ((Number) l.get("count")).longValue();
        }
        List<Map<String, Object>> kinds = new ArrayList<>();
        for (var e : byKind.entrySet()) {
            Map<String, Object> k = new LinkedHashMap<>();
            k.put("kind", e.getKey());
            k.put("links", e.getValue()[0]);
            k.put("events", e.getValue()[1]);
            kinds.add(k);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("measures", measures);
        out.put("byKind", kinds);
        return out;
    }

    /**
     * LA-18: the COUNT of entities breaching the rule's value Measure over the WHOLE Dataset the Investigation is
     * bound to — read now, not from the sealed log. ⚠ A sweep has no caller: {@link #read} re-decides the owner's authority
     * ({@link StandingDetection}) before calling this, and refuses (never reads) when it cannot.
     */
    private static Reading valueMeasure(Path writeRoot, Path dataRoot, Map<String, Object> header,
                                        AlertRule rule) throws java.sql.SQLException, java.io.IOException {
        String ds = String.valueOf(header.get("dataset"));
        Map<String, Object> dataset = com.gamma.query.DatasetRead.dataset(writeRoot, ds).orElse(null);
        if (dataset == null) {
            log.warn("alert rule '{}': no dataset '{}' — not evaluated", rule.name(), ds);
            return Reading.of(OptionalDouble.empty());
        }
        String relationSql = com.gamma.query.DatasetRead.relationSql(dataset, dataRoot, writeRoot);
        // parsed NOW: a rolling `last` window resolves against the sweep's clock (UTC), never the binding's
        ValueMeasures.Fences fences = ValueMeasures.Fences.of(writeRoot);   // the Space's measures.* limits, read LIVE at this sweep
        ValueMeasures.Spec spec = ValueMeasures.parse(rule.valueMeasure(), true, fences);
        ValueMeasures.Agents agents = ValueMeasures.agents(writeRoot, spec);   // read LIVE at this sweep
        List<Map<String, Object>> breaching = ValueMeasures.forInvestigation(ds, relationSql, header, spec, agents, fences)
                .entities();
        OptionalDouble value = OptionalDouble.of(breaching.size());
        // LA-DETECT-ALERT-AGGREGATE-1 (invariant 2): aggregate breach facts only — never an entity id, name or alias
        Map<String, String> evidence = new LinkedHashMap<>();
        evidence.put("measure", spec.name());
        evidence.put("threshold", ValueMeasures.label(spec));
        evidence.put("breachCount", String.valueOf(breaching.size()));
        String worstOf = WORST_OF.get(spec.name());
        if (!breaching.isEmpty() && breaching.get(0).get(worstOf) instanceof Number n) {   // rows come worst first
            evidence.put("worstOf", worstOf);
            evidence.put("worstValue", String.valueOf(n.doubleValue()));
        }
        if (agents != null) {
            // A3 (operator 2026-09-30): the list version in force when THIS sweep evaluated, recorded on the firing
            evidence.put("agentList", spec.agentList());
            evidence.put("agentListSeq", String.valueOf(agents.atSeq()));
            evidence.put("agentListHash", String.valueOf(agents.atHash()));
        }
        return new Reading(value, evidence);
    }

    /** Each alertable Measure's headline column — the one its breaching rows are ordered worst-first by. */
    private static final Map<String, String> WORST_OF = Map.of("passThrough", "ratio", "velocity", "hours",
            "timeToCashOut", "hours", "cashOutConcentration", "share", "structuring", "legs",
            "benefitTransfer", "recipients");

    /** Best-effort audit of a sweep outcome (the LA-04 pattern): the sweep is the actor, and no entity id is ever named. */
    private static void audit(String type, String action, String principal, String rule, String investigation,
                              java.util.function.UnaryOperator<Event.Builder> attrs) {
        try {
            Event.Builder b = Event.builder(type).source("inv").message(action + " — " + rule + " on " + investigation)
                    .actor(principal).actorType("system").action(action).actionCategory("analysis")
                    .attr("rule", rule).attr("investigationId", investigation).attr("principal", principal);
            EventLog.current().emit(attrs.apply(b));
        } catch (RuntimeException ignored) {
            // best effort — the sweep's outcome stands without it
        }
    }

    /** The hash a binding records: SHA-256 of the rule's canonical JSON — any edit to the rule breaks the match. */
    static String ruleHash(AlertRule rule) {
        Map<String, Object> m = new java.util.LinkedHashMap<>(rule.toMap());
        // the Measure block's numbers are doubles when bound from JSON but Integers once the rule is re-read from its TOON file
        // (10000 vs 10000.0) - hashed as written, a restart made every bound value-Measure rule read as "changed since it was bound"
        if (m.get("valueMeasure") instanceof Map<?, ?> vm) {
            Map<String, Object> norm = new java.util.LinkedHashMap<>();
            vm.forEach((k, v) -> norm.put(String.valueOf(k), v instanceof Number n ? (Object) n.doubleValue() : v));
            m.put("valueMeasure", norm);
        }
        return InvestigationEvaluator.sha256(InvestigationEvaluator.canonical(m));
    }

    @Override
    @SuppressWarnings("unchecked")
    public Reading read(Path writeRoot, Path dataRoot, AlertRule rule) {
        String id = rule.investigation();
        try {
            if (id == null || !SnapshotStore.SAFE_ID.matcher(id).matches()
                    || !SnapshotStore.SAFE_ID.matcher(rule.name()).matches()) return Reading.of(OptionalDouble.empty());
            InvestigationStore store = InvestigationStores.of(writeRoot);
            String rawHeader = store.header(id).orElse(null);
            if (rawHeader == null) {
                log.warn("alert rule '{}': no investigation '{}' — not evaluated", rule.name(), id);
                return Reading.of(OptionalDouble.empty());
            }
            String rawBinding = store.alertRuleBinding(id, rule.name()).orElse(null);
            Map<String, Object> header = ApiContext.JSON.readValue(rawHeader, Map.class);
            Map<String, Object> binding = rawBinding == null ? null : ApiContext.JSON.readValue(rawBinding, Map.class);
            if (binding == null || !ruleHash(rule).equals(binding.get("ruleHash"))
                    || !Objects.equals(header.get("owner"), binding.get("owner"))) {
                log.warn("alert rule '{}' has no binding by the owner of investigation '{}' matching its current "
                        + "content — not evaluated (bind it through POST /inv/investigations/{}/alert-rules)",
                        rule.name(), id, id);
                return Reading.of(OptionalDouble.empty());
            }
            if (rule.isValueMeasureRule()) {
                // LD-3 (D-LD1 option A): the live Dataset read has no caller, so its authority is the owner's, re-decided NOW.
                StandingDetection.Authority authority = StandingDetection.Authority.from(binding).orElse(null);
                StandingDetection.Verdict verdict = StandingDetection.check(writeRoot, store, id, header, authority);
                String principal = authority == null ? "sweep:" + id : authority.principal();
                if (!verdict.allowed()) {
                    log.warn("alert rule '{}' standing detection REFUSED [{}]: {} — not evaluated", rule.name(),
                            verdict.code(), verdict.reason());
                    audit(LinkEventTypes.LINK_STANDING_DETECTION_REFUSED, "link.standing_detection.refused", principal,
                            rule.name(), id, b -> b.attr("code", verdict.code()));
                    return Reading.of(OptionalDouble.empty());
                }
                Reading reading = valueMeasure(writeRoot, dataRoot, header, rule);
                audit(LinkEventTypes.LINK_STANDING_DETECTION_SWEPT, "link.standing_detection.swept", principal,
                        rule.name(), id, b -> b.attr("value", reading.value().isPresent() ? reading.value().getAsDouble() : null));
                return reading;
            }
            InvestigationRoutes.Inv inv = new InvestigationRoutes.Inv(store, writeRoot, id, header);
            WorkingSetRoutes.Relation rel = WorkingSetRoutes.relation(inv, new boolean[1]);
            return Reading.of(compute(rel.tables().get(rule.relation()), rule.relation(), rule.measure()));
        } catch (Exception e) {
            log.warn("alert rule '{}' over investigation '{}' could not be evaluated: {}", rule.name(), id,
                    e.getMessage());
            return Reading.of(OptionalDouble.empty());
        }
    }
}
