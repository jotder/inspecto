package com.gamma.opsapi;

import com.gamma.control.RouteErrors;
import com.gamma.control.RowScope;
import com.gamma.control.Subject;

import com.gamma.control.ApiContext;
import com.gamma.control.ApiException;
import com.gamma.control.Cursor;
import com.gamma.control.ErrorCodes;
import com.gamma.control.Handler;
import com.gamma.control.RouteModule;
import com.gamma.control.WriteGates;

import com.gamma.config.io.ConfigCodec;
import com.gamma.ops.ObjectQuery;
import com.gamma.ops.ObjectService;
import com.gamma.objects.ObjectType;
import com.gamma.ops.OperationalObject;
import com.gamma.objects.FindingsSpec;
import com.gamma.ops.link.ObjectLink;
import com.gamma.ops.note.NoteKind;
import com.gamma.ops.note.ObjectNote;
import com.gamma.objects.RcaTemplate;
import com.gamma.ops.tag.CaseRule;
import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;
import com.gamma.util.AtomicFiles;
import com.gamma.util.JsonAttributes;
import com.sun.net.httpserver.HttpExchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Alert Center / operational-object routes ({@code /objects*}, {@code /rca/templates}; v4.3.0
 * Phase 2-4): create, lifecycle transitions, correlation links + graph, comments, attachments and
 * RCA seeding. Extracted verbatim from {@link ControlApi}: identical routes, order and HTTP statuses.
 */
public final class ObjectRoutes implements RouteModule {

    private static final Logger log = LoggerFactory.getLogger(ObjectRoutes.class);

    @Override
    public void register(ApiContext api) {
        api.get("/objects", (e, m) -> objectsList(api, e));
        // Registered before the /objects/{id} catch-all so "analytics" is not read as an id (C4).
        api.get("/objects/analytics", (e, m) -> OpsEngine.of(api).analytics(parseObjectType(ApiContext.query(e, "type"))));
        api.post("/objects", ApiContext.withCapability("canManageIncidents",
                (e, m) -> createObject(api, e, api.body(e))));
        // Every by-id route runs behind the SEC-7d data-scope guard: an object whose caseType is outside
        // the caller's dataScopes answers 404, indistinguishable from absence (existence-hiding).
        //
        // ROUTE-UNGATED-DEFAULT-1, step 2b (operator decision 2026-09-15): triage is daily operator work,
        // but changing an Incident's DISPOSITION is administrative. So the state-changing routes — ack /
        // resolve / transition / assign / merge / split, the PATCH that edits priority, severity and
        // assignee, and the Case-Rule evaluate that groups Incidents into a Case — are gated on
        // `canAdminister`; comment / attach / link / RCA-seed stay open as collaboration and are recorded
        // as such in CapabilityManifest.EXEMPTIONS. ⚠ The capability gate wraps the scope guard, so a
        // caller lacking the capability gets 403 before existence-hiding gets to answer 404.
        // ✅ `POST /objects` (create) is gated by `canManageIncidents` (operator, 2026-09-16) — the same
        // answer as `POST /recon/promote`, deliberately: both perform the one act of OPENING an Incident,
        // and a second precedent for one concept is what the call was made to avoid. ⚠ Distinct from the
        // `canAdminister` gates on ack/resolve below: opening is triage, resolving is administration.
        api.post("/objects/([^/]+)/ack", ApiContext.withCapability("canAdminister", scoped(api, (e, m) -> transition(api, ApiContext.name(m), "ack", null, api.body(e)))));
        api.post("/objects/([^/]+)/resolve", ApiContext.withCapability("canAdminister", scoped(api, (e, m) -> transition(api, ApiContext.name(m), "resolve", null, api.body(e)))));
        api.post("/objects/([^/]+)/transition", ApiContext.withCapability("canAdminister", scoped(api, (e, m) -> transitionFromBody(api, ApiContext.name(m), api.body(e)))));
        api.post("/objects/([^/]+)/assign", ApiContext.withCapability("canAdminister", scoped(api, (e, m) -> assign(api, ApiContext.name(m), api.body(e)))));
        api.post("/objects/([^/]+)/links", scoped(api, (e, m) -> createLink(api, e, ApiContext.name(m), api.body(e))));
        api.get("/objects/([^/]+)/links", scoped(api, (e, m) -> toLinkMaps(OpsEngine.of(api).linksOf(ApiContext.name(m)))));
        api.delete("/objects/([^/]+)/links", scoped(api, (e, m) -> deleteLink(api, ApiContext.name(m), e)));
        api.post("/objects/([^/]+)/merge", ApiContext.withCapability("canAdminister", scoped(api, (e, m) -> mergeCases(api, e, ApiContext.name(m), api.body(e)))));
        api.post("/objects/([^/]+)/split", ApiContext.withCapability("canAdminister", scoped(api, (e, m) -> splitCase(api, e, ApiContext.name(m), api.body(e)))));
        api.get("/objects/([^/]+)/graph", scoped(api, (e, m) -> objectGraph(api, ApiContext.name(m), e)));
        api.post("/objects/([^/]+)/comments", scoped(api, (e, m) -> addComment(api, ApiContext.name(m), api.body(e))));
        api.get("/objects/([^/]+)/comments", scoped(api, (e, m) -> toNoteMaps(OpsEngine.of(api).notesOf(ApiContext.name(m), NoteKind.COMMENT))));
        api.post("/objects/([^/]+)/attachments", scoped(api, (e, m) -> addAttachment(api, ApiContext.name(m), api.body(e))));
        api.get("/objects/([^/]+)/attachments", scoped(api, (e, m) -> toNoteMaps(OpsEngine.of(api).notesOf(ApiContext.name(m), NoteKind.ATTACHMENT))));
        api.post("/objects/([^/]+)/rca", scoped(api, (e, m) -> applyRca(api, ApiContext.name(m), api.body(e))));
        api.patch("/objects/([^/]+)", ApiContext.withCapability("canAdminister", scoped(api, (e, m) -> patchObject(api, ApiContext.name(m), api.body(e)))));
        api.get("/objects/([^/]+)", scoped(api, (e, m) -> objectById(api, ApiContext.name(m))));
        api.get("/rca/templates", (e, m) -> rcaTemplateList(api));
        // The effective (possibly *_workflow.toon-overridden) lifecycle for a type — lets the UI derive
        // folders + action verbs instead of hardcoding state lists (case-management-design.md C6).
        api.get("/workflows/([^/]+)", (e, m) -> workflowOf(api, ApiContext.name(m)));
        // The effective Findings sections for a type (C3/D6) — authored as a findings-spec component, else
        // the built-in shape. A read-only resolver, deliberately not a second config idiom: authoring stays
        // on the generic /components CRUD (docs/superpower/findings-spec-plan.md §3.3).
        api.get("/findings/([^/]+)", (e, m) -> findingsSpecOf(api, ApiContext.name(m)));
        // Rule-raised cases (C5): auto-group Incidents into a Case. CRUD is capability-gated (config);
        // evaluate mutates objects (an operational action, like transition) — and since 2026-09-15 a
        // transition is `canAdminister`, so evaluate takes the same gate. ⚠ The route-gating audit had
        // filed it under "read-shaped POST"; it opens a Case, so that bucket was wrong for it.
        api.get("/cases/rules", (e, m) -> OpsEngine.of(api).caseRules().stream().map(CaseRule::toMap).toList());
        api.post("/cases/rules", ApiContext.withCapability("canAuthorWorkbench", (e, m) -> saveCaseRule(api, api.body(e))));
        api.delete("/cases/rules/([^/]+)", ApiContext.withCapability("canAuthorWorkbench", (e, m) -> deleteCaseRule(api, ApiContext.name(m))));
        api.post("/cases/rules/([^/]+)/evaluate", ApiContext.withCapability("canAdminister", (e, m) -> evaluateCaseRule(api, ApiContext.name(m))));
        // LA-CASE-CREATE-IN-PLACE-1 (operator, 2026-09-23 — "mint from the node"): open a Case whose first
        // members are minted from Link Analysis Entities. Its own route because POST /objects cannot compose
        // it: that refuses a body with no existing link target, and in a fresh space there is none to name.
        // Gated like POST /objects — it OPENS Incidents and a Case, the same act, the same capability.
        api.post("/cases/from-entities", ApiContext.withCapability("canManageIncidents",
                (e, m) -> openCaseFromEntities(api, e, api.body(e))));
    }

    /** At most this many Entities per Case opened from Link Analysis — a Case, not a bulk import. */
    static final int MAX_CASE_ENTITIES = 100;

    /**
     * {@code POST /cases/from-entities} — body {@code {title, description?, actor?, entities:[{id, dataset,
     * label?} | {objectId}]}}. An {@code {id, dataset}} entry is a Link Analysis Entity (node id
     * {@code entity:[<type>:]<key>} plus the Dataset it was projected from) and is minted as an INCIDENT, or
     * REUSED when that identity already exists and is visible to the caller; an {@code {objectId}} entry names
     * an Incident the graph already references. Answers {@code {case, members:[{…object, minted}]}}.
     * Missing title / no entities → 400; a malformed entity or more than {@link #MAX_CASE_ENTITIES} → 422; an
     * {@code objectId} absent or out of scope → 404; one that is not an INCIDENT → 422. All of it is checked
     * before the first write, and a failed write rolls back every object this call created
     * ({@link ObjectService#openCaseFromEntities}).
     */
    private Object openCaseFromEntities(ApiContext api, HttpExchange ex, Map<String, Object> body) {
        String title = ApiContext.str(body, "title");
        if (title == null) throw new ApiException(400, ErrorCodes.MALFORMED_REQUEST, "body must include 'title'");
        if (!(body.get("entities") instanceof List<?> raw) || raw.isEmpty())
            throw new ApiException(400, ErrorCodes.MALFORMED_REQUEST, "body must include at least one entry in 'entities'");
        if (raw.size() > MAX_CASE_ENTITIES)
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "at most " + MAX_CASE_ENTITIES + " entities per Case, got " + raw.size());
        List<ObjectService.EntityMember> entities = new java.util.ArrayList<>();
        List<String> existing = new java.util.ArrayList<>();
        try {
            for (Object o : raw) {
                if (!(o instanceof Map<?, ?> m)) throw new IllegalArgumentException("each entity must be an object");
                Object objectId = m.get("objectId");
                if (objectId != null && !objectId.toString().isBlank()) existing.add(objectId.toString().trim());
                else entities.add(new ObjectService.EntityMember(text(m.get("id")), text(m.get("dataset")),
                        text(m.get("label"))));
            }
            ObjectService.EntityCase made = OpsEngine.of(api).openCaseFromEntities(title,
                    ApiContext.str(body, "description"), entities, existing, o -> visibleTo(ex, o),
                    ApiContext.str(body, "actor"));
            List<Map<String, Object>> members = made.members().stream().map(o -> {
                Map<String, Object> row = new LinkedHashMap<>(o.toMap());
                row.put("minted", made.minted().contains(o.id()));
                return row;
            }).toList();
            return Map.of("case", made.caseObject().toMap(), "members", members);
        } catch (IllegalArgumentException refused) {
            // Only the refusals. A store that FAILS (IllegalStateException) stays a 500 — reporting it as a
            // bad body would send the analyst to fix input that was never wrong.
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, refused.getMessage());
        } catch (java.util.NoSuchElementException notFound) {
            throw new ApiException(404, ErrorCodes.NOT_FOUND, notFound.getMessage());
        }
    }

    private static String text(Object v) {
        return v == null ? null : v.toString().trim();
    }

    // ── rule-raised cases (C5) ────────────────────────────────────────────────────────

    /**
     * {@code POST /cases/rules} — save (create or replace) a Case Rule; body {@code {name, title,
     * filter:{…}, threshold?, windowMinutes?, category?, tags?}}. At least one filter criterion is
     * required (422); persisted as {@code <name>_caserule.toon} under the write root.
     */
    private Object saveCaseRule(ApiContext api, Map<String, Object> body) throws IOException {
        WriteGates.requireWriteRoot(api, "case rule write");
        CaseRule rule;
        try {
            rule = CaseRule.fromMap(body);
        } catch (IllegalArgumentException bad) {
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, bad.getMessage());
        }
        Path file = caseRuleFile(api, rule.name());
        byte[] bytes = ConfigCodec.toToon(Map.of("case_rule", rule.toMap())).getBytes(StandardCharsets.UTF_8);
        AtomicFiles.write(file, bytes, ".caserule-");
        return OpsEngine.of(api).registerCaseRule(rule).toMap();
    }

    /** {@code DELETE /cases/rules/{name}} — remove a rule (registry + persisted file); 404 if unknown. */
    private Object deleteCaseRule(ApiContext api, String name) throws IOException {
        WriteGates.requireWriteRoot(api, "case rule write");
        if (OpsEngine.of(api).caseRule(name).isEmpty())
            throw new ApiException(404, ErrorCodes.NOT_FOUND, "no case rule named '" + name + "'");
        boolean fileRemoved = Files.deleteIfExists(caseRuleFile(api, name));
        OpsEngine.of(api).removeCaseRule(name);
        return Map.of("deleted", name, "fileRemoved", fileRemoved);
    }

    /**
     * {@code POST /cases/rules/{name}/evaluate} — auto-group matching Incidents into a Case (C5).
     * Returns {@code {matched, grouped, caseId, opened}}; unknown rule → 404.
     */
    private Object evaluateCaseRule(ApiContext api, String name) {
        try {
            ObjectService.CaseRuleEvaluation r = OpsEngine.of(api).evaluateCaseRule(name);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("matched", r.matched());
            out.put("grouped", r.grouped());
            out.put("caseId", r.caseId());
            out.put("opened", r.opened());
            return out;
        } catch (NoSuchElementException notFound) {
            throw new ApiException(404, ErrorCodes.NOT_FOUND, notFound.getMessage());
        }
    }

    /** The jailed {@code <name>_caserule.toon} path under the write root; 422 on an unsafe name, 403 on escape. */
    private static Path caseRuleFile(ApiContext api, String name) {
        String safe = WriteGates.safeName(name, "case rule name");
        Path root = api.writeRoot();
        return WriteGates.jail(root, root.resolve(safe + "_caserule.toon"), "resolved path");
    }

    /** {@code GET /workflows/{type}} — the effective workflow definition; unknown type → 400. */
    private Object workflowOf(ApiContext api, String type) {
        return OpsEngine.of(api).workflow(parseObjectType(type)).toMap();
    }

    /**
     * {@code GET /findings/{type}} — the <b>effective</b> Findings sections for an object type (C3 / BACKLOG
     * D6): the authored {@code findings-spec} component when one exists, else
     * {@link FindingsSpec#defaultFor}. Unknown type → 400.
     *
     * <p>The overlay is resolved <em>here</em> rather than in the client so the built-in default has exactly
     * one definition. A spec that is malformed on disk falls back to the default with a warning — a broken
     * config file must not take the triage panel down (it was already rejected at authoring time by
     * {@link FindingsSpec#fromMap}, so reaching this branch means the file was hand-edited).
     */
    private Object findingsSpecOf(ApiContext api, String type) {
        return effectiveFindingsSpec(api, parseObjectType(type)).toMap();
    }

    /** The effective spec for {@code objectType} — see {@link #findingsSpecOf}, which serves it. */
    private static FindingsSpec effectiveFindingsSpec(ApiContext api, ObjectType objectType) {
        String id = objectType.name().toLowerCase(java.util.Locale.ROOT);
        Path root = api.writeRoot();
        if (root != null) {
            try {
                Map<String, Object> content = new ComponentStore(root.resolve("registry"))
                        .get("findings-spec", id).map(ComponentRegistry.Component::content).orElse(null);
                if (content != null) return FindingsSpec.fromMap(content);
            } catch (RuntimeException bad) {   // a malformed spec (IllegalArgumentException) or an unreadable file
                log.warn("findings-spec '{}' is unreadable, serving the built-in default: {}", id, bad.getMessage());
            }
        }
        return FindingsSpec.defaultFor(objectType);
    }

    // ── SEC-7d data-scoped grants ("a fraud analyst sees fraud cases") ───────────────

    /** Attribute key carrying an object's case type — the dimension {@link Subject#dataScopes()} filters on. */
    /** ⚠ Public since EDG-01 cell 7: the moved scoped-objects tests assert on this key. */
    public static final String ATTR_CASE_TYPE = "caseType";

    /**
     * Whether the caller may see {@code o}: an unscoped caller (Personal — no Subject; or a role with
     * {@code dataScopes = null}) sees everything; a scoped caller sees untyped objects plus those whose
     * {@code caseType} is in their scopes. Fail-closed: an empty scope set reveals only untyped objects.
     * ABAC A3: the edition's policy engine ({@link RowScope}) can additionally hide a row — a policy
     * deny over the object's attributes is indistinguishable from absence, same SEC-7d contract.
     */
    private static boolean visibleTo(HttpExchange ex, OperationalObject o) {
        Subject s = ApiContext.subject(ex).orElse(null);
        if (s != null && s.scoped()) {
            String caseType = o.attributes().get(ATTR_CASE_TYPE);
            if (caseType != null && !caseType.isBlank() && !s.dataScopes().contains(caseType)) return false;
        }
        return RowScope.visible(ex, o.objectType().name().toLowerCase(java.util.Locale.ROOT), resourceAttributes(o));
    }

    /** The row's attribute map for policy conditions ({@code resource.*}): kind + id + owner + the
     *  object's own string attributes (incl. {@code caseType}); the engine binds {@code resource.space}
     *  to the request's bound space when a row doesn't carry one. */
    private static Map<String, Object> resourceAttributes(OperationalObject o) {
        Map<String, Object> r = new LinkedHashMap<>(o.attributes());
        r.put("kind", o.objectType().name().toLowerCase(java.util.Locale.ROOT));
        r.put("id", o.id());
        if (o.owner() != null && !o.owner().isBlank()) r.put("owner", o.owner());
        return r;
    }

    private static List<OperationalObject> visibleOnly(List<OperationalObject> objs, HttpExchange ex) {
        return objs.stream().filter(o -> visibleTo(ex, o)).toList();
    }

    /**
     * The same SEC-7d gate {@link #scoped} applies to the URL {@code {id}}, for an object id that arrives in
     * the BODY or the QUERY instead — a link target, a merge source, a split member.
     *
     * <p>⛔ {@link #scoped} only ever sees the path id. Without this, a scoped caller could name an object
     * they cannot even read as the *other* end of a write: merging absorbs and <b>closes</b> the source case,
     * so the gate on the survivor alone left a state-mutation authorization bypass on a resource hidden from
     * the caller. Absent and out-of-scope answer the identical 404 (existence-hiding), so the check also
     * subsumes the plain existence checks these routes already made.
     */
    private static void requireVisible(ApiContext api, HttpExchange ex, String id) {
        OperationalObject o = OpsEngine.of(api).get(id).orElse(null);
        if (o == null || !visibleTo(ex, o)) throw new ApiException(404, ErrorCodes.NOT_FOUND, "no object with id '" + id + "'");
    }

    /** Wrap a by-id handler: out-of-scope answers the same 404 an absent id does (existence-hiding). */
    private Handler scoped(ApiContext api, Handler h) {
        return (e, m) -> {
            String id = ApiContext.name(m);
            OperationalObject o = OpsEngine.of(api).get(id).orElse(null);
            if (o != null && !visibleTo(e, o))
                throw new ApiException(404, ErrorCodes.NOT_FOUND, "no object with id '" + id + "'");
            return h.handle(e, m);   // absent ids keep their existing 404/behaviour
        };
    }

    /**
     * D10: the same SEC-7d/ABAC visibility gate {@link #scoped} applies, exposed so the kind-addressed
     * note routes ({@link NoteRoutes}) cannot become a way to read an object's notes around it.
     * Returns the object's correlation id ({@code ""} when it has none), or {@code null} when the id is
     * absent — out-of-scope throws the same 404 an absent id does (existence-hiding).
     */
    static String visibleObjectCorrelationId(ApiContext api, HttpExchange ex, String id) {
        OperationalObject o = OpsEngine.of(api).get(id).orElse(null);
        if (o == null) return null;
        if (!visibleTo(ex, o)) throw new ApiException(404, ErrorCodes.NOT_FOUND, "no object with id '" + id + "'");
        return o.correlationId() == null ? "" : o.correlationId();
    }

    private static List<Map<String, Object>> toObjectMaps(List<OperationalObject> objs) {
        return objs.stream().map(OperationalObject::toMap).toList();
    }

    /** The keyset order for {@code /objects} pagination: {@code createdAt DESC, id DESC} (a total order, so a
     *  cursor resumes unambiguously even when several objects share a {@code createdAt}). */
    private static final java.util.Comparator<OperationalObject> KEYSET_ORDER =
            java.util.Comparator.comparingLong(OperationalObject::createdAt)
                    .thenComparing(OperationalObject::id).reversed();

    /**
     * {@code GET /objects} — the object list. Legacy (unversioned) callers keep the exact
     * {@code ?limit=&offset=} offset slice, byte-for-byte unchanged. On the {@code /api/v1} surface the
     * list is instead cursor-paginated ({@link #objectsPage}), so the two share this one route.
     */
    private Object objectsList(ApiContext api, HttpExchange e) {
        if (!ApiContext.v1(e))
            return toObjectMaps(visibleOnly(OpsEngine.of(api).query(objectQuery(e)), e));
        return objectsPage(api, e);
    }

    /**
     * {@code GET /api/v1/objects?...&cursor=} — one cursor-paginated page, newest first (keyset
     * {@code (createdAt, id)}). The opaque {@code cursor} resumes strictly after the previous page's last
     * row, so pages don't drift as new objects open. Because SEC-7d visibility is a post-query filter
     * (see {@link #visibleOnly}), the keyset runs over the <em>visible</em> set — objects are low-volume,
     * and this keeps {@code total} scoped so a page never leaks the count of objects outside the caller's
     * grants. The v1 envelope's {@code metadata.pagination} carries {@code cursor/nextCursor/limit/total}.
     */
    private Object objectsPage(ApiContext api, HttpExchange e) {
        ObjectQuery q = objectQuery(e);
        int limit = q.limit();
        String cursor = ApiContext.query(e, "cursor");
        List<String> key = Cursor.decode(cursor);
        Long afterCreatedAt = key.size() == 2 ? parseLongOr(key.get(0), Long.MIN_VALUE) : null;
        String afterId = key.size() == 2 ? key.get(1) : null;

        List<OperationalObject> visible = visibleOnly(OpsEngine.of(api).query(q.unbounded()), e)
                .stream().sorted(KEYSET_ORDER).toList();
        long total = visible.size();
        List<OperationalObject> after = visible.stream()
                .filter(o -> afterCreatedAt == null || o.createdAt() < afterCreatedAt
                        || (o.createdAt() == afterCreatedAt && o.id().compareTo(afterId) < 0))
                .toList();
        boolean hasMore = after.size() > limit;
        List<OperationalObject> page = hasMore ? after.subList(0, limit) : after;
        String nextCursor = null;
        if (hasMore && !page.isEmpty()) {
            OperationalObject last = page.get(page.size() - 1);
            nextCursor = Cursor.encode(List.of(String.valueOf(last.createdAt()), last.id()));
        }
        ApiContext.pagination(e, cursor, nextCursor, limit, total);
        return toObjectMaps(page);
    }

    /** Build an {@link ObjectQuery} from {@code ?type=&status=&severity=&assignee=&owner=&correlationId=&q=&limit=&offset=}. */
    private static ObjectQuery objectQuery(HttpExchange ex) {
        return ObjectQuery.builder()
                .objectType(parseObjectType(ApiContext.query(ex, "type")))
                .status(ApiContext.query(ex, "status"))
                .severity(ApiContext.query(ex, "severity"))
                .assignee(ApiContext.query(ex, "assignee"))
                .owner(ApiContext.query(ex, "owner"))
                .correlationId(ApiContext.query(ex, "correlationId"))
                .textContains(ApiContext.query(ex, "q"))
                .limit(ApiContext.parseIntOr(ApiContext.query(ex, "limit"), ObjectQuery.DEFAULT_LIMIT))
                .offset(ApiContext.parseIntOr(ApiContext.query(ex, "offset"), 0))
                .build();
    }

    /** Parse a {@code ?type=} filter; an unknown value is a 400 rather than a silent match-everything. */
    private static ObjectType parseObjectType(String s) {
        try {
            return ObjectType.of(s);
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, ErrorCodes.MALFORMED_REQUEST, e.getMessage());
        }
    }

    /** {@code GET /objects/{id}} — the object, or 404. */
    private Object objectById(ApiContext api, String id) {
        return OpsEngine.of(api).get(id).map(OperationalObject::toMap)
                .orElseThrow(() -> new ApiException(404, ErrorCodes.NOT_FOUND, "no object with id '" + id + "'"));
    }

    /**
     * {@code POST /objects} (Phase 3) — create a managed object. The complement of alert auto-promotion:
     * ALERTs are opened by the {@code AlertService}, whereas INCIDENTs are operator-created here. Body
     * {@code {type?,title,description?,severity?,priority?,owner?,assignee?,correlationId?,attributes?,
     * dueAt?|dueInMinutes?,links:[{to,relationship?}|id…]}} — {@code type} defaults to {@code INCIDENT},
     * {@code title} is required, {@code dueAt} (epoch millis) or {@code dueInMinutes} sets the SLA deadline,
     * and {@code links} must name at least one existing object to correlate with (product decision
     * 2026-07-22: a case/incident with nothing linked isn't useful — the auto-creation paths that open
     * objects directly via {@code ObjectService.open} are unaffected). The object opens in its workflow's
     * initial state; lifecycle moves go through {@code /objects/{id}/transition}.
     */
    private Object createObject(ApiContext api, HttpExchange ex, Map<String, Object> body) {
        String title = ApiContext.str(body, "title");
        if (title == null) throw new ApiException(400, ErrorCodes.MALFORMED_REQUEST, "body must include 'title'");
        ObjectType type;
        try {
            type = ObjectType.of(ApiContext.str(body, "type"));
        } catch (IllegalArgumentException badType) {
            throw new ApiException(400, ErrorCodes.MALFORMED_REQUEST, badType.getMessage());
        }
        if (type == null) type = ObjectType.INCIDENT;   // the create path exists for operator-created incidents

        // ≥1 linked entity is mandatory. Validate every target exists BEFORE opening, so a dangling link
        // can't leave an orphan object behind (open() then link() is not atomic).
        List<LinkSpec> links = parseLinks(body.get("links"));
        if (links.isEmpty()) throw new ApiException(400, ErrorCodes.MALFORMED_REQUEST, "body must include at least one entry in 'links'");
        for (LinkSpec l : links) requireVisible(api, ex, l.to());

        Map<String, String> attrs = new LinkedHashMap<>();
        if (body.get("attributes") instanceof Map<?, ?> bag)
            bag.forEach((k, v) -> { if (k != null && v != null) attrs.put(k.toString(), v.toString()); });
        Long dueAt = parseDueAt(body);
        if (dueAt != null) attrs.put(ObjectService.ATTR_DUE_AT, Long.toString(dueAt));

        OperationalObject created = OpsEngine.of(api).open(type, title, ApiContext.str(body, "description"),
                ApiContext.str(body, "severity"), ApiContext.str(body, "priority"), ApiContext.str(body, "owner"),
                ApiContext.str(body, "assignee"), ApiContext.str(body, "correlationId"), attrs);
        String actor = ApiContext.str(body, "actor");
        for (LinkSpec l : links)
            OpsEngine.of(api).link(created.id(), l.to(), l.relationship(), actor);
        return created.toMap();
    }

    /** One entry of the create body's mandatory {@code links} array. */
    private record LinkSpec(String to, String relationship) {}

    /**
     * Parse the create body's {@code links} into {@code {to,relationship?}} specs: each element is either a
     * {@code {to,relationship?}} object (mirroring the {@code /objects/{id}/links} body) or a bare id string
     * (the relationship then defaults to {@code RELATED_TO} in {@code link()}). Blank/absent {@code to}
     * entries are skipped, mirroring {@link #stringList}, so an all-blank array reads as empty → 400.
     */
    private static List<LinkSpec> parseLinks(Object v) {
        List<LinkSpec> out = new java.util.ArrayList<>();
        if (v instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    Object to = m.get("to");
                    if (to == null || to.toString().isBlank()) continue;
                    Object rel = m.get("relationship");
                    out.add(new LinkSpec(to.toString().trim(), rel == null ? null : rel.toString()));
                } else if (o != null && !o.toString().isBlank()) {
                    out.add(new LinkSpec(o.toString().trim(), null));
                }
            }
        }
        return out;
    }

    /** SLA deadline from the create body: absolute {@code dueAt} (epoch millis) or relative {@code dueInMinutes}. */
    private static Long parseDueAt(Map<String, Object> body) {
        Object due = body.get("dueAt");
        if (due != null) {
            long ms = parseLongOr(due.toString(), -1L);
            if (ms > 0) return ms;
        }
        Object mins = body.get("dueInMinutes");
        if (mins != null) {
            long m = parseLongOr(mins.toString(), -1L);
            if (m >= 0) return System.currentTimeMillis() + m * 60_000L;
        }
        return null;
    }

    private static long parseLongOr(String s, long def) {
        if (s == null || s.isBlank()) return def;
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /**
     * {@code POST /objects/{id}/links} (Phase 4) — correlate this object with another: body
     * {@code {to, relationship?, actor?}} (e.g. a CASE {@code CONTAINS} an INCIDENT). A missing {@code to}
     * → 400; an unknown {@code id} or {@code to} → 404. Idempotent (a duplicate edge returns the existing one).
     */
    private Object createLink(ApiContext api, HttpExchange ex, String fromId, Map<String, Object> body) {
        String to = ApiContext.str(body, "to");
        if (to == null) throw new ApiException(400, ErrorCodes.MALFORMED_REQUEST, "body must include 'to'");
        requireVisible(api, ex, to);
        try {
            return OpsEngine.of(api).link(fromId, to, ApiContext.str(body, "relationship"), ApiContext.str(body, "actor")).toMap();
        } catch (java.util.NoSuchElementException notFound) {
            throw new ApiException(404, ErrorCodes.NOT_FOUND, notFound.getMessage());
        }
    }

    private static List<Map<String, Object>> toLinkMaps(List<ObjectLink> links) {
        return links.stream().map(ObjectLink::toMap).toList();
    }

    /**
     * {@code DELETE /objects/{id}/links?to=&relationship=} (case group management) — remove one edge
     * (e.g. taking a member incident out of a Case's Contents). Missing {@code to} → 400; unknown
     * object or edge → 404. The removal is audited on the Event Log.
     */
    private Object deleteLink(ApiContext api, String fromId, HttpExchange ex) {
        String to = ApiContext.query(ex, "to");
        String relationship = ApiContext.query(ex, "relationship");
        if (to == null || to.isBlank()) throw new ApiException(400, ErrorCodes.MALFORMED_REQUEST, "query must include 'to'");
        requireVisible(api, ex, to);
        try {
            if (!OpsEngine.of(api).unlink(fromId, to, relationship, ApiContext.query(ex, "actor")))
                throw new ApiException(404, ErrorCodes.NOT_FOUND, "no such link " + fromId + " -> " + to);
        } catch (java.util.NoSuchElementException notFound) {
            throw new ApiException(404, ErrorCodes.NOT_FOUND, notFound.getMessage());
        }
        return Map.of("from", fromId, "to", to, "deleted", true);
    }

    /**
     * {@code POST /objects/{id}/merge} (GLOSSARY §9 — Merge) — absorb the body's {@code sources} cases
     * into this surviving case: members re-point, tags/watchers union, sources close with a
     * {@code MERGED_INTO} trace. Body {@code {sources:[caseId…], actor?}}. Empty sources → 400;
     * unknown ids → 404; non-CASE / self-merge / already-closed-or-merged → 422.
     */
    private Object mergeCases(ApiContext api, HttpExchange ex, String survivorId, Map<String, Object> body) {
        List<String> sources = stringList(body.get("sources"));
        if (sources.isEmpty()) throw new ApiException(400, ErrorCodes.MALFORMED_REQUEST, "body must include non-empty 'sources'");
        for (String source : sources) requireVisible(api, ex, source);
        return RouteErrors.mapCaseErrors(() -> {
            var result = OpsEngine.of(api).mergeCases(survivorId, sources, ApiContext.str(body, "actor"));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("survivor", result.survivor().toMap());
            out.put("merged", result.merged());
            out.put("membersMoved", result.membersMoved());
            return out;
        });
    }

    /**
     * {@code POST /objects/{id}/split} (GLOSSARY §9 — Split) — carve the listed member incidents out of
     * this case into a new case managed individually. Body {@code {title, members:[incidentId…],
     * assignee?|queue?, actor?}}; repeat the call for multi-way splits. Blank title / empty members →
     * 400; unknown case → 404; non-CASE / closed case / a member not contained → 422.
     */
    private Object splitCase(ApiContext api, HttpExchange ex, String caseId, Map<String, Object> body) {
        String title = ApiContext.str(body, "title");
        List<String> members = stringList(body.get("members"));
        if (title == null || title.isBlank()) throw new ApiException(400, ErrorCodes.MALFORMED_REQUEST, "body must include 'title'");
        if (members.isEmpty()) throw new ApiException(400, ErrorCodes.MALFORMED_REQUEST, "body must include non-empty 'members'");
        for (String member : members) requireVisible(api, ex, member);
        return RouteErrors.mapCaseErrors(() -> {
            var result = OpsEngine.of(api).splitCase(caseId, title, members,
                    ApiContext.str(body, "assignee"), ApiContext.str(body, "actor"));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("case", result.part().toMap());
            out.put("membersMoved", result.membersMoved());
            return out;
        });
    }

    /** The body value as a trimmed, non-empty string list (a JSON array of ids). */
    private static List<String> stringList(Object v) {
        List<String> out = new java.util.ArrayList<>();
        if (v instanceof List<?> list) {
            for (Object o : list) {
                if (o == null) continue;
                String s = o.toString().trim();
                if (!s.isEmpty()) out.add(s);
            }
        }
        return out;
    }

    /**
     * {@code GET /objects/{id}/graph?depth=} (Phase 4) — correlation subgraph (default depth 2, capped at 5).
     * SEC-7d: out-of-scope neighbours are pruned from the result (nodes dropped, edges touching them dropped),
     * so a scoped analyst's graph never names case data outside their grants.
     */
    @SuppressWarnings("unchecked")
    private Object objectGraph(ApiContext api, String id, HttpExchange ex) {
        int depth = Math.min(5, Math.max(1, ApiContext.parseIntOr(ApiContext.query(ex, "depth"), 2)));
        Map<String, Object> g;
        try {
            g = OpsEngine.of(api).graph(id, depth);
        } catch (java.util.NoSuchElementException notFound) {
            throw new ApiException(404, ErrorCodes.NOT_FOUND, notFound.getMessage());
        }
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) g.get("nodes");
        java.util.Set<String> visible = new java.util.HashSet<>();
        List<Map<String, Object>> keptNodes = nodes.stream().filter(n -> {
            String nid = String.valueOf(n.get("id"));
            boolean ok = OpsEngine.of(api).get(nid).map(o -> visibleTo(ex, o)).orElse(false);
            if (ok) visible.add(nid);
            return ok;
        }).toList();
        List<Map<String, Object>> keptEdges = ((List<Map<String, Object>>) g.get("edges")).stream()
                .filter(l -> visible.contains(String.valueOf(l.get("from")))
                        && visible.contains(String.valueOf(l.get("to")))).toList();
        g.put("nodes", keptNodes);
        g.put("edges", keptEdges);
        return g;
    }

    /** {@code POST /objects/{id}/comments} (Phase 4) — add a comment; body {@code {body, author?}}. */
    private Object addComment(ApiContext api, String id, Map<String, Object> body) {
        String text = ApiContext.str(body, "body");
        if (text == null) throw new ApiException(400, ErrorCodes.MALFORMED_REQUEST, "body must include 'body'");
        try {
            return OpsEngine.of(api).comment(id, ApiContext.str(body, "author"), text).toMap();
        } catch (java.util.NoSuchElementException notFound) {
            throw new ApiException(404, ErrorCodes.NOT_FOUND, notFound.getMessage());
        }
    }

    /**
     * {@code POST /objects/{id}/attachments} (Phase 4) — attach an evidence reference (metadata only);
     * body {@code {name, uri, contentType?, author?, caption?}}.
     */
    private Object addAttachment(ApiContext api, String id, Map<String, Object> body) {
        String name = ApiContext.str(body, "name");
        String uri = ApiContext.str(body, "uri");
        if (name == null || uri == null) throw new ApiException(400, ErrorCodes.MALFORMED_REQUEST, "body must include 'name' and 'uri'");
        try {
            return OpsEngine.of(api).attach(id, ApiContext.str(body, "author"), name, ApiContext.str(body, "contentType"),
                    uri, ApiContext.str(body, "caption")).toMap();
        } catch (java.util.NoSuchElementException notFound) {
            throw new ApiException(404, ErrorCodes.NOT_FOUND, notFound.getMessage());
        }
    }

    private static List<Map<String, Object>> toNoteMaps(List<ObjectNote> notes) {
        return notes.stream().map(ObjectNote::toMap).toList();
    }

    /**
     * {@code POST /objects/{id}/rca} (Phase 4) — seed an RCA skeleton (one comment per section). Body is
     * the template: {@code {template:{name,sections[]}}} or an inline {@code {name?,sections[],actor?}}.
     */
    private Object applyRca(ApiContext api, String id, Map<String, Object> body) {
        RcaTemplate template;
        Object t = body.get("template");
        if (t instanceof String named) {       // a *_rca.toon template referenced by name
            template = api.service().rcaTemplate(named).orElseThrow(
                    () -> new ApiException(404, ErrorCodes.NOT_FOUND, "no RCA template named '" + named + "'"));
        } else {                                // an inline template ({template:{…}} or the body itself)
            Map<String, Object> tmpl = new LinkedHashMap<>();
            if (t instanceof Map<?, ?> tm) tm.forEach((k, v) -> tmpl.put(String.valueOf(k), v));
            else tmpl.putAll(body);
            tmpl.putIfAbsent("name", "ad-hoc"); // an inline template needn't name itself
            try {
                template = RcaTemplate.fromMap(tmpl);
            } catch (IllegalArgumentException ex) {
                throw new ApiException(400, ErrorCodes.MALFORMED_REQUEST, ex.getMessage());
            }
        }
        try {
            return toNoteMaps(OpsEngine.of(api).applyRca(id, template, ApiContext.str(body, "actor")));
        } catch (java.util.NoSuchElementException notFound) {
            throw new ApiException(404, ErrorCodes.NOT_FOUND, notFound.getMessage());
        }
    }

    /** {@code GET /rca/templates} (Phase 4) — the RCA templates loaded from {@code *_rca.toon}, by name. */
    private Object rcaTemplateList(ApiContext api) {
        return api.service().rcaTemplates().values().stream().map(RcaTemplate::toMap).toList();
    }

    /**
     * {@code POST /objects/{id}/assign} — assign to a person: body {@code {assignee, actor?}}.
     * A missing assignee → 400; an unknown object → 404.
     */
    private Object assign(ApiContext api, String id, Map<String, Object> body) {
        String assignee = ApiContext.str(body, "assignee");
        if (assignee == null) throw new ApiException(400, ErrorCodes.MALFORMED_REQUEST, "body must include 'assignee'");
        return RouteErrors.mapCaseErrors(
                () -> OpsEngine.of(api).assign(id, assignee, ApiContext.str(body, "actor")).toMap());
    }

    /**
     * {@code PATCH /objects/{id}} — partial update of the operator-mutable fields; body any of
     * {@code {priority?, severity?, assignee?, attributes?}} (attributes merge over the stored bag,
     * updates win). The mail view's Prioritize / tagging / postmortem saves ride this. At least one
     * field → else 400; unknown id → 404. No workflow involvement — status changes stay on
     * {@code /objects/{id}/transition}.
     */
    private Object patchObject(ApiContext api, String id, Map<String, Object> body) {
        Map<String, String> attrs = null;
        if (body.get("attributes") instanceof Map<?, ?> bag) {
            Map<String, String> collected = new LinkedHashMap<>();
            bag.forEach((k, v) -> { if (k != null && v != null) collected.put(k.toString(), v.toString()); });
            attrs = collected;
        }
        String priority = ApiContext.str(body, "priority");
        String severity = ApiContext.str(body, "severity");
        String assignee = ApiContext.str(body, "assignee");
        if (priority == null && severity == null && assignee == null && attrs == null)
            throw new ApiException(400, ErrorCodes.MALFORMED_REQUEST, "body must include at least one of 'priority', 'severity', 'assignee', 'attributes'");
        if (attrs != null) validateFindings(api, id, attrs);
        try {
            return OpsEngine.of(api).patch(id, priority, severity, assignee, attrs).toMap();
        } catch (java.util.NoSuchElementException notFound) {
            throw new ApiException(404, ErrorCodes.NOT_FOUND, notFound.getMessage());
        }
    }

    /**
     * Judge a submitted Findings blob against the object's effective Findings spec (BACKLOG D6 residual)
     * → 422. The spec configures the <em>form</em>, so before this a direct {@code PATCH} could store a
     * disposition no ladder offers or skip a {@code required} field the UI enforces.
     *
     * <p>Only {@code attributes.findings} is judged — the JSON blob the Findings panel writes, which D3 =
     * (a) (operator 2026-09-25) made the canonical home of Findings values. A patch that carries no blob
     * does not submit the form, so nothing is judged; top-level keys (the panel's flat
     * {@code impactAmount}/{@code recordsAffected} copies, {@code tags}, …) are ordinary attributes.
     *
     * <p>Resolved here rather than in {@code ObjectService} because the spec lives in the space's
     * {@code ComponentStore}, which is an edge concern — the engine stays store-agnostic. An unknown id is
     * left to the patch itself to 404.
     */
    private static void validateFindings(ApiContext api, String id, Map<String, String> attrs) {
        if (!attrs.containsKey(FINDINGS_ATTR)) return;
        OperationalObject o = OpsEngine.of(api).get(id).orElse(null);
        if (o == null) return;
        try {
            effectiveFindingsSpec(api, o.objectType()).validateFindings(
                    findingsBlob(attrs.get(FINDINGS_ATTR)), findingsBlob(o.attributes().get(FINDINGS_ATTR)));
        } catch (IllegalArgumentException bad) {
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, bad.getMessage());
        }
    }

    /** The attribute holding the Findings panel's values as one JSON object (D3). */
    private static final String FINDINGS_ATTR = "findings";

    /** A stored/submitted Findings blob as flat strings; absent or unreadable → empty (then judged as blank). */
    private static Map<String, String> findingsBlob(String json) {
        Map<String, String> out = new LinkedHashMap<>();
        JsonAttributes.fromPayloadJson(json).forEach((k, v) -> {
            if (v != null) out.put(k, v.toString());
        });
        return out;
    }

    /** {@code POST /objects/{id}/ack|resolve} — a fixed-action transition; {@code actor} from the body. */
    private Object transition(ApiContext api, String id, String action, String target, Map<String, Object> body) {
        return doTransition(api, id, action, target, ApiContext.str(body, "actor"));
    }

    /** {@code POST /objects/{id}/transition} — body {@code {action}} or {@code {status|to}} (+ optional {@code actor}). */
    private Object transitionFromBody(ApiContext api, String id, Map<String, Object> body) {
        String action = ApiContext.str(body, "action");
        String target = ApiContext.str(body, "status");
        if (target == null) target = ApiContext.str(body, "to");
        if (action == null && target == null)
            throw new ApiException(400, ErrorCodes.MALFORMED_REQUEST, "body must include 'action' or 'status'");
        return doTransition(api, id, action, target, ApiContext.str(body, "actor"));
    }

    /** Apply a lifecycle transition, mapping the service's exceptions to 404 (unknown id) / 422 (illegal move). */
    private Object doTransition(ApiContext api, String id, String action, String target, String actor) {
        try {
            OperationalObject updated = (action != null)
                    ? OpsEngine.of(api).transition(id, action, actor)
                    : OpsEngine.of(api).transitionTo(id, target, actor);
            return updated.toMap();
        } catch (java.util.NoSuchElementException notFound) {
            throw new ApiException(404, ErrorCodes.NOT_FOUND, notFound.getMessage());
        } catch (IllegalStateException | IllegalArgumentException illegal) {
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, illegal.getMessage());
        }
    }
}
