package com.gamma.control;

import com.gamma.spi.http.ApiContext;
import com.gamma.spi.http.RouteModule;
import com.gamma.module.KnownModules;
import com.gamma.module.ModuleActivator;
import com.gamma.module.ModuleManifest;
import com.gamma.module.ModuleManifests;
import com.gamma.module.ModuleStatus;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The installed-module topology (MODULE-REORG-1 P2a):
 * <pre>
 *   GET /modules   {hostBuildId, modules:[{id,title,buildId,buildRole,offeringRole,bindingTime,state,reasons,enabledInSpace,provides,requires}], diagnostics:[...]}
 * </pre>
 *
 * <p>Built from the manifests ({@code META-INF/inspecto/module.toon}) on the LIVE class path and resolved by
 * {@link ModuleActivator}: a module whose requirement is absent is reported {@code INERT} with a reason naming
 * the missing requirement. Read-only and carries no capability — like every other authenticated GET it needs no
 * {@code CapabilityManifest} entry. The class path cannot change while the process runs, so the report is built
 * once, lazily; {@code enabledInSpace} is per request (P2b: false when the CURRENT Space switched one of the module's
 * features off, {@code PUT /settings/modules}).
 */
final class ModulesRoutes implements RouteModule {

    private volatile ModuleManifests.Loaded loaded;
    private volatile List<ModuleManifest> known;

    @Override
    public void register(ApiContext api) {
        api.get("/modules", (e, m) -> ETags.respond(e, build(loaded(), api.disabledFeatures(), known())));
    }

    private ModuleManifests.Loaded loaded() {
        ModuleManifests.Loaded l = loaded;
        if (l == null) loaded = l = ModuleManifests.load(ModulesRoutes.class.getClassLoader(), ModulesRoutes.class);
        return l;
    }

    /** Every module of the source tree (P3b), whether installed or not; read once. */
    private List<ModuleManifest> known() {
        List<ModuleManifest> k = known;
        if (k == null) known = k = KnownModules.load(ModulesRoutes.class.getClassLoader()).manifests();
        return k;
    }

    static Map<String, Object> build(ModuleManifests.Loaded loaded) {
        return build(loaded, Set.of());
    }

    static Map<String, Object> build(ModuleManifests.Loaded loaded, Set<String> disabled) {
        return build(loaded, disabled, List.of());
    }

    /**
     * {@code disabled}: the feature ids the current Space switched off; a module is {@code enabledInSpace} unless one of
     * its features is. {@code known}: every module of the source tree; those not among the installed manifests are
     * listed {@code state: "not-installed"} and make a dependant that requires one go INERT, naming it.
     */
    static Map<String, Object> build(ModuleManifests.Loaded loaded, Set<String> disabled, List<ModuleManifest> known) {
        List<ModuleManifest> absent = KnownModules.absent(known, loaded.manifests());
        List<ModuleStatus> statuses = ModuleActivator.resolve(loaded.manifests(), loaded.hostBuildId(),
                absent.stream().map(ModuleManifest::id).collect(java.util.stream.Collectors.toSet()));
        List<Object> modules = new ArrayList<>();
        for (int i = 0; i < statuses.size(); i++) {
            ModuleManifest m = loaded.manifests().get(i);
            ModuleStatus s = statuses.get(i);
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("id", m.id());
            o.put("title", m.title());
            o.put("buildRole", m.buildRole());
            o.put("offeringRole", m.offeringRole());
            o.put("bindingTime", m.bindingTime());
            o.put("buildId", m.buildId());
            o.put("state", s.state().name());
            o.put("reasons", s.reasons());
            o.put("enabledInSpace", m.provides().features().stream().noneMatch(disabled::contains));
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("features", m.provides().features());
            p.put("contracts", m.provides().contracts());
            p.put("capabilities", m.provides().capabilities());
            p.put("configKinds", m.provides().configKinds());
            p.put("storeFamilies", m.provides().storeFamilies());
            p.put("jobTypes", m.provides().jobTypes());
            o.put("provides", p);
            Map<String, Object> q = new LinkedHashMap<>();
            q.put("modules", m.requires().modules());
            q.put("contracts", m.requires().contracts());
            o.put("requires", q);
            modules.add(o);
        }
        for (ModuleManifest m : absent) {
            Map<String, Object> o = describe(m, "not-installed");
            String why = "module " + m.id() + " is not on the class path";
            o.put("reason", why);
            o.put("reasons", List.of(why));
            o.put("enabledInSpace", false);
            modules.add(o);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("hostBuildId", loaded.hostBuildId());
        out.put("modules", modules);
        out.put("diagnostics", loaded.diagnostics());
        return out;
    }

    /** The descriptive fields of a manifest that has no status (a known-but-absent module). */
    private static Map<String, Object> describe(ModuleManifest m, String state) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("id", m.id());
        o.put("title", m.title());
        o.put("buildRole", m.buildRole());
        o.put("offeringRole", m.offeringRole());
        o.put("bindingTime", m.bindingTime());
        o.put("buildId", null);
        o.put("state", state);
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("features", m.provides().features());
        p.put("contracts", m.provides().contracts());
        p.put("capabilities", m.provides().capabilities());
        p.put("configKinds", m.provides().configKinds());
        p.put("storeFamilies", m.provides().storeFamilies());
        p.put("jobTypes", m.provides().jobTypes());
        o.put("provides", p);
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("modules", m.requires().modules());
        q.put("contracts", m.requires().contracts());
        o.put("requires", q);
        return o;
    }
}
