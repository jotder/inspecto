package com.gamma.control;

import com.gamma.module.ModuleManifest;
import com.gamma.module.ModuleManifests;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * The per-Space Enabled gate for a module's NON-HTTP work (MODULE-REORG-1 P4f; plan section 2.5, D-MR10), one object
 * shared by the three places that need the same answer: {@link JobModuleGate} (the Jobs of a module's Job Types), the
 * module's periodic sweeps / dispatchers ({@code provides.background}) and the {@code maintenance} tasks it contributes
 * ({@code provides.maintenanceTasks}). The switch itself is {@code modules.toon} read through {@link ModuleSettings},
 * the SAME file and cache the routes' gate reads, so the three can never disagree with {@code GET /settings/modules}.
 *
 * <p><b>It is checked, not subscribed.</b> There is no start/stop lifecycle: the component that owns a periodic tick
 * asks {@link #paused} at the top of EACH tick and returns when the answer is yes. A tick turned away does nothing and
 * changes nothing - the work simply happens on the first tick after the module is switched back on. A tick already
 * running is never interrupted. A {@code modules.toon} that cannot be read disables nothing (fail-open, as for the routes).
 *
 * <p><b>Logged once per transition</b>, never per tick: the first tick that finds the work paused logs it, and the
 * first tick after re-enable logs the resume.
 */
public final class ModuleGate {
    private ModuleGate() {}

    /** The SLA sweep tick of {@code CollectorService} (owner: {@code ops}). */
    public static final String SLA_SWEEP = "sla-sweep";

    /**
     * Every background id a host component gates. {@code provides.background} of the installed manifests must declare
     * exactly the ids that have an owner; a module's parity test pins its half of this (declared == gated).
     */
    public static final Set<String> GATED_BACKGROUND = Set.of(SLA_SWEEP);

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ModuleGate.class);

    private static volatile Map<String, ModuleManifest> backgroundOwners;
    private static volatile Map<String, ModuleManifest> taskOwners;

    /** Test seam: stand-in owner tables for a class path that carries no optional module; {@code null}s restore the real ones. */
    static void ownersForTest(Map<String, ModuleManifest> background, Map<String, ModuleManifest> tasks) {
        backgroundOwners = background;
        taskOwners = tasks;
        TRANSITIONS.clear();
    }

    private static List<ModuleManifest> installed() {
        return ModuleManifests.load(ModuleGate.class.getClassLoader()).manifests();
    }

    private static Map<String, ModuleManifest> background() {
        Map<String, ModuleManifest> o = backgroundOwners;
        if (o == null) backgroundOwners = o = ownersOf(installed(), ModuleManifest.Provides::background);
        return o;
    }

    private static Map<String, ModuleManifest> tasks() {
        Map<String, ModuleManifest> o = taskOwners;
        if (o == null) taskOwners = o = ownersOf(installed(), ModuleManifest.Provides::maintenanceTasks);
        return o;
    }

    /** id (lower-case) to the first installed module that declares it, by the list {@code ids} picks out of {@code provides}. */
    static Map<String, ModuleManifest> ownersOf(List<ModuleManifest> installed,
                                                Function<ModuleManifest.Provides, List<String>> ids) {
        Map<String, ModuleManifest> out = new LinkedHashMap<>();
        for (ModuleManifest m : installed)
            for (String id : ids.apply(m.provides())) out.putIfAbsent(id.toLowerCase(Locale.ROOT), m);
        return out;
    }

    /** The first of {@code m}'s features that {@code disabled} switches off, or {@code null} when the module is on. */
    static String switchedOffFeature(ModuleManifest m, Set<String> disabled) {
        for (String f : m.provides().features()) if (disabled.contains(f)) return f;
        return null;
    }

    private static String sentence(String feature, String what) {
        return "the '" + feature + "' module is switched off in this Space (an administrator can enable it with "
                + "PUT /settings/modules); " + what;
    }

    /** Why background work {@code id} is paused when {@code disabled} are the Space's switched-off features; {@code null} when it runs. */
    static String backgroundReason(String id, Set<String> disabled, Map<String, ModuleManifest> owners) {
        if (id == null || disabled.isEmpty()) return null;
        ModuleManifest m = owners.get(id.toLowerCase(Locale.ROOT));
        String f = m == null ? null : switchedOffFeature(m, disabled);
        return f == null ? null : sentence(f, "its background work '" + id + "' is paused");
    }

    /** Why a {@code maintenance} Job of {@code task} may not run when {@code disabled} are the switched-off features; {@code null} when it may. */
    public static String taskReason(String task, Set<String> disabled) {
        return taskReason(task, disabled, tasks());
    }

    static String taskReason(String task, Set<String> disabled, Map<String, ModuleManifest> owners) {
        if (task == null || disabled.isEmpty()) return null;
        ModuleManifest m = owners.get(task.toLowerCase(Locale.ROOT));
        String f = m == null ? null : switchedOffFeature(m, disabled);
        return f == null ? null : sentence(f, "its maintenance task '" + task + "' does not run");
    }

    /** The Job gate's task half for the Space whose config root is {@code configRoot}: task id to the reason it may not run, or {@code null}. */
    public static Function<String, String> taskGateForSpace(Path configRoot) {
        return task -> taskReason(task, ModuleSettings.disabled(configRoot));
    }

    private static final Map<String, Boolean> TRANSITIONS = new ConcurrentHashMap<>();

    /**
     * Whether the tick of background work {@code id} in the Space whose config root is {@code configRoot} must do nothing
     * now (its owning module is switched off there). A work id no installed manifest declares is never paused. Logs the
     * transition, once: paused on the first tick that finds it so, resumed on the first tick after.
     */
    public static boolean paused(String id, Path configRoot) {
        return paused(id, configRoot, background());
    }

    static boolean paused(String id, Path configRoot, Map<String, ModuleManifest> owners) {
        String why = backgroundReason(id, ModuleSettings.disabled(configRoot), owners);
        String key = (configRoot == null ? "" : configRoot.toAbsolutePath().normalize().toString()) + "|" + id;
        if (why != null) {
            if (TRANSITIONS.put(key, Boolean.TRUE) == null) log.info("background work '{}' paused: {}", id, why);
            return true;
        }
        if (TRANSITIONS.remove(key) != null) log.info("background work '{}' resumed: its module is switched back on in this Space", id);
        return false;
    }

    /** The background and task ids of {@code m} that its Space switched off ({@code disabled}); empty while the module is on. */
    public static List<String> pausedWork(ModuleManifest m, Set<String> disabled) {
        if (switchedOffFeature(m, disabled) == null) return List.of();
        List<String> out = new java.util.ArrayList<>(m.provides().background());
        out.addAll(m.provides().maintenanceTasks());
        return out;
    }
}
