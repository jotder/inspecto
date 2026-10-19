package com.gamma.la.api;

import com.gamma.access.WriteGates;
import com.gamma.audit.Event;
import com.gamma.audit.EventSink;
import com.gamma.entitystore.EntityTypes;
import com.gamma.entitystore.LaLimit;
import com.gamma.entitystore.LaLimitValues;
import com.gamma.entitystore.LinkAnalysisSettings;
import com.gamma.entitystore.MaskTokens;
import com.gamma.la.core.CasePort;
import com.gamma.la.core.CasePorts;
import com.gamma.la.core.InvestigationStore;
import com.gamma.la.core.InvestigationStores;
import com.gamma.la.core.LinkEventTypes;
import com.gamma.la.core.SnapshotStore;
import com.gamma.spi.auth.ApiException;
import com.gamma.spi.auth.ErrorCodes;
import com.gamma.spi.http.ApiContext;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code POST /inv/case-history} — the Incidents and Cases that reference ONE Entity, for a key the Link Analysis node
 * dialog may hold only as an alias. {@code GET /objects/by-entity} needs the raw id, which a masked canvas never has; this
 * route resolves the alias server-side and asks the same read through the {@link CasePort} ({@code ObjectAccess.byEntity}
 * on the host: identical matching, SEC-7d / ABAC post-filter and truncation).
 *
 * <p><b>Body</b> {@code {key, entityType?, investigationId?, status?: open|closed|all, limit?: 1..200}}. <b>Response</b>
 * {@code {entityType, resolved, objects, total, truncated, masking: {mode, masked}}}. {@code resolved=false} (with
 * {@code reason}) when the key names nothing this route may look up: an alias the server does not know, an alias for an
 * Entity Type the masking does not cover, or a RAW key the masking hides — answered alike, so the route is no oracle for
 * raw ids. No key is echoed (raw or alias); under masking every spelling of the raw id in a title is replaced by the key
 * as asked (any separators), any other token of 5+ digits by {@code [number]} and an email by {@code [email]}; rows carry
 * only an allowlist of fields.
 *
 * <p><b>Entity Type</b> — for an ALIAS key, {@code entityType} when given (one of the Space's Entity Types in force, else
 * 422; ignored for a raw key, so a caller cannot pick an unmasked type to read a masked number in the clear), else the Investigation's endpoint type (header {@code entityType}, else {@code sourceType}, else
 * {@code targetType}), else {@value #DEFAULT_TYPE} (the telecom profile's type).
 *
 * <p><b>Keys in</b> — the {@link ReferenceRows} rules: a pseudonym the named Investigation issued, else an exploration
 * alias, and only when the masking covers the Entity Type (mode {@code all}, or {@code typed} with a masked type); a raw
 * key only under masking mode {@code none} and when the Investigation does not hide it.
 *
 * <p><b>Gates, fail closed in order:</b> write root unset 503 · Case management not installed 503 · malformed body 422 ·
 * {@code investigationId} given: the Investigation read gate (404 absent or not a reader, 403 a member refused) · an
 * unsearchable resolved key 422 (the host's rule, its message replaced so it never names the key).
 */
final class CaseHistory {

    static final String DEFAULT_TYPE = "msisdn";
    // The key length and page ceiling are the Space's case_history.max_key_length / case_history.page_max (LaLimit).
    /** A digit-bearing token: digits with any of {@code space . - ( )} between them, an optional leading {@code +}. */
    private static final Pattern DIGIT_TOKEN = Pattern.compile("\\+?(?<!\\d)\\d(?:[\\s.\\-()]*\\d)*(?!\\d)");
    private static final Pattern EMAIL_RE = Pattern.compile("[\\w.+%-]+@[\\w-]+(?:\\.[\\w-]+)+");
    static final String NUMBER = "[number]";
    static final String EMAIL = "[email]";
    private static final String SENTINEL = "\u0000";
    private static final List<String> ROW_FIELDS = List.of("kind", "id", "title", "status", "severity", "createdAt", "matchedOn", "via");

    private CaseHistory() {}

    static Object handle(ApiContext api, HttpExchange ex, Map<String, Object> body) throws IOException {
        Path writeRoot = WriteGates.requireWriteRoot(api, "case history read");                         // 503
        CasePort port = CasePorts.available(api).orElseThrow(() -> new ApiException(503, ErrorCodes.CAPABILITY_UNAVAILABLE,
                "Case management is not installed - case history needs the optional ops module"));    // 503
        String key = ApiContext.str(body, "key");
        if (key == null || key.isBlank())
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "body must include 'key', the node's id as shown");
        key = key.trim();
        LaLimitValues lim = SpaceLimits.of(writeRoot);
        int maxKey = SpaceLimits.get(lim, LaLimit.CASE_HISTORY_MAX_KEY_LENGTH);
        if (key.length() > maxKey)
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'key' may be at most " + maxKey + " characters");
        String status = ApiContext.str(body, "status");
        status = status == null || status.isBlank() ? "all" : status.trim().toLowerCase(Locale.ROOT);
        if (!List.of("open", "closed", "all").contains(status))
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "status must be one of open, closed, all");
        int limit = limit(body.get("limit"), SpaceLimits.get(lim, LaLimit.CASE_HISTORY_PAGE_MAX));
        String invId = ApiContext.str(body, "investigationId");
        LinkAnalysisSettings settings = LinkAnalysisSettings.forRoot(writeRoot);
        String asked = ApiContext.str(body, "entityType");
        if (asked != null) {
            String a = asked.trim();
            // an Entity Type this Space has in force, by its canonical id; anything else is refused before any lookup
            asked = settings.effectiveEntityTypes().stream().map(EntityTypes.EntityType::id)
                    .filter(id -> id.equalsIgnoreCase(a)).findFirst()
                    .orElseThrow(() -> new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED,
                            "entityType must be one of this Space's Entity Types"));
        }

        EntityMasking invMask = null;
        String type = DEFAULT_TYPE;
        if (invId != null) {
            InvestigationRoutes.Inv inv = InvestigationRoutes.openForRead(api, ex, invId);             // 404 · 403
            invMask = EntityMasking.of(inv, List.of());
            for (String h : List.of("entityType", "sourceType", "targetType"))
                if (inv.header().get(h) instanceof String t && !t.isBlank()) { type = t; break; }
        }
        String mode = settings.effectiveMaskingMode();
        boolean alias = key.startsWith(MaskTokens.TOKEN_PREFIX);
        // the caller's explicit type steers only how an ALIAS is resolved: for a raw key it would let the caller pick an
        // unmasked type and look a masked number up in the clear, so a raw key keeps the Investigation's / default type
        if (alias && asked != null) type = asked;
        boolean covered = covered(settings, mode, type);

        String raw = null;
        if (alias) {
            // an alias resolves only for an Entity Type the masking covers: else the raw id behind it would be searched
            // as a type it was never masked as, a membership oracle
            if (covered) {
                if (invMask != null) raw = invMask.reveal(key);
                if (raw == null) raw = ExplorationMasking.lookup(writeRoot, key);
            }
        } else if ("none".equals(mode) && !(invMask != null && invMask.hidesRaw(key))) {
            // any masking at all refuses a raw key: an Investigation's header type is the caller's to choose, so an
            // "uncovered" type would read a masked number in the clear (LA-CASE-HISTORY-RAW-KEY-1)
            raw = key;
        }

        Map<String, Object> describe = new LinkedHashMap<>();
        describe.put("mode", mode);
        describe.put("masked", alias || covered);
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("entityType", type);
        if (raw == null) {
            res.put("resolved", false);
            res.put("reason", "this key names no entity case history can be looked up for: an alias this server does not"
                    + " know (re-run the query to mint it again), or a raw id the Space's masking hides");
            res.put("objects", List.of());
            res.put("total", 0);
            res.put("truncated", false);
            res.put("masking", describe);
            audit(ex, type, false, 0, 0, false);
            return res;
        }

        Map<String, Object> h;
        try {
            h = port.history(api, ex, type, raw, status, limit);
        } catch (ApiException e) {
            if (e.status != 422) throw e;
            // the host's message names the folded key: replace it, so a raw id never leaves through an error
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "case history cannot look this entity up as"
                    + " Entity Type '" + type + "': the type is unknown to this Space, or the id is too short to search");
        }

        List<Map<String, Object>> objects = new ArrayList<>();
        boolean remask = alias || covered;
        Object rows = h.get("objects");
        if (rows instanceof List<?> list)
            for (Object o : list)
                if (o instanceof Map<?, ?> m) {
                    // an allowlist, so a field the host adds to its rows later cannot leak through here
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (String f : ROW_FIELDS) if (m.get(f) != null) row.put(f, m.get(f));
                    if (remask && row.get("title") instanceof String t) row.put("title", scrub(t, raw, key));
                    objects.add(row);
                }
        int total = h.get("total") instanceof Number n ? n.intValue() : objects.size();
        boolean truncated = Boolean.TRUE.equals(h.get("truncated"));
        // the Cases the analyst attached graphs to: an Investigation linked to a Case, or a sealed snapshot attached to one,
        // that CONTAINS this entity. Neither registers an entity reference on the host, so the host's read misses them.
        List<Map<String, Object>> linked = new ArrayList<>();
        boolean linkedTruncated = linkedCases(api, ex, port, writeRoot, raw, status, linked);
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Map<String, Object> o : objects) seen.add(o.get("kind") + "|" + o.get("id"));
        for (Map<String, Object> row : linked) {
            if (!seen.add(row.get("kind") + "|" + row.get("id"))) continue;
            total++;
            if (objects.size() >= limit) { truncated = true; continue; }
            if (remask && row.get("title") instanceof String t) row.put("title", scrub(t, raw, key));
            objects.add(row);
        }
        truncated |= linkedTruncated;
        res.put("resolved", true);
        res.put("objects", objects);
        res.put("total", total);
        res.put("truncated", truncated);
        res.put("masking", describe);
        audit(ex, type, true, objects.size(), total, truncated);
        return res;
    }

    /**
     * {@code text} scrubbed fail-closed: emails become {@value #EMAIL}; a number whose digits are the raw key's (any
     * separators, a leading {@code +}) becomes {@code asked}, the caller's own alias; every OTHER token of 5+ digits
     * (separators allowed: a dashed IMEI, {@code AB-123456}) becomes {@value #NUMBER}, a placeholder and never a minted
     * alias, so no title grows the alias book or makes a third party's number pivotable.
     */
    static String scrub(String text, String raw, String asked) {
        String out = EMAIL_RE.matcher(text).replaceAll(EMAIL);
        String rawDigits = raw.replaceAll("\\D", "");
        // a non-numeric key (CELL-0042) by its literal spelling, held as a sentinel so the digit pass leaves the alias alone
        if (rawDigits.length() < 5)
            out = Pattern.compile(Pattern.quote(raw), Pattern.CASE_INSENSITIVE).matcher(out).replaceAll(SENTINEL);
        Matcher m = DIGIT_TOKEN.matcher(out);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String d = m.group().replaceAll("\\D", "");
            String rep = d.length() < 5 ? m.group() : d.equals(rawDigits) ? SENTINEL : NUMBER;
            m.appendReplacement(sb, Matcher.quoteReplacement(rep));
        }
        m.appendTail(sb);
        return sb.toString().replace(SENTINEL, asked);
    }

    // At most the Space's case_history.max_linked_investigations ADMITTED (readable) Case-linked Investigations are evaluated,
    // and its case_history.max_attachments admitted attachment records.
    /** DoS guard: total records scanned, admitted or not, is bounded to this multiple of the caps above. */
    static final int SCAN_FACTOR = 5;

    /**
     * Adds to {@code out} one row per Case linked to an Investigation (its Case link) or to a sealed snapshot (the attach
     * log) that contains {@code raw}: {@code matchedOn: investigation|snapshot}, {@code via} the Investigation / snapshot
     * id. Fail closed: only Investigations the caller can read ({@link InvestigationRoutes#openForRead}; a snapshot is
     * judged by the Investigation it is anchored to), only an existing {@code case} the caller may see
     * ({@link CasePort#visibleTo}); a record that cannot be read is skipped.
     *
     * <p><b>Caps count ADMITTED records only</b> (a readable Investigation; an attachment whose snapshot is anchored to a
     * readable one), so records the caller cannot see neither consume the cap nor set the flag, and the caller's own
     * matches are never crowded out. Returns {@code true} only when an admitted record existed beyond the cap.
     *
     * <p><b>DoS bound, and what it leaks.</b> The scan reads at most {@value #SCAN_FACTOR} x the cap records in total,
     * hidden included. When it is hit, {@code truncated} is set if the caller has at least one admitted record. That reveals
     * one coarse Space-wide bit (at least 5 x the cap + 1 Case-linked Investigations, or attachment lines) to ANY such caller, and
     * any caller can obtain an admitted record by creating its own Investigation. Accepted trade-off against silently hiding
     * that the answer is incomplete. Only the per-snapshot verdict is cached, never the parsed document.
     */
    private static boolean linkedCases(ApiContext api, HttpExchange ex, CasePort port, Path writeRoot, String raw,
                                       String status, List<Map<String, Object>> out) throws IOException {
        boolean truncated = false;
        Map<String, Boolean> readable = new java.util.HashMap<>();   // investigation id -> admitted by openForRead
        InvestigationStore store = InvestigationStores.of(writeRoot);
        int admitted = 0, scanned = 0;
        LaLimitValues lim = SpaceLimits.of(writeRoot);
        int maxLinked = SpaceLimits.get(lim, LaLimit.CASE_HISTORY_MAX_LINKED_INVESTIGATIONS);
        int maxAttachments = SpaceLimits.get(lim, LaLimit.CASE_HISTORY_MAX_ATTACHMENTS);
        for (String id : store.ids()) {
            String caseRef;
            try {
                caseRef = InvestigationCaseRoutes.caseRef(store, id);
            } catch (IOException | RuntimeException bad) {
                continue;
            }
            if (caseRef == null) continue;
            if (scanned++ >= maxLinked * SCAN_FACTOR) { truncated |= admitted > 0; break; }
            InvestigationRoutes.Inv inv = readable(api, ex, id, readable);
            if (inv == null) continue;   // hidden: counts toward neither cap nor flag
            if (admitted++ >= maxLinked) { truncated = true; break; }
            boolean member = false;
            try {
                for (Map<String, Object> row : WorkingSetRoutes.relation(inv, new boolean[1]).tables().get("entities"))
                    if (raw.equals(row.get("entityId"))) { member = true; break; }
            } catch (IOException | RuntimeException bad) {
                continue;
            }
            if (member) caseRow(api, ex, port, caseRef, status, "investigation", id, out);
        }

        boolean[] cut = {false};
        SnapshotStore snapshots = new SnapshotStore(writeRoot);
        Map<String, Boolean> contains = new java.util.HashMap<>();   // sid -> holds raw; null value = not admitted (no document kept)
        int admittedLines = 0;
        for (String line : snapshots.recentAttachments(maxAttachments * SCAN_FACTOR, cut)) {
            String sid, caseId;
            try {
                @SuppressWarnings("unchecked") Map<String, Object> a = ApiContext.JSON.readValue(line, Map.class);
                sid = a.get("snapshotId") instanceof String s ? s : null;
                caseId = a.get("caseId") instanceof String c ? c : null;
            } catch (IOException | RuntimeException bad) {
                continue;
            }
            if (sid == null || caseId == null || !SnapshotStore.SAFE_ID.matcher(sid).matches()) continue;
            if (!contains.containsKey(sid)) contains.put(sid, admittedContains(api, ex, snapshots, sid, raw, writeRoot, readable));
            Boolean in = contains.get(sid);
            if (in == null) continue;   // not anchored to a readable Investigation: hidden, counted nowhere
            if (admittedLines++ >= maxAttachments) { truncated = true; cut[0] = false; break; }
            if (in) caseRow(api, ex, port, caseId, status, "snapshot", sid, out);
        }
        return truncated || cut[0] && admittedLines > 0;
    }

    private static InvestigationRoutes.Inv readable(ApiContext api, HttpExchange ex, String id, Map<String, Boolean> seen) {
        if (Boolean.FALSE.equals(seen.get(id))) return null;
        try {
            InvestigationRoutes.Inv inv = InvestigationRoutes.openForRead(api, ex, id);
            seen.put(id, true);
            return inv;
        } catch (IOException | RuntimeException refused) {
            seen.put(id, false);   // not readable by this caller: it does not exist here
            return null;
        }
    }

    /**
     * {@code null} when snapshot {@code sid} is not anchored to an Investigation the caller can read (hidden); else whether
     * it holds {@code raw} among its nodes. The parsed document is not retained: callers cache only this verdict.
     */
    private static Boolean admittedContains(ApiContext api, HttpExchange ex, SnapshotStore snapshots, String sid, String raw,
                                            Path writeRoot, Map<String, Boolean> readable) {
        try {
            String json = snapshots.read(sid);
            if (json == null) return null;
            @SuppressWarnings("unchecked") Map<String, Object> snap = ApiContext.JSON.readValue(json, Map.class);
            if (!(snap.get("investigationId") instanceof String invId)) return null;   // unanchored: no gate to judge it by
            InvestigationRoutes.Inv inv = readable(api, ex, invId, readable);
            if (inv == null) return null;
            if (!(snap.get("nodes") instanceof List<?> nodes)) return false;
            EntityMasking[] mask = {null};
            java.util.function.UnaryOperator<String> reveal = t -> {
                if (mask[0] == null) {
                    try {
                        mask[0] = EntityMasking.of(inv, List.of());
                    } catch (IOException e) {
                        throw new java.io.UncheckedIOException(e);   // caught below as before: the snapshot is hidden
                    }
                }
                return mask[0].reveal(t);
            };
            for (Object n : nodes) {
                if (!(n instanceof Map<?, ?> m) || !(m.get("id") instanceof String nid)) continue;
                if (nodeHolds(raw, nid, writeRoot, reveal)) return true;
            }
            return false;
        } catch (IOException | RuntimeException bad) {
            return null;
        }
    }

    /**
     * Whether snapshot node {@code nid} is {@code raw}. A masked node is tried as the Investigation's own pseudonym FIRST
     * (an in-memory map), and only when that does not know it as an exploration alias, whose miss may read the book file.
     */
    static boolean nodeHolds(String raw, String nid, Path writeRoot, java.util.function.UnaryOperator<String> reveal) {
        if (raw.equals(nid)) return true;
        if (!nid.startsWith(MaskTokens.TOKEN_PREFIX)) return false;
        String pseudonymOf = reveal.apply(nid);
        if (pseudonymOf != null) return raw.equals(pseudonymOf);
        return raw.equals(ExplorationMasking.lookup(writeRoot, nid));
    }

    /** One allowlisted row for Case {@code ref} when it exists, is a Case, the caller may see it and it passes {@code status}. */
    private static void caseRow(ApiContext api, HttpExchange ex, CasePort port, String ref, String status, String matchedOn,
                                String via, List<Map<String, Object>> out) {
        try {
            Map<String, Object> c = port.summary(api, ref).orElse(null);
            if (c == null || !"case".equals(c.get("kind")) || !port.visibleTo(ex, c)) return;
            boolean closed = !Boolean.FALSE.equals(c.get("closed"));
            if (status.equals("open") && closed || status.equals("closed") && !closed) return;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("kind", "case");
            row.put("id", c.get("id") == null ? ref : String.valueOf(c.get("id")));
            for (String f : List.of("title", "status", "severity", "createdAt")) if (c.get(f) != null) row.put(f, c.get(f));
            row.put("matchedOn", matchedOn);
            row.put("via", via);
            out.add(row);
        } catch (RuntimeException bad) {
            // a Case that cannot be read is not shown
        }
    }

    /** Whether the Space's masking covers {@code type}; a type this Space does not know is masked (fail closed), as EntityMasking does. */
    private static boolean covered(LinkAnalysisSettings settings, String mode, String type) {
        return switch (mode) {
            case "none" -> false;
            case "all" -> true;
            default -> settings.effectiveEntityTypes().stream().filter(t -> t.id().equalsIgnoreCase(type)).findFirst()
                    .map(EntityTypes.EntityType::masked).orElse(true);
        };
    }

    private static int limit(Object v, int maxLimit) {
        if (v == null) return Math.min(50, maxLimit);
        if (v instanceof Number n && n.doubleValue() == n.intValue() && n.intValue() >= 1 && n.intValue() <= maxLimit)
            return n.intValue();
        throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "limit must be an integer in [1, " + maxLimit + "]");
    }

    /** Best effort, like every LA read (LA-04): counts only, the key is never recorded. */
    private static void audit(HttpExchange ex, String type, boolean resolved, int objects, int total, boolean truncated) {
        try {
            EventSink.current().emit(Event.builder(LinkEventTypes.LINK_CASE_HISTORY_READ).source("inv")
                    .message("link.case-history.read " + type + " — " + (resolved ? objects + " of " + total + " objects"
                            : "key not resolved") + (truncated ? " (truncated)" : ""))
                    .actor(ApiContext.actor(ex)).actorType(ApiContext.actorType(ex))
                    .action("link.case-history.read").actionCategory("analysis")
                    .target("entityType", type)
                    .attr("entityType", type).attr("resolved", resolved).attr("objects", objects)
                    .attr("total", total).attr("truncated", truncated));
        } catch (RuntimeException ignore) {
            // best effort — the audit must never fail the analyst's read
        }
    }
}
