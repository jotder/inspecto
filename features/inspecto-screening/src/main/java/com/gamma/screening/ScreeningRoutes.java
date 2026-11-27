package com.gamma.screening;

import com.gamma.access.WriteGates;
import com.gamma.entitystore.EntityFactLog;
import com.gamma.entitystore.EntityListFacts;
import com.gamma.entitystore.EntityRegistry;
import com.gamma.ops.ObjectService;
import com.gamma.opsapi.OpsEngine;
import com.gamma.spi.auth.ApiException;
import com.gamma.spi.auth.ErrorCodes;
import com.gamma.spi.http.ApiContext;
import com.gamma.spi.http.RouteModule;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * <b>Screening</b> (SCREENING-1, {@code docs/superpower/screening-addon-plan.md}): Match Scores of subjects against
 * Entity Lists, and the review of the Screening Hits a {@code screening.run} raises.
 *
 * <ul>
 *   <li>{@code POST /screening/check} {@code {subjects:[{key?, name?, identifier?}], lists[], threshold?, maxMatches?}}
 *       → {@code {threshold, results:[{key, name, identifier, matches:[…]}]}}. Read-shaped (a {@code CapabilityManifest}
 *       exemption): it persists nothing, and it is a POST so names never ride in a URL.</li>
 *   <li>{@code GET /screening/hits?state=&limit=&cursor=} → {@code {hits:[…], nextCursor, total}}, newest first, a
 *       page of at most {@code limit} (default 100, 1..500) from the hit index (SCREENING-1 D8); only the page's
 *       documents are read and verified. {@code GET /screening/hits/{id}}.</li>
 *   <li>{@code POST /screening/hits/{id}/decide} {@code {decision: confirm|dismiss|escalate, reason, version}} → the
 *       hit. {@code canWorkIncidents} (SCR-D13). Four-eyes (SCREENING-1-D1): whoever escalated a hit cannot confirm
 *       it. A confirm opens an Incident and a Case for the subject (SCREENING-1-D2) and stamps their ids on the hit.</li>
 * </ul>
 *
 * <p><b>Gates.</b> decide: {@code canWorkIncidents} (the wrapper) → no write root 503 → body 422 → unknown hit 404 →
 * forged / edited record 409 → stale {@code version} 409 → not allowed from its state 409 → save under the store
 * lock, then the hit index (best effort). list: no write root 503 → bad {@code state} / {@code limit} / {@code cursor}
 * 422. No path-jail 403 can trigger from a caller: the id must match {@code sh-<14 digits>-<6 hex>} (else 404).
 */
public final class ScreeningRoutes implements RouteModule {

    static final int MAX_SUBJECTS = 1_000;
    static final int MAX_LISTS = 20;
    static final int MAX_VALUE_LENGTH = 512;
    static final int DEFAULT_LIMIT = 100, MAX_LIMIT = 500;
    /** The most matches per subject a check answers an agent (D-AK16). */
    static final int AGENT_MAX_MATCHES = 10;
    /** D-AK24 (operator 2026-11-26): an agent's check costs {@value #AGENT_WEIGHT_PER_BATCH} per started {@value #AGENT_BATCH} subjects. */
    static final int AGENT_BATCH = 10, AGENT_WEIGHT_PER_BATCH = 2;

    @Override
    public Set<String> featureIds() {
        return Set.of("screening");
    }

    @Override
    public void register(ApiContext api) {
        // ⚠ String LITERALS on purpose — CapabilityManifestTest's scanner matches only a literal argument.
        api.post("/screening/check", (e, m) -> check(api, e, api.body(e)));
        api.get("/screening/hits", (e, m) -> hits(api, e));
        api.get("/screening/hits/([^/]+)", (e, m) -> one(api, e, m.group(1)));
        api.get("/screening/hits/([^/]+)/reveal", ApiContext.withCapability("canRevealLinkEntities",
                (e, m) -> reveal(api, e, m.group(1))));
        api.post("/screening/hits/([^/]+)/decide", ApiContext.withCapability("canWorkIncidents",
                (e, m) -> decide(api, e, m.group(1), api.body(e))));
    }

    private Object check(ApiContext api, HttpExchange ex, Map<String, Object> body) throws IOException {
        Path root = WriteGates.requireWriteRoot(api, "screening");
        List<Screener.Subject> subjects = subjects(body);
        if (agent(ex)) chargeAgentSubjects(ex, subjects.size());
        List<String> listIds = lists(body);
        // LA-GROW-EXCLUDE-1 GE-8: a check answers the matched entry, so a restricted list is refused (403) to a caller who
        // may not see its members. A Screening Job's hits are not refused: the hit reads hide a restricted list's entry
        // (hiddenLists / shown below).
        EntityFactLog.Log facts = EntityListFacts.read(new EntityFactLog(root));
        Map<String, EntityRegistry.EntityList> folded = EntityRegistry.fold(facts.facts(), facts.headSeq());
        for (String id : listIds)
            if (folded.get(id) != null) EntityListFacts.requireSeesMembers(ex, folded.get(id), "screen against");
        double threshold = threshold(body.get("threshold"));
        int maxMatches = agent(ex) ? agentMaxMatches(body.get("maxMatches")) : maxMatches(body.get("maxMatches"));
        Instant now = Instant.now();
        List<Screener.Prepared> lists;
        try {
            lists = Screener.load(root, listIds, now);
        } catch (IllegalArgumentException e) {
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, e.getMessage());
        }
        // W6 group E (D-AK13): each entry leaves as its list shows it - a masked list's entry is its Space token
        // (Screener.load); the subjects echoed back are the caller's own values (D-AK15)
        int maskedLists = (int) new java.util.LinkedHashSet<>(listIds).stream()
                .filter(id -> EntityListFacts.masked(root, folded.get(id))).count();
        com.gamma.util.AnswerMasking.applied("screening check (entries as each Entity List shows them)", maskedLists);
        List<Map<String, Object>> results = new ArrayList<>();
        for (Screener.Subject s : subjects) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("key", s.key());
            r.put("name", s.name());
            r.put("identifier", s.identifier());
            r.put("matches", Screener.screen(s, lists, threshold, maxMatches, now).stream().map(Screener::toMap).toList());
            results.add(r);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("threshold", threshold);
        out.put("results", results);
        return out;
    }

    private Object hits(ApiContext api, HttpExchange ex) throws IOException {
        Path root = WriteGates.requireWriteRoot(api, "screening hits");
        String state = ApiContext.query(ex, "state");
        if (state != null && !ScreeningHits.STATES.contains(state))
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'state' must be one of "
                    + new java.util.TreeSet<>(ScreeningHits.STATES));
        int limit = limit(ApiContext.query(ex, "limit"));
        String cursor = ApiContext.query(ex, "cursor");
        String after = null;
        if (cursor != null) {
            List<String> key = com.gamma.control.Cursor.decode(cursor);
            if (key.size() != 1 || !ScreeningHits.SAFE_ID.matcher(key.get(0)).matches())
                throw bad("'cursor' is not a cursor this listing returned; start again without one");
            after = key.get(0);
        }
        ScreeningHits.Page page = ScreeningHits.page(root, state, after, limit);
        List<Map<String, Object>> out = new ArrayList<>();
        Set<String> hidden = hiddenLists(ex, root);
        boolean agent = agent(ex);
        for (Map<String, Object> rec : page.hits()) out.add(shown(root, rec, hidden, agent));
        // W6 (D-AK13): name and identifier masked at write, a restricted list's entry withheld, an agent's subject key masked
        com.gamma.util.AnswerMasking.applied("screening hits (masked at write; key masked for an agent)", hidden.size() + 1);
        String next = page.next() == null ? null : com.gamma.control.Cursor.encode(List.of(page.next()));
        ApiContext.pagination(ex, cursor, next, limit, page.total());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("hits", out);
        body.put("nextCursor", next);
        body.put("total", page.total());
        return body;
    }

    /** {@code limit}: absent = {@link #DEFAULT_LIMIT}; else an integer in 1..{@link #MAX_LIMIT}. */
    static int limit(String raw) {
        if (raw == null) return DEFAULT_LIMIT;
        try {
            int n = Integer.parseInt(raw.trim());
            if (n >= 1 && n <= MAX_LIMIT) return n;
        } catch (NumberFormatException ignored) {
            // refused below
        }
        throw bad("'limit' must be an integer in 1.." + MAX_LIMIT);
    }

    private Object one(ApiContext api, HttpExchange ex, String id) throws IOException {
        Path root = WriteGates.requireWriteRoot(api, "screening hits");
        Map<String, Object> rec = ScreeningHits.read(root, id);
        if (rec == null) throw notFound(id);
        Set<String> hidden = hiddenLists(ex, root);
        // W6 (D-AK13): name and identifier masked at write; for an agent the subject key too (shown)
        com.gamma.util.AnswerMasking.applied("screening hit (masked at write; key masked for an agent)", hidden.size() + 1);
        return shown(root, rec, hidden, agent(ex));
    }

    /**
     * LA-GROW-EXCLUDE-1 GE-8: the restricted Entity Lists whose members this caller may not see (none for a holder of
     * canManageExclusionCategories or canRevealLinkEntities, or with no Subject). A hit stores the matched member as its
     * {@code entry}, so a hit on such a list is read with {@code entry} and {@code dedupeKey} (a hash over it) removed.
     */
    private static Set<String> hiddenLists(HttpExchange ex, Path root) throws IOException {
        if (EntityListFacts.seesRestricted(ex)) return Set.of();
        EntityFactLog.Log facts = EntityListFacts.read(new EntityFactLog(root));
        Set<String> out = new java.util.HashSet<>();
        for (EntityRegistry.EntityList l : EntityRegistry.fold(facts.facts(), facts.headSeq()).values())
            if (l.restricted()) out.add(l.id());
        return out;
    }

    /**
     * D-AK24 (operator 2026-11-26): an agent's check costs {@code ceil(n / 10) * 2} of its data budget, so every guess
     * spends it. The host charged the route's weight (the first batch) before this handler; the rest is spent here, before
     * any list is read, through the host's charger ({@link com.gamma.util.AgentBudget}) - a 429 past the budget, and a check
     * costing more than the whole burst (1000 subjects: 200) is refused outright. Fails closed when no charger is there.
     * A person is not charged and keeps 1..1000 subjects.
     */
    private static void chargeAgentSubjects(HttpExchange ex, int subjects) {
        int extra = (subjects + AGENT_BATCH - 1) / AGENT_BATCH * AGENT_WEIGHT_PER_BATCH - AGENT_WEIGHT_PER_BATCH;
        if (extra <= 0) return;
        if (!(ApiContext.attr(ex, com.gamma.util.AgentBudget.ATTR) instanceof java.util.function.IntConsumer charge))
            throw new IllegalStateException("an agent's screening check reached no agent budget");
        charge.accept(extra);
    }

    /** An AI agent client acting for a person (AGENT-KIT-1): it never reveals, so the subject key leaves masked. */
    private static boolean agent(HttpExchange ex) {
        return ApiContext.subject(ex).map(com.gamma.spi.auth.Subject::agent).orElse(false);
    }

    /**
     * A hit as this caller reads it. The subject key is the raw value of the Job's key column, stored unmasked (the
     * Job keys hits on it): for an agent it leaves as the Space token, as a score's key does ({@code EntityKeyMasking}),
     * and the {@code dedupeKey} hashed over it is withheld, since a guessed key could be tested against it
     * (AGENT-GET-DATA-READS-1 review, 2026-11-26). A person's read is unchanged ({@code SCREENING-HIT-KEY-RAW-1}).
     */
    private static Map<String, Object> shown(Path root, Map<String, Object> rec, Set<String> hidden, boolean agent) {
        Map<String, Object> out = shown(rec, hidden);
        if (agent) {
            Object key = out.get("subjectKey");
            if (key != null) out.put("subjectKey", com.gamma.mask.EvidenceMasker.forSpace(root).tokenFor(String.valueOf(key)));
            out.put("subjectKeyMasked", true);
            out.put("dedupeKey", null);
        }
        return out;
    }

    private static Map<String, Object> shown(Map<String, Object> rec, Set<String> hidden) {
        Map<String, Object> out = ScreeningHits.render(rec);
        if (!hidden.contains(String.valueOf(rec.get("listId")))) return out;
        out.put("entry", null);
        out.put("dedupeKey", null);
        out.put("entryHidden", true);
        return out;
    }

    /**
     * {@code GET /screening/hits/{id}/reveal} (SCREENING-1-D3, operator 2026-10-14): the subject's raw name and
     * identifier and the matched Entity List entry, for a holder of {@code canRevealLinkEntities} - the one grant that
     * already means "may see raw keys". The hit keeps its masks; the subject values are read again from the Dataset
     * the Job screened, the entry from the list. Every call is audited ({@code screening.hit.revealed}: ids only).
     */
    private Object reveal(ApiContext api, HttpExchange ex, String id) throws IOException {
        Path root = WriteGates.requireWriteRoot(api, "screening hit reveal");
        Map<String, Object> rec = ScreeningHits.read(root, id);
        if (rec == null) throw notFound(id);
        if (ScreeningHits.invalid(rec))
            throw new ApiException(409, ErrorCodes.CONFLICT, "screening hit '" + id + "' failed its integrity check "
                    + "(a record no server wrote) and cannot be revealed");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        String job = rec.get("source") instanceof Map<?, ?> s && s.get("job") != null ? String.valueOf(s.get("job")) : null;
        var cfg = job == null ? java.util.Optional.<com.gamma.job.JobConfig>empty()
                : com.gamma.control.HostContext.of(api).service().jobServiceOrCreate().jobConfig(job);
        Map<String, String> subject = new LinkedHashMap<>();
        String why = null;
        if (cfg.isEmpty()) why = "the Job that raised this hit ('" + job + "') no longer exists, so the raw subject cannot be located";
        else {
            try {
                Path data = com.gamma.pipeline.SpaceConfigRoot.currentDataRoot();
                subject = ScreeningJobType.ScreeningJob.subjectValues(root, data, ScreeningJobType.Params.of(cfg.get().params()),
                        String.valueOf(rec.get("subjectKey")));
            } catch (IllegalArgumentException | IllegalStateException e) {
                why = e.getMessage();
            }
        }
        out.put("subjectName", subject.get("subjectName"));
        out.put("subjectIdentifier", subject.get("subjectIdentifier"));
        String entry = null;
        try {
            Instant now = Instant.now();
            List<Screener.Prepared> lists = Screener.load(root, List.of(String.valueOf(rec.get("listId"))), now);
            entry = Screener.unmaskEntry(lists.get(0), String.valueOf(rec.get("entry")), now);
        } catch (IllegalArgumentException ignored) {
            // the list is gone: no entry to show
        }
        out.put("entry", entry);
        if (why != null) out.put("note", why);
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("screeningHit", id);
        attrs.put("listId", rec.get("listId"));
        ScreeningHits.audit(ApiContext.actor(ex), ApiContext.actorType(ex), "screening.hit.revealed",
                "screening hit " + id + " revealed", attrs);
        return out;
    }

    private Object decide(ApiContext api, HttpExchange ex, String id, Map<String, Object> body) throws IOException {
        Path root = WriteGates.requireWriteRoot(api, "screening hit decision");
        String decision = ApiContext.str(body, "decision");
        String to = decision == null ? null : ScreeningHits.DECISIONS.get(decision);
        if (to == null)
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'decision' must be one of "
                    + new java.util.TreeSet<>(ScreeningHits.DECISIONS.keySet()));
        String reason = EntityListFacts.reason(body);
        if (!(body.get("version") instanceof Number v) || v.doubleValue() != Math.rint(v.doubleValue()))
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "body must include 'version', the hit's "
                    + "current version (an integer)");
        int version = v.intValue();
        String actor = ApiContext.actor(ex);
        Map<String, Object> rec;
        String from;
        synchronized (ScreeningHits.lock()) {
            rec = ScreeningHits.read(root, id);
            if (rec == null) throw notFound(id);
            if (ScreeningHits.invalid(rec))
                throw new ApiException(409, ErrorCodes.CONFLICT, "screening hit '" + id + "' failed its integrity check "
                        + "(a record no server wrote) and cannot be decided");
            int current = ((Number) rec.get("version")).intValue();
            if (current != version)
                throw new ApiException(409, ErrorCodes.CONFLICT, "screening hit '" + id + "' is at version " + current
                        + ", not " + version + " — it changed since you read it; reload and decide again");
            from = String.valueOf(rec.get("state"));
            if (!ScreeningHits.allowed(from, to))
                throw new ApiException(409, ErrorCodes.CONFLICT, "screening hit '" + id + "' is " + from
                        + " and cannot be moved to " + to);
            if (ScreeningHits.CONFIRMED.equals(to) && ScreeningHits.ESCALATED.equals(from) && actor != null
                    && actor.equals(ScreeningHits.escalatedBy(rec)))
                throw new ApiException(409, ErrorCodes.CONFLICT, "screening hit '" + id + "' was escalated by you; a second "
                        + "person must confirm it (four-eyes)");
            ObjectService svc = ScreeningHits.CONFIRMED.equals(to) ? OpsEngine.of(api) : null;   // 503 when absent: nothing changed
            ScreeningCases.Opened opened = svc == null ? null : ScreeningCases.open(svc, rec, actor);
            try {
                ScreeningHits.transition(rec, to, actor, reason);
                if (opened != null) {
                    rec.put("caseId", opened.caseId());
                    rec.put("incidentId", opened.incidentId());
                }
                ScreeningHits.save(root, rec);
            } catch (IOException | RuntimeException failure) {
                if (opened != null) ScreeningCases.discard(svc, opened, actor,
                        failure instanceof RuntimeException r ? r : new IllegalStateException(failure));
                throw failure;
            }
            try {
                ScreeningHits.reindex(root, rec);
            } catch (IOException | RuntimeException lost) {
                // The decision stands: its document's new file time makes the next index read re-read it.
            }
        }
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("screeningHit", id);
        attrs.put("listId", rec.get("listId"));
        attrs.put("from", from);
        attrs.put("to", to);
        if (rec.get("caseId") != null) attrs.put("caseId", rec.get("caseId"));
        ScreeningHits.audit(actor, ApiContext.actorType(ex), "screening.hit.decided",
                "screening hit " + id + " " + from + " -> " + to, attrs);
        return shown(rec, hiddenLists(ex, root));
    }

    // ── body parsing ──────────────────────────────────────────────────────────────────────────────────────

    private static List<Screener.Subject> subjects(Map<String, Object> body) {
        if (!(body.get("subjects") instanceof List<?> raw) || raw.isEmpty() || raw.size() > MAX_SUBJECTS)
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'subjects' must list 1.." + MAX_SUBJECTS
                    + " subjects");
        List<Screener.Subject> out = new ArrayList<>(raw.size());
        for (int i = 0; i < raw.size(); i++) {
            if (!(raw.get(i) instanceof Map<?, ?> m))
                throw bad("'subjects[" + i + "]' must be an object {key?, name?, identifier?}");
            String key = text(m.get("key"), "subjects[" + i + "].key");
            String name = text(m.get("name"), "subjects[" + i + "].name");
            String identifier = text(m.get("identifier"), "subjects[" + i + "].identifier");
            if ((name == null || name.isBlank()) && (identifier == null || identifier.isBlank()))
                throw bad("'subjects[" + i + "]' needs a 'name' or an 'identifier'");
            out.add(new Screener.Subject(key != null ? key : String.valueOf(i), name, identifier));
        }
        return out;
    }

    private static String text(Object o, String what) {
        if (o == null) return null;
        if (!(o instanceof String s) || s.length() > MAX_VALUE_LENGTH)
            throw bad("'" + what + "' must be a string of at most " + MAX_VALUE_LENGTH + " characters");
        return s;
    }

    private static List<String> lists(Map<String, Object> body) {
        if (!(body.get("lists") instanceof List<?> raw) || raw.isEmpty() || raw.size() > MAX_LISTS)
            throw bad("'lists' must name 1.." + MAX_LISTS + " Entity Lists");
        List<String> out = new ArrayList<>();
        for (Object o : raw) {
            if (!(o instanceof String s) || !EntityListFacts.LIST_ID.matcher(s).matches())
                throw bad("'lists' must hold Entity List ids matching " + EntityListFacts.LIST_ID.pattern());
            out.add(s);
        }
        return out;
    }

    /** {@code threshold}: absent = the default; else a number in {@link Screener#MIN_THRESHOLD}..1. */
    static double threshold(Object raw) {
        if (raw == null) return Screener.DEFAULT_THRESHOLD;
        double t;
        if (raw instanceof Number n) t = n.doubleValue();
        else {
            try {
                t = Double.parseDouble(String.valueOf(raw).trim());
            } catch (NumberFormatException e) {
                t = Double.NaN;
            }
        }
        if (!(t >= Screener.MIN_THRESHOLD && t <= 1.0))
            throw bad("'threshold' must be a number in " + Screener.MIN_THRESHOLD + "..1.0");
        return t;
    }

    static int maxMatches(Object raw) {
        if (raw == null) return Screener.DEFAULT_MAX_MATCHES;
        if (!(raw instanceof Number n) || n.doubleValue() != Math.rint(n.doubleValue())
                || n.intValue() < 1 || n.intValue() > Screener.MAX_MATCHES)
            throw bad("'maxMatches' must be an integer in 1.." + Screener.MAX_MATCHES);
        return n.intValue();
    }

    /**
     * An agent's {@code maxMatches} (W6 group E, D-AK16 signed 2026-11-26): clamped to {@value #AGENT_MAX_MATCHES} per
     * subject - a larger whole number is answered with {@value #AGENT_MAX_MATCHES}, not refused; anything else is checked
     * as a person's.
     */
    static int agentMaxMatches(Object raw) {
        if (raw instanceof Number n && n.doubleValue() == Math.rint(n.doubleValue()) && n.doubleValue() > AGENT_MAX_MATCHES)
            return AGENT_MAX_MATCHES;
        return Math.min(maxMatches(raw), AGENT_MAX_MATCHES);
    }

    private static ApiException bad(String message) {
        return new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, message);
    }

    private static ApiException notFound(String id) {
        return new ApiException(404, ErrorCodes.NOT_FOUND, "screening hit '" + id + "' not found");
    }
}
