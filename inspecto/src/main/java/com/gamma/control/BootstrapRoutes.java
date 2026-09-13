package com.gamma.control;

import com.gamma.config.spec.ConfigSpecs;
import com.gamma.service.SpaceContext;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Platform bootstrap ({@code GET /bootstrap}; W3, design §6.1): the one cached, ETag'd call that
 * hands the UI everything the <b>backend authoritatively owns</b> to start operating — edition +
 * feature flags, every config spec (folding in {@code GET /config/spec/{type}}), the canonical
 * platform enumerations, the Space list, and a session stub. Replaces today's N× config-spec +
 * {@code /spaces} + {@code /spaces/_meta} round-trips with one (guidelines 3/5).
 *
 * <p><b>Boundary (stated deviation from §6.1).</b> The ComponentKind registry, the Visualization
 * Type registry, the {@code $}-parameter definitions and theme/icons are compile-time constants in
 * the SPA, <em>not</em> backend config — the backend has no authority over them and does not ship
 * them (that would invent a sync problem); the UI merges them client-side. Session
 * {@code capabilities} come from the authenticated {@link Subject}'s resolved grants (W6); the
 * auth-free core has no {@code Subject} (no {@link Authenticator} is ever present there), so
 * {@code authenticated} stays {@code false} and {@code capabilities} empty — Personal edition,
 * unchanged. {@code /bootstrap} itself stays public even on Standard (it is how the SPA discovers it
 * needs to start the OIDC redirect). The response is space-agnostic; a per-space bootstrap (dataset
 * descriptors, lookup values, per-Lens navigation) is a follow-on once those become backend-owned.
 */
final class BootstrapRoutes implements RouteModule {

    @Override
    public void register(ApiContext api) {
        // POD-SCOPE-DIVERGENCE-1: the `spaces` field is the answering Pod's roster, so it declares that.
        //
        // 🔴 CORRECTION 2026-09-13: this comment previously said the field "feeds the SPA's space-switcher
        // on every page load", inherited from the backlog row. That is FALSE and was never checked.
        // `session.service.ts` consumes only `edition`/`features`/`session`/`auth` from this payload and
        // contains no reference to `spaces` at all; the switcher calls GET /spaces and /spaces/_meta
        // (`spaces.service.ts:122-148`). ⚠ So `spaces` here is currently DEAD WEIGHT on the wire — the
        // sharp roster is GET /spaces, not this one.
        api.get("/bootstrap", (e, m) -> {
            ApiContext.podScoped(e);
            return bootstrap(api, e);
        });
    }

    private Object bootstrap(ApiContext api, HttpExchange ex) throws IOException {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("edition", edition());
        // D12: the deployment's topology, so an operator can tell a single node from one member of a set
        // that shares state — which decides whether a degraded store is tolerable or a boot failure.
        data.put("topology", com.gamma.util.Topology.mode().name().toLowerCase());
        data.put("features", features(api));
        data.put("configSpecs", configSpecs());
        data.put("enumerations", enumerations());
        data.put("spaces", spaces(api));
        data.put("session", session(ex));

        String etag = ETags.of(ContentHash.of(data));
        if (ETags.isFresh(ex, etag)) return ETags.notModified(ex, etag);
        ETags.set(ex, etag);
        return data;
    }

    /** Auth-free core = Personal; the Standard build (security module + {@code -Dauth.mode=oidc}) reports itself. */
    private static String edition() {
        return "none".equalsIgnoreCase(System.getProperty("auth.mode", "none")) ? "personal" : "standard";
    }

    private static Map<String, Object> features(ApiContext api) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("authoring", api.writeRoot() != null);      // write-root set ⇒ config authoring enabled
        f.put("multiSpace", api.spaces().supportsCrud());
        // EDITIONS SEC-10 (EDG-01 cell 4): BOTH conditions. The multi-space runtime is necessary
        // (-Dspaces.root) but not sufficient — the optional inspecto-exchange module must also have
        // registered. ⚠ Until 2026-09-07 this was the containerRoot check alone, so a Personal install with
        // -Dspaces.root reported exchange:true and the SPA's Share buttons 404'd on click. Derived from what
        // registered, never guessed; the stub does not count (ApiContext.hasRoute excludes stubs).
        f.put("exchange", api.spaces().containerRoot() != null && api.hasRoute("POST", "/exchange/offers"));
        // EDITIONS CP-09 (EDG-01 cell 3b): true only when the optional inspecto-geo-link module actually
        // registered its routes — derived, never an edition guess. The SPA hides the two nav entries and
        // the two widget offers on it.
        f.put("geoLink", api.hasRoute("POST", "/geo/projection") && api.hasRoute("POST", "/inv/projection"));
        // EDITIONS CP-13 second half (EDG-01 cell 6): true only when the optional inspecto-events module
        // registered the feed. ⚠ Probed on /events/search, a LITERAL path — not on /events/([^/]+), whose
        // regex would also be the shape of a catch-all, and not on /events, which is the one path a future
        // core route is most likely to reclaim. The SPA drops the Events nav entry and falls the Ops lens
        // home back to pipelines when this is false, so nobody lands on a screen that only 503s.
        f.put("events", api.hasRoute("GET", "/events/search"));
        // EDITIONS CP-11 (EDG-01 cell 7): true only when the optional inspecto-ops module registered the
        // operational-object routes. ⚠ Probed on POST /objects — a LITERAL path, and a WRITE, so it cannot
        // be confused with the GET /objects/([^/]+) catch-all that a future core route might reclaim.
        // The SPA hides the Incidents, Cases and Tags nav entries and the cross-entity tag menus on it.
        f.put("ops", api.hasRoute("POST", "/objects"));
        f.put("authMode", System.getProperty("auth.mode", "none"));
        return f;
    }

    /** Every config spec in one payload (the {@code GET /config/spec/{type}} calls folded together). */
    private static Map<String, Object> configSpecs() {
        Map<String, Object> specs = new LinkedHashMap<>();
        for (String type : ConfigSpecs.TYPES) specs.put(type, ConfigSpecs.forType(type));
        return specs;
    }

    /** Canonical platform enumerations (mirror the binding GLOSSARY lists; the R4 severity ladder). */
    private static Map<String, Object> enumerations() {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("severities", List.of("trace", "debug", "info", "warn", "error", "critical"));
        e.put("attributeTypes", List.of("string", "integer", "decimal", "boolean", "date",
                "datetime", "time", "currency", "enum", "array", "object"));
        e.put("outputFormats", List.of("CSV", "PARQUET"));
        return e;
    }

    private Object spaces(ApiContext api) {
        return api.spaces().all().stream()
                .sorted(Comparator.comparing(c -> c.id().value()))
                .map(BootstrapRoutes::spaceEntry)
                .toList();
    }

    private static Map<String, Object> spaceEntry(SpaceContext ctx) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", ctx.id().value());
        m.put("displayName", ctx.manifest().displayName());
        return m;
    }

    /** {@code authenticated}/{@code capabilities} reflect the {@link Subject} the security module resolved
     *  for this request (W6); absent on Personal edition, where this is still the honor-system stub. */
    private static Map<String, Object> session(HttpExchange ex) {
        Map<String, Object> s = new LinkedHashMap<>();
        var subject = ApiContext.subject(ex);
        s.put("authenticated", subject.isPresent());
        s.put("actor", ApiContext.actor(ex));
        s.put("capabilities", subject.map(sub -> List.copyOf(sub.capabilities())).orElse(List.of()));
        return s;
    }
}
