package com.gamma.control.testkit;

import com.gamma.control.RouteModule;
import com.gamma.module.ModuleManifest;
import com.gamma.module.ModuleManifests;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Technology Compatibility Kit for {@link RouteModule} (MODULE-REORG-1 P5a). A module's test tree subclasses this and
 * supplies {@link #module()}; every module then proves the same contract, driven on a {@link FakeApiContext} with no
 * host:
 * <ol>
 *   <li>{@code register()} throws nothing and registers at least one route;</li>
 *   <li>no {@code (method, pattern)} is registered twice;</li>
 *   <li>every MUTATING route (POST/PUT/PATCH/DELETE) is registered through {@code withCapability}, or is named in
 *       {@link #exemptMutatingRoutes()} WITH a reason;</li>
 *   <li>{@code featureIds()} is a subset of the module's own {@code module.toon} {@code provides.features};</li>
 *   <li>registering on two fresh contexts yields the same routes (no static state is consumed).</li>
 * </ol>
 * A module whose {@code register()} needs a host object cannot be driven by this kit; it does NOT subclass this - record
 * why in docs/superpower/module-architecture-reorg-plan.md section 6 "P5a as built" instead of mocking the host.
 */
public abstract class RouteModuleContract {

    /** A fresh module instance under test. */
    protected abstract RouteModule module();

    /** Mutating routes allowed to skip {@code withCapability}: key {@code "METHOD pattern"} to the reason. Default none. */
    protected Map<String, String> exemptMutatingRoutes() { return Map.of(); }

    private static final Set<String> MUTATING = Set.of("POST", "PUT", "PATCH", "DELETE");

    private FakeApiContext registered(RouteModule m) {
        FakeApiContext api = new FakeApiContext();
        m.register(api);
        return api;
    }

    @Test
    void registersAtLeastOneRoute() {
        assertFalse(registered(module()).routes().isEmpty(), "register() registered no route");
    }

    @Test
    void noDuplicateMethodAndPattern() {
        Set<String> seen = new HashSet<>();
        for (FakeApiContext.Route r : registered(module()).routes())
            assertTrue(seen.add(r.key()), "registered twice: " + r.key());
    }

    @Test
    void everyMutatingRouteIsCapabilityGatedOrExemptWithAReason() {
        Map<String, String> exempt = exemptMutatingRoutes();
        exempt.forEach((k, why) -> assertFalse(why == null || why.isBlank(), "exemption without a reason: " + k));
        Set<String> keys = new HashSet<>();
        for (FakeApiContext.Route r : registered(module()).routes()) {
            keys.add(r.key());
            if (MUTATING.contains(r.method()) && !r.gated())
                assertTrue(exempt.containsKey(r.key()), "mutating route not behind withCapability and not exempt: " + r.key());
        }
        for (String k : exempt.keySet())
            assertTrue(keys.contains(k), "exemption names a route that is not registered (stale): " + k);
    }

    @Test
    void featureIdsAreDeclaredInTheModulesOwnManifest() {
        Set<String> ids = module().featureIds();
        if (ids.isEmpty()) return;
        Set<String> provided = new HashSet<>(ownManifest().provides().features());
        assertTrue(provided.containsAll(ids), "featureIds() " + ids + " not within module.toon provides.features " + provided);
    }

    @Test
    void registeringTwiceOnFreshContextsIsIdempotent() {
        List<String> a = registered(module()).routes().stream().map(FakeApiContext.Route::key).toList();
        List<String> b = registered(module()).routes().stream().map(FakeApiContext.Route::key).toList();
        assertEquals(a, b);
    }

    /** The manifest of the jar or classes directory the module under test was loaded from (not the whole class path). */
    private ModuleManifest ownManifest() {
        URL home = module().getClass().getProtectionDomain().getCodeSource().getLocation();
        // Parent null: only this location is visible, so the other modules' module.toon files on the class path are not.
        try (URLClassLoader isolated = new URLClassLoader(new URL[]{home}, null)) {
            ModuleManifests.Loaded loaded = ModuleManifests.load(isolated);
            assertEquals(List.of(), loaded.diagnostics());
            assertEquals(1, loaded.manifests().size(), "expected exactly one module.toon at " + home);
            return loaded.manifests().get(0);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
