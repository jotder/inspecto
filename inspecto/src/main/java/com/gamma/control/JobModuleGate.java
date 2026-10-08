package com.gamma.control;

import com.gamma.module.ModuleManifest;
import com.gamma.module.ModuleManifests;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * The per-Space Enabled gate for BACKGROUND work (MODULE-REORG-1 P4e; plan section 2.5, D-MR10). The gate on the HTTP
 * surface ({@code ControlApi.requireModuleEnabled}) stops a disabled module's routes; this one stops the Jobs of the
 * Job Types the module contributes ({@code provides.jobTypes}). The owning module of a type is read from the INSTALLED
 * manifests; a type no manifest declares (a built-in, a Job Pack) belongs to no module and is never gated.
 *
 * <p>Used twice with one sentence: the scheduler records a turned-away fire as a {@code SKIPPED} run
 * ({@code JobService.jobTypeGate}), and a manual trigger or replay answers 404 {@code MODULE_DISABLED}, the same
 * envelope as a disabled module's routes. The Job config is never touched.
 */
public final class JobModuleGate {
    private JobModuleGate() {}

    private static volatile Map<String, ModuleManifest> ownerByType;

    /** Test seam: a stand-in owner table for a class path that carries no optional module; {@code null} restores the real one. */
    static void ownersForTest(Map<String, ModuleManifest> owners) {
        ownerByType = owners;
    }

    private static Map<String, ModuleManifest> owners() {
        Map<String, ModuleManifest> o = ownerByType;
        if (o == null) ownerByType = o = ownersOf(ModuleManifests.load(JobModuleGate.class.getClassLoader()).manifests());
        return o;
    }

    static Map<String, ModuleManifest> ownersOf(List<ModuleManifest> installed) {
        Map<String, ModuleManifest> out = new LinkedHashMap<>();
        for (ModuleManifest m : installed)
            for (String t : m.provides().jobTypes()) out.putIfAbsent(t.toLowerCase(java.util.Locale.ROOT), m);
        return out;
    }

    /** Why a Job of {@code jobType} may not run when {@code disabled} are the Space's switched-off features; {@code null} when it may. */
    public static String reason(String jobType, Set<String> disabled) {
        return reason(jobType, disabled, owners());
    }

    static String reason(String jobType, Set<String> disabled, Map<String, ModuleManifest> owners) {
        if (jobType == null || disabled.isEmpty()) return null;
        ModuleManifest m = owners.get(jobType.toLowerCase(java.util.Locale.ROOT));
        if (m == null) return null;
        for (String f : m.provides().features())
            if (disabled.contains(f))
                return "the '" + f + "' module is switched off in this Space (an administrator can enable it with "
                        + "PUT /settings/modules); its Job Type '" + jobType + "' does not run";
        return null;
    }

    /** The gate for the Space whose config root is {@code configRoot}: reads {@code modules.toon} at every call. */
    public static Function<String, String> forSpace(Path configRoot) {
        return type -> reason(type, ModuleSettings.disabled(configRoot));
    }
}
