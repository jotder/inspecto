package com.gamma.entitylist;

import com.gamma.control.ApiContext;
import com.gamma.control.ApiException;
import com.gamma.control.EntityTypes;
import com.gamma.control.ErrorCodes;
import com.gamma.control.LinkAnalysisSettings;
import com.gamma.control.PendingChanges;
import com.gamma.control.RouteModule;
import com.gamma.control.WriteGates;
import com.gamma.event.Event;
import com.gamma.event.EventLog;
import com.gamma.event.EventType;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static com.gamma.entitylist.EntityListFacts.LIST_ID;
import static com.gamma.entitylist.EntityListFacts.append;
import static com.gamma.entitylist.EntityListFacts.masked;
import static com.gamma.entitylist.EntityListFacts.read;
import static com.gamma.entitylist.EntityListFacts.reason;
import static com.gamma.entitylist.EntityListFacts.type;

/**
 * <b>Entity Lists</b> (LA-17 slice 1, {@code docs/superpower/link-analysis-entity-model-design.md} §4.3 and the
 * binding wire contract §4.3.1): named, Space-scoped sets of typed Entity keys, persisted as facts in the Space's
 * {@link EntityFactLog} and read as a fold over it ({@link EntityRegistry}).
 *
 * <ul>
 *   <li>{@code GET /entity-lists} → {@code {lists: [summary…], headSeq, headHash}} (retired lists included).</li>
 *   <li>{@code GET /entity-lists/{id}?at=<seq>} → summary + {@code {members, atSeq, headHash}}.</li>
 *   <li>{@code POST /entity-lists} {@code {id?, title, purpose, entityType, reason}} → 201 + the list.</li>
 *   <li>{@code POST /entity-lists/{id}/members} {@code {add?, remove?, reason}} → 200 + the list + {@code changed}.</li>
 *   <li>{@code POST /entity-lists/{id}/retire} {@code {reason}} → 200 + the list.</li>
 *   <li>{@code POST /entity-lists/{id}/match} {@code {values[]}} → {@code {matches: [{value, matched, entry?, match?}]}}
 *       (ASSURE-ENTITY-LISTS-1; read-shaped: a POST so keys never ride in a URL).</li>
 * </ul>
 *
 * <p><b>ASSURE-ENTITY-LISTS-1 (WS-12)</b> adds the assurance entries. {@code members} also takes
 * {@code addRanges} / {@code removeRanges} ({@link EntityListEntries}: prefix, same-length range, CIDR) and
 * {@code expiresAt} (an ISO instant in the future, applied to every entry the call adds; an expired entry stays in
 * the as-of fold but stops matching). An expiring add never shortens a PERMANENT entry. Each write rewrites the
 * list's Parquet sidecar ({@link EntityListSidecar}). Under an approval policy for kind {@code entity-list},
 * {@code members} and {@code retire} are held as Pending Changes (four-eyes). The exception is an add-only change
 * whose every entry expires within 24 h: it applies at once and answers {@code reviewAfter: true} (D-P5).
 *
 * <p><b>Gates.</b> Writes: {@code canManageIncidents} (the wrapper) → no write root 503 → body 422 → unknown list 404
 * → retired / id ever used 409 → normalised value empty or in both {@code add} and {@code remove} 422 → append under
 * the log's JVM lock. There is no path-jail 403: no caller text ever names a file (fact files are named by seq).
 * Reads need only Space access, but the log lives under the write root, so no write root is a 503 there too (as
 * {@code GET /inv/snapshots}). A broken fact chain is a 500 {@code INTEGRITY_VIOLATION} on every route.
 *
 * <p><b>{@code headHash} on a single list</b> is the hash of the fact AT {@code atSeq} — the head of the prefix that
 * was folded — so {@code {atSeq, headHash}} is a self-verifying pin, and without {@code at} it is the log's head.
 *
 * <p><b>Masking</b> (D-U6) at render only: under the Space's {@code maskingMode} {@code typed}, a list whose Entity Type
 * is {@code masked: true} — or is no longer in force, failing closed — has its members masked; {@code all} masks every
 * list, {@code none} none. The token is {@link MaskTokens}' HMAC (the one Link Analysis' {@code EntityMasking} uses), under one key per Space fact log. Members are
 * sorted AFTER rendering, so the order of masked tokens says nothing about the raw keys.
 */
public final class EntityListRoutes implements RouteModule {

    private static final List<String> PURPOSES = List.of("allow", "block", "watch", "exclusion");
    private static final int MAX_VALUES = 5_000;
    private static final int MAX_TITLE = 200;
    private static final int MAX_VALUE_LENGTH = 512;

    @Override
    public void register(ApiContext api) {
        // ⚠ String LITERALS on purpose — CapabilityManifestTest's scanner matches only a literal argument.
        api.get("/entity-lists", (e, m) -> list(api));
        api.get("/entity-lists/([^/]+)", (e, m) -> one(api, e, m.group(1)));
        api.post("/entity-lists", ApiContext.withCapability("canManageIncidents",
                (e, m) -> create(api, e, api.body(e))));
        api.post("/entity-lists/([^/]+)/members", ApiContext.withCapability("canManageIncidents",
                (e, m) -> members(api, e, m.group(1), api.body(e))));
        api.post("/entity-lists/([^/]+)/retire", ApiContext.withCapability("canManageIncidents",
                (e, m) -> retire(api, e, m.group(1), api.body(e))));
        api.post("/entity-lists/([^/]+)/match", (e, m) -> match(api, m.group(1), api.body(e)));
    }

    // ── reads ──────────────────────────────────────────────────────────────────────────────────────────

    private Object list(ApiContext api) throws IOException {
        Path root = WriteGates.requireWriteRoot(api, "entity lists");
        EntityFactLog.Log log = read(new EntityFactLog(root));
        List<Map<String, Object>> lists = new ArrayList<>();
        for (EntityRegistry.EntityList l : EntityRegistry.fold(log.facts(), log.headSeq()).values()) lists.add(summary(l));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("lists", lists);
        out.put("headSeq", log.headSeq());
        out.put("headHash", log.headHash());
        return out;
    }

    private Object one(ApiContext api, HttpExchange ex, String id) throws IOException {
        Path root = WriteGates.requireWriteRoot(api, "entity lists");
        String rawAt = ApiContext.query(ex, "at");
        long at = -1;
        if (rawAt != null) {
            try {
                at = Long.parseLong(rawAt.trim());
            } catch (NumberFormatException e) {
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'at' must be a log seq (an integer >= 0)");
            }
            if (at < 0) throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'at' must be >= 0");
        }
        EntityFactLog log = new EntityFactLog(root);
        EntityFactLog.Log facts = read(log);
        if (at > facts.headSeq())
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'at' " + at + " is beyond the log head "
                    + facts.headSeq());
        long atSeq = at < 0 ? facts.headSeq() : at;
        EntityRegistry.EntityList l = EntityRegistry.fold(facts.facts(), atSeq).get(id);
        if (l == null) throw notFound(id, rawAt != null ? " at seq " + atSeq : "");
        return render(root, log, facts, l, atSeq);
    }

    private Object match(ApiContext api, String id, Map<String, Object> body) throws IOException {
        Path root = WriteGates.requireWriteRoot(api, "entity lists");
        List<String> values = values(body, "values");
        if (values.isEmpty() || values.size() > MAX_VALUES)
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'values' must list 1.." + MAX_VALUES + " strings");
        EntityFactLog log = new EntityFactLog(root);
        EntityFactLog.Log facts = read(log);
        EntityRegistry.EntityList l = current(facts, id);
        if (l == null) throw notFound(id, "");
        boolean masked = masked(root, l);
        byte[] key = masked ? MaskTokens.key(log.directory()) : null;
        Instant now = Instant.now();
        List<Map<String, Object>> out = new ArrayList<>();
        for (String v : values) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("value", v);
            String entry = l.retired() ? null : l.match(v, now);   // a retired list matches nothing
            m.put("matched", entry != null);
            if (entry != null) {
                boolean exact = entry.equals(EntityTypes.normalise(l.normaliser(), v)) && l.members().contains(entry);
                m.put("match", exact ? "key" : EntityListEntries.parse(entry).match());
                m.put("entry", masked ? MaskTokens.token(key, entry) : entry);
            }
            out.add(m);
        }
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("listId", id);
        res.put("purpose", l.purpose());
        res.put("atSeq", facts.headSeq());
        res.put("matches", out);
        return res;
    }

    // ── writes ─────────────────────────────────────────────────────────────────────────────────────────

    private Object create(ApiContext api, HttpExchange ex, Map<String, Object> body) throws IOException {
        Path root = WriteGates.requireWriteRoot(api, "entity list create");
        String reason = reason(body);
        String title = ApiContext.str(body, "title");
        if (title == null || title.length() > MAX_TITLE)
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "body must include 'title', 1.." + MAX_TITLE + " characters");
        String purpose = ApiContext.str(body, "purpose");
        if (purpose == null || !PURPOSES.contains(purpose))
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'purpose' must be one of " + PURPOSES);
        String entityType = ApiContext.str(body, "entityType");
        Optional<EntityTypes.EntityType> sealedType = entityType == null ? Optional.empty() : type(root, entityType);
        if (sealedType.isEmpty())
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'entityType' must be an Entity Type in force "
                    + typeIds(root) + ", got '" + entityType + "'");
        String given = ApiContext.str(body, "id");
        String id = given != null ? given : "el-" + UUID.randomUUID();
        if (!LIST_ID.matcher(id).matches())
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "entity list id must match " + LIST_ID.pattern()
                    + ", got '" + id + "'");

        EntityFactLog log = new EntityFactLog(root);
        Map<String, Object> created;
        synchronized (log.lock()) {
            EntityFactLog.Log head = read(log);
            if (EntityRegistry.fold(head.facts(), head.headSeq()).containsKey(id))
                throw new ApiException(409, ErrorCodes.CONFLICT, "entity list '" + id + "' already exists (an id is never reused, "
                        + "retired lists included)");
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("title", title);
            payload.put("purpose", purpose);
            payload.put("entityType", entityType);
            payload.put("normaliser", sealedType.get().normaliser());   // D-M9: sealed; member writes use this, not the type
            head = append(log, head, ex, reason, "list.created", id, payload);
            emit(ex, id, "list.created", 0, 0, head.headSeq());
            created = render(root, log, head, current(head, id), head.headSeq());
            created.put("sidecar", EntityListSidecar.write(api.dataRoot(), current(head, id), head.facts()));
        }
        return ApiContext.respondJson(ex, 201, created);   // outside the lock: a slow client must not stall writers
    }

    private Object members(ApiContext api, HttpExchange ex, String id, Map<String, Object> body) throws IOException {
        Path root = WriteGates.requireWriteRoot(api, "entity list members");
        String reason = reason(body);
        List<String> add = values(body, "add");
        List<String> remove = values(body, "remove");
        List<Object> addRanges = rangeSpecs(body, "addRanges");
        List<Object> removeRanges = rangeSpecs(body, "removeRanges");
        if (add.size() + remove.size() + addRanges.size() + removeRanges.size() > MAX_VALUES)
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "at most " + MAX_VALUES
                    + " values per call (add + remove + addRanges + removeRanges), got "
                    + (add.size() + remove.size() + addRanges.size() + removeRanges.size()));
        Instant now = Instant.now();
        String expiresAt = expiresAt(body, now);

        EntityFactLog log = new EntityFactLog(root);
        synchronized (log.lock()) {
            EntityFactLog.Log head = read(log);
            EntityRegistry.EntityList l = EntityRegistry.fold(head.facts(), head.headSeq()).get(id);
            if (l == null) throw notFound(id, "");
            if (l.retired()) throw new ApiException(409, ErrorCodes.CONFLICT, "entity list '" + id + "' is retired");
            type(root, l.entityType()).orElseThrow(() -> new ApiException(409, ErrorCodes.CONFLICT,
                    "entity list '" + id + "' is of Entity Type '" + l.entityType() + "', which is no longer in force"));
            Set<String> toAdd = normalise(l, add, "add");
            Set<String> toRemove = normalise(l, remove, "remove");
            Set<String> rangesAdd = canonical(l, addRanges, "addRanges");
            Set<String> rangesRemove = canonical(l, removeRanges, "removeRanges");
            for (String k : toAdd)
                if (toRemove.contains(k))
                    throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "a value normalises to a key named in both "
                            + "'add' and 'remove'");
            for (String k : rangesAdd)
                if (rangesRemove.contains(k))
                    throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "a range is named in both 'addRanges' "
                            + "and 'removeRanges'");
            // An add is a no-op when it would not change the entry: already there with the same expiry, or already
            // PERMANENT (an expiring add never shortens a permanent entry).
            toAdd.removeIf(k -> l.members().contains(k) && unchanged(l.expiresAt().get(k), expiresAt));
            rangesAdd.removeIf(k -> l.ranges().contains(k) && unchanged(l.rangeExpiresAt().get(k), expiresAt));
            toRemove.retainAll(l.members());
            rangesRemove.retainAll(l.ranges());
            int changed = toAdd.size() + toRemove.size() + rangesAdd.size() + rangesRemove.size();

            boolean reviewAfter = false;
            if (changed > 0) {
                // D-P5: an add-only change whose every entry expires within 24 h applies at once and is reviewed after.
                boolean shortLived = toRemove.isEmpty() && rangesRemove.isEmpty() && expiresAt != null
                        && !Instant.parse(expiresAt).isAfter(now.plus(SHORT_LIVED));
                if (shortLived) {
                    reviewAfter = PendingChanges.governs(root, KIND);
                } else {
                    Map<String, Object> proposed = new LinkedHashMap<>();
                    proposed.put("add", maskedList(root, log, l, toAdd));
                    proposed.put("remove", maskedList(root, log, l, toRemove));
                    proposed.put("addRanges", maskedList(root, log, l, rangesAdd));
                    proposed.put("removeRanges", maskedList(root, log, l, rangesRemove));
                    proposed.put("expiresAt", expiresAt);
                    PendingChanges.hold(api, ex, KIND, id, proposed, heldBase(l));
                }
            }

            Map<String, Object> approved = approvedBy(ex);
            if (!toAdd.isEmpty()) {
                head = append(log, head, ex, reason, "list.member.added", id, payload("keys", toAdd, expiresAt, approved));
                emit(ex, id, "list.member.added", toAdd.size(), 0, head.headSeq());
            }
            if (!toRemove.isEmpty()) {
                head = append(log, head, ex, reason, "list.member.removed", id, payload("keys", toRemove, null, approved));
                emit(ex, id, "list.member.removed", 0, toRemove.size(), head.headSeq());
            }
            if (!rangesAdd.isEmpty()) {
                head = append(log, head, ex, reason, "list.range.added", id, payload("ranges", rangesAdd, expiresAt, approved));
                emit(ex, id, "list.range.added", rangesAdd.size(), 0, head.headSeq());
            }
            if (!rangesRemove.isEmpty()) {
                head = append(log, head, ex, reason, "list.range.removed", id, payload("ranges", rangesRemove, null, approved));
                emit(ex, id, "list.range.removed", 0, rangesRemove.size(), head.headSeq());
            }
            EntityRegistry.EntityList after = current(head, id);
            Map<String, Object> out = render(root, log, head, after, head.headSeq());
            out.put("changed", changed);
            if (reviewAfter) out.put("reviewAfter", true);
            out.put("sidecar", changed == 0 ? "unchanged" : EntityListSidecar.write(api.dataRoot(), after, head.facts()));
            return out;
        }
    }

    private Object retire(ApiContext api, HttpExchange ex, String id, Map<String, Object> body) throws IOException {
        Path root = WriteGates.requireWriteRoot(api, "entity list retire");
        String reason = reason(body);
        EntityFactLog log = new EntityFactLog(root);
        synchronized (log.lock()) {
            EntityFactLog.Log head = read(log);
            EntityRegistry.EntityList l = EntityRegistry.fold(head.facts(), head.headSeq()).get(id);
            if (l == null) throw notFound(id, "");
            if (l.retired()) throw new ApiException(409, ErrorCodes.CONFLICT, "entity list '" + id + "' is already retired");
            PendingChanges.hold(api, ex, KIND, id, null, heldBase(l));   // retiring a block list loosens control
            Map<String, Object> approved = approvedBy(ex);
            head = append(log, head, ex, reason, "list.retired", id,
                    approved == null ? Map.of() : Map.of("approvedChange", approved));
            emit(ex, id, "list.retired", 0, 0, head.headSeq());
            Map<String, Object> out = render(root, log, head, current(head, id), head.headSeq());
            out.put("sidecar", EntityListSidecar.write(api.dataRoot(), current(head, id), head.facts()));
            return out;
        }
    }

    // ── ASSURE-ENTITY-LISTS-1 helpers ──────────────────────────────────────────────────────────────────────

    /** The approval-policy kind of an Entity List change. */
    static final String KIND = "entity-list";
    private static final java.time.Duration SHORT_LIVED = java.time.Duration.ofHours(24);

    /**
     * What a held change replaces: the list at its last fact. Any later change to the list moves {@code lastSeq},
     * so approving a stale proposal is refused (409) by the hold's base check.
     */
    private static Map<String, Object> heldBase(EntityRegistry.EntityList l) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("listId", l.id());
        m.put("purpose", l.purpose());
        m.put("lastSeq", l.lastSeq());
        return m;
    }

    /** The Pending Change that approved this replay, as the fact records it ({@code null} when not a replay). */
    private static Map<String, Object> approvedBy(HttpExchange ex) {
        if (!(ApiContext.attr(ex, ApiContext.ATTR_APPROVED_CHANGE) instanceof Map<?, ?> pc)) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", pc.get("id"));
        m.put("author", pc.get("author"));
        return m;
    }

    private static Map<String, Object> payload(String field, Set<String> entries, String expiresAt,
                                               Map<String, Object> approved) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(field, List.copyOf(entries));
        if (expiresAt != null) m.put("expiresAt", expiresAt);
        if (approved != null) m.put("approvedChange", approved);
        return m;
    }

    private static boolean unchanged(String currentExpiry, String requested) {
        if (currentExpiry == null) return true;   // permanent: a re-add keeps it and an expiring add never shortens it
        return currentExpiry.equals(requested);
    }

    /** {@code expiresAt}: absent = permanent; else an ISO-8601 instant after now (a past one would add nothing live). */
    private static String expiresAt(Map<String, Object> body, Instant now) {
        Object raw = body.get("expiresAt");
        if (raw == null) return null;
        Instant at;
        try {
            at = Instant.parse(String.valueOf(raw));
        } catch (java.time.format.DateTimeParseException e) {
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'expiresAt' must be an ISO-8601 instant "
                    + "(e.g. 2026-10-01T00:00:00Z)");
        }
        if (!at.isAfter(now))
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'expiresAt' must be in the future");
        return at.toString();
    }

    private static List<Object> rangeSpecs(Map<String, Object> body, String key) {
        Object raw = body.get(key);
        if (raw == null) return List.of();
        if (!(raw instanceof List<?> l))
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'" + key + "' must be a list of range entries");
        return new ArrayList<>(l);
    }

    /** Range specs ({@code {prefix} | {from, to} | {cidr}}) or canonical strings, canonicalised under the sealed normaliser. */
    private static Set<String> canonical(EntityRegistry.EntityList l, List<Object> specs, String key) {
        Set<String> out = new TreeSet<>();
        for (int i = 0; i < specs.size(); i++) {
            Object o = specs.get(i);
            try {
                if (o instanceof Map<?, ?> m) out.add(EntityListEntries.canonical(m, l.normaliser()));
                else if (o instanceof String str && str.length() <= MAX_VALUE_LENGTH) out.add(EntityListEntries.parse(str).canonical());
                else throw new IllegalArgumentException("must be a range object or a canonical range string");
            } catch (IllegalArgumentException | IndexOutOfBoundsException e) {
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'" + key + "[" + i + "]': "
                        + (e instanceof IllegalArgumentException ? e.getMessage() : "malformed range"));
            }
        }
        return out;
    }

    /** Entries as the Pending Change shows them to the approver: masked exactly as the list renders. */
    private static List<String> maskedList(Path root, EntityFactLog log, EntityRegistry.EntityList l, Set<String> entries)
            throws IOException {
        List<String> out = new ArrayList<>(entries);
        if (!out.isEmpty() && masked(root, l)) {
            byte[] key = MaskTokens.key(log.directory());
            out.replaceAll(k -> MaskTokens.token(key, k));
            out.sort(null);
        }
        return out;
    }

    // ── helpers ────────────────────────────────────────────────────────────────────────────────────────

    private static EntityRegistry.EntityList current(EntityFactLog.Log head, String id) {
        return EntityRegistry.fold(head.facts(), head.headSeq()).get(id);
    }

    private static ApiException notFound(String id, String at) {
        return new ApiException(404, ErrorCodes.NOT_FOUND, "entity list '" + id + "' not found" + at);
    }

    /** {@code add} / {@code remove}: absent = none; otherwise a list of strings. */
    private static List<String> values(Map<String, Object> body, String key) {
        Object raw = body.get(key);
        if (raw == null) return List.of();
        if (!(raw instanceof List<?> l))
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'" + key + "' must be a list of strings");
        List<String> out = new ArrayList<>(l.size());
        for (Object o : l) {
            if (!(o instanceof String s) || s.length() > MAX_VALUE_LENGTH)
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'" + key + "' must be a list of strings of at most "
                        + MAX_VALUE_LENGTH + " characters");
            out.add(s);
        }
        return out;
    }

    /** Normalise with the list's SEALED normaliser (D-M9), never the Entity Type's current one. */
    private static Set<String> normalise(EntityRegistry.EntityList l, List<String> raw, String key) {
        Set<String> out = new TreeSet<>();
        for (int i = 0; i < raw.size(); i++) {
            String k = EntityTypes.normalise(l.normaliser(), raw.get(i));
            if (k.isEmpty())
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'" + key + "[" + i + "]' is empty after the "
                        + l.entityType() + " list's sealed normaliser (" + l.normaliser() + ")");
            out.add(k);
        }
        return out;
    }

    private static List<String> typeIds(Path root) {
        return LinkAnalysisSettings.forRoot(root).effectiveEntityTypes().stream().map(EntityTypes.EntityType::id).toList();
    }

    private static Map<String, Object> summary(EntityRegistry.EntityList l) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", l.id());
        m.put("title", l.title());
        m.put("purpose", l.purpose());
        m.put("entityType", l.entityType());
        m.put("normaliser", l.normaliser());
        m.put("size", l.members().size());
        m.put("rangeCount", l.ranges().size());
        m.put("retired", l.retired());
        m.put("createdAt", l.createdAt());
        m.put("createdBy", l.createdBy());
        m.put("lastSeq", l.lastSeq());
        return m;
    }

    /** Summary + members rendered through the Space's {@code maskingMode} + {@code atSeq} + the hash of that fact. */
    private static Map<String, Object> render(Path root, EntityFactLog log, EntityFactLog.Log facts,
                                              EntityRegistry.EntityList l, long atSeq) throws IOException {
        Map<String, Object> out = summary(l);
        List<String> members = new ArrayList<>(l.members());
        if (!members.isEmpty() && masked(root, l)) {
            byte[] key = MaskTokens.key(log.directory());
            members.replaceAll(k -> MaskTokens.token(key, k));
            members.sort(null);
        }
        out.put("members", members);
        // ASSURE-ENTITY-LISTS-1: the range entries and every expiring entry, masked like the members.
        boolean masked = masked(root, l);
        byte[] key = masked && (!l.ranges().isEmpty() || !l.expiresAt().isEmpty()) ? MaskTokens.key(log.directory()) : null;
        List<String> ranges = new ArrayList<>(l.ranges());
        if (masked) ranges.replaceAll(k -> MaskTokens.token(key, k));
        ranges.sort(null);
        out.put("ranges", ranges);
        Instant now = Instant.now();
        List<Map<String, Object>> expiring = new ArrayList<>();
        l.expiresAt().forEach((k, at) -> expiring.add(expiring(masked ? MaskTokens.token(key, k) : k, "key", at, now)));
        l.rangeExpiresAt().forEach((k, at) -> expiring.add(expiring(masked ? MaskTokens.token(key, k) : k, "range", at, now)));
        expiring.sort(java.util.Comparator.comparing(m -> String.valueOf(m.get("entry"))));
        out.put("expiring", expiring);
        out.put("atSeq", atSeq);
        out.put("headHash", atSeq == 0 ? "" : facts.facts().get((int) atSeq - 1).hash());
        return out;
    }

    private static Map<String, Object> expiring(String entry, String kind, String at, Instant now) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("entry", entry);
        m.put("kind", kind);
        m.put("expiresAt", at);
        m.put("expired", !Instant.parse(at).isAfter(now));
        return m;
    }

    /** Best-effort audit (LA-04 pattern), emitted only AFTER the fact is sealed. Counts, never keys. */
    private static void emit(HttpExchange ex, String listId, String kind, int added, int removed, long seq) {
        emit(ApiContext.actor(ex), ApiContext.actorType(ex), listId, kind, added, removed, seq);
    }

    static void emit(String actor, String actorType, String listId, String kind, int added, int removed, long seq) {
        try {
            Event.Builder b = Event.builder(EventType.ENTITY_LIST_CHANGED).source("inv")
                    .message("entity.list.changed — " + listId + " " + kind)
                    .actor(actor).actorType(actorType)
                    .action("entity.list.changed").actionCategory("analysis")
                    .attr("listId", listId).attr("kind", kind).attr("added", added).attr("removed", removed)
                    .attr("seq", seq);
            EventLog.current().emit(b);
        } catch (RuntimeException ignored) {
            // best effort — the fact is already sealed and that is what matters
        }
    }
}
