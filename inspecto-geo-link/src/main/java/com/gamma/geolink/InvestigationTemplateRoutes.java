package com.gamma.geolink;

import com.gamma.control.ApiContext;
import com.gamma.control.ApiException;
import com.gamma.control.RouteModule;
import com.gamma.control.Subject;
import com.gamma.control.WriteGates;
import com.gamma.event.Event;
import com.gamma.event.EventLog;
import com.gamma.event.EventType;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;

import static com.gamma.geolink.InvestigationEvaluator.canonical;
import static com.gamma.geolink.InvestigationEvaluator.strings;

/**
 * The <b>Investigation Template</b> (LA-23, {@code docs/superpower/link-analysis-backlog-plan.md} §2.7; the Type half
 * of the D-E1 pair): an Investigation's op log saved as a reusable METHOD, and instantiated into a new Investigation
 * over a (possibly different) Dataset with the same column roles.
 *
 * <ul>
 *   <li>{@code POST /inv/investigations/{id}/template} — save the Investigation's effective log as a template.</li>
 *   <li>{@code GET /inv/investigation-templates/{id}} — read one template.</li>
 *   <li>{@code POST /inv/investigation-templates/{id}/instantiate} — bind it to seeds and a Dataset: a NEW
 *       Investigation whose every {@code expand} reads (and seals) that Dataset now.</li>
 * </ul>
 *
 * <p><b>The method, not the case (D-E8, gate G-E12).</b> Extraction keeps what is a stated rule and drops what is a
 * judgement about one graph:
 * <ul>
 *   <li>{@code seed} → a PARAMETER ({@code seed1}, {@code seed2}, … in log order): the ids are not stored.</li>
 *   <li>{@code window} (LA-13) → a PARAMETER ({@code window1}, … — {@code kind: "window"}) whose {@code default} is
 *       the authored window; instantiation takes an override window (or {@code "full"}) under that name, or the
 *       default when none is given. Seed parameters say {@code kind: "seed"}.</li>
 *   <li>{@code expand} → carried with its whole rung (direction, link kinds, window, thresholds, fan-out, budget;
 *       LA-13). A template names no entities, so an expand that named its
 *       frontier becomes an expand of the whole Working Set; it is listed under {@code generalised}, with
 *       {@code exact} saying whether the named frontier WAS the whole Working Set at that step.</li>
 *   <li>{@code exclude}, {@code hide}, {@code keep}, {@code annotate} (LA-19) → DROPPED. They name entities of this one graph with an
 *       analyst's reason, which D-E8 keeps with the Investigation that made the call. Only their COUNT is listed
 *       under {@code dropped} — never the ids or the reason text, which are case data.</li>
 *   <li>Any other op is carried verbatim. Named reference lists travel with a template (D-E8), and the op that
 *       would carry one ({@code excludeBy} over a named list) lands there — ⚠ but today no such op can be in a log:
 *       {@code excludeBy} answers "not implemented yet" at append, and no persisted named list exists (LA-17 is
 *       deferred). So nothing is carried by that clause yet; the rule is in place for when one is.</li>
 * </ul>
 *
 * <p><b>Store: {@link SnapshotStore}, not {@code ComponentStore}.</b> D-E2's one durable mechanism holds the method
 * beside the logs it came from, write-once ({@code CREATE_NEW}): a changed method is a new id, so an instantiated
 * Investigation always names exactly the method it ran. {@code ComponentStore} was rejected on three counts: its
 * documents are overwritable; a registry kind is reachable through the generic {@code /components/{type}} CRUD,
 * which would let a template carrying ad-hoc exclusion ids be written around this extraction; and registry
 * components are shared through {@code ComponentAccess}, a sharing model nobody decided for Link Analysis objects.
 *
 * <p><b>Access.</b> Owner-only, like the Investigation: the template's creator owns it, and anyone else gets a 404
 * indistinguishable from absence. Saving one opens the source Investigation through
 * {@link InvestigationRoutes#open} (owner, R3 Dataset gate, Enterprise PDP); instantiating one applies the R3
 * Dataset gate to the NEW binding through {@code InvRoutes.relationFor}. Writes are gated on
 * {@code canManageIncidents}, as every Investigation write is.
 */
public final class InvestigationTemplateRoutes implements RouteModule {

    /** Extensional ops that name entities of one graph with an analyst's judgement — they stay with the case. */
    static final Set<String> CASE_OPS = Set.of("exclude", "hide", "keep", "annotate");

    @Override
    public void register(ApiContext api) {
        // ⚠ String LITERALS on purpose — CapabilityManifestTest's scanner matches only a literal argument.
        api.post("/inv/investigations/([^/]+)/template", ApiContext.withCapability("canManageIncidents",
                (e, m) -> save(api, e, m.group(1), api.body(e))));
        api.get("/inv/investigation-templates/([^/]+)", (e, m) -> openTemplate(api, e, m.group(1)).doc());
        api.post("/inv/investigation-templates/([^/]+)/instantiate", ApiContext.withCapability("canManageIncidents",
                (e, m) -> instantiate(api, e, m.group(1), api.body(e))));
    }

    private record Template(Path writeRoot, Map<String, Object> doc) {}

    /**
     * {@code POST /inv/investigations/{id}/template} — body {@code {id?, title?}}. Gates: the Investigation's
     * {@link InvestigationRoutes#open} (503 · 422 · 403 · 404) → an unsafe template id 422 → escaping the store 403 →
     * nothing to template (no effective {@code seed}) 422 → id taken 409 → write CREATE_NEW.
     */
    @SuppressWarnings("unchecked")
    private Object save(ApiContext api, HttpExchange ex, String invId, Map<String, Object> body) throws IOException {
        InvestigationRoutes.Inv inv = InvestigationRoutes.open(api, ex, invId);
        String given = ApiContext.str(body, "id");
        String id = given != null ? given : "tpl-" + UUID.randomUUID();
        requireSafeId(id);
        jail(inv.store(), id);

        List<Map<String, Object>> log = new ArrayList<>();
        for (String line : inv.store().readLog(invId)) log.add(ApiContext.JSON.readValue(line, Map.class));
        Set<Integer> undone = InvestigationEvaluator.undone(log);
        InvestigationEvaluator.State state = new InvestigationEvaluator.State();   // the effective log, in order
        List<Map<String, Object>> ops = new ArrayList<>(), parameters = new ArrayList<>();
        List<Map<String, Object>> dropped = new ArrayList<>(), generalised = new ArrayList<>();
        for (Map<String, Object> e : log) {
            int step = ((Number) e.get("step")).intValue();
            if (!"op".equals(e.get("kind")) || undone.contains(step)) continue;
            String op = String.valueOf(e.get("op"));
            Map<String, Object> p = (Map<String, Object>) e.get("params");
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("op", op);
            t.put("step", step);
            if (op.equals("seed")) {
                String name = "seed" + (parameters.stream().filter(x -> "seed".equals(x.get("kind"))).count() + 1);
                parameters.add(ordered("name", name, "kind", "seed", "entityType", p.get("entityType"), "step", step));
                t.put("param", name);
                t.put("entityType", p.get("entityType"));
            } else if (op.equals("expand")) {
                List<String> named = strings(p.get("ids"));
                if (!named.isEmpty())
                    generalised.add(ordered("step", step, "namedFrontier", named.size(),
                            "exact", new HashSet<>(named).equals(state.entities.keySet())));
                t.putAll(p);   // the rung (§2.4) is method, not case data — it travels whole
                t.remove("ids");
            } else if (op.equals("window")) {
                // LA-13: a window is a PARAMETER with the authored window as its default — a template re-run over
                // another period says so, and one that does not re-reads the period the method was written for.
                String name = "window" + (parameters.stream().filter(x -> "window".equals(x.get("kind"))).count() + 1);
                parameters.add(ordered("name", name, "kind", "window", "default", p.get("window"), "step", step));
                t.put("windowParam", name);
            } else if (CASE_OPS.contains(op)) {
                dropped.add(ordered("step", step, "op", op, "count", strings(p.get("ids")).size()));
                t = null;
            } else {
                t.putAll(p);   // an intensional op the method states (see the class note) travels verbatim
            }
            InvestigationEvaluator.apply(state, e);
            if (t != null) ops.add(t);
        }
        if (parameters.stream().noneMatch(x -> "seed".equals(x.get("kind"))))
            throw new ApiException(422, "investigation '" + invId + "' has no effective seed step — nothing to "
                    + "parameterise, so nothing to template");

        Map<String, Object> h = inv.header();
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("id", id);
        doc.put("title", ApiContext.str(body, "title"));
        doc.put("owner", ApiContext.actor(ex));
        doc.put("createdAt", Instant.now().toString());
        doc.put("derivedFrom", ordered("investigation", invId, "steps", log.size(),
                "workingSetHash", state.hash()));
        doc.put("roles", ordered("dataset", h.get("dataset"), "sourceCol", h.get("sourceCol"),
                "targetCol", h.get("targetCol"), "linkKindCol", h.get("linkKindCol"), "timeCol", h.get("timeCol"),
                "timeColZone", h.get("timeColZone")));
        doc.put("parameters", parameters);
        doc.put("ops", ops);
        doc.put("dropped", dropped);
        doc.put("generalised", generalised);
        if (!inv.store().createTemplate(id, canonical(doc)))
            throw new ApiException(409, "investigation template '" + id + "' already exists");
        emit(ex, EventType.LINK_INVESTIGATION_TEMPLATE_SAVED, "link.investigation.template.saved",
                "link.investigation.template.saved — " + invId + " → " + id,
                b -> b.attr("templateId", id).attr("investigationId", invId).attr("parameters", parameters.size())
                        .attr("dropped", dropped.size()));
        return doc;
    }

    /**
     * {@code POST /inv/investigation-templates/{id}/instantiate} — body
     * {@code {id?, title?, params: {seed1: [ids…], …}, dataset?, sourceCol?, targetCol?, linkKindCol?}}. The Dataset
     * and column names default to the template's; the ROLES are the template's and cannot be dropped (a template
     * that bound a link-kind column binds one here too). Gates: the template (503 · 422 · 403 · 404) → a missing,
     * empty or unknown parameter 422 → then {@link InvestigationRoutes#instantiate}'s own (column 422 → Dataset 404
     * → column 422 → 403 → 409).
     */
    @SuppressWarnings("unchecked")
    private Object instantiate(ApiContext api, HttpExchange ex, String templateId, Map<String, Object> body)
            throws IOException {
        Template tpl = openTemplate(api, ex, templateId);
        Map<String, Object> doc = tpl.doc();
        Map<String, Object> given = body.get("params") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        Set<String> names = new HashSet<>();
        for (Map<String, Object> p : (List<Map<String, Object>>) doc.get("parameters")) {
            String name = String.valueOf(p.get("name"));
            names.add(name);
            if ("window".equals(p.get("kind"))) continue;   // optional: the authored window is its default
            if (!(given.get(name) instanceof List<?> l) || l.isEmpty())
                throw new ApiException(422, "parameter '" + name + "' needs a non-empty list of seed ids");
        }
        for (String k : given.keySet())
            if (!names.contains(k)) throw new ApiException(422, "unknown parameter '" + k + "' — this template takes "
                    + names);

        Map<String, Object> roles = (Map<String, Object>) doc.get("roles");
        Map<String, Object> header = new LinkedHashMap<>();
        String id = ApiContext.str(body, "id");
        header.put("id", id != null ? id : "inv-" + UUID.randomUUID());
        header.put("title", ApiContext.str(body, "title"));
        for (String key : List.of("dataset", "sourceCol", "targetCol", "linkKindCol", "timeCol", "timeColZone")) {
            String override = ApiContext.str(body, key);
            header.put(key, override != null && roles.get(key) != null ? override : roles.get(key));
        }
        header.put("template", ordered("id", templateId));

        List<Map<String, Object>> ops = new ArrayList<>();
        for (Map<String, Object> t : (List<Map<String, Object>>) doc.get("ops")) {
            Map<String, Object> op = new LinkedHashMap<>(t);
            op.remove("step");
            Object param = op.remove("param");
            if (param != null) op.put("ids", given.get(String.valueOf(param)));
            Object windowParam = op.remove("windowParam");
            if (windowParam != null) {
                Map<String, Object> declared = null;
                for (Map<String, Object> p : (List<Map<String, Object>>) doc.get("parameters"))
                    if (windowParam.equals(p.get("name"))) declared = p;
                Object w = given.containsKey(String.valueOf(windowParam)) ? given.get(String.valueOf(windowParam))
                        : declared == null ? null : declared.get("default");
                op.put("window", w == null ? "full" : w);   // validated by the same params() an append uses
            }
            op.put("derivedFrom", ordered("template", templateId, "step", t.get("step")));
            ops.add(op);
        }
        Map<String, Object> out = new InvestigationRoutes().instantiate(api, ex, tpl.writeRoot(), header, ops);
        String invId = String.valueOf(out.get("id"));
        emit(ex, EventType.LINK_INVESTIGATION_TEMPLATE_INSTANTIATED, "link.investigation.template.instantiated",
                "link.investigation.template.instantiated — " + templateId + " → " + invId,
                b -> b.attr("templateId", templateId).attr("investigationId", invId)
                        .attr("dataset", header.get("dataset")).attr("steps", out.get("steps")));
        return out;
    }

    /** Resolve one template: 503 without a write root · 422 unsafe id · 403 escaping · 404 absent or not the owner's. */
    @SuppressWarnings("unchecked")
    private static Template openTemplate(ApiContext api, HttpExchange ex, String id) throws IOException {
        Path writeRoot = WriteGates.requireWriteRoot(api, "link analysis investigation template");
        requireSafeId(id);
        SnapshotStore store = new SnapshotStore(writeRoot);
        jail(store, id);
        String raw = store.readTemplate(id);
        if (raw == null) throw new ApiException(404, "no investigation template '" + id + "'");
        Map<String, Object> doc = ApiContext.JSON.readValue(raw, Map.class);
        Optional<Subject> subject = ApiContext.subject(ex);
        if (subject.isPresent() && !subject.get().id().equals(doc.get("owner")))
            throw new ApiException(404, "no investigation template '" + id + "'");
        return new Template(writeRoot, doc);
    }

    private static void requireSafeId(String id) {
        if (id == null || !SnapshotStore.SAFE_ID.matcher(id).matches())
            throw new ApiException(422, "investigation template id must match " + SnapshotStore.SAFE_ID.pattern()
                    + ", got '" + id + "'");
    }

    private static void jail(SnapshotStore store, String id) {
        Path root = store.templateDirectory().normalize();
        Path target = root.resolve(id + ".json").normalize();
        if (!target.startsWith(root) || target.getParent() == null || !target.getParent().equals(root))
            throw new ApiException(403, "investigation template id escapes the template store");
    }

    /** A small insertion-ordered map that, unlike {@code Map.of}, tolerates null values. */
    private static Map<String, Object> ordered(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    /** Best-effort audit (LA-04 pattern), emitted only AFTER the act succeeded so the trail never over-claims. */
    private static void emit(HttpExchange ex, String type, String action, String message,
                             UnaryOperator<Event.Builder> attrs) {
        try {
            Event.Builder b = Event.builder(type).source("inv").message(message)
                    .actor(ApiContext.actor(ex)).actorType(ApiContext.actorType(ex))
                    .action(action).actionCategory("analysis");
            EventLog.current().emit(attrs.apply(b));
        } catch (RuntimeException ignored) {
            // best effort — the template (or the new Investigation) is already written
        }
    }
}
