package com.gamma.control;

import com.gamma.event.EventLog;
import com.gamma.service.SpaceContext;
import com.gamma.service.SpaceId;
import com.gamma.service.SpaceLock;
import com.gamma.service.SpaceManager;
import com.gamma.spi.http.ApiContext;
import com.gamma.spi.http.RouteModule;

import java.nio.file.Path;
import java.util.Map;

/**
 * SE-1a — {@code GET /spaces/{id}/integrity} ({@code docs/okf/product/module/config/spec-space-lock.md} §7): the
 * Space lock's report — broken links, duplicates and files changed outside the server. Reached as
 * {@code /spaces/{id}/integrity}: the Space seam binds the Space and strips the prefix.
 *
 * <p>Report-only and always 200: {@code computing} until the first lock is ready, {@code disabled} under
 * {@code -Dspace.integrity=off}, {@code unavailable} for the single-tenant default Space (no Space folder).
 * Gated on {@code canAuthorWorkbench} because it lists every config path. Every edition (D-LC5).
 */
final class SpaceIntegrityRoutes implements RouteModule {

    @Override
    public void register(ApiContext api) {
        api.get("/integrity", ApiContext.withCapability("canAuthorWorkbench", (e, m) -> report(api)));
    }

    private static Object report(ApiContext api) {
        SpaceManager spaces = HostContext.of(api).spaces();
        SpaceContext ctx = spaces.space(SpaceId.of(EventLog.currentSpaceId())).orElseGet(spaces::current);
        Path config = ctx.root().config();
        if (!SpaceLock.enabled()) return Map.of("status", "disabled");
        if (config == null) return Map.of("status", "unavailable",
                "reason", "this Space has no Space folder (single-tenant -Dassist.write.root), so it has no lock");
        return SpaceLock.report(ctx.id().value(), config).body();
    }
}
