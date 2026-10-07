package com.gamma.control;

import com.gamma.module.KnownModules;
import com.gamma.module.ModuleManifest;

import java.util.ArrayList;
import java.util.List;

/**
 * The core's answer for the HTTP surface of every optional module that is <b>not installed</b> (EDITIONS section 4:
 * an absent capability explains itself). One generic class replaces the eight hand-written {@code Absent*Routes}
 * stubs (MODULE-REORG-1 P3b): the surface is read from {@code provides.routes} of each KNOWN module's manifest
 * ({@link KnownModules} - shipped in this jar whether or not the module is), and a parity test in the module itself
 * keeps that list equal to what the module registers.
 *
 * <p>Same contract as before, unchanged:
 * <ul>
 *   <li><b>Registered LAST</b>, after {@code ServiceLoader} discovery, and only for paths {@link ApiContext#hasRoute}
 *       reports unclaimed - so a module that IS installed is never shadowed (and never trips the duplicate guard), and a
 *       module that is on the class path but failed to link still answers 503 instead of 404.</li>
 *   <li>Through {@link ApiContext#stub}: the path occupies the route table (503, not 404) WITHOUT making
 *       {@code hasRoute} true, which {@code /bootstrap}'s derived feature flags read.</li>
 *   <li>⚠ <b>Order is the manifest's order</b> and is load-bearing inside one module: routing is first-match, so a
 *       catch-all ({@code /events/([^/]+)}, {@code /objects/([^/]+)}) stays after the literals it would swallow.
 *       {@code AbsentModuleRoutesTest} fails when any stub shadows a later one.</li>
 * </ul>
 * The refusal is 503 {@code CAPABILITY_UNAVAILABLE} carrying {@link KnownModules#absentMessage}.
 */
final class AbsentModuleRoutes implements RouteModule {

    private final List<ModuleManifest> known;

    AbsentModuleRoutes() { this(KnownModules.load(AbsentModuleRoutes.class.getClassLoader()).manifests()); }

    /** Known manifests passed as data (tests). */
    AbsentModuleRoutes(List<ModuleManifest> known) { this.known = known; }

    @Override
    public void register(ApiContext api) {
        for (ModuleManifest m : known) {
            String message = KnownModules.absentMessage(m);
            for (String[] r : surface(m)) {
                if (api.hasRoute(r[0], r[1])) continue;   // the real module is here - nothing to stub
                api.stub(r[0], r[1], (e, mt) -> { throw new ApiException(503, ErrorCodes.CAPABILITY_UNAVAILABLE, message); });
            }
        }
    }

    /** The {@code {method, regex}} pairs a manifest declares, in order. */
    static List<String[]> surface(ModuleManifest m) {
        List<String[]> out = new ArrayList<>();
        for (String route : m.provides().routes()) {
            int sp = route.indexOf(' ');
            out.add(new String[]{route.substring(0, sp), route.substring(sp + 1)});
        }
        return out;
    }

    /** The surface of the named known modules, concatenated in the order given (tests read this, not a private copy). */
    static List<String[]> surface(String... moduleIds) {
        List<ModuleManifest> all = KnownModules.load(AbsentModuleRoutes.class.getClassLoader()).manifests();
        List<String[]> out = new ArrayList<>();
        for (String id : moduleIds) {
            ModuleManifest m = all.stream().filter(x -> x.id().equals(id)).findFirst()
                    .orElseThrow(() -> new IllegalStateException("module '" + id + "' is not in known-modules"));
            out.addAll(surface(m));
        }
        return out;
    }
}
