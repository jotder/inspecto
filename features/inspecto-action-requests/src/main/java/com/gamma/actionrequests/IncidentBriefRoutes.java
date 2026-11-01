package com.gamma.actionrequests;

import com.gamma.control.LinkedSubjectProvider;
import com.gamma.control.LinkedSubjects;
import com.gamma.access.WriteGates;
import com.gamma.control.HostContext;
import com.gamma.objects.ObjectAccess;
import com.gamma.spi.auth.ApiException;
import com.gamma.spi.auth.ErrorCodes;
import com.gamma.spi.http.ApiContext;
import com.gamma.spi.http.RouteModule;
import com.gamma.workflow.ObjectType;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The Incident Brief surface ({@code AGENTIC-INVESTIGATION-1} S1) — see {@link IncidentBriefs} for the model.
 * <pre>
 *   GET  /incidents/{id}/briefs     the Incident's Briefs, newest first                         (canWorkIncidents)
 *   POST /incidents/{id}/briefs     investigate: build and save a Brief, optionally filing its  (canWorkIncidents)
 *                                   proposed next action as a pending Action Request
 * </pre>
 *
 * <p><b>Create gates, in order</b>: {@code canWorkIncidents} (the route) → write root 503 → an unknown body key, or a
 * {@code propose} that is not a JSON object 422 → no operational objects 503 → the Incident absent or not visible to
 * the caller 404 → the object not an Incident 422 → (with {@code propose}) every Action Request create gate of
 * {@link ActionRequestRoutes#propose} → saved.
 *
 * <p><b>Never acts.</b> The only write besides the Brief is a {@code pending} Action Request whose makers are the
 * starting human and the Brief's agent identity; the always-on four-eyes decide gate refuses both, so the person who
 * asked for the Brief can never approve what it proposes.
 */
public final class IncidentBriefRoutes implements RouteModule {

    @Override
    public Set<String> featureIds() {
        return Set.of("actionRequests");
    }

    private static final Set<String> CREATE_KEYS = Set.of("propose");
    private static final Set<String> PROPOSE_KEYS = Set.of("connection", "method", "payloadTemplate", "idempotencyKey",
            "reason");

    @Override
    public void register(ApiContext api) {
        api.get("/incidents/([^/]+)/briefs", ApiContext.withCapability("canWorkIncidents",
                (e, m) -> list(api, e, ApiContext.name(m))));
        api.post("/incidents/([^/]+)/briefs", ApiContext.withCapability("canWorkIncidents",
                (e, m) -> create(api, e, ApiContext.name(m), api.body(e))));
    }

    private Object list(ApiContext api, HttpExchange ex, String incidentId) throws IOException {
        requireVisibleIncident(api, ex, incidentId);
        Path root = api.writeRoot();
        LinkedSubjectProvider subjects = LinkedSubjects.of(api, "incident").orElseThrow();
        // a Brief was filtered for whoever STARTED it: filter its citations again for this reader
        List<Map<String, Object>> items = root == null ? List.of() : IncidentBriefs.list(root, incidentId).stream()
                .map(b -> IncidentBriefs.forReader(b, id -> subjects.visibleTo(api, ex, id))).toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("total", items.size());
        return out;
    }

    @SuppressWarnings("unchecked")
    private Object create(ApiContext api, HttpExchange ex, String incidentId, Map<String, Object> body) throws IOException {
        Path root = WriteGates.requireWriteRoot(api, "incident brief");
        for (String k : body.keySet())
            if (!CREATE_KEYS.contains(k)) throw invalid("unknown key '" + k + "' — an incident brief takes " + CREATE_KEYS);
        Object propose = body.get("propose");
        if (propose != null && !(propose instanceof Map<?, ?>))
            throw invalid("'propose' must be a JSON object (an Action Request: connection, method, payloadTemplate)");
        if (propose instanceof Map<?, ?> p)
            for (Object k : p.keySet())
                if (!PROPOSE_KEYS.contains(String.valueOf(k)))
                    throw invalid("unknown key 'propose." + k + "' — a proposal takes " + PROPOSE_KEYS);

        Map<String, Object> incident = requireVisibleIncident(api, ex, incidentId);
        LinkedSubjectProvider subjects = LinkedSubjects.of(api, "incident").orElseThrow();
        List<Map<String, Object>> candidates = objects(api).recentDetail(ObjectType.INCIDENT, IncidentBriefs.SIMILAR_SCAN);

        String actor = ApiContext.actor(ex);
        String id = IncidentBriefs.newId();
        Map<String, Object> brief = IncidentBriefs.build(id, incident, candidates,
                o -> subjects.visibleTo(api, ex, String.valueOf(o.get("id"))), actor);

        if (propose instanceof Map<?, ?> p) {
            Map<String, Object> spec = new LinkedHashMap<>((Map<String, Object>) p);
            spec.put("incidentId", incidentId);
            spec.put("context", Map.of("brief", Map.of("id", id, "valueAtRisk", brief.get("valueAtRisk"))));
            // the human asked, the agent authored: both are makers, so four-eyes refuses both at decide time
            Map<String, Object> ar = ActionRequestRoutes.propose(api, root, spec, actor, ApiContext.actorType(ex),
                    "incident-brief", List.of(String.valueOf(brief.get("author"))));
            Map<String, Object> proposal = new LinkedHashMap<>();
            proposal.put("actionRequestId", ar.get("id"));
            proposal.put("status", ar.get("status"));
            proposal.put("approval", "four-eyes: a person other than " + actor + " must approve the Action Request");
            brief.put("proposal", proposal);
        }
        synchronized (IncidentBriefs.lock()) {
            IncidentBriefs.save(root, brief);
        }
        ActionRequests.audit(actor, ApiContext.actorType(ex), "incident-brief.created", "incident brief " + id
                + " built for incident " + incidentId, Map.of("id", id, "incidentId", incidentId, "author", actor));
        return brief;
    }

    /** The Incident, or 404 when absent or out of the caller's scope (invisible reads as absent); 422 when not an Incident. */
    private static Map<String, Object> requireVisibleIncident(ApiContext api, HttpExchange ex, String id) {
        LinkedSubjectProvider subjects = LinkedSubjects.of(api, "incident").orElse(null);
        if (subjects == null || !subjects.available(api))
            throw new ApiException(503, ErrorCodes.CAPABILITY_UNAVAILABLE, "operational objects (inspecto-ops) are not "
                    + "installed in this bundle, so there is no Incident to brief");
        Map<String, Object> o = objects(api).detail(id).orElse(null);
        if (o == null || !subjects.visibleTo(api, ex, id))
            throw new ApiException(404, ErrorCodes.NOT_FOUND, "no incident '" + id + "'");
        if (!"incident".equals(o.get("kind")))
            throw invalid("'" + id + "' is a " + o.get("kind") + ", not an Incident — a Brief investigates an Incident");
        return o;
    }

    private static ObjectAccess objects(ApiContext api) {
        return HostContext.of(api).service().objects().orElseThrow(() -> new ApiException(503,
                ErrorCodes.CAPABILITY_UNAVAILABLE, "operational objects are not available in this bundle"));
    }

    private static ApiException invalid(String message) {
        return new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, message);
    }
}
