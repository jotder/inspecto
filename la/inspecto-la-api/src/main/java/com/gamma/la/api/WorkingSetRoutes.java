package com.gamma.la.api;

import com.gamma.la.core.InvestigationStore;
import com.gamma.la.core.LinkEventTypes;
import com.gamma.la.core.InvestigationEvaluator;
import com.gamma.la.core.LinkIds;
import com.gamma.spi.http.ApiContext;
import com.gamma.spi.auth.ApiException;
import com.gamma.spi.auth.ErrorCodes;
import com.gamma.spi.http.RouteModule;
import com.gamma.audit.Event;
import com.gamma.audit.EventSink;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The <b>Working Set as a derived relation</b> (LA-20, {@code docs/archived-documents/plans-archive/link-analysis-backlog-plan.md} §2.7):
 * {@code GET /inv/investigations/{id}/working-set?of=entities|links|excluded&limit&offset} answers an
 * Investigation's Working Set as rows with fixed columns, carrying the provenance columns §2.7 names
 * ({@code opSeq}, {@code seedId}, {@code hop}, {@code reason}). It is evaluated from the SEALED log alone
 * ({@link InvestigationEvaluator}, D-E3), so it needs no Dataset read.
 *
 * <p><b>Cache (a functional requirement — six tiles = six re-runs per view).</b> The relation is a pure function of
 * the log, so it is cached under a key that IS the log: the Investigation's directory plus the SHA-256 of the log's
 * committed bytes (every line up to the last newline — the head step and every seal fingerprint are inside it).
 * ⇒ Invalidation needs no hook in the write path: an op or an undo appends a line, which changes the key; a fork is
 * a different directory, so a different relation; a log edited on disk hashes differently too. The file is read on
 * every request — what the cache saves is the JSON parse of every sealed row, the fold, and the row building.
 * ⛔ A cheaper stat-only key (size / mtime) was rejected: an append-only file's size is monotone only while nobody
 * rewrites it, and the replay equivalence check exists precisely because someone might.
 *
 * <p><b>Who may evaluate it (D-E7, decided 2026-09-23).</b> The gate runs on EVERY request, BEFORE the cache is
 * consulted, so a cached relation can never be served to a caller the gate would refuse:
 * <ol>
 *   <li>{@link InvestigationRoutes#open} — owner-only (anyone else: 404, the same answer as absence) and the R3
 *       Dataset gate. On Professional and below, where no {@code AccessDecider} is on the classpath, that is the
 *       whole rule: OWNER-ONLY, fail closed. ⛔ There is deliberately no fallback to {@code ComponentAccess}
 *       Dataset sharing — being able to view the Dataset does NOT make someone else's Investigation readable.</li>
 *   <li>{@link RowScope#visible} with {@code resourceKind = "investigation"} — the Enterprise PDP
 *       ({@code inspecto-policy}'s {@code PolicyEngine}) judges the resolved Investigation; a {@code DENY} is a 404,
 *       even for the owner — applied inside {@link InvestigationRoutes#open}, so EVERY Investigation route obeys
 *       it, not only this one. {@code ALLOW}/{@code ABSTAIN} fall through to rule 1 — the {@code AccessDecider}
 *       contract that a policy allow never widens an existing gate — so on Enterprise the effective rule is
 *       owner AND not policy-denied. With no Subject attached (Personal) nothing is enforced, as everywhere else in
 *       the control plane.</li>
 * </ol>
 *
 * <p><b>Not a BI relation.</b> The relation is never registered as a DuckDB view, a Dataset or a registry component,
 * so there is no name {@code /bi/query} or {@code /db/*} could address; the sealed files under the write root are
 * unreachable from SQL because {@code SqlGuard} refuses file-reading functions and path-shaped identifiers. This
 * Investigation-scoped route is the ONLY way to read it — exposing it to BI waits for a Widget binding that carries
 * the D-E7 gate with it (LA-21).
 *
 * <p><b>Masked (LA-19, D-U6).</b> Entity ids in the answer are masked per the Space's {@code maskingMode}
 * ({@link EntityMasking}); the answer carries a {@code masking} note saying what was masked and why.
 *
 * <p>A {@code GET}: reads are open by policy (no capability — {@code route-gating.md}), so the gate is the row
 * rule above, not a capability. Audited per the LA-04 query pattern.
 *
 * <p><b>{@code ?at=<step>} — the relation at a past head (LA-21).</b> A Frozen Working Set Widget pins
 * {@code head{step, workingSetHash}} and re-reads the relation at that step: the log is append-only and an undo is a
 * LATER entry, so the prefix up to a step evaluates to the same Working Set forever (the evaluator's prefix semantics).
 * The tile compares the answered {@code head.workingSetHash} with its pin, so a log rewritten on disk shows as a broken
 * pin, never as quietly different evidence. It goes through the same gate as every other read — nothing is stored in
 * the Widget, so a Widget can never carry rows past the owner-only / PDP rule. A step past the head is a 422.
 */
public final class WorkingSetRoutes implements RouteModule {

    /** Gated by the geoLink feature: a Space whose modules.toon disables it answers 404 MODULE_DISABLED here. */
    @Override
    public java.util.Set<String> featureIds() {
        return java.util.Set.of("geoLink");
    }

    private static final int DEFAULT_LIMIT = 1_000;
    private static final int MAX_LIMIT = 10_000;
    private static final int CACHE_ENTRIES = 32;

    public static final Map<String, List<String>> COLUMNS = Map.of(
            "entities", List.of("entityId", "type", "hop", "seedId", "opSeq", "hidden", "kept", "identity", "highConnectivity"),
            "links", List.of("source", "target", "kind", "count", "opSeq"),
            "excluded", List.of("entityId", "opSeq", "reason"));

    /** One evaluated relation at one log head. Immutable once cached. */
    public record Relation(String key, int headStep, String workingSetHash, Map<String, List<Map<String, Object>>> tables) {}

    /** Access-ordered LRU, bounded; keyed by {@code <dir>\0<sha256 of the committed log>}. */
    private static final Map<String, Relation> CACHE = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Relation> eldest) {
            return size() > CACHE_ENTRIES;
        }
    };

    @Override
    public void register(ApiContext api) {
        api.get("/inv/investigations/([^/]+)/working-set", (e, m) -> workingSet(api, e, m.group(1)));
    }

    private Object workingSet(ApiContext api, HttpExchange ex, String id) throws IOException {
        return serve(ex, InvestigationRoutes.openForRead(api, ex, id));   // 503 · 422 · 403 · 404 owner-or-Case-member/R3/PDP
    }

    /**
     * The relation answer over an OPENED view: the main log's (above) or a Draft's ({@link DraftRoutes}, D7-3). The caller
     * has already passed its gate; everything below - the cache, the masking after it - is the same for both.
     */
    static Object serve(HttpExchange ex, InvestigationRoutes.Inv inv) throws IOException {
        String id = inv.id();
        String of = ApiContext.query(ex, "of");
        if (of == null || of.isBlank()) of = "entities";
        if (!COLUMNS.containsKey(of))
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'of' must be one of entities, links, excluded — got '" + of + "'");
        int limit = Math.min(Math.max(intParam(ex, "limit", DEFAULT_LIMIT), 1), MAX_LIMIT);
        int offset = intParam(ex, "offset", 0);
        if (offset < 0) throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "offset must be >= 0");
        int at = intParam(ex, "at", -1);
        if (ApiContext.query(ex, "at") != null && at < 0) throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "at must be >= 0");

        boolean[] cached = {false};
        Relation rel = relation(inv, at, cached);
        List<Map<String, Object>> all = rel.tables().get(of);
        List<Map<String, Object>> rows = all.subList(Math.min(offset, all.size()), (int) Math.min((long) offset + limit, all.size()));
        boolean truncated = (long) offset + rows.size() < all.size();

        String relation = of;
        emit(ex, id, b -> {
            b.attr("investigationId", id).attr("relation", relation).attr("rows", rows.size())
                    .attr("total", all.size()).attr("truncated", truncated).attr("cached", cached[0])
                    .attr("key", rel.key()).attr("at", at < 0 ? null : at);
            if (inv.draft() != null) b.attr("draftId", inv.draft().draftId());
            return b;
        });

        Map<String, Object> head = new LinkedHashMap<>();
        head.put("step", rel.headStep());
        head.put("workingSetHash", rel.workingSetHash());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        if (inv.draft() != null) out.put("draftId", inv.draft().draftId());
        out.put("relation", of);
        out.put("columns", COLUMNS.get(of));
        out.put("rows", rows);
        out.put("total", all.size());
        out.put("offset", offset);
        out.put("limit", limit);
        out.put("truncated", truncated);
        out.put("head", head);
        out.put("key", rel.key());
        out.put("cached", cached[0]);
        // LA-17 slice 2: the merged nodes of a resolve in force at this head, beside any relation — not a relation of
        // their own (an Alert Rule / Widget measures entities, links, excluded; `identity` counts merged nodes once).
        if (!rel.tables().get("groups").isEmpty()) out.put("groups", rel.tables().get("groups"));
        // D-U6: masked on the way out, AFTER the cache — the cached relation holds raw ids, the answer never does.
        EntityMasking mask = EntityMasking.of(inv, List.of());
        // DR-D2: the same entity is a different alias here (this Investigation's key) than on the query graph (the Space's
        // key); each masked entity of the page names its exploration alias so the UI keeps them one entity. Aliases only.
        Map<String, String> exploreAliases = new LinkedHashMap<>();
        if (of.equals("entities") && mask.masking())
            for (Map<String, Object> row : rows)
                if (row.get("entityId") instanceof String raw && mask.hidesRaw(raw))
                    exploreAliases.put(mask.tokenOf(raw), ExplorationMasking.alias(inv.writeRoot(), raw));
        @SuppressWarnings("unchecked") Map<String, Object> masked = (Map<String, Object>) mask.apply(out);
        masked.put("masking", mask.describe());
        if (!exploreAliases.isEmpty()) masked.put("exploreAliases", exploreAliases);
        if (of.equals("links")) {   // D-U9: each link's wire id, minted from the MASKED values — on copies, never the cache
            List<Map<String, Object>> stamped = new ArrayList<>();
            for (Object o : (List<?>) masked.get("rows")) {
                @SuppressWarnings("unchecked") Map<String, Object> r = new LinkedHashMap<>((Map<String, Object>) o);
                LinkIds.stampOne(r);
                stamped.add(r);
            }
            masked.put("rows", stamped);
            List<String> cols = new ArrayList<>(COLUMNS.get(of));
            cols.add("linkId");
            masked.put("columns", cols);
        }
        return masked;
    }

    /** The resolved Investigation as the PDP sees it — {@code resource.*} in an Access Policy's {@code when}. */
    /** The relation at the log's current committed head — from the cache when the head has not moved. */
    public static Relation relation(InvestigationRoutes.Inv inv, boolean[] cachedOut) throws IOException {
        return relation(inv, -1, cachedOut);
    }

    /**
     * The relation at step {@code at} ({@code < 0} = the current committed head). A past step is keyed by the log AND
     * the step, so it shares the cache without ever answering for the head, and vice versa.
     */
    static Relation relation(InvestigationRoutes.Inv inv, int at, boolean[] cachedOut) throws IOException {
        // The store hands back COMMITTED lines only (a line being appended right now is not one), each followed by one '\n':
        // exactly the bytes the log file holds for them, so the key is the same on every backend.
        java.io.ByteArrayOutputStream joined = new java.io.ByteArrayOutputStream();
        if (inv.draft() != null) {   // D7-3: the main prefix the Draft forked from, then the Draft's own lines - the key hashes BOTH
            List<String> main = inv.store().log(InvestigationStore.Scope.main(inv.id()));
            for (String line : main.subList(0, Math.min(inv.draft().baseStep(), main.size())))
                joined.writeBytes((line + "\n").getBytes(StandardCharsets.UTF_8));
        }
        for (String line : inv.store().log(inv.scope())) joined.writeBytes((line + "\n").getBytes(StandardCharsets.UTF_8));
        byte[] bytes = joined.toByteArray();
        int end = bytes.length;
        String key = inv.cacheKey() + "\u0000" + sha256(bytes, end) + (at < 0 ? "" : "@" + at);
        synchronized (CACHE) {
            Relation hit = CACHE.get(key);
            if (hit != null) {
                cachedOut[0] = true;
                return hit;
            }
        }
        if (inv.draft() == null) return build(key, bytes, end, at);
        final byte[] draftBytes = bytes;
        final int draftEnd = end;
        // D7-6: a Draft's cold relation build is a heavy job - admitted under the heavy-job cap (429 when it is full), never queued
        return DraftAdmission.heavy("Working Set relation of draft " + inv.draft().draftId(), () -> build(key, draftBytes, draftEnd, at));
    }

    /** D7-6 (hibernate / expire): forget every cached relation of the log named {@code cacheKey}. */
    static void evict(String cacheKey) {
        String prefix = cacheKey + "\u0000";
        synchronized (CACHE) {
            CACHE.keySet().removeIf(k -> k.startsWith(prefix));
        }
    }

    /** The cold path of {@link #relation}: evaluate the committed bytes and cache the relation under {@code key}. */
    private static Relation build(String key, byte[] bytes, int end, int at) throws IOException {
        List<Map<String, Object>> log = new ArrayList<>();
        for (String line : new String(bytes, 0, end, StandardCharsets.UTF_8).split("\n")) {
            if (line.isBlank()) continue;
            @SuppressWarnings("unchecked") Map<String, Object> m = ApiContext.JSON.readValue(line, Map.class);
            log.add(m);
        }
        int lastStep = log.isEmpty() ? 0 : ((Number) log.get(log.size() - 1).get("step")).intValue();
        if (at > lastStep)
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "at " + at + " is past the Investigation's head (step " + lastStep + ")");
        InvestigationEvaluator.State s = InvestigationEvaluator.evaluate(log, at, null);
        int headStep = 0;   // the last log entry at or before `at` — the head the answer is the relation OF
        for (Map<String, Object> e : log) {
            int step = ((Number) e.get("step")).intValue();
            if (at >= 0 && step > at) break;
            headStep = step;
        }
        Relation rel = new Relation(key.substring(key.indexOf('\u0000') + 1), headStep, s.hash(), tables(s));
        synchronized (CACHE) {
            CACHE.put(key, rel);
        }
        return rel;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, List<Map<String, Object>>> tables(InvestigationEvaluator.State s) {
        // LA-17 slice 2: an entity's identity is the group it resolves to under a resolve in force, else itself.
        Map<String, String> resolvedTo = new LinkedHashMap<>();
        Map<String, Object> view = s.resolutionView(resolvedTo);
        List<Map<String, Object>> entities = new ArrayList<>();
        for (InvestigationEvaluator.Entity e : s.entities.values()) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("entityId", e.id());
            r.put("type", e.type());
            r.put("hop", e.hop());
            r.put("seedId", e.seed());
            r.put("opSeq", e.admittedBy());
            r.put("hidden", s.hidden.contains(e.id()));
            r.put("kept", s.kept.contains(e.id()));
            r.put("identity", resolvedTo.getOrDefault(e.id(), e.id()));
            r.put("highConnectivity", s.highConnectivity.contains(e.id()));   // supernode suppression: shown, not expanded further
            entities.add(r);
        }
        List<Map<String, Object>> links = new ArrayList<>();
        for (InvestigationEvaluator.Link l : s.links.values()) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("source", l.source());
            r.put("target", l.target());
            r.put("kind", l.kind());
            r.put("count", l.count());
            r.put("opSeq", l.admittedBy());
            links.add(r);
        }
        List<Map<String, Object>> excluded = new ArrayList<>();
        for (var x : s.excluded.entrySet()) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("entityId", x.getKey());
            r.put("opSeq", x.getValue().step());
            r.put("reason", x.getValue().reason());
            excluded.add(r);
        }
        List<Map<String, Object>> groups = new ArrayList<>();
        if (view != null)
            for (Object o : (List<Object>) view.get("groups")) {
                Map<String, Object> g = (Map<String, Object>) o;
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("groupId", g.get("id"));
                r.put("members", g.get("members"));
                r.put("assertions", g.get("assertions"));
                r.put("entities", g.get("entities"));
                r.put("opSeq", view.get("step"));
                groups.add(r);
            }
        return Map.of("entities", List.copyOf(entities), "links", List.copyOf(links), "excluded", List.copyOf(excluded),
                "groups", List.copyOf(groups));
    }

    private static int intParam(HttpExchange ex, String name, int dflt) {
        String raw = ApiContext.query(ex, name);
        if (raw == null || raw.isBlank()) return dflt;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, name + " must be an integer, got '" + raw + "'");
        }
    }

    private static String sha256(byte[] bytes, int len) {
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            d.update(bytes, 0, len);
            return "sha256:" + HexFormat.of().formatHex(d.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Best-effort audit (LA-04 pattern): an audit failure never fails the analyst's read. */
    private static void emit(HttpExchange ex, String id, java.util.function.UnaryOperator<Event.Builder> attrs) {
        try {
            Event.Builder b = Event.builder(LinkEventTypes.LINK_INVESTIGATION_WORKING_SET_READ).source("inv")
                    .message("link.investigation.working_set.read — " + id)
                    .actor(ApiContext.actor(ex)).actorType(ApiContext.actorType(ex))
                    .action("link.investigation.working_set.read").actionCategory("analysis");
            EventSink.current().emit(attrs.apply(b));
        } catch (RuntimeException ignored) {
            // best effort — the read already succeeded
        }
    }
}
