package com.gamma.control;

import com.gamma.config.spec.ConfigSpecs;
import com.gamma.service.SpaceContext;
import com.gamma.service.SpaceManager;
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
        // HOME-VERSION-1: the deployment reports its own version, pre-sign-in like branding — a support
        // call reads it off the sign-in page. "dev" for an unstamped (target/classes) run, by design.
        data.put("version", ProductVersion.current());
        // D12: the deployment's topology, so an operator can tell a single node from one member of a set
        // that shares state — which decides whether a degraded store is tolerable or a boot failure.
        data.put("topology", com.gamma.util.Topology.mode().name().toLowerCase());
        data.put("features", features(api));
        // DEMO-AUTH-1: the session broker's pre-sign-in context (the demo relay's Demo User picker). Omitted when
        // no relay is installed or it publishes nothing — the OIDC relay, and Personal, are unchanged.
        TokenRelays.active().map(TokenRelay::bootstrapAuth).filter(a -> !a.isEmpty()).ifPresent(a -> data.put("auth", a));
        // Landing-page plan D2 (2026-09-15): the deployment's own branding is PRE-SIGN-IN CONTEXT, not
        // inventory, so it is served to an anonymous caller too — it is what makes the sign-in page belong
        // to this deployment, and the operator authored it precisely to be displayed. ⚠ The SPA cannot get
        // it the usual way there: `GET /settings/branding` sits behind the auth gate, so a sign-in page
        // calling `BrandingService` would 401 on exactly the screen that needs it.
        data.put("branding", branding(api));
        // Landing-page plan D2 (2026-09-15): before sign-in a Standard/Enterprise deployment tells an
        // unauthenticated caller only what the SPA needs to START the OIDC redirect — edition, topology,
        // feature flags and the anonymous session. The Space roster and the spec catalogue are
        // deployment inventory and wait for a bearer. Personal registers no Authenticator, so it is
        // unchanged: there is no "before sign-in" to protect. Derived from the SPI slot, not the edition.
        if (!(Authenticators.active().isPresent() && ApiContext.subject(ex).isEmpty())) {
            data.put("configSpecs", configSpecs());
            data.put("enumerations", enumerations());
            data.put("spaces", spaces(api));
        }
        data.put("session", session(ex));

        String etag = ETags.of(ContentHash.of(data));
        if (ETags.isFresh(ex, etag)) return ETags.notModified(ex, etag);
        ETags.set(ex, etag);
        return data;
    }

    /** Auth-free core = Personal; the Professional build (security module + {@code -Dauth.mode=oidc}) reports itself. */
    private static String edition() {
        return "none".equalsIgnoreCase(System.getProperty("auth.mode", "none")) ? "personal" : "professional";
    }

    private static Map<String, Object> features(ApiContext api) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("authoring", writeRootOrNull(api) != null); // write-root set ⇒ config authoring enabled
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
        // UIE-9: the Home exposure notice keys off the real bind, not off authMode alone.
        f.put("loopbackOnly", ControlApi.bindsLoopbackOnly());
        return f;
    }

    /**
     * The bound Space's config root, or {@code null} when ZERO Spaces are hosted. ⛔ R2-19 (2026-09-26):
     * {@code api.writeRoot()} resolves the current Space and throws {@link SpaceManager.NoSpaceHostedException}
     * on an empty spaces root, which the error boundary turns into a 503 for the WHOLE bootstrap. The SPA
     * reads a failed bootstrap as {@code {}} — authMode {@code none}, not loopback — so a fresh demo bundle
     * (which ships no Spaces) showed the "listening on every network interface, no sign-in" banner while
     * bound to 127.0.0.1 behind demo sign-in. Bootstrap is platform-level and must answer with no Space.
     */
    private static java.nio.file.Path writeRootOrNull(ApiContext api) {
        try {
            return api.writeRoot();
        } catch (SpaceManager.NoSpaceHostedException none) {
            return null;
        }
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

    /** The bound space's {@code branding.toon}, in the same wire shape {@code GET /settings/branding}
     *  serves — nulls kept, so a client with no configured branding falls back to the shipped defaults. */
    private static Map<String, Object> branding(ApiContext api) {
        java.nio.file.Path root = writeRootOrNull(api);
        BrandingSettings b = root == null
                ? BrandingSettings.EMPTY
                : BrandingSettings.read(root.resolve("branding.toon"));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("logoDataUrl", b.logoDataUrl());
        m.put("caption", b.caption());
        m.put("footerText", b.footerText());
        return m;
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
