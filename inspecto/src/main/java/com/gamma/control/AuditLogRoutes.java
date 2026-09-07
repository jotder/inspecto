package com.gamma.control;

import com.gamma.event.AuditAttrs;
import com.gamma.event.Event;
import com.gamma.event.EventQuery;
import com.gamma.event.EventType;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.util.List;
import java.util.Set;

/**
 * The <b>audit</b> read, kept in core when the {@code /events*} feed left for the optional
 * {@code inspecto-events} module (EDG-01 cell 6, 2026-09-08).
 *
 * <p>🔴 <b>Why this route exists at all.</b> Cell 6 gates CP-13's feed out of Personal — but
 * {@code EDITIONS.md} §Audit promises Personal "local append-only logs", and the Audit-log screen read that
 * trail through {@code GET /events/search?type=AUDIT}. Gating the feed alone would therefore have taken away
 * Personal's ability to READ ITS OWN AUDIT TRAIL while a matrix row still promised it — a compliance
 * regression dressed as a packaging change. Operator decision (2026-09-08): gate the browsing feed, keep the
 * audit projection in core. So CP-13 is precisely "feed gated, audit read retained", and the matrix says so.
 *
 * <p>⛔ <b>This is NOT a back door onto the feed.</b> {@link #AUDITABLE} is a closed set and the type
 * parameter is validated against it <b>fail-closed</b>: a missing type is refused, and any other type — a
 * {@code BATCH_COMMITTED}, an {@code ERROR}, or a blank meaning "everything" — is a 400, never a silent
 * widening. Were it permissive, {@code /audit/search} would serve exactly what {@code /events/search} does
 * and cell 6 would gate nothing. The falsification test asserts the refusal, not just the happy path.
 *
 * <p>⚠ Deliberately narrower than the feed in three further ways: no by-id lookup, no saved views, and no
 * cursor pagination (offset paging is what the Audit screen uses). Anything beyond reading the audit
 * projection belongs to the module.
 */
final class AuditLogRoutes implements RouteModule {

    /**
     * The only event types this route will serve. Both are compliance records rather than operational
     * telemetry: {@code AUDIT} is the actor-attributed mutation trail, {@code ACCESS_DENIED} the refusals.
     */
    static final Set<String> AUDITABLE = Set.of(EventType.AUDIT, EventType.ACCESS_DENIED);

    @Override
    public void register(ApiContext api) {
        api.get("/audit/search", (e, m) -> search(api, e));
        api.get("/audit/export", (e, m) -> export(api, e));
    }

    /** {@code GET /audit/search?type=AUDIT|ACCESS_DENIED&limit=&offset=&pipeline=&correlationId=&q=&from=&to=} */
    private static Object search(ApiContext api, HttpExchange ex) {
        return api.service().events().query(auditQuery(ex, EventQuery.DEFAULT_LIMIT))
                .stream().map(Event::toMap).toList();
    }

    /**
     * {@code GET /audit/export?format=csv&type=AUDIT} — the audit-shaped CSV (AUDIT-CSV-1 / compliance G10):
     * the base seven columns plus one per {@link AuditAttrs} key. Kept in core with the search for the same
     * reason — an auditor's evidence export is the compliance obligation, not an edition feature. ⚠ Both
     * auditable types get the audit columns, because both are what the plain projection used to drop.
     */
    private static Object export(ApiContext api, HttpExchange ex) throws IOException {
        List<Event> rows = api.service().events().query(auditQuery(ex, EventQuery.MAX_LIMIT));
        if (!"csv".equalsIgnoreCase(ApiContext.query(ex, "format"))) {
            return rows.stream().map(Event::toMap).toList();
        }
        StringBuilder sb = new StringBuilder("timestamp,level,type,source,pipeline,correlationId,message");
        for (String col : AuditAttrs.ALL) sb.append(',').append(csv(col));
        sb.append('\n');
        for (Event e : rows) {
            sb.append(csv(e.timestamp())).append(',').append(csv(e.level().name())).append(',')
              .append(csv(e.type())).append(',').append(csv(e.source())).append(',')
              .append(csv(e.pipeline())).append(',').append(csv(e.correlationId())).append(',')
              .append(csv(e.message()));
            for (String col : AuditAttrs.ALL) sb.append(',').append(csv(e.attributes().get(col)));
            sb.append('\n');
        }
        return ApiContext.respondText(ex, sb.toString(), "text/csv; charset=utf-8");
    }

    /**
     * Build the query, refusing any type outside {@link #AUDITABLE}.
     *
     * <p>⚠ The type is <b>required</b>. A blank type on the feed means "every type", which here would serve
     * the whole event stream from a route whose entire justification is that it serves only the audit
     * projection — so absent is refused exactly like wrong.
     */
    private static EventQuery auditQuery(HttpExchange ex, int defaultLimit) {
        String type = ApiContext.query(ex, "type");
        if (type == null || type.isBlank())
            throw new ApiException(400, "type is required and must be one of " + sorted()
                    + " - this route serves only the audit projection (the full events feed is the "
                    + "optional inspecto-events module)");
        String canonical = AUDITABLE.stream().filter(t -> t.equalsIgnoreCase(type)).findFirst()
                .orElseThrow(() -> new ApiException(400, "type '" + type + "' is not auditable - must be one of "
                        + sorted() + " (the full events feed is the optional inspecto-events module)"));
        return EventQuery.builder()
                .type(canonical)
                .pipeline(ApiContext.query(ex, "pipeline"))
                .correlationId(ApiContext.query(ex, "correlationId"))
                .textContains(ApiContext.query(ex, "q"))
                .from(TimeBounds.epochMillis(ApiContext.query(ex, "from")))
                .to(TimeBounds.epochMillis(ApiContext.query(ex, "to")))
                .limit(ApiContext.parseIntOr(ApiContext.query(ex, "limit"), defaultLimit))
                .offset(ApiContext.parseIntOr(ApiContext.query(ex, "offset"), 0))
                .build();
    }

    private static List<String> sorted() {
        return AUDITABLE.stream().sorted().toList();
    }

    /** Minimal RFC-4180 CSV field escape. */
    private static String csv(String v) {
        if (v == null) return "";
        if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r"))
            return '"' + v.replace("\"", "\"\"") + '"';
        return v;
    }
}
