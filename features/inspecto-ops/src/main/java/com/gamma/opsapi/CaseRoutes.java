package com.gamma.opsapi;

import com.gamma.access.WriteGates;
import com.gamma.config.io.ConfigCodec;
import com.gamma.ops.ObjectService;
import com.gamma.ops.tag.CaseRule;
import com.gamma.spi.auth.ApiException;
import com.gamma.spi.auth.ErrorCodes;
import com.gamma.spi.auth.Subject;
import com.gamma.spi.http.ApiContext;
import com.gamma.spi.http.RouteModule;
import com.gamma.control.RouteErrors;
import com.gamma.util.AtomicFiles;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Case Management routes (MODULE-REORG-P7, step 2): Case Rules ({@code /cases/rules*}), opening a Case from
 * Link Analysis Entities ({@code POST /cases/from-entities}) and Merge / Split ({@code POST
 * /objects/{id}/merge|split}). Carved verbatim out of {@link ObjectRoutes}: identical paths, capabilities and
 * HTTP statuses; the by-id routes reuse its SEC-7d data-scope guard ({@code ObjectRoutes.scoped} /
 * {@code requireVisible}). None of these paths overlaps another route, so registration order relative to
 * {@link ObjectRoutes} is immaterial; it is registered directly after it.
 */
public final class CaseRoutes implements RouteModule {

    @Override
    public java.util.Set<String> featureIds() {
        return java.util.Set.of("ops");
    }

    @Override
    public void register(ApiContext api) {
        api.post("/objects/([^/]+)/merge", ApiContext.withCapability("canAdminister", ObjectRoutes.scoped(api, (e, m) -> mergeCases(api, e, ApiContext.name(m), api.body(e)))));
        api.post("/objects/([^/]+)/split", ApiContext.withCapability("canAdminister", ObjectRoutes.scoped(api, (e, m) -> splitCase(api, e, ApiContext.name(m), api.body(e)))));

        // Rule-raised cases (C5): auto-group Incidents into a Case. CRUD is capability-gated (config);
        // evaluate mutates objects (an operational action, like transition) — and since 2026-09-15 it is
        // `canAdminister`. ⚠ It STAYS there after transition moved to `canWorkIncidents` (operator,
        // 2026-09-26): evaluate decides which Incidents a Case holds, like merge / split, not one move of
        // one object. ⚠ The route-gating audit had filed it under "read-shaped POST"; it opens a Case, so
        // that bucket was wrong for it.
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
                    ApiContext.str(body, "description"), entities, existing, o -> ObjectRoutes.visibleTo(ex, o),
                    ApiContext.str(body, "actor"), ApiContext.subject(ex).map(Subject::id).orElse(null));
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
        TagRoutes.requireModelledRule("a case rule", body, CaseRule.MODELLED);
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

    /**
     * {@code POST /objects/{id}/merge} (GLOSSARY §9 — Merge) — absorb the body's {@code sources} cases
     * into this surviving case: members re-point, tags/watchers union, sources close with a
     * {@code MERGED_INTO} trace. Body {@code {sources:[caseId…], actor?}}. Empty sources → 400;
     * unknown ids → 404; non-CASE / self-merge / already-closed-or-merged → 422.
     */
    private Object mergeCases(ApiContext api, HttpExchange ex, String survivorId, Map<String, Object> body) {
        List<String> sources = stringList(body.get("sources"));
        if (sources.isEmpty()) throw new ApiException(400, ErrorCodes.MALFORMED_REQUEST, "body must include non-empty 'sources'");
        for (String source : sources) ObjectRoutes.requireVisible(api, ex, source);
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
        for (String member : members) ObjectRoutes.requireVisible(api, ex, member);
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

}
