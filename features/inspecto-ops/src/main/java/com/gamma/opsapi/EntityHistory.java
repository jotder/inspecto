package com.gamma.opsapi;

import com.gamma.control.ObjectReadMasking;
import com.gamma.entitystore.EntityTypes;
import com.gamma.entitystore.LinkAnalysisSettings;
import com.gamma.ops.ObjectService;
import com.gamma.ops.OperationalObject;
import com.gamma.ops.link.LinkRelationship;
import com.gamma.ops.link.ObjectLink;
import com.gamma.spi.auth.ApiException;
import com.gamma.spi.auth.ErrorCodes;
import com.gamma.spi.http.ApiContext;
import com.gamma.workflow.ObjectType;
import com.sun.net.httpserver.HttpExchange;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code GET /objects/by-entity?type=&id=[&status=open|closed|all][&limit=]} — an Entity's "case history": the
 * Incidents and Cases that reference it, newest first. An object matches when, after folding both sides with the
 * Entity Type's normaliser ({@link EntityTypes#normalise}):
 * <ul>
 *   <li>{@code key.<column>} — a keyed Alert Rule attribute whose column names the type (the same
 *       "column contains the type id" rule the Incident page's "Investigate in Link Analysis" link uses), or
 *       {@code key.entity_key} (the Risk / Anomaly Score Alert Rules, whose column names no type);</li>
 *   <li>{@code entityKey} — an Incident minted from a Link Analysis Entity ({@code entity:<type>:<key>}, or the
 *       untyped {@code entity:<key>});</li>
 *   <li>{@code member} — a Case that CONTAINS a matched Incident ({@code via} names the Incident).</li>
 * </ul>
 * Under {@code e164} a leading {@code +} is not significant ({@code +256…} and {@code 256…} are one number), since
 * Alert Rule key columns carry raw Dataset values. ⚠ The store prefilter (DbObjectStore: a side table of reference values) is a substring of the folded key (its
 * digits, for a number), so a stored value with separators INSIDE the digits ({@code 256 700 …}) is not found.
 * SEC-7d / ABAC visibility is the same post-filter {@code GET
 * /objects} applies, so a hidden object (and a Case reached only through one) is simply absent, never counted.
 */
public final class EntityHistory {

    private EntityHistory() {}

    static final int DEFAULT_LIMIT = 50;
    static final int MAX_LIMIT = 200;
    /** Candidates the store prefilter may return per call (newest first) - bounds the exact re-check work. */
    static int SCAN_CAP = 2_000;   // not final: a test lowers it to exercise truncation
    private static final int MAX_ID_LENGTH = 256;
    /** A folded key shorter than this would match a large share of the store — refused (422), not scanned. */
    static final int MIN_DIGITS = 5;
    static final int MIN_CHARS = 2;
    static final String TRUNCATED_HEADER = "X-Inspecto-Truncated";
    private static final List<ObjectType> KINDS = List.of(ObjectType.INCIDENT, ObjectType.CASE);

    static Object byEntity(ApiContext api, HttpExchange ex) {
        ObjectReadMasking masking = ObjectReadMasking.forRequest(api, ex);
        // A caller who cannot reveal gets no match through a classified key: a raw number that hits one would confirm
        // that the number is under investigation, so such an object is absent (D-P8, fail closed).
        java.util.function.BiPredicate<OperationalObject, String> hidden =
                (o, on) -> masking != null && masking.hides(o.attributes(), on);
        Result r = query(api.writeRoot(), OpsEngine.of(api), ex, ApiContext.query(ex, "type"), ApiContext.query(ex, "id"),
                ApiContext.query(ex, "status"), ApiContext.query(ex, "limit"), hidden);
        ApiContext.pagination(ex, null, null, r.limit(), r.total());
        if (r.truncated()) {
            // The scan stopped at its cap: older references may exist, so `total` is a lower bound — say so.
            if (ApiContext.attr(ex, ApiContext.ATTR_PAGINATION) instanceof Map<?, ?> p) {
                @SuppressWarnings("unchecked") Map<String, Object> pm = (Map<String, Object>) p;
                pm.put("truncated", true);
                pm.put("message", truncatedMessage());
            }
            ex.getResponseHeaders().set(TRUNCATED_HEADER, "true");
        }
        return r.objects();
    }

    static String truncatedMessage() {
        return "only the newest " + SCAN_CAP + " candidate objects were scanned; older references"
                + " to this Entity may exist and are not counted in total";
    }

    /** One history read: the page of rows, the TRUE count of matches, the limit applied, and whether the scan hit its cap. */
    public record Result(List<Map<String, Object>> objects, int total, int limit, boolean truncated) {}

    /**
     * The whole read behind {@code GET /objects/by-entity} — validation (422s), matching and the SEC-7d / ABAC
     * visibility post-filter against {@code ex}'s caller — shared by the route and {@code ObjectAccess.byEntity}
     * (Link Analysis's masked case history), so the two cannot disagree. {@code status}/{@code limit} may be null.
     */
    public static Result query(Path root, ObjectService svc, HttpExchange ex, String typeIn, String idIn, String statusIn, String limitIn) {
        return query(root, svc, ex, typeIn, idIn, statusIn, limitIn, (o, on) -> false);
    }

    /** As above; {@code hidden} drops a match the caller may not learn of (an object, the attribute that matched it). */
    static Result query(Path root, ObjectService svc, HttpExchange ex, String typeIn, String idIn, String statusIn,
                        String limitIn, java.util.function.BiPredicate<OperationalObject, String> hidden) {
        String type = trim(typeIn);
        String rawId = trim(idIn);
        String status = trim(statusIn);
        status = status == null ? "all" : status.toLowerCase(Locale.ROOT);
        if (!List.of("open", "closed", "all").contains(status))
            throw invalid("status must be one of open, closed, all, got '" + status + "'");
        int limit = parseLimit(limitIn);
        if (type == null) throw invalid("'type' (an Entity Type id) is required");
        if (rawId == null) throw invalid("'id' (the Entity's value) is required");
        if (rawId.length() > MAX_ID_LENGTH) throw invalid("'id' may be at most " + MAX_ID_LENGTH + " characters");

        List<EntityTypes.EntityType> types = (root == null ? LinkAnalysisSettings.EMPTY
                : LinkAnalysisSettings.read(root.resolve(LinkAnalysisSettings.FILE))).effectiveEntityTypes();
        EntityTypes.EntityType et = types.stream().filter(t -> t.id().equals(type)).findFirst()
                .orElseThrow(() -> invalid("unknown Entity Type '" + type + "' — one of "
                        + types.stream().map(EntityTypes.EntityType::id).toList()));
        java.util.Set<String> known = types.stream().map(EntityTypes.EntityType::id).collect(java.util.stream.Collectors.toSet());
        String key = canonical(et.normaliser(), EntityTypes.normalise(et.normaliser(), stripTyped(rawId, type, known)));
        int min = digitsOnly(et.normaliser()) ? MIN_DIGITS : MIN_CHARS;
        if (key.length() < min)
            throw invalid("'id' must fold to at least " + min + " characters as a " + type + " key, got '" + key + "'");

        // The longest literal both raw and folded forms share: the digits for a number, else the folded key.
        String fragment = digitsOnly(et.normaliser()) ? key : key.toLowerCase(Locale.ROOT);
        List<OperationalObject> scanned = svc.findByAttributeFragment(KINDS, fragment, SCAN_CAP);

        Map<String, Map<String, Object>> hits = new LinkedHashMap<>();
        List<OperationalObject> matchedIncidents = new ArrayList<>();
        for (OperationalObject o : scanned) {
            String on = matchedOn(o, et, key, known);
            if (on == null || !ObjectRoutes.visibleTo(ex, o) || hidden.test(o, on)) continue;
            hits.put(o.id(), row(o, on, null));
            if (o.objectType() == ObjectType.INCIDENT) matchedIncidents.add(o);
        }
        for (OperationalObject inc : matchedIncidents) {
            for (ObjectLink l : svc.linksOf(inc.id())) {
                if (!LinkRelationship.CONTAINS.equals(l.relationship()) || !inc.id().equals(l.toId())) continue;
                if (hits.containsKey(l.fromId())) continue;
                svc.get(l.fromId()).filter(c -> c.objectType() == ObjectType.CASE && ObjectRoutes.visibleTo(ex, c))
                        .ifPresent(c -> hits.put(c.id(), row(c, "member", inc.id())));
            }
        }
        String want = status;
        List<Map<String, Object>> out = hits.values().stream()
                .filter(r -> want.equals("all") || want.equals("closed") == (Boolean) r.get("_closed"))
                .sorted(Comparator.comparingLong((Map<String, Object> r) -> (Long) r.get("createdAt"))
                        .thenComparing(r -> (String) r.get("id")).reversed())
                .toList();
        out.forEach(r -> r.remove("_closed"));
        return new Result(out.size() > limit ? out.subList(0, limit) : out, out.size(), limit, scanned.size() >= SCAN_CAP);
    }

    /** Which attribute names the Entity, or null. */
    private static String matchedOn(OperationalObject o, EntityTypes.EntityType et, String key, java.util.Set<String> known) {
        for (Map.Entry<String, String> a : o.attributes().entrySet()) {
            String k = a.getKey(), v = a.getValue();
            if (v == null || v.isBlank() || "NULL".equals(v)) continue;
            if (k.startsWith("key.")) {
                String column = k.substring(4).toLowerCase(Locale.ROOT);
                if ((namesType(column, et.id()) || column.equals("entity_key")) && same(et, v, key)) return k;
            } else if (k.equals(ObjectService.ATTR_ENTITY_KEY)) {
                String rest = v.startsWith("entity:") ? v.substring("entity:".length()) : v;
                if (rest.startsWith(et.id() + ":")) rest = rest.substring(et.id().length() + 1);
                else if (typedAsKnown(rest, known)) continue;   // typed as another KNOWN Entity Type
                if (same(et, rest, key)) return k;
            }
        }
        return null;
    }

    private static boolean same(EntityTypes.EntityType et, String raw, String key) {
        return canonical(et.normaliser(), EntityTypes.normalise(et.normaliser(), raw)).equals(key);
    }

    /** The comparison form: an e164 key without its {@code +} (a raw Dataset value rarely carries one). */
    private static String canonical(String normaliser, String folded) {
        return "e164".equals(normaliser) && folded.startsWith("+") ? folded.substring(1) : folded;
    }

    private static boolean digitsOnly(String normaliser) {
        return "e164".equals(normaliser) || "digits".equals(normaliser);
    }

    /** Accept the typed node-id spellings too ({@code entity:msisdn:+…}, {@code msisdn:+…}); an id typed as a
     *  DIFFERENT known Entity Type is a 422. An unknown prefix ({@code host:8080}) is part of the value. */
    private static String stripTyped(String id, String type, java.util.Set<String> known) {
        String s = id.startsWith("entity:") ? id.substring("entity:".length()) : id;
        if (s.startsWith(type + ":")) return s.substring(type.length() + 1);
        if (typedAsKnown(s, known))
            throw invalid("'id' is typed as '" + s.substring(0, s.indexOf(':')) + "', not '" + type + "'");
        return s;
    }

    private static boolean typedAsKnown(String s, java.util.Set<String> known) {
        int i = s.indexOf(':');
        return i > 0 && known.contains(s.substring(0, i));
    }

    /** A key column names the type when one of its {@code _ . -} separated tokens IS the type id
     *  ({@code caller_msisdn} names msisdn; {@code zip} / {@code ship_to} do not name ip). */
    private static boolean namesType(String column, String typeId) {
        return List.of(column.split("[_.-]")).contains(typeId);
    }

    private static Map<String, Object> row(OperationalObject o, String matchedOn, String via) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("kind", o.objectType() == ObjectType.CASE ? "case" : "incident");
        r.put("id", o.id());
        r.put("title", o.title());
        r.put("status", o.status());
        if (o.severity() != null) r.put("severity", o.severity());
        r.put("createdAt", o.createdAt());
        r.put("matchedOn", matchedOn);
        if (via != null) r.put("via", via);
        r.put("_closed", o.isClosed());
        return r;
    }

    private static int parseLimit(String s) {
        if (s == null || s.isBlank()) return DEFAULT_LIMIT;
        try {
            int n = Integer.parseInt(s.trim());
            if (n >= 1 && n <= MAX_LIMIT) return n;
        } catch (NumberFormatException ignored) {
            // fall through to the 422
        }
        throw invalid("limit must be an integer in [1, " + MAX_LIMIT + "], got '" + s + "'");
    }

    private static String trim(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static ApiException invalid(String msg) {
        return new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, msg);
    }
}
