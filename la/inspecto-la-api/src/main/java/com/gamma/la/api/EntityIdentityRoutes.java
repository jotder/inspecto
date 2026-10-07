package com.gamma.la.api;

import com.gamma.la.core.DatasetProviders;
import com.gamma.la.core.DatasetProvider;
import com.gamma.control.ApiContext;
import com.gamma.control.ApiException;
import com.gamma.control.EntityTypes;
import com.gamma.control.ErrorCodes;
import com.gamma.control.LinkAnalysisSettings;
import com.gamma.control.RouteModule;
import com.gamma.access.WriteGates;
import com.gamma.entitystore.EntityFactLog;
import com.gamma.entitystore.EntityListFacts;
import com.gamma.entitystore.EntityRegistry;
import com.gamma.audit.Event;
import com.gamma.audit.EventLog;
import com.gamma.audit.EventType;
import com.gamma.sql.SqlGuard;
import com.gamma.sql.SqlSandboxPolicy;
import com.gamma.util.DuckDbUtil;
import com.gamma.util.SqlIdent;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * <b>Analyst identity resolution</b> (LA-17 slice 2, {@code docs/archived-documents/plans-archive/link-analysis-entity-model-design.md}
 * §8.1, D-M10): an analyst asserts that two typed Entity keys are one identity, or retracts such an assertion. Both
 * are Identity Facts in the Space's {@link EntityFactLog} — the same log, chain and lock as the Entity Lists — and the
 * resolved groups are a union-find fold over the live assertions ({@link EntityRegistry#resolve}).
 *
 * <ul>
 *   <li>{@code GET /inv/entity-identities?at=<seq>} → {@code {groups: [group…], atSeq, headSeq, headHash}}.</li>
 *   <li>{@code GET /inv/entity-identities/group?key=<typed key>&at=<seq>} → {@code {group, atSeq, headHash}}; a key in
 *       no live assertion resolves to itself (a one-member group with no assertions).</li>
 *   <li>{@code POST /inv/entity-identities} {@code {a, b, reason}} → 201 {@code {assertion, group, atSeq, headHash}}.</li>
 *   <li>{@code POST /inv/entity-identities/import} {@code {dataset, aCol, bCol, aType?, bType?, reason, limit?}} → 201
 *       (200 when nothing new) — bulk assertions from a mapping Dataset; see {@link #importDataset}.</li>
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
 * change never re-keys an assertion (D-M9's stance). Reads need {@code canManageIncidents} too, then a write root (503):
 * under masking a Space-only caller could probe the group read with a guessed RAW key and learn its membership.
 *
 * <p><b>Masking</b> at render, per member key, as {@code EntityListRoutes}: under {@code typed} a key whose Entity Type is
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
    static final int IMPORT_DEFAULT_LIMIT = 1_000;
    static final int IMPORT_MAX_LIMIT = 10_000;
    static final int IMPORT_TIMEOUT_SECONDS = 10;
    private static final Pattern SAFE_IDENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    @Override
    public void register(ApiContext api) {
        // ⚠ String LITERALS on purpose — CapabilityManifestTest's scanner matches only a literal argument.
        // Reads need the write capability too: under masking the group-by-key read is a membership oracle for a
        // guessed RAW key (member count, token), so both identity reads fail closed (review 2026-09-27).
        api.get("/inv/entity-identities", ApiContext.withCapability("canManageIncidents", (e, m) -> groups(api, e)));
        api.get("/inv/entity-identities/group", ApiContext.withCapability("canManageIncidents", (e, m) -> group(api, e)));
        api.post("/inv/entity-identities", ApiContext.withCapability("canManageIncidents",
                (e, m) -> assertIdentity(api, e, api.body(e))));
        api.post("/inv/entity-identities/import", ApiContext.withCapability("canManageIncidents",
                (e, m) -> importDataset(api, e, api.body(e))));
        api.post("/inv/entity-identities/([^/]+)/retract", ApiContext.withCapability("canManageIncidents",
                (e, m) -> retract(api, e, m.group(1), api.body(e))));
    }

    // ── reads ──────────────────────────────────────────────────────────────────────────────────────────

    private Object groups(ApiContext api, HttpExchange ex) throws IOException {
        Path root = WriteGates.requireWriteRoot(api, "entity identities");
        EntityFactLog log = new EntityFactLog(root);
        EntityFactLog.Log facts = EntityListFacts.read(log);
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
        EntityFactLog.Log facts = EntityListFacts.read(log);
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
        String reason = EntityListFacts.reason(body);
        String a = typedKey(root, body, "a");
        String b = typedKey(root, body, "b");
        if (a.equals(b))
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'a' and 'b' are the same key after normalising "
                    + "— an identity cannot be asserted with itself");

        EntityFactLog log = new EntityFactLog(root);
        Map<String, Object> out;
        synchronized (log.lock()) {
            EntityFactLog.Log head = EntityListFacts.read(log);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("a", a);
            payload.put("b", b);
            payload.put("via", "analyst");   // D-M10: dataset:<id>@<fingerprint> is reserved for the import cut
            head = EntityListFacts.append(log, head, ex, reason, ASSERTED, null, payload);
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

    /**
     * {@code POST /inv/entity-identities/import} {@code {dataset, aCol, bCol, aType?, bType?, reason, limit?}} — the
     * mapping-Dataset import cut (design §8.2): every distinct {@code (aCol, bCol)} row of the Dataset with both values
     * non-empty after normalisation becomes one {@code identity.asserted} fact (a NULL or blank value is read, and
     * counted under {@code skipped.empty}, so {@code rowsRead} accounts for every row; plan §5.10), both values normalised with the SAME rule an analyst assertion uses.
     *
     * <p>Gates: capability → write root 503 → body 422 → {@link InvRoutes#relationFor} (unknown or not viewable: the same
     * 404, R3) → both columns against the relation's REAL columns 422 → types 422 (a column's registry classification
     * decides it; a stated type may only agree) → D-U7 four-eyes 403 ({@link InvRoutes#refuseIfSensitive}: there is no
     * Investigation to hold a request) → one bounded read ({@code limit}, default {@value #IMPORT_DEFAULT_LIMIT}, max
     * {@value #IMPORT_MAX_LIMIT}; a {@value #IMPORT_TIMEOUT_SECONDS} s statement timeout; {@code ORDER BY}, so a
     * truncated read is the same rows every time) → append under the log lock.
     *
     * <p><b>Provenance.</b> {@code fingerprint} = SHA-256 of the JSON {@code [dataset, aCol, bCol, rows]} exactly as read
     * (raw values, in order). Every fact carries {@code via: "dataset:<id>@<fingerprint>"} (the form §8.1 reserved) and
     * {@code import: {dataset, aCol, bCol, aType, bType, fingerprint, rowsRead, truncated}}.
     *
     * <p><b>Idempotent.</b> A key pair already asserted — live OR retracted — by an import of the same Dataset and column
     * pair is skipped: the same Dataset state adds nothing (200, {@code imported: 0}), and a re-import never undoes an
     * analyst's retraction. A pair gone from the Dataset is NOT retracted by a later import.
     */
    private Object importDataset(ApiContext api, HttpExchange ex, Map<String, Object> body) throws IOException {
        Path root = WriteGates.requireWriteRoot(api, "entity identity import");
        String reason = EntityListFacts.reason(body);
        String datasetId = ApiContext.str(body, "dataset");
        if (datasetId == null) throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "body must include 'dataset'");
        String aCol = ident(body, "aCol"), bCol = ident(body, "bCol");
        if (aCol.equalsIgnoreCase(bCol))
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'aCol' and 'bCol' must be two different columns");
        int limit = IMPORT_DEFAULT_LIMIT;
        if (body.get("limit") != null) {
            if (!(body.get("limit") instanceof Number n) || n.intValue() < 1 || n.intValue() > IMPORT_MAX_LIMIT)
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'limit' must be an integer 1.." + IMPORT_MAX_LIMIT);
            limit = n.intValue();
        }

        String relationSql = InvRoutes.relationFor(api, ex, root, datasetId);
        List<String> columns = InvRoutes.relationColumns(datasetId, relationSql);
        aCol = realColumn(columns, aCol, datasetId);
        bCol = realColumn(columns, bCol, datasetId);
        Map<String, Map<String, Object>> classified = InvRoutes.columnTypes(root, datasetId, List.of(aCol, bCol));
        EntityTypes.EntityType aType = columnType(root, body, "aType", aCol, classified);
        EntityTypes.EntityType bType = columnType(root, body, "bType", bCol, classified);
        InvRoutes.refuseIfSensitive(root, "an identity import (limit " + limit + ")", limit, limit);

        String qa = SqlIdent.q(aCol), qb = SqlIdent.q(bCol);
        String sql = "SELECT DISTINCT CAST(" + qa + " AS VARCHAR) AS a, CAST(" + qb + " AS VARCHAR) AS b FROM "
                + SqlIdent.q(datasetId) + " ORDER BY 1 NULLS LAST, 2 NULLS LAST";   // a NULL row is read and counted as empty
        if (!SqlGuard.check(sql, datasetId).isEmpty())
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "the import of dataset '" + datasetId
                    + "' failed the SQL safety check");
        DatasetProvider.Result read;
        try {
            read = DatasetProviders.require().run(new DatasetProvider.Request(datasetId, relationSql, sql, limit, 0, List.of(), List.of()),
                    SqlSandboxPolicy.withCaps(null, 0, IMPORT_TIMEOUT_SECONDS));
        } catch (SQLException e) {
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "the import read failed: "
                    + DuckDbUtil.withoutPendingQueryPreamble(e.getMessage()));
        }
        List<List<String>> rows = new ArrayList<>(read.rows().size());
        for (Map<String, Object> row : read.rows())
            rows.add(java.util.Arrays.asList(row.get("a") == null ? null : String.valueOf(row.get("a")),
                    row.get("b") == null ? null : String.valueOf(row.get("b"))));
        String fingerprint = EntityFactLog.sha256(ApiContext.JSON.writeValueAsBytes(List.of(datasetId, aCol, bCol, rows)));
        String via = "dataset:" + datasetId + "@" + fingerprint;

        // Normalise with the types' rules; one key pair (in either order) counts once.
        int empty = 0, self = 0, duplicate = 0;
        Map<String, String[]> pairs = new LinkedHashMap<>();
        for (List<String> row : rows) {
            String ka = row.get(0) == null ? "" : EntityTypes.normalise(aType.normaliser(), row.get(0));
            String kb = row.get(1) == null ? "" : EntityTypes.normalise(bType.normaliser(), row.get(1));
            if (ka.isEmpty() || kb.isEmpty()) { empty++; continue; }
            ka = aType.id() + ":" + ka;
            kb = bType.id() + ":" + kb;
            if (ka.equals(kb)) { self++; continue; }
            if (pairs.putIfAbsent(pairKey(ka, kb), new String[]{ka, kb}) != null) duplicate++;
        }

        Map<String, Object> provenance = new LinkedHashMap<>();
        provenance.put("dataset", datasetId);
        provenance.put("aCol", aCol);
        provenance.put("bCol", bCol);
        provenance.put("aType", aType.id());
        provenance.put("bType", bType.id());
        provenance.put("fingerprint", fingerprint);
        provenance.put("rowsRead", rows.size());
        provenance.put("truncated", read.truncated());

        EntityFactLog log = new EntityFactLog(root);
        Map<String, Object> out = new LinkedHashMap<>();
        int imported = 0, already = 0;
        synchronized (log.lock()) {
            EntityFactLog.Log head = EntityListFacts.read(log);
            long fromSeq = head.headSeq() + 1;
            Set<String> seen = new HashSet<>();   // live OR retracted: a retraction sticks
            for (EntityFactLog.Fact f : head.facts())
                if (ASSERTED.equals(f.body().get("kind")) && f.body().get("import") instanceof Map<?, ?> imp
                        && datasetId.equals(imp.get("dataset")) && aCol.equals(imp.get("aCol")) && bCol.equals(imp.get("bCol")))
                    seen.add(pairKey(String.valueOf(f.body().get("a")), String.valueOf(f.body().get("b"))));
            List<Long> seqs = new ArrayList<>();
            for (Map.Entry<String, String[]> p : pairs.entrySet()) {
                if (seen.contains(p.getKey())) { already++; continue; }
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("a", p.getValue()[0]);
                payload.put("b", p.getValue()[1]);
                payload.put("via", via);
                payload.put("import", provenance);
                head = EntityListFacts.append(log, head, ex, reason, ASSERTED, null, payload);
                seqs.add(head.headSeq());
                imported++;
            }
            if (!seqs.isEmpty()) {
                Map<String, EntityRegistry.Group> groups = EntityRegistry.resolve(head.facts(), head.headSeq());
                for (long seq : seqs) {
                    int size = 2;
                    for (EntityRegistry.Group g : groups.values()) if (g.assertions().contains(seq)) size = g.members().size();
                    emit(ex, ASSERTED, seq, seq, size);
                }
            }
            Map<String, Object> skipped = new LinkedHashMap<>();
            skipped.put("alreadyAsserted", already);
            skipped.put("duplicate", duplicate);
            skipped.put("empty", empty);
            skipped.put("self", self);
            out.put("imported", imported);
            out.put("skipped", skipped);
            out.put("rowsRead", rows.size());
            out.put("truncated", read.truncated());
            out.put("dataset", datasetId);
            out.put("columns", Map.of("a", aCol, "b", bCol));
            out.put("types", Map.of("a", aType.id(), "b", bType.id()));
            out.put("fingerprint", fingerprint);
            out.put("via", via);
            out.put("fences", Map.of("limit", limit, "timeoutMs", IMPORT_TIMEOUT_SECONDS * 1000));
            out.put("fromSeq", imported == 0 ? null : fromSeq);
            out.put("atSeq", head.headSeq());
            out.put("headHash", head.headHash());
        }
        return ApiContext.respondJson(ex, imported == 0 ? 200 : 201, out);   // counts only: no key is echoed
    }

    private static String pairKey(String x, String y) {
        return x.compareTo(y) <= 0 ? x + "\n" + y : y + "\n" + x;
    }

    private static String ident(Map<String, Object> body, String key) {
        String v = ApiContext.str(body, key);
        if (v == null) throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "body must include '" + key + "'");
        if (!SAFE_IDENT.matcher(v).matches())
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "unsafe column identifier '" + v + "' for " + key);
        return v;
    }

    /** The relation's own spelling of {@code col}, or 422 when the relation has no such column. */
    private static String realColumn(List<String> columns, String col, String datasetId) {
        for (String c : columns) if (c.equalsIgnoreCase(col)) return c;
        throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'" + col + "' is not a column of dataset '"
                + datasetId + "' " + columns);
    }

    /**
     * An import column's Entity Type: the in-force type claiming its registry classification (D-M6, as the projections
     * type it); a stated {@code field} must agree. An unclassified column needs a stated, in-force type.
     */
    private static EntityTypes.EntityType columnType(Path root, Map<String, Object> body, String field, String col,
                                                     Map<String, Map<String, Object>> classified) {
        String stated = ApiContext.str(body, field);
        String byColumn = classified.containsKey(col) ? String.valueOf(classified.get(col).get("id")) : null;
        if (byColumn != null && stated != null && !stated.equals(byColumn))
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'" + field + "' is '" + stated + "' but column '"
                    + col + "' is classified as Entity Type '" + byColumn + "'");
        String id = byColumn != null ? byColumn : stated;
        if (id == null)
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "column '" + col + "' has no classification an "
                    + "Entity Type claims — state '" + field + "'");
        return EntityListFacts.type(root, id).orElseThrow(() -> new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED,
                "'" + field + "' names Entity Type '" + id + "', which is not in force"));
    }

    private Object retract(ApiContext api, HttpExchange ex, String rawSeq, Map<String, Object> body) throws IOException {
        Path root = WriteGates.requireWriteRoot(api, "entity identity retract");
        String reason = EntityListFacts.reason(body);
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
            EntityFactLog.Log head = EntityListFacts.read(log);
            EntityRegistry.Assertion x = EntityRegistry.assertions(head.facts(), head.headSeq()).get(seq);
            if (x == null)
                throw new ApiException(409, ErrorCodes.CONFLICT, "no identity assertion at seq " + seq);
            if (!x.live())
                throw new ApiException(409, ErrorCodes.CONFLICT, "the identity assertion at seq " + seq
                        + " is already retracted (by seq " + x.retractedBy() + ")");
            head = EntityListFacts.append(log, head, ex, reason, RETRACTED, null, Map.of("assertionSeq", seq));
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
        EntityTypes.EntityType t = EntityListFacts.type(root, type).orElseThrow(() -> new ApiException(422,
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
                default -> EntityListFacts.type(root, typedKey.substring(0, typedKey.indexOf(':')))
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
