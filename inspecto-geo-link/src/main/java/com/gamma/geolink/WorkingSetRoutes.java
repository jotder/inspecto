package com.gamma.geolink;

import com.gamma.control.ApiContext;
import com.gamma.control.ApiException;
import com.gamma.control.RouteModule;
import com.gamma.control.RowScope;
import com.gamma.event.Event;
import com.gamma.event.EventLog;
import com.gamma.event.EventType;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The <b>Working Set as a derived relation</b> (LA-20, {@code docs/superpower/link-analysis-backlog-plan.md} §2.7):
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
 *       even for the owner. {@code ALLOW}/{@code ABSTAIN} fall through to rule 1 — the {@code AccessDecider}
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
 * <p>A {@code GET}: reads are open by policy (no capability — {@code route-gating.md}), so the gate is the row
 * rule above, not a capability. Audited per the LA-04 query pattern.
 */
public final class WorkingSetRoutes implements RouteModule {

    private static final int DEFAULT_LIMIT = 1_000;
    private static final int MAX_LIMIT = 10_000;
    private static final int CACHE_ENTRIES = 32;

    static final Map<String, List<String>> COLUMNS = Map.of(
            "entities", List.of("entityId", "type", "hop", "seedId", "opSeq", "hidden", "kept"),
            "links", List.of("source", "target", "kind", "count", "opSeq"),
            "excluded", List.of("entityId", "opSeq", "reason"));

    /** One evaluated relation at one log head. Immutable once cached. */
    record Relation(String key, int headStep, String workingSetHash, Map<String, List<Map<String, Object>>> tables) {}

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
        InvestigationRoutes.Inv inv = InvestigationRoutes.open(api, ex, id);   // 503 · 422 · 403 · 404 owner/R3
        if (!RowScope.visible(ex, "investigation", resource(inv)))              // Enterprise PDP (D-E7)
            throw new ApiException(404, "no investigation '" + id + "'");

        String of = ApiContext.query(ex, "of");
        if (of == null || of.isBlank()) of = "entities";
        if (!COLUMNS.containsKey(of))
            throw new ApiException(422, "'of' must be one of entities, links, excluded — got '" + of + "'");
        int limit = Math.min(Math.max(intParam(ex, "limit", DEFAULT_LIMIT), 1), MAX_LIMIT);
        int offset = intParam(ex, "offset", 0);
        if (offset < 0) throw new ApiException(422, "offset must be >= 0");

        boolean[] cached = {false};
        Relation rel = relation(inv, cached);
        List<Map<String, Object>> all = rel.tables().get(of);
        List<Map<String, Object>> rows = all.subList(Math.min(offset, all.size()), (int) Math.min((long) offset + limit, all.size()));
        boolean truncated = (long) offset + rows.size() < all.size();

        String relation = of;
        emit(ex, id, b -> b.attr("investigationId", id).attr("relation", relation).attr("rows", rows.size())
                .attr("total", all.size()).attr("truncated", truncated).attr("cached", cached[0])
                .attr("key", rel.key()));

        Map<String, Object> head = new LinkedHashMap<>();
        head.put("step", rel.headStep());
        head.put("workingSetHash", rel.workingSetHash());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
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
        return out;
    }

    /** The resolved Investigation as the PDP sees it — {@code resource.*} in an Access Policy's {@code when}. */
    private static Map<String, Object> resource(InvestigationRoutes.Inv inv) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", inv.id());
        r.put("owner", inv.header().get("owner"));
        r.put("dataset", inv.dataset());
        if (inv.header().get("parent") instanceof Map<?, ?> p) r.put("parent", p.get("id"));
        return r;
    }

    /** The relation at the log's current committed head — from the cache when the head has not moved. */
    static Relation relation(InvestigationRoutes.Inv inv, boolean[] cachedOut) throws IOException {
        Path logFile = inv.dir().resolve("log.jsonl");
        byte[] bytes = Files.isRegularFile(logFile) ? Files.readAllBytes(logFile) : new byte[0];
        int end = bytes.length;
        while (end > 0 && bytes[end - 1] != '\n') end--;   // a line being appended right now is not committed yet
        String key = inv.dir().toAbsolutePath().normalize() + "\u0000" + sha256(bytes, end);
        synchronized (CACHE) {
            Relation hit = CACHE.get(key);
            if (hit != null) {
                cachedOut[0] = true;
                return hit;
            }
        }
        List<Map<String, Object>> log = new ArrayList<>();
        for (String line : new String(bytes, 0, end, StandardCharsets.UTF_8).split("\n")) {
            if (line.isBlank()) continue;
            @SuppressWarnings("unchecked") Map<String, Object> m = ApiContext.JSON.readValue(line, Map.class);
            log.add(m);
        }
        InvestigationEvaluator.State s = InvestigationEvaluator.evaluate(log, -1, null);
        int headStep = log.isEmpty() ? 0 : ((Number) log.get(log.size() - 1).get("step")).intValue();
        Relation rel = new Relation(key.substring(key.indexOf('\u0000') + 1), headStep, s.hash(), tables(s));
        synchronized (CACHE) {
            CACHE.put(key, rel);
        }
        return rel;
    }

    private static Map<String, List<Map<String, Object>>> tables(InvestigationEvaluator.State s) {
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
        return Map.of("entities", List.copyOf(entities), "links", List.copyOf(links), "excluded", List.copyOf(excluded));
    }

    private static int intParam(HttpExchange ex, String name, int dflt) {
        String raw = ApiContext.query(ex, name);
        if (raw == null || raw.isBlank()) return dflt;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new ApiException(422, name + " must be an integer, got '" + raw + "'");
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
            Event.Builder b = Event.builder(EventType.LINK_INVESTIGATION_WORKING_SET_READ).source("inv")
                    .message("link.investigation.working_set.read — " + id)
                    .actor(ApiContext.actor(ex)).actorType(ApiContext.actorType(ex))
                    .action("link.investigation.working_set.read").actionCategory("analysis");
            EventLog.current().emit(attrs.apply(b));
        } catch (RuntimeException ignored) {
            // best effort — the read already succeeded
        }
    }
}
