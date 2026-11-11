package com.gamma.control;

import com.gamma.audit.Event;
import com.gamma.audit.EventType;
import com.gamma.event.EventLog;
import com.gamma.spi.auth.ApiException;
import com.gamma.spi.auth.ErrorCodes;
import com.gamma.spi.http.ApiContext;
import com.gamma.spi.http.RouteModule;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Live App install (PRODUCT-ASSEMBLY-1 PA-7d; D-PA25). An administrator installs, upgrades and removes an App on a running
 * server; every App is a signed package ({@code tools/inspecto-app.mjs package --key}, PA-7c) whose signer this install trusts
 * (the issuer keys the core jar carries, plus {@code conf/app-signers.properties}).
 *
 * <pre>
 *   GET    /apps                       the Product's own Apps, the installed ones, and the trusted signers
 *   POST   /apps[?dryRun=true]         install a package (the body is the zip, application/zip)
 *   PUT    /apps/{id}[?dryRun=true]    upgrade it; the answer offers each Space made from it the file-by-file difference (D-PA25)
 *   DELETE /apps/{id}[?dryRun=true]    remove it; its Spaces keep every file and are named in the answer
 * </pre>
 *
 * Gates, in order (the endpoint skill's): no Spaces root, so nowhere to put a template, 503 · not a Product build of a Platform
 * product 409 · a package that does not verify 422 · the App's Platform range, Add-ons or entitlement 422 · an id or template
 * conflict 409 · act (templates staged and swapped, then the App folder). Read-only (an expired entitlement) refuses the writes
 * with 423 before the handler. Every change is audited.
 */
final class AppInstallRoutes implements RouteModule {

    /** The largest package accepted (the archive; its contents are capped again by {@link AppPackage}). */
    static final int MAX_PACKAGE_BYTES = 64 * 1024 * 1024;

    @Override
    public void register(ApiContext api) {
        api.get("/apps", (e, m) -> { ApiContext.podScoped(e); return view(api); });
        api.post("/apps", ApiContext.withCapability("canAdminister", (e, m) -> { ApiContext.podScoped(e); return change(api, e, null, false); }));
        api.put("/apps/([^/]+)", ApiContext.withCapability("canAdminister", (e, m) -> { ApiContext.podScoped(e); return change(api, e, ApiContext.name(m), true); }));
        api.delete("/apps/([^/]+)", ApiContext.withCapability("canAdminister", (e, m) -> { ApiContext.podScoped(e); return remove(api, e, ApiContext.name(m)); }));
    }

    /** The installed Apps (empty when the server hosts no Spaces root) - read by {@code /bootstrap}. */
    static List<InstalledApps.Installed> installed(ApiContext api) {
        Path spaces = HostContext.of(api).spaces().containerRoot();
        return spaces == null ? List.of() : new InstalledApps(InstalledApps.defaultRoot(), spaces).list();
    }

    private static Object view(ApiContext api) {
        Map<String, Object> out = new LinkedHashMap<>();
        Optional<ProductDescriptor> p = ProductDescriptor.current();
        out.put("product", p.map(ProductDescriptor::identity).orElse(null));
        out.put("shipped", p.map(d -> d.apps().stream().map(a -> Map.of("id", a.id(), "version", a.version() == null ? "" : a.version())).toList()).orElse(List.of()));
        out.put("installed", installed(api).stream().map(InstalledApps.Installed::view).toList());
        out.put("signers", AppPackage.trustedSigners().keySet().stream().sorted().toList());
        out.put("liveInstall", liveInstallRefusal(api).orElse(null) == null);
        return out;
    }

    /** Why this server cannot install an App live, or empty when it can. */
    private static Optional<String> liveInstallRefusal(ApiContext api) {
        Optional<ProductDescriptor> p = ProductDescriptor.current();
        if (p.isEmpty()) return Optional.of("this server was not built as a Product (no lib/product.json), so it has no App catalog to add to");
        if (!"platform".equals(p.get().shape())) return Optional.of("Product '" + p.get().id() + "' is a Domain product: its Apps are its definition, "
                + "so an App is added to a Platform product build");
        return Optional.empty();
    }

    private static InstalledApps apps(ApiContext api) {
        Path spaces = HostContext.of(api).spaces().containerRoot();
        if (spaces == null) throw new ApiException(503, ErrorCodes.CAPABILITY_UNAVAILABLE,
                "this server hosts no Spaces root (-Dspaces.root), so an App's templates have nowhere to go");
        return new InstalledApps(InstalledApps.defaultRoot(), spaces);
    }

    private Object change(ApiContext api, HttpExchange ex, String pathId, boolean upgrade) throws IOException {
        InstalledApps apps = apps(api);
        liveInstallRefusal(api).ifPresent(why -> { throw new ApiException(409, ErrorCodes.CONFLICT, why); });
        AppPackage pkg;
        try {
            pkg = AppPackage.verify(api.rawBody(ex, MAX_PACKAGE_BYTES), AppPackage.trustedSigners());
        } catch (AppPackage.Refused bad) {
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "the package is refused: " + bad.getMessage());
        }
        if (pathId != null && !pathId.equals(pkg.id()))
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "the package is App '" + pkg.id() + "', not '" + pathId + "'");
        String platform = AppPackage.text(pkg.app(), "platform");
        if (!InstalledApps.satisfies(ProductVersion.current(), platform))
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "App '" + pkg.id() + "' needs Platform " + platform
                    + ", and this server is " + ProductVersion.current());
        List<String> missing = pkg.requiredAddons().stream().filter(a -> !com.gamma.module.Entitlements.addonEntitled(a)).toList();
        if (!missing.isEmpty())
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "App '" + pkg.id() + "' needs Add-on(s) " + missing
                    + ", which this installation's entitlement does not list");
        List<String> shipped = ProductDescriptor.current().map(d -> d.apps().stream().map(ProductDescriptor.App::id).toList()).orElse(List.of());
        synchronized (InstalledApps.LOCK) {   // plan and apply as one step: what was planned is what is applied
            Map<String, Object> plan = apps.plan(pkg, upgrade, shipped);
            if (!((List<?>) plan.get("findings")).isEmpty())
                throw new ApiException(409, ErrorCodes.CONFLICT, String.join("; ", ((List<?>) plan.get("findings")).stream().map(String::valueOf).toList()));
            plan.remove("findings");
            if ("true".equals(ApiContext.query(ex, "dryRun"))) {
                plan.put("dryRun", true);
                return plan;
            }
            InstalledApps.Installed done = apps.apply(pkg, upgrade);
            audit(ex, upgrade ? "app.upgraded" : "app.installed", pkg.id(), pkg.version(), pkg.keyId());
            plan.put("installed", done.view());
            return plan;
        }
    }

    private Object remove(ApiContext api, HttpExchange ex, String id) throws IOException {
        InstalledApps apps = apps(api);
        if (id == null || !id.matches("[a-z][a-z0-9-]{1,40}"))
            throw new ApiException(400, ErrorCodes.MALFORMED_REQUEST, "'" + id + "' is not an App id");
        synchronized (InstalledApps.LOCK) {
        InstalledApps.Installed app = apps.read(id).orElseThrow(() -> new ApiException(404, ErrorCodes.NOT_FOUND,
                "App '" + id + "' is not installed on this server" + (ProductDescriptor.current().map(d -> d.apps().stream()
                        .anyMatch(a -> a.id().equals(id))).orElse(false) ? " (it ships with the build)" : "")));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("app", id);
        out.put("version", app.version());
        out.put("templates", app.templates());
        if ("true".equals(ApiContext.query(ex, "dryRun"))) {
            out.put("dryRun", true);
            out.put("spacesKept", apps.spacesMadeFrom(id));
            return out;
        }
        out.put("spacesKept", apps.remove(id));
        audit(ex, "app.removed", id, app.version(), app.keyId());
        return out;
        }
    }

    private static void audit(HttpExchange ex, String action, String id, String version, String keyId) {
        try {
            EventLog.current().emit(Event.builder(EventType.AUDIT).source("audit")
                    .message(ApiContext.actor(ex) + " " + action.substring(4) + " App " + id + " " + version)
                    .actor(ApiContext.actor(ex)).actorType(ApiContext.actorType(ex))
                    .action(action).actionCategory("configuration")
                    .attr("app", id).attr("version", version).attr("signedBy", keyId));
        } catch (RuntimeException ignored) {
            // an audit sink that is absent or failing never undoes the change it records
        }
    }
}
