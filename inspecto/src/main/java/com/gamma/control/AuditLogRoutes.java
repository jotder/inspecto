package com.gamma.control;

import com.gamma.audit.AuditAttrs;
import com.gamma.audit.Event;
import com.gamma.audit.EventQuery;
import com.gamma.audit.EventType;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import com.gamma.access.WriteGates;

/**
 * The <b>audit</b> read, kept in core when the {@code /events*} feed left for the optional
 * {@code inspecto-observability} module (EDG-01 cell 6, 2026-09-08).
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
        // 4d: the route inventory the evidence report and an auditor's own probe both consume. A READ,
        // therefore open by policy (3e) - it exposes the SHAPE of the surface, never data behind it.
        api.get("/audit/route-inventory", (e, m) -> routeInventory(api));
        // ASSURE-AUDIT-CHAIN-1: tamper evidence. Reads, but gated: a verify walks the whole trail (bounded, still
        // costly) and the anchors are the evidence an auditor carries off the box, so both are an administrator's.
        api.get("/audit/verify", ApiContext.withCapability("canAdminister", (e, m) -> verify(api, e)));
        api.get("/audit/anchors", ApiContext.withCapability("canAdminister", (e, m) -> anchors(api, e)));
        api.post("/audit/anchors", ApiContext.withCapability("canAdminister", (e, m) -> anchorNow(api)));
        // The operator's acknowledged break: never a repair — a signed BREAK anchor naming the problem and a reason.
        api.post("/audit/anchors/rebaseline", ApiContext.withCapability("canAdminister",
                (e, m) -> rebaseline(api, e, api.body(e))));
    }

    /** The most chain records one {@code /audit/verify} walks; past it the result says {@code complete: false}
     *  and names the seq to resume from. */
    static final int MAX_VERIFY = 100_000;

    /** The default and the ceiling of {@code /audit/anchors?limit=}. */
    static final int ANCHORS_DEFAULT = 1000;
    static final int ANCHORS_MAX = 10_000;

    /**
     * {@code GET /audit/verify?from=&to=} — recompute the Space's audit hash chain over the seq range
     * {@code [from, to]} (both optional: the lowest retained seq, the head) and answer {@code ok}, or the FIRST bad
     * seq with its reason ({@link AuditVerifier}). A broken chain is a 200 with {@code ok: false} — the answer to
     * the question asked, not a failure of the request. Without a write root there is no key and so no anchors:
     * the chain is still verified, and {@code anchors} says {@code unavailable}.
     */
    private static Object verify(ApiContext api, HttpExchange ex) throws IOException {
        Long from = seqParam(ex, "from");
        Long to = seqParam(ex, "to");
        if (from != null && to != null && from > to)
            throw new ApiException(400, ErrorCodes.MALFORMED_REQUEST, "from (" + from + ") is after to (" + to + ")");
        Path root = api.writeRoot();
        AuditAnchors.AnchorFile anchors = root == null ? AuditAnchors.AnchorFile.NONE : AuditAnchors.readFile(root);
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>(AuditVerifier.verify(
                HostContext.of(api).service().events(), anchors, from, to, MAX_VERIFY, e -> true,
                policy(api, root, "current".equals(ApiContext.query(ex, "epoch")))).toMap());
        out.put("anchors", root == null ? "unavailable" : "checked");
        return out;
    }

    /** {@code GET /audit/anchors?limit=} — the Space's signed anchors, oldest first, each with its MAC verdict; the
     *  newest {@code limit} when there are more, with {@code total} and {@code truncated}. For export off the box. */
    private static Object anchors(ApiContext api, HttpExchange ex) throws IOException {
        Path root = WriteGates.requireWriteRoot(api, "audit anchors");
        int limit = Math.max(1, Math.min(ANCHORS_MAX,
                ApiContext.parseIntOr(ApiContext.query(ex, "limit"), ANCHORS_DEFAULT)));
        List<AuditAnchors.Anchor> all = AuditAnchors.read(root);
        List<AuditAnchors.Anchor> shown = all.subList(Math.max(0, all.size() - limit), all.size());
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("total", all.size());
        out.put("truncated", shown.size() < all.size());
        out.put("anchors", shown.stream().map(AuditAnchors.Anchor::toMap).toList());
        return out;
    }

    /** {@code POST /audit/anchors} — anchor now (after closing any finished day); 409 over a chain that does not
     *  verify. {@code created: false} when nothing was chained since the last anchor. */
    private static Object anchorNow(ApiContext api) throws IOException {
        Path root = WriteGates.requireWriteRoot(api, "audit anchors");
        AuditAnchors.OnDemand done = AuditAnchors.onDemand(HostContext.of(api).service().events(), root);
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("created", done.created());
        out.put("anchor", done.anchor() == null ? null : done.anchor().toMap());
        return out;
    }

    /** Today, whether anchors are expected (a key exists only with a write root), and the configured event retention
     *  cutoff — the shortest enabled {@code event_prune} window, or none. */
    private static AuditVerifier.Policy policy(ApiContext api, Path root, boolean currentEpochOnly) {
        java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneOffset.UTC);
        java.util.OptionalLong days = HostContext.of(api).service().jobService()
                .map(com.gamma.job.JobService::eventRetentionDays).orElse(java.util.OptionalLong.empty());
        java.time.LocalDate cutoff = days.isPresent() ? today.minusDays(days.getAsLong()) : null;
        // reported only: truncation is judged by the chained prune records, never by what is merely configured
        String source = days.isPresent() ? "event_prune retention_days=" + days.getAsLong() : "none configured";
        return new AuditVerifier.Policy(today, root != null, cutoff, currentEpochOnly, source);
    }

    /** The longest rebaseline reason accepted. */
    static final int REASON_MAX = 500;

    /**
     * {@code POST /audit/anchors/rebaseline {reason}} — the operator's acknowledged break over a chain or anchor
     * file that does not verify ({@link AuditAnchors#rebaseline}). 503 without a write root, 422 without a reason,
     * 409 when there is nothing to rebaseline, 429 more than once a minute.
     */
    private static Object rebaseline(ApiContext api, HttpExchange ex, java.util.Map<String, Object> body)
            throws IOException {
        Path root = WriteGates.requireWriteRoot(api, "audit anchor rebaseline");
        Object r = body == null ? null : body.get("reason");
        if (!(r instanceof String reason) || reason.isBlank() || reason.length() > REASON_MAX)
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED,
                    "reason is required: a non-blank string of at most " + REASON_MAX + " characters");
        String actor = ApiContext.actor(ex);
        AuditAnchors.Anchor b = AuditAnchors.rebaseline(HostContext.of(api).service().events(), root, reason.trim(),
                policy(api, root, false), actor);
        // its OWN audit event, chained like every other, carrying what the break records (beside the generic
        // POST row the dispatch seam writes)
        com.gamma.audit.EventLog.current().emit(com.gamma.audit.Event.builder(EventType.AUDIT)
                .source("audit").message(actor + " audit.rebaseline at seq " + b.firstSeq() + ": " + reason.trim())
                .actor(actor).actorType(ApiContext.actorType(ex))
                .action("audit.rebaseline").actionCategory("configuration")
                .target("audit-anchors", String.valueOf(b.firstSeq()))
                .attr("reason", reason.trim()).attr("problem", b.extra().get("problem"))
                .attr("lastGoodSeq", b.extra().get("lastGoodSeq")).attr("breakMac", b.mac()));
        return b.toMap();
    }

    /** A positive seq query parameter, or {@code null} when absent; anything else is a 400. */
    private static Long seqParam(HttpExchange ex, String name) {
        String v = ApiContext.query(ex, name);
        if (v == null || v.isBlank()) return null;
        try {
            long n = Long.parseLong(v.trim());
            if (n >= 1) return n;
        } catch (NumberFormatException ignore) {
            // fall through
        }
        throw new ApiException(400, ErrorCodes.MALFORMED_REQUEST, name + " must be a chain seq (an integer from 1), got '" + v + "'");
    }

    /** {@code GET /audit/search?type=AUDIT|ACCESS_DENIED&limit=&offset=&pipeline=&correlationId=&q=&from=&to=} */
    /**
     * {@code GET /audit/route-inventory} — every registered route with the posture it declared
     * (route-gating plan step 4d): a capability, or an exemption with its category and reason.
     *
     * <p>⚠ This is the RUNTIME table, from the router itself, so it reports what this server actually
     * deployed — including optional modules a source scan cannot see. The digest is the one the boot event
     * records, so an auditor can tie a running server to the event that announced its shape.
     */
    private static Object routeInventory(ApiContext api) {
        if (!(api instanceof ControlApi control))
            throw new ApiException(501, ErrorCodes.NOT_SUPPORTED, "route inventory is not available on this host");
        List<java.util.Map<String, Object>> rows = new java.util.ArrayList<>();
        for (ControlApi.RouteRow r : control.routeInventory()) {
            java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("method", r.method());
            row.put("pattern", r.pattern());
            row.put("posture", r.posture());
            if (r.capability() != null) row.put("capability", r.capability());
            if (r.exemptionCategory() != null) {
                row.put("exemptionCategory", r.exemptionCategory());
                row.put("exemptionReason", r.exemptionReason());
            }
            rows.add(row);
        }
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("digest", control.routeInventoryDigest());
        out.put("counts", java.util.Map.of(
                "total", rows.size(),
                "gated", rows.stream().filter(r -> "gated".equals(r.get("posture"))).count(),
                "exempt", rows.stream().filter(r -> "exempt".equals(r.get("posture"))).count(),
                "openRead", rows.stream().filter(r -> "open-read".equals(r.get("posture"))).count()));
        out.put("routes", rows);
        return out;
    }

    private static Object search(ApiContext api, HttpExchange ex) {
        // D-P8 (operator 2026-10-06): classified values masked on read for a caller without the unmask capability
        return HostContext.of(api).service().events().query(auditQuery(ex, EventQuery.DEFAULT_LIMIT))
                .stream().map(AuditReadMasking.forRequest(api, ex)).map(Event::toMap).toList();
    }

    /**
     * {@code GET /audit/export?format=csv&type=AUDIT} — the audit-shaped CSV (AUDIT-CSV-1 / compliance G10):
     * the base seven columns plus one per {@link AuditAttrs} key. Kept in core with the search for the same
     * reason — an auditor's evidence export is the compliance obligation, not an edition feature. ⚠ Both
     * auditable types get the audit columns, because both are what the plain projection used to drop.
     */
    private static Object export(ApiContext api, HttpExchange ex) throws IOException {
        List<Event> rows = HostContext.of(api).service().events().query(auditQuery(ex, EventQuery.MAX_LIMIT))
                .stream().map(AuditReadMasking.forRequest(api, ex)).toList();   // D-P8
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
            throw new ApiException(400, ErrorCodes.MALFORMED_REQUEST, "type is required and must be one of " + sorted()
                    + " - this route serves only the audit projection (the full events feed is the "
                    + "optional inspecto-observability module)");
        String canonical = AUDITABLE.stream().filter(t -> t.equalsIgnoreCase(type)).findFirst()
                .orElseThrow(() -> new ApiException(400, ErrorCodes.MALFORMED_REQUEST, "type '" + type + "' is not auditable - must be one of "
                        + sorted() + " (the full events feed is the optional inspecto-observability module)"));
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
