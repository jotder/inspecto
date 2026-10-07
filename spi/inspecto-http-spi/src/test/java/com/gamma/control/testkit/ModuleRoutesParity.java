package com.gamma.control.testkit;

import com.gamma.spi.http.RouteModule;
import com.gamma.module.ModuleManifest;
import com.gamma.module.ModuleManifests;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.HashSet;
import java.util.List;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The honesty check for {@code provides.routes} (MODULE-REORG-1 P3b). A module's manifest declares the HTTP surface
 * the host stubs with 503 when the module is absent; this asserts that declaration EQUALS what the module's
 * {@link RouteModule}s really register, in BOTH directions. It replaces the four hand-kept {@code Absent*Routes}
 * parity tests: a comment asking two lists to agree is not a mechanism, a failing test is.
 *
 * <p>Both halves are read from the code source of {@code anchor} (the jar or classes directory of the module under
 * test), so a test class path that also carries sibling modules does not leak their routes or manifests in.
 * Registration happens on a {@link FakeApiContext}: no host, no Space.
 */
public final class ModuleRoutesParity {
    private ModuleRoutesParity() {}

    /** The {@code "METHOD path"} strings of the one {@code module.toon} at the anchor's code source. */
    public static List<String> declared(Class<?> anchor) {
        URL home = home(anchor);
        try (URLClassLoader isolated = new URLClassLoader(new URL[]{home}, null)) {
            ModuleManifests.Loaded loaded = ModuleManifests.load(isolated);
            assertEquals(List.of(), loaded.diagnostics());
            assertEquals(1, loaded.manifests().size(), "expected exactly one module.toon at " + home);
            ModuleManifest m = loaded.manifests().get(0);
            return m.provides().routes();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** The {@code "METHOD pattern"} of every non-stub route the module's discovered RouteModules register, in order. */
    public static List<String> registered(Class<?> anchor) {
        URL home = home(anchor);
        FakeApiContext api = new FakeApiContext();
        for (ServiceLoader.Provider<RouteModule> p : ServiceLoader.load(RouteModule.class).stream().toList())
            if (home.equals(home(p.type()))) p.get().register(api);
        return api.routes().stream().filter(r -> !r.stub()).map(FakeApiContext.Route::key).toList();
    }

    /** Fails with the exact routes missing from / stale in the manifest. */
    public static void assertParity(Class<?> anchor, String moduleName) {
        List<String> declared = declared(anchor);
        List<String> registered = registered(anchor);
        assertEquals(declared.size(), new HashSet<>(declared).size(), "provides.routes lists a route twice");
        Set<String> missing = new TreeSet<>(registered);
        missing.removeAll(declared);
        Set<String> stale = new TreeSet<>(declared);
        stale.removeAll(registered);
        assertEquals(Set.of(), missing, "registered by " + moduleName + " but NOT in its module.toon provides.routes - "
                + "these 404 instead of 503 where the module is absent, and are missing from docs/api/openapi-v1.json");
        assertEquals(Set.of(), stale, "declared in " + moduleName + "'s module.toon provides.routes but no longer registered - "
                + "a stub outliving its route keeps a dead path in the published contract");
    }

    private static URL home(Class<?> c) {
        return c.getProtectionDomain().getCodeSource().getLocation();
    }
}
