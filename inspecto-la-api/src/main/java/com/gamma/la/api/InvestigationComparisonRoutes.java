package com.gamma.la.api;

import com.gamma.control.ApiContext;
import com.gamma.control.ApiException;
import com.gamma.control.ErrorCodes;
import com.gamma.control.RouteModule;
import com.gamma.event.Event;
import com.gamma.event.EventLog;
import com.gamma.la.core.InvestigationEvaluator;
import com.gamma.la.core.InvestigationStore;
import com.gamma.la.core.LinkEventTypes;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <b>Comparison mode</b> (LA-INVESTIGATION-OPS-DEFERRED-1): {@code GET /inv/investigations/{id}/compare?aFrom&aTo&bFrom&bTo&timezone&at}
 * - the entities and links of the Working Set present in window A and not B, B and not A, and both, with counts
 * ({@link WindowComparison} holds the semantics).
 *
 * <p><b>A read-only route, not an op - and why.</b> The comparable features: an op is part of the sealed, replayable log, its
 * effect on the Working Set is a pure function of the log, and it is hashed. A comparison changes nothing in the Working Set
 * (so it is not a Working Set state; it would be a marker like {@code snapshot}), and what it reads - per-window event
 * counts - is NOT in the Working Set: a sealed row is a folded count with no timestamps left (see {@code window}), so a
 * time-sliced diff can only come from the live Dataset. That is the same live read {@code coverage} makes, which is a read
 * route for the same reason. An op would have to seal the result into the entry (as {@code expand} seals rows) and thread
 * through append, undo, fork, Draft rebase, template and Dossier replay - a wider change for evidence a reader can re-run by
 * asking again. The Dossier carries it as an explicitly UNSEALED section instead ({@link DossierRoutes}).
 *
 * <p>Access: {@link InvestigationRoutes#openForRead} (owner or linked-Case member, R3, Enterprise PDP) and the R3 gate again on
 * the Dataset read. Masked exactly as the Working Set is ({@link EntityMasking}, applied at render over raw ids; every id
 * in the answer is one the Working Set already holds, so the diff names nothing the analyst could not already see).
 * Persists nothing; audited best-effort as {@code LINK_INVESTIGATION_COMPARED}.
 */
public final class InvestigationComparisonRoutes implements RouteModule {

    @Override
    public void register(ApiContext api) {
        api.get("/inv/investigations/([^/]+)/compare", (e, m) -> compare(api, e, m.group(1)));
    }

    @SuppressWarnings("unchecked")
    private Object compare(ApiContext api, HttpExchange ex, String id) throws IOException {
        InvestigationRoutes.Inv inv = InvestigationRoutes.openForRead(api, ex, id);
        Map<String, Map<String, Object>> windows = WindowComparison.windows(ex);
        if (windows == null)
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "pass window A and window B: aFrom/aTo and bFrom/bTo (ISO-8601 with an offset), "
                    + "optionally 'timezone'");
        List<String> lines = inv.store().log(InvestigationStore.Scope.main(id));
        int at = DossierRoutes.parseAt(ex, lines);
        List<Map<String, Object>> log = new ArrayList<>();
        for (String line : lines.subList(0, at)) log.add(ApiContext.JSON.readValue(line, Map.class));
        InvestigationEvaluator.State state = InvestigationEvaluator.evaluate(log, -1, null);

        String mode = ApiContext.query(ex, "mode");
        if (mode != null && !"presence".equals(mode) && !"activity".equals(mode))
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'mode' is \"presence\" (default) or \"activity\" (event-count delta), got " + mode);
        Long minAbs = WindowComparison.minAbsDelta(ApiContext.query(ex, "minAbsDelta"));
        if (minAbs != null && !"activity".equals(mode))
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'minAbsDelta' applies to mode=activity only");
        Map<String, Object> raw = WindowComparison.compare(api, ex, inv, state, windows, "activity".equals(mode), minAbs);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("at", at);
        out.putAll(raw);
        out.put("readAt", java.time.Instant.now().toString());
        EntityMasking mask = EntityMasking.of(inv, List.of());
        Map<String, Object> masked = (Map<String, Object>) mask.apply(out);   // at render, never at storage (D-U6)
        masked.put("masking", mask.describe());
        emit(ex, id, inv.dataset(), raw);
        return masked;
    }

    @SuppressWarnings("unchecked")
    static void emit(HttpExchange ex, String id, String dataset, Map<String, Object> raw) {
        try {
            Map<String, Object> links = (Map<String, Object>) raw.get("links");
            EventLog.current().emit(Event.builder(LinkEventTypes.LINK_INVESTIGATION_COMPARED).source("inv")
                    .message("link.investigation.compared - " + id)
                    .actor(ApiContext.actor(ex)).actorType(ApiContext.actorType(ex))
                    .action("link.investigation.compared").actionCategory("analysis")
                    .attr("investigationId", id).attr("dataset", dataset)
                    .attr("linksOnlyA", ((Map<String, Object>) links.get("onlyA")).get("count"))
                    .attr("linksOnlyB", ((Map<String, Object>) links.get("onlyB")).get("count")));
        } catch (RuntimeException ignored) {
            // best effort (LA-04) - an audit failure never fails the analyst's read
        }
    }
}
