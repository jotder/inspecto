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
    public static List<ModuleStatus> resolve(List<ModuleManifest> manifests) { return resolve(manifests, null); }

    /**
     * As {@link #resolve(List)}, plus the build-id gate (plan §2.4 "build id matches"): a module whose stamp differs
     * from {@code hostBuildId} starts INERT. An absent stamp on either side (null) is "unknown", never a mismatch.
     */
    public static List<ModuleStatus> resolve(List<ModuleManifest> manifests, String hostBuildId) {
        return resolve(manifests, hostBuildId, Set.of());
    }

    /**
     * As {@link #resolve(List, String)}, plus the ids of modules that are KNOWN (their manifest shipped in
     * {@code known-modules}, P3b) but not on the class path: a dependant's reason then says the module "is not on the
     * class path" rather than the generic "is not installed". The set is data; the activator stays pure.
     */
    public static List<ModuleStatus> resolve(List<ModuleManifest> manifests, String hostBuildId, Set<String> knownAbsent) {
        Set<String> active = new HashSet<>();
        for (ModuleManifest m : manifests) if (!mismatch(m, hostBuildId)) active.add(m.id());
        boolean changed = true;
        while (changed) {
            changed = false;
            for (ModuleManifest m : manifests)
                if (active.contains(m.id()) && !unmet(m, manifests, active, hostBuildId, knownAbsent).isEmpty()) {
                    active.remove(m.id());
                    changed = true;
                }
        }
        List<ModuleStatus> out = new ArrayList<>();
        for (ModuleManifest m : manifests) {
            boolean on = active.contains(m.id());
            out.add(new ModuleStatus(m.id(), on ? ModuleStatus.State.ACTIVE : ModuleStatus.State.INERT,
                    on ? List.of() : unmet(m, manifests, active, hostBuildId, knownAbsent)));
        }
        return out;
    }

    static boolean mismatch(ModuleManifest m, String host) {
        return host != null && m.buildId() != null && !host.equals(m.buildId());
    }

    static String mismatchReason(ModuleManifest m, String host) {
        return "build id " + m.buildId() + " does not match host " + host;
    }

    private static List<String> unmet(ModuleManifest m, List<ModuleManifest> all, Set<String> active, String host,
                                      Set<String> knownAbsent) {
        Set<String> known = new HashSet<>();
        for (ModuleManifest x : all) known.add(x.id());
        List<String> reasons = new ArrayList<>();
        if (mismatch(m, host)) reasons.add(mismatchReason(m, host));
        for (String r : m.requires().modules()) {
            if (!known.contains(r)) reasons.add("requires module '" + r + "', which is "
                    + (knownAbsent.contains(r) ? "not on the class path" : "not installed"));
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
