package com.gamma.geolink;

import com.gamma.control.ApiContext;
import com.gamma.control.ApiException;
import com.gamma.control.EntityTypes;
import com.gamma.control.ErrorCodes;
import com.gamma.control.LinkAnalysisSettings;
import com.gamma.control.RouteModule;
import com.gamma.control.WriteGates;
import com.gamma.event.Event;
import com.gamma.event.EventLog;
import com.gamma.event.EventType;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * <b>Entity Lists</b> (LA-17 slice 1, {@code docs/superpower/link-analysis-entity-model-design.md} §4.3 and the
 * binding wire contract §4.3.1): named, Space-scoped sets of typed Entity keys, persisted as facts in the Space's
 * {@link EntityFactLog} and read as a fold over it ({@link EntityRegistry}).
 *
 * <ul>
 *   <li>{@code GET /inv/entity-lists} → {@code {lists: [summary…], headSeq, headHash}} (retired lists included).</li>
 *   <li>{@code GET /inv/entity-lists/{id}?at=<seq>} → summary + {@code {members, atSeq, headHash}}.</li>
 *   <li>{@code POST /inv/entity-lists} {@code {id?, title, purpose, entityType, reason}} → 201 + the list.</li>
 *   <li>{@code POST /inv/entity-lists/{id}/members} {@code {add?, remove?, reason}} → 200 + the list + {@code changed}.</li>
 *   <li>{@code POST /inv/entity-lists/{id}/retire} {@code {reason}} → 200 + the list.</li>
 * </ul>
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
 * list, {@code none} none. The token is {@link EntityMasking}'s HMAC, under one key per Space fact log. Members are
 * sorted AFTER rendering, so the order of masked tokens says nothing about the raw keys.
 */
public final class EntityListRoutes implements RouteModule {

    private static final Pattern LIST_ID = Pattern.compile("^[a-z0-9][a-z0-9_-]{0,63}$");
    private static final List<String> PURPOSES = List.of("allow", "block", "watch", "exclusion");
    private static final int MAX_VALUES = 5_000;
    private static final int MAX_REASON = 1_000;
    private static final int MAX_TITLE = 200;
    private static final int MAX_VALUE_LENGTH = 512;

    @Override
    public void register(ApiContext api) {
        // ⚠ String LITERALS on purpose — CapabilityManifestTest's scanner matches only a literal argument.
        api.get("/inv/entity-lists", (e, m) -> list(api));
        api.get("/inv/entity-lists/([^/]+)", (e, m) -> one(api, e, m.group(1)));
        api.post("/inv/entity-lists", ApiContext.withCapability("canManageIncidents",
                (e, m) -> create(api, e, api.body(e))));
        api.post("/inv/entity-lists/([^/]+)/members", ApiContext.withCapability("canManageIncidents",
                (e, m) -> members(api, e, m.group(1), api.body(e))));
        api.post("/inv/entity-lists/([^/]+)/retire", ApiContext.withCapability("canManageIncidents",
                (e, m) -> retire(api, e, m.group(1), api.body(e))));
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
        if (entityType == null || type(root, entityType).isEmpty())
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
            head = append(log, head, ex, reason, "list.created", id, payload);
            emit(ex, id, "list.created", 0, 0, head.headSeq());
            created = render(root, log, head, current(head, id), head.headSeq());
        }
        return ApiContext.respondJson(ex, 201, created);   // outside the lock: a slow client must not stall writers
    }

    private Object members(ApiContext api, HttpExchange ex, String id, Map<String, Object> body) throws IOException {
        Path root = WriteGates.requireWriteRoot(api, "entity list members");
        String reason = reason(body);
        List<String> add = values(body, "add");
        List<String> remove = values(body, "remove");
        if (add.size() + remove.size() > MAX_VALUES)
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "at most " + MAX_VALUES
                    + " values per call (add + remove), got " + (add.size() + remove.size()));

        EntityFactLog log = new EntityFactLog(root);
        synchronized (log.lock()) {
            EntityFactLog.Log head = read(log);
            EntityRegistry.EntityList l = EntityRegistry.fold(head.facts(), head.headSeq()).get(id);
            if (l == null) throw notFound(id, "");
            if (l.retired()) throw new ApiException(409, ErrorCodes.CONFLICT, "entity list '" + id + "' is retired");
            EntityTypes.EntityType t = type(root, l.entityType()).orElseThrow(() -> new ApiException(409, ErrorCodes.CONFLICT,
                    "entity list '" + id + "' is of Entity Type '" + l.entityType() + "', which is no longer in force"));
            Set<String> toAdd = normalise(t, add, "add");
            Set<String> toRemove = normalise(t, remove, "remove");
            for (String k : toAdd)
                if (toRemove.contains(k))
                    throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "a value normalises to a key named in both "
                            + "'add' and 'remove'");
            toAdd.removeAll(l.members());
            toRemove.retainAll(l.members());

            if (!toAdd.isEmpty()) {
                head = append(log, head, ex, reason, "list.member.added", id, Map.of("keys", List.copyOf(toAdd)));
                emit(ex, id, "list.member.added", toAdd.size(), 0, head.headSeq());
            }
            if (!toRemove.isEmpty()) {
                head = append(log, head, ex, reason, "list.member.removed", id, Map.of("keys", List.copyOf(toRemove)));
                emit(ex, id, "list.member.removed", 0, toRemove.size(), head.headSeq());
            }
            Map<String, Object> out = render(root, log, head, current(head, id), head.headSeq());
            out.put("changed", toAdd.size() + toRemove.size());
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
            head = append(log, head, ex, reason, "list.retired", id, Map.of());
            emit(ex, id, "list.retired", 0, 0, head.headSeq());
            return render(root, log, head, current(head, id), head.headSeq());
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────────────────────────────────

    private static EntityFactLog.Log read(EntityFactLog log) throws IOException {
        try {
            return log.read();
        } catch (EntityFactLog.BrokenChainException broken) {
            throw new ApiException(500, ErrorCodes.INTEGRITY_VIOLATION, broken.getMessage());
        }
    }

    private static EntityFactLog.Log append(EntityFactLog log, EntityFactLog.Log head, HttpExchange ex, String reason,
                                            String kind, String id, Map<String, Object> payload) throws IOException {
        try {
            return log.append(head, ApiContext.actor(ex), reason, kind, id, payload);
        } catch (FileAlreadyExistsException raced) {
            // Another process appended the same seq between our read and our move — nothing was overwritten.
            throw new ApiException(409, ErrorCodes.CONFLICT, "the identity fact log moved on concurrently; retry");
        }
    }

    private static EntityRegistry.EntityList current(EntityFactLog.Log head, String id) {
        return EntityRegistry.fold(head.facts(), head.headSeq()).get(id);
    }

    private static ApiException notFound(String id, String at) {
        return new ApiException(404, ErrorCodes.NOT_FOUND, "entity list '" + id + "' not found" + at);
    }

    private static String reason(Map<String, Object> body) {
        String r = ApiContext.str(body, "reason");
        if (r == null || r.length() > MAX_REASON)
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "body must include 'reason', 1.." + MAX_REASON
                    + " characters");
        return r.trim();
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

    private static Set<String> normalise(EntityTypes.EntityType t, List<String> raw, String key) {
        Set<String> out = new TreeSet<>();
        for (int i = 0; i < raw.size(); i++) {
            String k = EntityTypes.normalise(t.normaliser(), raw.get(i));
            if (k.isEmpty())
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'" + key + "[" + i + "]' is empty after the "
                        + t.id() + " normaliser (" + t.normaliser() + ")");
            out.add(k);
        }
        return out;
    }

    private static Optional<EntityTypes.EntityType> type(Path root, String id) {
        return LinkAnalysisSettings.forRoot(root).effectiveEntityTypes().stream().filter(t -> t.id().equals(id)).findFirst();
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
        m.put("size", l.members().size());
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
            byte[] key = EntityMasking.key(log.directory());
            members.replaceAll(k -> EntityMasking.token(key, k));
            members.sort(null);
        }
        out.put("members", members);
        out.put("atSeq", atSeq);
        out.put("headHash", atSeq == 0 ? "" : facts.facts().get((int) atSeq - 1).hash());
        return out;
    }

    private static boolean masked(Path root, EntityRegistry.EntityList l) {
        return switch (LinkAnalysisSettings.forRoot(root).effectiveMaskingMode()) {
            case "none" -> false;
            case "all" -> true;
            default -> type(root, l.entityType()).map(EntityTypes.EntityType::masked).orElse(true);
        };
    }

    /** Best-effort audit (LA-04 pattern), emitted only AFTER the fact is sealed. Counts, never keys. */
    private static void emit(HttpExchange ex, String listId, String kind, int added, int removed, long seq) {
        try {
            Event.Builder b = Event.builder(EventType.ENTITY_LIST_CHANGED).source("inv")
                    .message("entity.list.changed — " + listId + " " + kind)
                    .actor(ApiContext.actor(ex)).actorType(ApiContext.actorType(ex))
                    .action("entity.list.changed").actionCategory("analysis")
                    .attr("listId", listId).attr("kind", kind).attr("added", added).attr("removed", removed)
                    .attr("seq", seq);
            EventLog.current().emit(b);
        } catch (RuntimeException ignored) {
            // best effort — the fact is already sealed and that is what matters
        }
    }
}
