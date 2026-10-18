package com.gamma.analytics.cohort;

import com.gamma.util.SqlIdent;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.TreeMap;

/**
 * An ordered conversion funnel (ANALYTICS-CORE-1 C9): how many entities got through step 1, then step 2 after it, and so
 * on. A JVM function ({@link #compute}) and a DuckDB SQL builder ({@link #sql}) that share one definition, held equal by
 * {@code FunnelDuckDbParityTest}.
 *
 * <p><b>Definition.</b> An <i>event</i> is (entity, step name, instant); an event with a NULL entity, step or time, or a
 * step not among the funnel's, is ignored. Steps are distinct names, ≥ 2 and ≤ {@link #MAX_STEPS}. For each entity the
 * chain is built <b>earliest-first</b>:
 * <ul>
 *   <li>t₁ = the entity's earliest step-1 event (the <b>anchor</b>); no step-1 event → the entity is in no step;</li>
 *   <li>tᵢ = the earliest step-i event with time ≥ tᵢ₋₁ (an event at the very same instant counts, so a step may follow its
 *       predecessor with no gap) and, when {@code within} is set, time ≤ t₁ + within.</li>
 * </ul>
 * An entity <i>reached</i> step i when tᵢ exists (so it reached every step before). Steps need not be consecutive in the
 * entity's history — other events between them are ignored — and one event serves one step. The anchor is always the
 * earliest step-1: an entity whose first attempt is too old for {@code within} but whose later attempt would fit is
 * <b>not</b> rescued by it (state that to whoever reads the funnel; "any attempt" would need every step-1 as an anchor).
 *
 * <p>Per step: <b>entities</b> reached, <b>fromPrevious</b> = entities / entities of the previous step (empty when that is
 * 0; step 1 has none), <b>fromFirst</b> = entities / entities of step 1 (1 for step 1; empty when that is 0). Entities are compared as
 * text. The caller bounds the time range by filtering the relation (an as-of is a filter on the events, as C1's window).
 */
public final class Funnel {

    /** The most steps a funnel may have. */
    public static final int MAX_STEPS = 12;
    /** Longest event list the JVM form accepts (fails closed beyond it). */
    public static final int MAX_EVENTS = 5_000_000;

    private Funnel() {}

    /**
     * @param steps  the ordered step names, distinct, 2..{@link #MAX_STEPS}
     * @param within the longest time from the anchor to a later step, or {@code null} for no limit (whole seconds, &gt; 0)
     */
    public record Params(List<String> steps, Duration within) {
        public Params {
            steps = List.copyOf(steps);
            if (steps.size() < 2 || steps.size() > MAX_STEPS)
                throw new IllegalArgumentException("a funnel needs 2.." + MAX_STEPS + " steps: " + steps.size());
            if (steps.stream().distinct().count() != steps.size())
                throw new IllegalArgumentException("funnel steps must be distinct: " + steps);
            if (within != null && (within.isNegative() || within.isZero() || within.getNano() != 0))
                throw new IllegalArgumentException("within must be a positive whole number of seconds: " + within);
        }
    }

    /** One (entity, step, instant) event. */
    public record Event(String entity, String step, Instant at) {}

    /** One step of the result. */
    public record StepResult(int step, String name, long entities, OptionalDouble fromPrevious, OptionalDouble fromFirst) {}

    /** The steps' results, in funnel order. */
    public static List<StepResult> compute(List<Event> events, Params p) {
        Objects.requireNonNull(events, "events");
        if (events.size() > MAX_EVENTS)
            throw new IllegalArgumentException("funnel: " + events.size() + " events exceed the cap of " + MAX_EVENTS);
        Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < p.steps().size(); i++) index.put(p.steps().get(i), i);
        // entity -> step -> sorted event instants (a TreeMap of counts keeps them ordered and cheap)
        Map<String, List<TreeMap<Instant, Integer>>> byEntity = new HashMap<>();
        for (Event e : events) {
            if (e.entity() == null || e.step() == null || e.at() == null) continue;
            Integer i = index.get(e.step());
            if (i == null) continue;
            byEntity.computeIfAbsent(e.entity(), k -> {
                List<TreeMap<Instant, Integer>> l = new ArrayList<>();
                for (int s = 0; s < p.steps().size(); s++) l.add(new TreeMap<>());
                return l;
            }).get(i).merge(e.at(), 1, Integer::sum);
        }
        long[] reached = new long[p.steps().size()];
        for (List<TreeMap<Instant, Integer>> steps : byEntity.values()) {
            if (steps.get(0).isEmpty()) continue;
            Instant anchor = steps.get(0).firstKey(), prev = anchor;
            Instant limit = p.within() == null ? null : anchor.plus(p.within());
            reached[0]++;
            for (int s = 1; s < steps.size(); s++) {
                Map.Entry<Instant, Integer> next = steps.get(s).ceilingEntry(prev);
                if (next == null || (limit != null && next.getKey().isAfter(limit))) break;
                prev = next.getKey();
                reached[s]++;
            }
        }
        List<StepResult> out = new ArrayList<>();
        for (int s = 0; s < reached.length; s++)
            out.add(new StepResult(s + 1, p.steps().get(s), reached[s],
                    s == 0 || reached[s - 1] == 0 ? OptionalDouble.empty() : OptionalDouble.of((double) reached[s] / reached[s - 1]),
                    reached[0] == 0 ? OptionalDouble.empty() : OptionalDouble.of((double) reached[s] / reached[0])));
        return out;
    }

    // ── DuckDB SQL ───────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * DuckDB SQL for {@link #compute}: columns {@code step}, {@code name}, {@code entities}, {@code from_previous},
     * {@code from_first} (NULL where empty), ordered by step. {@code timeColumn} is a {@code TIMESTAMP} or
     * {@code TIMESTAMP WITH TIME ZONE}; the order and the window are on the instants as stored.
     */
    public static String sql(String relation, String entityColumn, String stepColumn, String timeColumn, Params p) {
        Objects.requireNonNull(relation, "relation");
        String k = SqlIdent.q(entityColumn), s = SqlIdent.q(stepColumn), t = SqlIdent.q(timeColumn);
        StringBuilder names = new StringBuilder();
        for (String n : p.steps()) names.append(names.length() == 0 ? "" : ", ").append(SqlIdent.sqlStr(n));
        StringBuilder sql = new StringBuilder("WITH __ev AS (SELECT CAST(" + k + " AS VARCHAR) AS __k, CAST(" + s
                + " AS VARCHAR) AS __s, " + t + " AS __t FROM " + SqlIdent.q(relation) + " WHERE " + k + " IS NOT NULL AND "
                + s + " IS NOT NULL AND " + t + " IS NOT NULL AND CAST(" + s + " AS VARCHAR) IN (" + names + "))");
        sql.append(", __s1 AS (SELECT __k, min(__t) AS __t, min(__t) AS __anchor FROM __ev WHERE __s = ")
                .append(SqlIdent.sqlStr(p.steps().get(0))).append(" GROUP BY __k)");
        for (int i = 1; i < p.steps().size(); i++) {
            String limit = p.within() == null ? "" : " AND e.__t <= p.__anchor + INTERVAL (" + p.within().getSeconds() + ") SECOND";
            sql.append(", __s").append(i + 1).append(" AS (SELECT p.__k, min(e.__t) AS __t, any_value(p.__anchor) AS __anchor FROM __s")
                    .append(i).append(" p JOIN __ev e ON e.__k = p.__k AND e.__s = ").append(SqlIdent.sqlStr(p.steps().get(i)))
                    .append(" AND e.__t >= p.__t").append(limit).append(" GROUP BY p.__k)");
        }
        sql.append(", __n AS (");
        for (int i = 0; i < p.steps().size(); i++)
            sql.append(i == 0 ? "" : " UNION ALL ").append("SELECT ").append(i + 1).append(" AS step, ")
                    .append(SqlIdent.sqlStr(p.steps().get(i))).append(" AS name, count(*) AS entities FROM __s").append(i + 1);
        return sql.append(") SELECT step, name, entities, CAST(entities AS DOUBLE) / NULLIF(lag(entities) OVER (ORDER BY step), 0)"
                + " AS from_previous, CAST(entities AS DOUBLE) / NULLIF(first_value(entities) OVER (ORDER BY step), 0) AS from_first"
                + " FROM __n ORDER BY step").toString();
    }
}
