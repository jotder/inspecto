package com.gamma.module;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Requirement resolution (plan §2.2): a module is INERT when a required module is absent or itself inert, or a
 * required contract is provided by no ACTIVE module. Pure — no I/O. Resolved by fixpoint from an all-active
 * start, so a chain of dependants goes inert together and a dependency cycle between present modules stays
 * active (nothing in it is missing).
 */
public final class ModuleActivator {
    private ModuleActivator() {}

    /** One status per manifest, in input order. */
    public static List<ModuleStatus> resolve(List<ModuleManifest> manifests) {
        Set<String> active = new HashSet<>();
        for (ModuleManifest m : manifests) active.add(m.id());
        boolean changed = true;
        while (changed) {
            changed = false;
            for (ModuleManifest m : manifests)
                if (active.contains(m.id()) && !unmet(m, manifests, active).isEmpty()) {
                    active.remove(m.id());
                    changed = true;
                }
        }
        List<ModuleStatus> out = new ArrayList<>();
        for (ModuleManifest m : manifests) {
            boolean on = active.contains(m.id());
            out.add(new ModuleStatus(m.id(), on ? ModuleStatus.State.ACTIVE : ModuleStatus.State.INERT,
                    on ? List.of() : unmet(m, manifests, active)));
        }
        return out;
    }

    private static List<String> unmet(ModuleManifest m, List<ModuleManifest> all, Set<String> active) {
        Set<String> known = new HashSet<>();
        for (ModuleManifest x : all) known.add(x.id());
        List<String> reasons = new ArrayList<>();
        for (String r : m.requires().modules()) {
            if (!known.contains(r)) reasons.add("requires module '" + r + "', which is not installed");
            else if (!active.contains(r)) reasons.add("requires module '" + r + "', which is inert");
        }
        for (String c : m.requires().contracts()) {
            boolean provided = all.stream().anyMatch(x -> !x.id().equals(m.id()) && active.contains(x.id())
                    && x.provides().contracts().contains(c));
            if (!provided) reasons.add("requires contract '" + c + "', which no active module provides");
        }
        return reasons;
    }
}
