package com.gamma.la.api;

import com.gamma.access.WriteGates;
import com.gamma.audit.Event;
import com.gamma.audit.EventSink;
import com.gamma.entitystore.EntityTypes;
import com.gamma.entitystore.LinkAnalysisSettings;
import com.gamma.entitystore.MaskTokens;
import com.gamma.la.core.CasePort;
import com.gamma.la.core.CasePorts;
import com.gamma.la.core.LinkEventTypes;
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
 * <p><b>Body</b> {@code {key, investigationId?, status?: open|closed|all, limit?: 1..200}}. <b>Response</b>
 * {@code {entityType, resolved, objects, total, truncated, masking: {mode, masked}}}. {@code resolved=false} (with
 * {@code reason}) when the key names nothing this route may look up: an alias the server does not know, an alias for an
 * Entity Type the masking does not cover, or a RAW key the masking hides — answered alike, so the route is no oracle for
 * raw ids. No key is echoed (raw or alias); under masking every spelling of the raw id in a title is replaced by the key
 * as asked, and any other long number by its exploration alias.
 *
 * <p><b>Entity Type</b> — the Investigation's endpoint type (header {@code entityType}, else {@code sourceType}, else
 * {@code targetType}), else {@value #DEFAULT_TYPE} (the telecom profile's type).
 *
 * <p><b>Keys in</b> — the {@link ReferenceRows} rules: a pseudonym the named Investigation issued, else an exploration
 * alias, and only when the masking covers the Entity Type (mode {@code all}, or {@code typed} with a masked type); a raw
 * key only when that masking does NOT cover it and the Investigation does not hide it.
 *
 * <p><b>Gates, fail closed in order:</b> write root unset 503 · Case management not installed 503 · malformed body 422 ·
 * {@code investigationId} given: the Investigation read gate (404 absent or not a reader, 403 a member refused) · an
 * unsearchable resolved key 422 (the host's rule, its message replaced so it never names the key).
 */
final class CaseHistory {

    static final String DEFAULT_TYPE = "msisdn";
    private static final int MAX_KEY = 256;
    private static final int MAX_LIMIT = 200;
    /** A digit run this long in a title is treated as an id under masking. */
    private static final Pattern LONG_NUMBER = Pattern.compile("\\+?\\d{7,}");

    private CaseHistory() {}

    static Object handle(ApiContext api, HttpExchange ex, Map<String, Object> body) throws IOException {
        Path writeRoot = WriteGates.requireWriteRoot(api, "case history read");                         // 503
        CasePort port = CasePorts.available(api).orElseThrow(() -> new ApiException(503, ErrorCodes.CAPABILITY_UNAVAILABLE,
                "Case management is not installed - case history needs the optional ops module"));    // 503
        String key = ApiContext.str(body, "key");
        if (key == null || key.isBlank())
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "body must include 'key', the node's id as shown");
        key = key.trim();
        if (key.length() > MAX_KEY)
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'key' may be at most " + MAX_KEY + " characters");
        String status = ApiContext.str(body, "status");
        status = status == null || status.isBlank() ? "all" : status.trim().toLowerCase(Locale.ROOT);
        if (!List.of("open", "closed", "all").contains(status))
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "status must be one of open, closed, all");
        int limit = limit(body.get("limit"));
        String invId = ApiContext.str(body, "investigationId");

        EntityMasking invMask = null;
        String type = DEFAULT_TYPE;
        if (invId != null) {
            InvestigationRoutes.Inv inv = InvestigationRoutes.openForRead(api, ex, invId);             // 404 · 403
            invMask = EntityMasking.of(inv, List.of());
            for (String h : List.of("entityType", "sourceType", "targetType"))
                if (inv.header().get(h) instanceof String t && !t.isBlank()) { type = t; break; }
        }

        LinkAnalysisSettings settings = LinkAnalysisSettings.forRoot(writeRoot);
        String mode = settings.effectiveMaskingMode();
        String typeId = type;
        boolean covered = switch (mode) {
            case "none" -> false;
            case "all" -> true;
            // an Entity Type this Space does not know is treated as masked (fail closed), as EntityMasking does
            default -> settings.effectiveEntityTypes().stream().filter(t -> t.id().equalsIgnoreCase(typeId)).findFirst()
                    .map(EntityTypes.EntityType::masked).orElse(true);
        };

        boolean alias = key.startsWith(MaskTokens.TOKEN_PREFIX);
        String raw = null;
        if (alias) {
            // an alias resolves only for an Entity Type the masking covers: else the raw id behind it would be searched
            // as a type it was never masked as, a membership oracle
            if (covered) {
                if (invMask != null) raw = invMask.reveal(key);
                if (raw == null) raw = ExplorationMasking.lookup(writeRoot, key);
            }
        } else if (!covered && !(invMask != null && invMask.hidesRaw(key))) {
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
        ExplorationMasking others = remask ? ExplorationMasking.forced(writeRoot, mode, "case history") : null;
        Object rows = h.get("objects");
        if (rows instanceof List<?> list)
            for (Object o : list)
                if (o instanceof Map<?, ?> m) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    m.forEach((k, v) -> row.put(String.valueOf(k), v));
                    if (remask && row.get("title") instanceof String t) row.put("title", scrub(t, raw, key, others));
                    objects.add(row);
                }
        int total = h.get("total") instanceof Number n ? n.intValue() : objects.size();
        boolean truncated = Boolean.TRUE.equals(h.get("truncated"));
        res.put("resolved", true);
        res.put("objects", objects);
        res.put("total", total);
        res.put("truncated", truncated);
        res.put("masking", describe);
        audit(ex, type, true, objects.size(), total, truncated);
        return res;
    }

    /** {@code text} with every spelling of {@code raw} replaced by {@code asked}, and any other long number by its alias. */
    static String scrub(String text, String raw, String asked, ExplorationMasking others) {
        String out = text;
        String digits = raw.replaceAll("\\D", "");
        if (digits.length() >= 5)   // a number's other spellings first, so a leading + goes with it
            out = Pattern.compile("\\+?" + Pattern.quote(digits) + "(?!\\d)").matcher(out).replaceAll(Matcher.quoteReplacement(asked));
        out = Pattern.compile(Pattern.quote(raw), Pattern.CASE_INSENSITIVE).matcher(out).replaceAll(Matcher.quoteReplacement(asked));
        Matcher m = LONG_NUMBER.matcher(out);
        StringBuilder sb = new StringBuilder();
        while (m.find()) m.appendReplacement(sb, Matcher.quoteReplacement(others.out(m.group())));
        m.appendTail(sb);
        return sb.toString();
    }

    private static int limit(Object v) {
        if (v == null) return 50;
        if (v instanceof Number n && n.doubleValue() == n.intValue() && n.intValue() >= 1 && n.intValue() <= MAX_LIMIT)
            return n.intValue();
        throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "limit must be an integer in [1, " + MAX_LIMIT + "]");
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
