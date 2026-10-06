package com.gamma.control;

import com.gamma.module.ModuleActivator;
import com.gamma.module.ModuleManifest;
import com.gamma.module.ModuleManifests;
import com.gamma.module.ModuleStatus;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The installed-module topology (MODULE-REORG-1 P2a):
 * <pre>
 *   GET /modules   {modules:[{id,title,buildRole,offeringRole,bindingTime,state,reasons,provides,requires}], diagnostics:[...]}
 * </pre>
 *
 * <p>Built from the manifests ({@code META-INF/inspecto/module.toon}) on the LIVE class path and resolved by
 * {@link ModuleActivator}: a module whose requirement is absent is reported {@code INERT} with a reason naming
 * the missing requirement. Read-only and carries no capability — like every other authenticated GET it needs no
 * {@code CapabilityManifest} entry. The class path cannot change while the process runs, so the report is built
 * once, lazily.
 */
final class ModulesRoutes implements RouteModule {

    private volatile Map<String, Object> report;

    @Override
    public void register(ApiContext api) {
        api.get("/modules", (e, m) -> ETags.respond(e, report()));
    }

    private Map<String, Object> report() {
        Map<String, Object> r = report;
        if (r == null) report = r = build(ModuleManifests.load(ModulesRoutes.class.getClassLoader()));
        return r;
    }

    static Map<String, Object> build(ModuleManifests.Loaded loaded) {
        List<ModuleStatus> statuses = ModuleActivator.resolve(loaded.manifests());
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
            o.put("state", s.state().name());
            o.put("reasons", s.reasons());
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("features", m.provides().features());
            p.put("contracts", m.provides().contracts());
            p.put("capabilities", m.provides().capabilities());
            p.put("configKinds", m.provides().configKinds());
            p.put("storeFamilies", m.provides().storeFamilies());
            o.put("provides", p);
            Map<String, Object> q = new LinkedHashMap<>();
            q.put("modules", m.requires().modules());
            q.put("contracts", m.requires().contracts());
            o.put("requires", q);
            modules.add(o);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("modules", modules);
        out.put("diagnostics", loaded.diagnostics());
        return out;
    }
}
