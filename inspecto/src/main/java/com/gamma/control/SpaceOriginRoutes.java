package com.gamma.control;

import com.gamma.event.EventLog;
import com.gamma.service.SpaceContext;
import com.gamma.service.SpaceId;
import com.gamma.service.SpaceManager;
import com.gamma.service.SpaceOrigin;
import com.gamma.spi.auth.ApiException;
import com.gamma.spi.auth.ErrorCodes;
import com.gamma.spi.http.ApiContext;
import com.gamma.spi.http.RouteModule;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * SE-5a — a Space's origin ({@code docs/okf/product/module/config/spec-space-origin.md}). Reached as
 * {@code /spaces/{id}/origin…}: the Space seam binds the Space and strips the prefix.
 *
 * <ul>
 *   <li>{@code GET /origin} — the record and its report: files unchanged since the origin, files the Space changed, and,
 *       when the template's version moved, what the template changed since. Report-only; {@code canAuthorWorkbench}
 *       (it lists config paths, like {@code /integrity}); {@code status: none} for a Space without a record.</li>
 *   <li>{@code POST /origin/adopt {template, version?}} — match the Space against a template by file hash and record the
 *       origin. {@code canAdminister}. Gates: no Space folder 503 → body 400/422 → template id leaving the catalog 403 →
 *       no such template 404 → a record already there or a version that is not the template's current one 409 → write.
 *       Writes only {@code .inspecto/created-from.*}, never config.</li>
 * </ul>
 */
final class SpaceOriginRoutes implements RouteModule {

    private static final Pattern TEMPLATE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
    /** One adopt at a time, so the "no record yet" check and the write cannot interleave. */
    private static final Object ADOPT_LOCK = new Object();

    @Override
    public void register(ApiContext api) {
        api.get("/origin", ApiContext.withCapability("canAuthorWorkbench", (e, m) -> read(api)));
        api.post("/origin/adopt", ApiContext.withCapability("canAdminister", (e, m) -> adopt(api, api.body(e))));
    }

    private static SpaceContext space(ApiContext api) {
        SpaceManager spaces = HostContext.of(api).spaces();
        return spaces.space(SpaceId.of(EventLog.currentSpaceId())).orElseGet(spaces::current);
    }

    private static Object read(ApiContext api) throws IOException {
        SpaceManager spaces = HostContext.of(api).spaces();
        SpaceContext ctx = space(api);
        if (ctx.root().config() == null) return Map.of("status", "unavailable",
                "reason", "this Space has no Space folder (single-tenant -Dassist.write.root), so it has no origin");
        Path base = ctx.root().base();
        var origin = SpaceOrigin.read(base);
        if (origin.isEmpty()) return Map.of("status", "none");
        return SpaceOrigin.report(origin.get(), base, spaces.templateDir(origin.get().template()).orElse(null), ctx.id().value());
    }

    private static Object adopt(ApiContext api, Map<String, Object> body) throws IOException {
        SpaceManager spaces = HostContext.of(api).spaces();
        SpaceContext ctx = space(api);
        if (ctx.root().config() == null)
            throw new ApiException(503, ErrorCodes.CAPABILITY_UNAVAILABLE,
                    "this Space has no Space folder (single-tenant -Dassist.write.root), so it cannot record an origin");
        if (!(body.get("template") instanceof String template) || template.isBlank())
            throw new ApiException(400, ErrorCodes.MALFORMED_REQUEST, "'template' (a Space Template id) is required");
        Object v = body.get("version");
        if (v != null && !(v instanceof String))
            throw new ApiException(400, ErrorCodes.MALFORMED_REQUEST, "'version' must be a string");
        String version = (String) v;
        if (version != null && !version.equals(SpaceOrigin.UNVERSIONED) && !SpaceOrigin.VERSION.matcher(version).matches())
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'version' is not a template version: " + version);
        if (!TEMPLATE_ID.matcher(template).matches())
            throw new ApiException(403, ErrorCodes.PATH_JAIL_VIOLATION, "'template' must be a bare template id");
        Path tpl = spaces.templateDir(template)
                .orElseThrow(() -> new ApiException(404, ErrorCodes.NOT_FOUND, "no such space template '" + template + "'"));
        Path base = ctx.root().base();
        synchronized (ADOPT_LOCK) {
            var existing = SpaceOrigin.read(base);
            if (existing.isPresent())
                throw new ApiException(409, ErrorCodes.CONFLICT, "this Space already records its origin: template '"
                        + existing.get().template() + "' version " + existing.get().templateVersion());
            String current = SpaceOrigin.templateVersion(tpl);
            if (version != null && !version.equals(current))
                throw new ApiException(409, ErrorCodes.CONFLICT, "template '" + template + "' is at version " + current
                        + ", not " + version + "; only the version installed now can be matched");
            return SpaceOrigin.adopt(tpl, base, template, ctx.id().value()).view();
        }
    }
}
