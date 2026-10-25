package com.gamma.la.api;

import com.gamma.entitystore.LaLimit;
import com.gamma.entitystore.LaLimitValues;
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
import java.util.function.BooleanSupplier;

/**
 * The <b>Working Set as a derived relation</b> (LA-20, {@code docs/archived-documents/plans-archive/link-analysis-backlog-plan.md} §2.7):
 * {@code GET /inv/investigations/{id}/working-set?of=entities|links|excluded&limit&offset} answers an
 * Investigation's Working Set as rows with fixed columns, carrying the provenance columns §2.7 names
 * ({@code opSeq}, {@code seedId}, {@code hop}, {@code reason}). It is evaluated from the SEALED log alone
 * ({@link InvestigationEvaluator}, D-E3), so it needs no Dataset read.
 *
 * <p><b>Cache (a functional requirement — six tiles = six re-runs per view).</b> The relation is a pure function of
 * the log, so it is cached per log under the store's change token ({@code InvestigationStore#logToken}: size, mtime in
 * nanoseconds, fileKey and a hash of the last line for the filesystem store; append-only rows and a non-decreasing
 * Draft version for the Postgres store). A hit reads no log, hashes nothing and parses nothing. An op or an undo appends
 * a line and a fork is a different directory, so both move the token; every in-app rewrite (a truncate after a failed
 * promote or Draft append, a rebase swap) changes the size or the mtime too.
 * <ul>
 *   <li><b>What the token proves:</b> no append, replace or in-app rewrite since the entry was built.</li>
 *   <li><b>What it cannot prove:</b> that nobody edited the file out of band keeping its size, its nanosecond mtime, its
 *       fileKey AND its last line. The replay equivalence check exists precisely because someone might.</li>
 *   <li><b>The bound:</b> an entry is trusted for at most {@link #WORKING_SET_TRUST_MS} (30 s), measured on a MONOTONIC clock
 *       ({@code System.nanoTime}; a backwards wall-clock step cannot stretch it). After
 *       that the next read does the FULL content check - read every committed line, SHA-256 it, compare with the entry's
 *       {@code key} - and either re-seeds the entry (same content) or rebuilds it (different content). So such an edit is
 *       stale for at most the TTL, never indefinitely, and the answer's {@code key} and ETag always name the content
 *       that was last verified.</li>
 *   <li><b>Drafts:</b> a Draft's entry is also keyed on the main log's token, so any main-log append invalidates every
 *       Draft relation of that Investigation (they rebuild cold under the heavy-job cap and can answer 429). A cheaper key
 *       would need the main prefix's content, i.e. a read of the log - the cost this cache exists to avoid.</li>
 * </ul>
 *
 * <p><b>Conditional GET (PERF-P4-3).</b> The ETag is built from the token, the verified content {@code key}, the table,
 * the page, the pin and the masking profile, and a matching {@code If-None-Match} answers a bodiless 304. It is decided
 * AFTER the caller's gate and after the relation was obtained without error: an error response of ANY status never
 * carries an ETag (it is attached only around a successful serialisation, {@link #sendTagged}) and a conditional request
 * that would be an error returns that error. The token is taken before the log is read and RE-CHECKED after the read and
 * the fold: if it moved meanwhile (a failed promote / Draft append truncating inside one mtime tick) the freshly read
 * answer is served uncached with no ETag, so a stale token is never cached against newer content nor a 304 source. The 304 writes the same audit
 * event as the 200 (plus {@code notModified}).
 *
 * <p><b>Who may evaluate it (D-E7, decided 2026-09-23).</b> The gate runs on EVERY request, BEFORE the cache is
 * consulted, so a cached relation can never be served to a caller the gate would refuse:
 * <ol>
 *   <li>{@link InvestigationRoutes#open} — owner-only (anyone else: 404, the same answer as absence) and the R3
 *       Dataset gate. On Standard and below, where no {@code AccessDecider} is on the classpath, that is the
 *       whole rule: OWNER-ONLY, fail closed. ⛔ There is deliberately no fallback to {@code ComponentAccess}
 *       Dataset sharing — being able to view the Dataset does NOT make someone else's Investigation readable.</li>
 *   <li>{@link RowScope#visible} with {@code resourceKind = "investigation"} — the Advanced PDP
 *       ({@code inspecto-policy}'s {@code PolicyEngine}) judges the resolved Investigation; a {@code DENY} is a 404,
 *       even for the owner — applied inside {@link InvestigationRoutes#open}, so EVERY Investigation route obeys
 *       it, not only this one. {@code ALLOW}/{@code ABSTAIN} fall through to rule 1 — the {@code AccessDecider}
 *       contract that a policy allow never widens an existing gate — so on Advanced the effective rule is
 *       owner AND not policy-denied. With no Subject attached (Basic) nothing is enforced, as everywhere else in
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

    /** Gated by the geoLink feature (404 MODULE_DISABLED where a Space switches it off) and the investigations edition feature (503 CAPABILITY_UNAVAILABLE on Basic, LA-EDITIONS-1). */
    @Override
    public java.util.Set<String> featureIds() {
        return java.util.Set.of("geoLink", "investigations");
    }

    private static final int CACHE_ENTRIES = 32;

    /** How long a cached relation is trusted on its change token alone before the next read re-validates by content. */
    public static final long WORKING_SET_TRUST_MS = 30_000;

    /**
     * The trust window's clock: MONOTONIC milliseconds ({@code System.nanoTime}), never the wall clock - a backwards step
     * of the wall clock (NTP, a manual change, a VM resume) must not stretch the window. Only differences are meaningful.
     */
    static long monotonicMs() {
        return System.nanoTime() / 1_000_000L;
    }

    /** TEST SEAM (package-private; a test in another module reaches it through a same-package accessor): the trust window (default {@link #WORKING_SET_TRUST_MS}). */
    static volatile long trustMs = WORKING_SET_TRUST_MS;

    /** TEST SEAM (package-private): the MONOTONIC clock the trust window is measured on; a replacement must be monotonic too. */
    static volatile java.util.function.LongSupplier clockMs = WorkingSetRoutes::monotonicMs;

    /** TEST SEAM (package-private): full content reads (a miss, or a re-validation after the trust window) - a hit within the window adds none. */
    static final java.util.concurrent.atomic.LongAdder FULL_READS = new java.util.concurrent.atomic.LongAdder();

    /** A cached relation and when (monotonic ms) it was last verified against the log's content. */
    private static final class Cached {
        final Relation rel;
        volatile long validatedMs;
        Cached(Relation rel, long validatedMs) { this.rel = rel; this.validatedMs = validatedMs; }
    }

    public static final Map<String, List<String>> COLUMNS = Map.of(
            "entities", List.of("entityId", "type", "hop", "seedId", "opSeq", "hidden", "kept", "identity", "highConnectivity", "contacts", "expanded"),
            "links", List.of("source", "target", "kind", "count", "opSeq"),
            "excluded", List.of("entityId", "opSeq", "reason"));

    /** One evaluated relation at one log head. Immutable once cached. */
    public record Relation(String key, int headStep, String workingSetHash, Map<String, List<Map<String, Object>>> tables) {}

    /** Access-ordered LRU, bounded; keyed by {@code <dir>\0<hash of the store's change token>[@at]}; see the class javadoc for the trust bound. */
    private static final Map<String, Cached> CACHE = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Cached> eldest) {
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
        LaLimitValues lim = SpaceLimits.of(inv.writeRoot());   // the Space's working_set.page_default / working_set.page_max
        int limit = Math.min(Math.max(intParam(ex, "limit", SpaceLimits.get(lim, LaLimit.WORKING_SET_PAGE_DEFAULT)), 1),
                SpaceLimits.get(lim, LaLimit.WORKING_SET_PAGE_MAX));
        int offset = intParam(ex, "offset", 0);
        if (offset < 0) throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "offset must be >= 0");
        int at = intParam(ex, "at", -1);
        if (ApiContext.query(ex, "at") != null && at < 0) throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "at must be >= 0");

        // PERF-P4-3: the caller's gate (open / R3 / PDP) has already passed. The relation comes from the cache (no log read
        // within the trust window); only then - nothing has failed - is the ETag derived and a matching If-None-Match answered 304.
        String token = WorkingSetEtag.logToken(inv);
        boolean[] cached = {false};
        boolean[] stable = {true};
        Relation rel = relation(inv, at, cached, token, stable);
        // The token was taken BEFORE the log was read. If the log's token moved while it was read (a failed promote / Draft
        // append truncating in the same mtime tick), the pre-read token does not describe the content just read: serve that
        // content as a correct 200, uncached, with NO ETag - never a 304 source.
        String etag = stable[0] ? WorkingSetEtag.of(inv, token, rel.key(), of, limit, offset, at) : null;
        List<Map<String, Object>> all = rel.tables().get(of);
        List<Map<String, Object>> rows = all.subList(Math.min(offset, all.size()), (int) Math.min((long) offset + limit, all.size()));
        boolean truncated = (long) offset + rows.size() < all.size();
        boolean notModified = etag != null && WorkingSetEtag.isFresh(ex, etag);

        String relation = of;
        emit(ex, id, b -> {
            b.attr("investigationId", id).attr("relation", relation).attr("rows", rows.size())
                    .attr("total", all.size()).attr("truncated", truncated).attr("cached", cached[0])
                    .attr("key", rel.key()).attr("at", at < 0 ? null : at);
            if (inv.draft() != null) b.attr("draftId", inv.draft().draftId());
            if (notModified) b.attr("notModified", true);
            return b;
        });
        if (notModified) {
            ex.getResponseHeaders().set("ETag", etag);
            ex.sendResponseHeaders(304, -1);   // -1 => no body, as 304 requires
            return ApiContext.HANDLED;
        }

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
        return etag == null ? masked : sendTagged(ex, etag, masked);
    }

    /**
     * Send {@code body} as the 200 and tag it - the tag is attached only around a successful serialisation. Serialising is
     * what can still fail here (the framework used to do it AFTER {@code serve} returned, with the header already set, so a
     * late 500 carried the tag of a body that was never sent). On any failure the tag is withdrawn before the error escapes:
     * an error response of any status never carries an ETag.
     */
    static Object sendTagged(HttpExchange ex, String etag, Object body) throws IOException {
        try {
            ex.getResponseHeaders().set("ETag", etag);
            return ApiContext.respondJson(ex, 200, body);
        } catch (IOException | RuntimeException | Error e) {
            ex.getResponseHeaders().remove("ETag");
            throw e;
        }
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
        return relation(inv, at, cachedOut, WorkingSetEtag.logToken(inv), null);
    }

    /** True when the store's change token is still {@code token}; a failure to stat it counts as moved (fail toward not caching). */
    private static boolean tokenUnchanged(InvestigationRoutes.Inv inv, String token) {
        try {
            return token.equals(WorkingSetEtag.logToken(inv));
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /**
     * PERF-P4-3: the cache is keyed by the store's {@code logToken} (a stat), so a hit reads no log and hashes nothing. Only a
     * miss reads the committed lines - and still hashes them, because the answer's {@code key} (the log's SHA-256) is part of
     * the body clients parse.
     */
    static Relation relation(InvestigationRoutes.Inv inv, int at, boolean[] cachedOut, String token, boolean[] stableOut) throws IOException {
        if (stableOut != null) stableOut[0] = true;
        String tokenKey = WorkingSetEtag.relationKey(inv, token, at);
        Cached known;
        synchronized (CACHE) {
            known = CACHE.get(tokenKey);
        }
        long startedMs = clockMs.getAsLong();   // stamped when the verification BEGAN: a slow read never lengthens the window
        if (known != null && startedMs - known.validatedMs < trustMs) {
            cachedOut[0] = true;
            return known.rel;
        }
        // A miss, or the trust window ran out: read the committed lines. The store hands back COMMITTED lines only (a line being
        // appended right now is not one), each followed by one '\n': exactly the bytes the log file holds for them, so the key is
        // the same on every backend.
        FULL_READS.increment();
        java.io.ByteArrayOutputStream joined = new java.io.ByteArrayOutputStream();
        if (inv.draft() != null) {   // D7-3: the main prefix the Draft forked from, then the Draft's own lines - the key hashes BOTH
            List<String> main = inv.store().log(InvestigationStore.Scope.main(inv.id()));
            for (String line : main.subList(0, Math.min(inv.draft().baseStep(), main.size())))
                joined.writeBytes((line + "\n").getBytes(StandardCharsets.UTF_8));
        }
        for (String line : inv.store().log(inv.scope())) joined.writeBytes((line + "\n").getBytes(StandardCharsets.UTF_8));
        byte[] bytes = joined.toByteArray();
        int end = bytes.length;
        String hash = sha256(bytes, end) + (at < 0 ? "" : "@" + at);
        // READ-vs-WRITE race: `token` was taken BEFORE the read. If the log's token moved while it was read (a failed promote or
        // Draft append truncating in the same mtime tick as the write before it), `token` may name older bytes than the ones just
        // read. That result is correct to serve once, but must never be cached under `token` nor tagged: stableOut[0] = false.
        // Checked here (after the read) and again in build (after the fold, just before the put).
        BooleanSupplier unchanged = () -> {
            boolean same = tokenUnchanged(inv, token);
            if (!same && stableOut != null) stableOut[0] = false;
            return same;
        };
        if (known != null && known.rel.key().equals(hash)) {   // the same content as when it was built: trust it for another window
            if (unchanged.getAsBoolean()) known.validatedMs = startedMs;
            cachedOut[0] = true;
            return known.rel;
        }
        String key = inv.cacheKey() + "\u0000" + hash;
        if (inv.draft() == null) return build(tokenKey, key, bytes, end, at, startedMs, unchanged);
        final byte[] draftBytes = bytes;
        final int draftEnd = end;
        // D7-6: a Draft's cold relation build is a heavy job - admitted under the heavy-job cap (429 when it is full), never queued
        return DraftAdmission.heavy("Working Set relation of draft " + inv.draft().draftId(),
                () -> build(tokenKey, key, draftBytes, draftEnd, at, startedMs, unchanged));
    }

    /** D7-6 (hibernate / expire): forget every cached relation of the log named {@code cacheKey}. */
    static void evict(String cacheKey) {
        String prefix = cacheKey + "\u0000";
        synchronized (CACHE) {
            CACHE.keySet().removeIf(k -> k.startsWith(prefix));
        }
    }

    /** The cold path of {@link #relation}: evaluate the committed bytes and cache the relation under {@code key}. */
    private static Relation build(String tokenKey, String key, byte[] bytes, int end, int at, long startedMs, BooleanSupplier unchanged)
            throws IOException {
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
        if (!unchanged.getAsBoolean()) return rel;   // the token moved during the read or the fold: serve it, never cache it
        synchronized (CACHE) {
            CACHE.put(tokenKey, new Cached(rel, startedMs));
        }
        return rel;
    }

    @SuppressWarnings("unchecked")
    static Map<String, List<Map<String, Object>>> tables(InvestigationEvaluator.State s) {
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
            // ...with the distinct-contact degree the flagging step SEALED (read.hubs[].degree); null when unflagged
            r.put("contacts", s.highConnectivity.contains(e.id()) ? s.hubDegree.get(e.id()) : null);
            // LA-UNEXPANDED-MARKER-1: an effective expand already had it in its frontier (false = still an unexpanded node)
            r.put("expanded", s.expanded.contains(e.id()));
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
