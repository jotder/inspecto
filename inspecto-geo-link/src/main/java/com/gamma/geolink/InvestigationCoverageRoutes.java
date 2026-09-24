package com.gamma.geolink;

import com.gamma.control.ApiContext;
import com.gamma.control.ApiException;
import com.gamma.control.RouteModule;
import com.gamma.event.Event;
import com.gamma.event.EventLog;
import com.gamma.event.EventType;
import com.gamma.query.QueryExecutor;
import com.gamma.util.SqlIdent;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The <b>coverage indicator</b> (LA-19, plan §2.6): {@code GET /inv/investigations/{id}/coverage?from&to&timezone}
 * answers which local calendar days of a window have NO rows at all in the Investigation's bound Dataset. 🔴 A gap in
 * the data is visually identical to innocence, so a missing day is named, never implied by an empty graph.
 *
 * <ul>
 *   <li><b>The window.</b> {@code from}/{@code to} (and optional {@code timezone}) on the query string, validated by
 *       {@link InvestigationTime#window}; otherwise the window the Investigation's latest {@code window} op set, with
 *       its day mask. Both bounds are required — an open range has no list of expected days.</li>
 *   <li><b>A day.</b> A local calendar date in the window's {@code timezone} (UTC when it names none), per the
 *       {@link InvestigationTime} timezone contract. The first and last day may be partial; the half-open range is
 *       applied to the event instant. A day the day mask excludes is not expected, so it is never "missing".</li>
 *   <li><b>Covered</b> means at least one row with a non-null event time on that day, over the WHOLE Dataset — the
 *       intraday slot is deliberately NOT applied: coverage asks whether data arrived, not whether matching activity
 *       occurred, and a slot-empty day is exactly the "innocent or absent?" ambiguity this route exists to settle.</li>
 * </ul>
 *
 * <p>⏳ Per-Collector coverage (which Collectors are missing) is NOT assessed: a Dataset row carries no Collector
 * attribution this route could read, and the response says so explicitly under {@code collectors}.
 *
 * <p>Access: {@link InvestigationRoutes#open} (owner-only, R3 Dataset gate, Enterprise PDP) and the R3 gate again on
 * the Dataset read. An open read — it persists nothing — audited best-effort as {@code LINK_INVESTIGATION_COVERAGE}.
 */
public final class InvestigationCoverageRoutes implements RouteModule {

    /** Ten years of days — a bound on the expected-day list, not a property of the data. */
    static final int MAX_DAYS = 3_660;

    @Override
    public void register(ApiContext api) {
        api.get("/inv/investigations/([^/]+)/coverage", (e, m) -> coverage(api, e, m.group(1)));
    }

    @SuppressWarnings("unchecked")
    private Object coverage(ApiContext api, HttpExchange ex, String id) throws IOException {
        InvestigationRoutes.Inv inv = InvestigationRoutes.open(api, ex, id);
        Map<String, Object> h = inv.header();
        if (h.get("timeCol") == null)
            throw new ApiException(422, "this Investigation has no time column — create it with 'timeCol' to assess coverage");

        Map<String, Object> window;
        String qFrom = ApiContext.query(ex, "from"), qTo = ApiContext.query(ex, "to"), qZone = ApiContext.query(ex, "timezone");
        if (qFrom != null || qTo != null || qZone != null) {
            Map<String, Object> raw = new LinkedHashMap<>();
            if (qFrom != null) raw.put("from", qFrom);
            if (qTo != null) raw.put("to", qTo);
            if (qZone != null) raw.put("timezone", qZone);
            window = InvestigationTime.window(raw, "coverage");
        } else {
            List<Map<String, Object>> log = new ArrayList<>();
            for (String line : inv.store().readLog(id)) log.add(ApiContext.JSON.readValue(line, Map.class));
            window = InvestigationEvaluator.evaluate(log, -1, null).window;
        }
        if (window == null || window.get("from") == null || window.get("to") == null)
            throw new ApiException(422, "coverage needs a bounded window — pass 'from' and 'to', or set a window "
                    + "with both on the Investigation");

        String zone = InvestigationTime.localZone(window);
        ZoneId zid = ZoneId.of(zone);
        Instant from = Instant.parse(String.valueOf(window.get("from")));
        Instant to = Instant.parse(String.valueOf(window.get("to")));
        LocalDate first = from.atZone(zid).toLocalDate(), last = to.minusMillis(1).atZone(zid).toLocalDate();
        List<String> mask = window.get("days") instanceof List<?> d ? InvestigationEvaluator.strings(d) : null;
        List<LocalDate> expected = new ArrayList<>();
        for (LocalDate day = first; !day.isAfter(last); day = day.plusDays(1)) {
            if (expected.size() >= MAX_DAYS)
                throw new ApiException(422, "coverage is capped at " + MAX_DAYS + " days; narrow the window");
            if (mask == null || mask.contains(InvestigationTime.DAYS.get(day.getDayOfWeek().getValue() - 1)))
                expected.add(day);
        }

        String dataset = inv.dataset();
        String relationSql = InvRoutes.relationFor(api, ex, inv.writeRoot(), dataset);   // R3 gate on the read
        String col = SqlIdent.q(String.valueOf(h.get("timeCol")));
        String naive = h.get("timeColZone") == null ? null : String.valueOf(h.get("timeColZone"));
        List<String> binds = new ArrayList<>();
        binds.add(zone);
        // Binds are positional: the zone, the naive column's zone (if any), then the range — in textual order.
        String sql = "SELECT CAST(CAST(timezone(?, ts) AS DATE) AS VARCHAR) AS d, COUNT(*) AS n FROM (SELECT "
                + InvestigationTime.instantExpr(col, naive, binds) + " AS ts FROM " + SqlIdent.q(dataset) + " WHERE "
                + col + " IS NOT NULL) e WHERE epoch_ms(ts) >= CAST(? AS BIGINT) AND epoch_ms(ts) < CAST(? AS BIGINT) "
                + "GROUP BY d ORDER BY d";
        binds.add(Long.toString(from.toEpochMilli()));
        binds.add(Long.toString(to.toEpochMilli()));
        TreeMap<String, Long> rows = new TreeMap<>();
        try {
            QueryExecutor.Result r = QueryExecutor.run(new QueryExecutor.Request(
                    dataset, relationSql, sql, MAX_DAYS + 2, 0, List.of(), List.of(), binds));
            for (Map<String, Object> row : r.rows()) rows.put(String.valueOf(row.get("d")), ((Number) row.get("n")).longValue());
        } catch (SQLException e) {
            throw new ApiException(422, "coverage over dataset '" + dataset + "' failed: " + e.getMessage());
        }

        List<Map<String, Object>> perDay = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (LocalDate day : expected) {
            long n = rows.getOrDefault(day.toString(), 0L);
            if (n == 0) missing.add(day.toString());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("date", day.toString());
            m.put("rows", n);
            perDay.add(m);
        }
        emit(ex, id, dataset, expected.size(), missing.size());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("dataset", dataset);
        out.put("window", window);
        out.put("zone", zone);
        out.put("expectedDays", expected.size());
        out.put("coveredDays", expected.size() - missing.size());
        out.put("missingDays", missing);
        out.put("complete", missing.isEmpty());
        out.put("perDay", perDay);
        out.put("readAt", Instant.now().toString());
        out.put("collectors", Map.of("assessed", false, "note", "per-Collector coverage is not assessed: a Dataset "
                + "row carries no Collector attribution"));
        return out;
    }

    private static void emit(HttpExchange ex, String id, String dataset, int expected, int missing) {
        try {
            EventLog.current().emit(Event.builder(EventType.LINK_INVESTIGATION_COVERAGE).source("inv")
                    .message("link.investigation.coverage — " + id + ": " + missing + " of " + expected + " days missing")
                    .actor(ApiContext.actor(ex)).actorType(ApiContext.actorType(ex))
                    .action("link.investigation.coverage").actionCategory("analysis")
                    .attr("investigationId", id).attr("dataset", dataset)
                    .attr("expectedDays", expected).attr("missingDays", missing));
        } catch (RuntimeException ignored) {
            // best effort (LA-04) — an audit failure never fails the analyst's read
        }
    }
}
