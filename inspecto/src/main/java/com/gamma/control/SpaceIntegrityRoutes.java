package com.gamma.control;

import com.gamma.audit.Event;
import com.gamma.audit.EventType;
import com.gamma.event.EventLog;
import com.gamma.service.SpaceContext;
import com.gamma.service.SpaceId;
import com.gamma.service.SpaceLock;
import com.gamma.service.SpaceLockAccept;
import com.gamma.service.SpaceManager;
import com.gamma.spi.auth.ApiException;
import com.gamma.spi.auth.ErrorCodes;
import com.gamma.spi.http.ApiContext;
import com.gamma.spi.http.RouteModule;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SE-1a — {@code GET /spaces/{id}/integrity} ({@code docs/okf/product/module/config/spec-space-lock.md} §7): the
 * Space lock's report — broken links, duplicates and files changed outside the server. Reached as
 * {@code /spaces/{id}/integrity}: the Space seam binds the Space and strips the prefix.
 *
 * <p>Report-only and always 200: {@code computing} until the first lock is ready, {@code disabled} under
 * {@code -Dspace.integrity=off}, {@code unavailable} for the single-tenant default Space (no Space folder).
 * Gated on {@code canAuthorWorkbench} because it lists every config path. Every edition (D-LC5). A Space report
 * carries {@code linksMode} (LC-4, {@link SpaceLinksMode}) and, when ready, {@code activationHolds} and {@code held}
 * (SE-1b, {@link ActivationHolds}).
 *
 * <p>LC-6 — {@code POST /spaces/{id}/integrity/accept {paths?}} (§12): accept hand edits, so they leave
 * {@code changedOutside}. {@code canAdminister}: accepting clears the record of an out-of-band change to any config,
 * fixed-name settings such as {@code roles.toon} included, so it is an administrator's act, not an author's. Every
 * edition. Gates: kill switch off or no Space folder 503 → bad body 400 → a path the lock does not know 404 → accept.
 */
final class SpaceIntegrityRoutes implements RouteModule {

    @Override
    public void register(ApiContext api) {
        api.get("/integrity", ApiContext.withCapability("canAuthorWorkbench", (e, m) -> report(api)));
        api.post("/integrity/accept", ApiContext.withCapability("canAdminister", (e, m) -> accept(api, e)));
    }

    private static SpaceContext space(ApiContext api) {
        SpaceManager spaces = HostContext.of(api).spaces();
        return spaces.space(SpaceId.of(EventLog.currentSpaceId())).orElseGet(spaces::current);
    }

    private static Object report(ApiContext api) {
        SpaceContext ctx = space(api);
        Path config = ctx.root().config();
        if (!SpaceLock.enabled()) return Map.of("status", "disabled");
        if (config == null) return Map.of("status", "unavailable",
                "reason", "this Space has no Space folder (single-tenant -Dassist.write.root), so it has no lock");
        Map<String, Object> body = new LinkedHashMap<>(SpaceLock.report(ctx.id().value(), config).body());
        body.put("linksMode", SpaceLinksMode.of(ctx.id().value()).body());
        ActivationHolds.addTo(body, ctx.id().value(), config);   // SE-1b: what is held and why
        return body;
    }

    /**
     * The Spaces-screen count of one Space (LC-4): {@code status} and, when {@code ready}, the three totals. Read from
     * the report already in memory; a Space with none yet answers {@code computing} (its first read schedules one).
     */
    static Map<String, Object> summary(SpaceContext ctx) {
        Path config = ctx.root().config();
        if (!SpaceLock.enabled()) return Map.of("status", "disabled");
        if (config == null) return Map.of("status", "unavailable");
        Map<String, Object> b = SpaceLock.report(ctx.id().value(), config).body();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", b.get("status"));
        if ("ready".equals(b.get("status")))
            for (String k : List.of("broken", "duplicates", "changedOutside"))
                m.put(k, b.get(k) instanceof Map<?, ?> list && list.get("total") instanceof Number n ? n.intValue() : 0);
        return m;
    }

    private static Object accept(ApiContext api, HttpExchange ex) throws IOException {
        SpaceContext ctx = space(api);
        Path config = ctx.root().config();
        if (!SpaceLock.enabled())
            throw new ApiException(503, ErrorCodes.CAPABILITY_UNAVAILABLE, "the Space lock is off (-Dspace.integrity=off): nothing to accept");
        if (config == null)
            throw new ApiException(503, ErrorCodes.CAPABILITY_UNAVAILABLE,
                    "this Space has no Space folder (single-tenant -Dassist.write.root), so it has no lock");
        List<String> paths = null;
        Object raw = api.body(ex).get("paths");
        if (raw != null) {
            if (!(raw instanceof List<?> l) || l.isEmpty())
                throw new ApiException(400, ErrorCodes.MALFORMED_REQUEST,
                        "'paths' must be a non-empty list of config-relative paths; omit it to accept every file changed outside");
            paths = new ArrayList<>();
            for (Object o : l) {
                if (!(o instanceof String p) || p.isBlank())
                    throw new ApiException(400, ErrorCodes.MALFORMED_REQUEST, "'paths' holds a non-text or blank entry");
                paths.add(p.trim().replace('\\', '/'));
            }
        }
        SpaceLockAccept.Result r;
        try {
            r = SpaceLockAccept.accept(ctx.id().value(), config, paths);
        } catch (IOException notComputed) {
            throw new ApiException(503, ErrorCodes.CAPABILITY_UNAVAILABLE, notComputed.getMessage());
        }
        if (!r.unknown().isEmpty())
            throw new ApiException(404, ErrorCodes.NOT_FOUND, "not a config file of this Space: " + String.join(", ", r.unknown()));
        if (!r.accepted().isEmpty()) audit(ex, ctx.id().value(), r.accepted());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("accepted", r.accepted());
        out.put("notChangedOutside", r.notChangedOutside());
        out.put("changedOutside", r.changedOutsideLeft());
        out.put("acceptedBy", ApiContext.actor(ex));
        return out;
    }

    private static void audit(HttpExchange ex, String space, List<Map<String, Object>> accepted) {
        List<String> what = new ArrayList<>();
        for (Map<String, Object> a : accepted) what.add(a.get("change") + " " + a.get("path"));
        try {
            EventLog.current().emit(Event.builder(EventType.AUDIT).source("audit")
                    .message(ApiContext.actor(ex) + " accepted " + accepted.size() + " config change(s) made outside the server: "
                            + String.join(", ", what))
                    .actor(ApiContext.actor(ex)).actorType(ApiContext.actorType(ex))
                    .action("config.changed-outside.accepted").actionCategory("configuration")
                    .attr("space", space).attr("count", String.valueOf(accepted.size())).attr("paths", String.join(", ", what)));
        } catch (RuntimeException ignored) {
            // an absent or failing audit sink never undoes the accept it records
        }
    }
}
