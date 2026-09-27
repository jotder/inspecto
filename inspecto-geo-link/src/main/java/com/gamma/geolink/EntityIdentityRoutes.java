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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * <b>Analyst identity resolution</b> (LA-17 slice 2, {@code docs/superpower/link-analysis-entity-model-design.md}
 * §8.1, D-M10): an analyst asserts that two typed Entity keys are one identity, or retracts such an assertion. Both
 * are Identity Facts in the Space's {@link EntityFactLog} — the same log, chain and lock as the Entity Lists — and the
 * resolved groups are a union-find fold over the live assertions ({@link EntityRegistry#resolve}).
 *
 * <ul>
 *   <li>{@code GET /inv/entity-identities?at=<seq>} → {@code {groups: [group…], atSeq, headSeq, headHash}}.</li>
 *   <li>{@code GET /inv/entity-identities/group?key=<typed key>&at=<seq>} → {@code {group, atSeq, headHash}}; a key in
 *       no live assertion resolves to itself (a one-member group with no assertions).</li>
 *   <li>{@code POST /inv/entity-identities} {@code {a, b, reason}} → 201 {@code {assertion, group, atSeq, headHash}}.</li>
 *   <li>{@code POST /inv/entity-identities/{seq}/retract} {@code {reason}} → 200 {@code {retracted, groups, atSeq, headHash}}
 *       — {@code groups} are the groups of the assertion's two keys afterwards (one, or two when it split).</li>
 * </ul>
 * A group is {@code {id, members[], assertions[{seq, a, b, via, actor, at, reason}]}}: the id is the smallest member
 * key, and every joining assertion is listed so a merge never hides which identifier matched.
 *
 * <p><b>Gates.</b> Writes: {@code canManageIncidents} (as the Entity List writes) → no write root 503 → body 422 (reason;
 * {@code a}/{@code b} typed {@code <type>:<value>}, the type in force, the value non-empty after the type's normaliser;
 * the two keys differ after normalising) → retract: unknown or already-retracted assertion 409 → append under the log's
 * JVM lock. Keys are normalised with the type's normaliser AT ASSERT TIME and sealed in the fact, so a later settings
 * change never re-keys an assertion (D-M9's stance). Reads need Space access and a write root (503), as the list reads.
 *
 * <p><b>Masking</b> at render, per member key, as {@link EntityListRoutes}: under {@code typed} a key whose Entity Type is
 * {@code masked: true} — or is no longer in force, failing closed — is masked; {@code all} masks every key; {@code none}
 * none. A group id is rendered like any member. Same token and per-log key as the list members.
 *
 * <p>⚠ {@code key=} is a query parameter, so a {@code +} must be sent as {@code %2B} (a raw {@code +} decodes to a space;
 * see {@link #rawQuery} for why {@code ApiContext.query} could not be used).
 * The lookup is exact: pass the typed, normalised key as this API returns it.
 */
public final class EntityIdentityRoutes implements RouteModule {

    static final String ASSERTED = "identity.asserted";
    static final String RETRACTED = "identity.retracted";
    private static final int MAX_KEY_LENGTH = 512;

    @Override
    public void register(ApiContext api) {
        // ⚠ String LITERALS on purpose — CapabilityManifestTest's scanner matches only a literal argument.
        api.get("/inv/entity-identities", (e, m) -> groups(api, e));
        api.get("/inv/entity-identities/group", (e, m) -> group(api, e));
        api.post("/inv/entity-identities", ApiContext.withCapability("canManageIncidents",
                (e, m) -> assertIdentity(api, e, api.body(e))));
        api.post("/inv/entity-identities/([^/]+)/retract", ApiContext.withCapability("canManageIncidents",
                (e, m) -> retract(api, e, m.group(1), api.body(e))));
    }

    // ── reads ──────────────────────────────────────────────────────────────────────────────────────────

    private Object groups(ApiContext api, HttpExchange ex) throws IOException {
        Path root = WriteGates.requireWriteRoot(api, "entity identities");
        EntityFactLog log = new EntityFactLog(root);
        EntityFactLog.Log facts = EntityListRoutes.read(log);
        long atSeq = at(ex, facts);
        Renderer r = new Renderer(root, log, facts, atSeq);
        List<Map<String, Object>> out = new ArrayList<>();
        for (EntityRegistry.Group g : EntityRegistry.resolve(facts.facts(), atSeq).values()) out.add(r.group(g));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("groups", out);
        body.put("atSeq", atSeq);
        body.put("headSeq", facts.headSeq());
        body.put("headHash", hashAt(facts, atSeq));
        return body;
    }

    private Object group(ApiContext api, HttpExchange ex) throws IOException {
        Path root = WriteGates.requireWriteRoot(api, "entity identities");
        String key = ApiContext.query(ex, "key");
        if (key == null || key.isBlank() || key.indexOf(':') <= 0)
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'key' must be a typed Entity key <type>:<value>");
        EntityFactLog log = new EntityFactLog(root);
        EntityFactLog.Log facts = EntityListRoutes.read(log);
        long atSeq = at(ex, facts);
        EntityRegistry.Group g = groupOf(facts, atSeq, key);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("group", new Renderer(root, log, facts, atSeq).group(g));
        body.put("atSeq", atSeq);
        body.put("headHash", hashAt(facts, atSeq));
        return body;
    }

    // ── writes ─────────────────────────────────────────────────────────────────────────────────────────

    private Object assertIdentity(ApiContext api, HttpExchange ex, Map<String, Object> body) throws IOException {
        Path root = WriteGates.requireWriteRoot(api, "entity identity assert");
        String reason = EntityListRoutes.reason(body);
        String a = typedKey(root, body, "a");
        String b = typedKey(root, body, "b");
        if (a.equals(b))
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'a' and 'b' are the same key after normalising "
                    + "— an identity cannot be asserted with itself");

        EntityFactLog log = new EntityFactLog(root);
        Map<String, Object> out;
        synchronized (log.lock()) {
            EntityFactLog.Log head = EntityListRoutes.read(log);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("a", a);
            payload.put("b", b);
            payload.put("via", "analyst");   // D-M10: dataset:<id>@<fingerprint> is reserved for the import cut
            head = EntityListRoutes.append(log, head, ex, reason, ASSERTED, null, payload);
            EntityRegistry.Group g = groupOf(head, head.headSeq(), a);
            emit(ex, ASSERTED, head.headSeq(), head.headSeq(), g.members().size());
            Renderer r = new Renderer(root, log, head, head.headSeq());
            out = new LinkedHashMap<>();
            out.put("assertion", r.assertion(EntityRegistry.assertions(head.facts(), head.headSeq()).get(head.headSeq())));
            out.put("group", r.group(g));
            out.put("atSeq", head.headSeq());
            out.put("headHash", head.headHash());
        }
        return ApiContext.respondJson(ex, 201, out);   // outside the lock: a slow client must not stall writers
    }

    private Object retract(ApiContext api, HttpExchange ex, String rawSeq, Map<String, Object> body) throws IOException {
        Path root = WriteGates.requireWriteRoot(api, "entity identity retract");
        String reason = EntityListRoutes.reason(body);
        long seq;
        try {
            seq = Long.parseLong(rawSeq);
        } catch (NumberFormatException e) {
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "the assertion must be named by its log seq (an "
                    + "integer >= 1), got '" + rawSeq + "'");
        }
        if (seq < 1) throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "the assertion seq must be >= 1");

        EntityFactLog log = new EntityFactLog(root);
        synchronized (log.lock()) {
            EntityFactLog.Log head = EntityListRoutes.read(log);
            EntityRegistry.Assertion x = EntityRegistry.assertions(head.facts(), head.headSeq()).get(seq);
            if (x == null)
                throw new ApiException(409, ErrorCodes.CONFLICT, "no identity assertion at seq " + seq);
            if (!x.live())
                throw new ApiException(409, ErrorCodes.CONFLICT, "the identity assertion at seq " + seq
                        + " is already retracted (by seq " + x.retractedBy() + ")");
            head = EntityListRoutes.append(log, head, ex, reason, RETRACTED, null, Map.of("assertionSeq", seq));
            EntityRegistry.Group ga = groupOf(head, head.headSeq(), x.a());
            EntityRegistry.Group gb = groupOf(head, head.headSeq(), x.b());
            emit(ex, RETRACTED, head.headSeq(), seq, ga.members().size());
            Renderer r = new Renderer(root, log, head, head.headSeq());
            List<Map<String, Object>> groups = new ArrayList<>();
            groups.add(r.group(ga));
            if (!ga.id().equals(gb.id())) groups.add(r.group(gb));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("retracted", seq);
            out.put("groups", groups);
            out.put("atSeq", head.headSeq());
            out.put("headHash", head.headHash());
            return out;
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────────────────────────────────

    /** {@code body[field]} as a typed key: {@code <type>:<value>}, the type in force, the value normalised by its rule. */
    private static String typedKey(Path root, Map<String, Object> body, String field) {
        Object raw = body.get(field);
        if (!(raw instanceof String s) || s.length() > MAX_KEY_LENGTH)
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "body must include '" + field + "', a typed Entity "
                    + "key <type>:<value> of at most " + MAX_KEY_LENGTH + " characters");
        int colon = s.indexOf(':');
        if (colon <= 0)
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'" + field + "' must be a typed Entity key "
                    + "<type>:<value> — an untyped key cannot be resolved");
        String type = s.substring(0, colon);
        EntityTypes.EntityType t = EntityListRoutes.type(root, type).orElseThrow(() -> new ApiException(422,
                ErrorCodes.CONFIG_VALIDATION_FAILED, "'" + field + "' names Entity Type '" + type + "', which is not in force "
                        + LinkAnalysisSettings.forRoot(root).effectiveEntityTypes().stream().map(EntityTypes.EntityType::id).toList()));
        String value = EntityTypes.normalise(t.normaliser(), s.substring(colon + 1));
        if (value.isEmpty())
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'" + field + "' is empty after the " + type
                    + " normaliser (" + t.normaliser() + ")");
        return type + ":" + value;
    }

    /** The group holding {@code key} as of {@code atSeq}; a key in no live assertion resolves to itself. */
    private static EntityRegistry.Group groupOf(EntityFactLog.Log facts, long atSeq, String key) {
        for (EntityRegistry.Group g : EntityRegistry.resolve(facts.facts(), atSeq).values())
            if (g.members().contains(key)) return g;
        return new EntityRegistry.Group(key, new TreeSet<>(List.of(key)), new TreeSet<>());
    }

    private static long at(HttpExchange ex, EntityFactLog.Log facts) {
        String rawAt = ApiContext.query(ex, "at");
        if (rawAt == null) return facts.headSeq();
        long at;
        try {
            at = Long.parseLong(rawAt.trim());
        } catch (NumberFormatException e) {
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'at' must be a log seq (an integer >= 0)");
        }
        if (at < 0) throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'at' must be >= 0");
        if (at > facts.headSeq())
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'at' " + at + " is beyond the log head "
                    + facts.headSeq());
        return at;
    }

    private static String hashAt(EntityFactLog.Log facts, long atSeq) {
        return atSeq == 0 ? "" : facts.facts().get((int) atSeq - 1).hash();
    }

    /** Renders groups and assertions with every key passed through the Space's {@code maskingMode}. */
    private static final class Renderer {
        private final Path root;
        private final EntityFactLog log;
        private final Map<Long, EntityRegistry.Assertion> assertions;
        private final String mode;
        private byte[] key;

        Renderer(Path root, EntityFactLog log, EntityFactLog.Log facts, long atSeq) {
            this.root = root;
            this.log = log;
            this.assertions = EntityRegistry.assertions(facts.facts(), atSeq);
            this.mode = LinkAnalysisSettings.forRoot(root).effectiveMaskingMode();
        }

        Map<String, Object> group(EntityRegistry.Group g) throws IOException {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", render(g.id()));
            SortedSet<String> members = new TreeSet<>();   // sorted AFTER rendering: token order says nothing
            for (String k : g.members()) members.add(render(k));
            m.put("members", new ArrayList<>(members));
            List<Map<String, Object>> joins = new ArrayList<>();
            for (long s : g.assertions()) joins.add(assertion(assertions.get(s)));
            m.put("assertions", joins);
            return m;
        }

        Map<String, Object> assertion(EntityRegistry.Assertion x) throws IOException {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("seq", x.seq());
            m.put("a", render(x.a()));
            m.put("b", render(x.b()));
            m.put("via", x.via());
            m.put("actor", x.actor());
            m.put("at", x.at());
            m.put("reason", x.reason());
            return m;
        }

        private String render(String typedKey) throws IOException {
            if (!masked(typedKey)) return typedKey;
            if (key == null) key = EntityMasking.key(log.directory());
            return EntityMasking.token(key, typedKey);
        }

        private boolean masked(String typedKey) {
            return switch (mode) {
                case "none" -> false;
                case "all" -> true;
                default -> EntityListRoutes.type(root, typedKey.substring(0, typedKey.indexOf(':')))
                        .map(EntityTypes.EntityType::masked).orElse(true);
            };
        }
    }

    /** Best-effort audit, emitted only AFTER the fact is sealed. Seqs and a count, never keys. */
    private static void emit(HttpExchange ex, String kind, long seq, long assertionSeq, int groupSize) {
        try {
            Event.Builder b = Event.builder(EventType.ENTITY_IDENTITY_CHANGED).source("inv")
                    .message("entity.identity.changed — " + kind + " " + assertionSeq)
                    .actor(ApiContext.actor(ex)).actorType(ApiContext.actorType(ex))
                    .action("entity.identity.changed").actionCategory("analysis")
                    .attr("kind", kind).attr("seq", seq).attr("assertionSeq", assertionSeq).attr("groupSize", groupSize);
            EventLog.current().emit(b);
        } catch (RuntimeException ignored) {
            // best effort — the fact is already sealed and that is what matters
        }
    }
}
