package com.gamma.pipeline;

import com.gamma.event.EventLog;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The current Space's config root, for engine code that has to read the component registry at run time.
 *
 * <p><b>Why this exists</b> ({@code MATERIALIZE-SPACE-ROOT-1}, 2026-09-15). Registry-reading job types used
 * to read {@code -Dassist.write.root} directly — a single <b>JVM-wide</b> value — while every control-plane
 * route resolves {@code currentContext().root().config()}, <b>this Space's</b> config root. In a
 * multi-Space deployment the two disagree, so a job read one Space's registry while writing another
 * Space's data directory. The symptom was a run failing with {@code unknown dataset '<id>'} naming an id
 * the route had just resolved successfully, moments earlier, from the other registry.
 *
 * <p>⚠ <b>Single-Space deployments make the two identical</b> — Personal, and every test — which is why
 * the defect survived: it is a no-op everywhere it was ever exercised.
 *
 * <p><b>How it resolves.</b> {@code SpaceBootstrap} (control-plane module) publishes each Space's config
 * root under its id; a lookup keys on {@link EventLog#currentSpaceId()}, which the space MDC carries onto the
 * job worker thread (both {@code JobService} submit paths set it, so this is correct inside a run, not
 * only on the request thread). The <b>default</b> Space alone falls back to {@code -Dassist.write.root},
 * the legacy single-tenant write root — mirroring how the control plane resolves the same registry, and
 * keeping every single-Space deployment byte-identical.
 *
 * <p>⛔ A <b>named</b> Space deliberately does <b>not</b> fall back to the JVM property. Falling back is
 * what produced the defect: reading some other Space's registry is worse than reporting that this one has
 * none, because it fails far away and looks like missing data rather than a misconfiguration.
 */
public final class SpaceConfigRoot {

    /** {@code space id -> config root}; keyed by {@link EventLog#currentSpaceId()}. */
    private static final Map<String, Path> ROOTS = new ConcurrentHashMap<>();

    /** {@code space id -> data root}; the sibling of {@link #ROOTS}, and never registered without it. */
    private static final Map<String, Path> DATA_ROOTS = new ConcurrentHashMap<>();

    /**
     * {@code space id -> config READ root} ({@code JOB-PATH-PIPELINEJOBRUNNER-SPLIT-1}, operator 2026-09-17,
     * option 1: ADDITIVE). Consulted only by {@link #currentConfigReadRoot()}; {@link #ROOTS} stays what
     * {@link #current()} / {@link #currentRegistry()} / {@link #forSpace} resolve, untouched.
     */
    private static final Map<String, Path> READ_ROOTS = new ConcurrentHashMap<>();

    private SpaceConfigRoot() {
    }

    /**
     * Publish (or replace) a Space's config root; {@code null}s are ignored.
     *
     * <p>⛔ <b>Register the data root in the same breath</b> ({@link #registerDataRoot}). A caller that
     * resolves the config root per-Space while still taking the data root from a JVM-wide property
     * reproduces {@code MATERIALIZE-SPACE-ROOT-1} exactly — one Space's registry read beside another
     * Space's data. Half of this fix is worse than none of it.
     */
    public static void register(String spaceId, Path configRoot) {
        if (spaceId != null && configRoot != null) ROOTS.put(spaceId, configRoot);
    }

    /** Publish (or replace) a Space's data root — the sibling of {@link #register}; see its note. */
    public static void registerDataRoot(String spaceId, Path dataRoot) {
        if (spaceId != null && dataRoot != null) DATA_ROOTS.put(spaceId, dataRoot);
    }

    /** Drop a Space's registration (on Space deletion) — both roots, so neither can outlive the other. */
    public static void forget(String spaceId) {
        if (spaceId != null) {
            ROOTS.remove(spaceId);
            DATA_ROOTS.remove(spaceId);
            READ_ROOTS.remove(spaceId);
        }
    }

    /** Clear every registration (tests). */
    public static void clear() {
        ROOTS.clear();
        DATA_ROOTS.clear();
        READ_ROOTS.clear();
    }

    /**
     * Publish (or replace) the root a Space's <b>job-authored config paths</b> resolve against — the base a
     * job's relative {@code pipeline_config:} / {@code data_dir:} means — separately from the write root
     * {@link #register} publishes. {@code null}s are ignored.
     *
     * <p>Why a second root ({@code JOB-PATH-PIPELINEJOBRUNNER-SPLIT-1}): in the single-tenant layout the
     * write root ({@code -Dassist.write.root}, where {@code out/}, {@code registry/} live) and the root a
     * job's relative path has always meant (the launch directory, {@code LegacySpaceRoot.base()}) are
     * DIFFERENT directories. Resolving a job path against {@link #current()} there would silently re-point
     * every committed relative value — the 19-value breakage the runner's own comment documents. So the
     * control plane registers the read root explicitly ({@code SpaceManager.single()}), and
     * {@link #current()} keeps meaning exactly what it means today.
     */
    public static void registerConfigReadRoot(String spaceId, Path readRoot) {
        if (spaceId != null && readRoot != null) READ_ROOTS.put(spaceId, readRoot);
    }

    /**
     * This Space's data root, or {@code null} when it has none.
     *
     * <p>🔴 <b>The precedence here is the OPPOSITE of {@link #current()}, deliberately, because the two
     * lanes already disagreed before this class existed and silently "harmonising" them would change
     * behaviour.</b> For the <b>data</b> root an explicitly-set {@code -Ddata.dir} <b>wins</b> over the
     * Space's own directory — the exact rule {@code CollectorService}'s four call sites apply
     * ({@code System.getProperty("data.dir", root.dataDir())}). For the <b>config</b> root the Space wins
     * and the property is only the default-Space fallback — the rule {@code ControlApi.writeRoot()}
     * applies. ⛔ Do not "fix" this asymmetry here; it is a product-level question, and matching each
     * lane's existing rule is what keeps this class a refactor rather than a behaviour change.
     */
    public static Path currentDataRoot() {
        String dd = System.getProperty("data.dir");
        if (dd != null && !dd.isBlank()) return Path.of(dd.trim());
        return DATA_ROOTS.get(EventLog.currentSpaceId());
    }

    /**
     * This Space's config root, or {@code null} when it has none — the default Space falling back to
     * {@code -Dassist.write.root}. See the class note for why a named Space does not.
     */
    public static Path current() {
        return forSpace(EventLog.currentSpaceId());
    }

    /**
     * A named Space's config root, for a caller that already holds the id — preferred over {@link #current()}
     * there, since an explicit id cannot be wrong the way an unset MDC silently can. Same default-Space
     * fallback as {@link #current()}.
     */
    public static Path forSpace(String spaceId) {
        Path root = spaceId == null ? null : ROOTS.get(spaceId);
        if (root != null) return root;
        if (spaceId != null && !EventLog.DEFAULT_SPACE_ID.equals(spaceId)) return null;
        String wr = System.getProperty("assist.write.root");
        return wr == null || wr.isBlank() ? null : Path.of(wr.trim());
    }

    /**
     * The root this Space's <b>job-authored config paths</b> resolve against — the {@code base} for
     * {@code PathJail.requireJobPathUnderAny} in a job runner. Never {@code null} for the default Space.
     *
     * <p>Resolution, most specific first:
     * <ol>
     *   <li>a root published by {@link #registerConfigReadRoot} — the control plane's explicit answer;</li>
     *   <li>the Space's config root from {@link #register} — a self-contained Space ({@code SpaceBootstrap.load})
     *       has ONE root that serves both roles, its {@code config/} directory;</li>
     *   <li>for the <b>default</b> Space only, the process launch directory — {@code Path.of("").toAbsolutePath()},
     *       the twin of {@code LegacySpaceRoot.base()}, which this module cannot import (it lives above the
     *       engine). This is exactly the rule a job's relative path has always been resolved by, so a reader
     *       that runs before {@code SpaceManager.single()} has registered anything — the engine CLI, and every
     *       {@code PipelineJobRunner} test, which constructs the runner directly — is byte-identical to before.
     *       ⛔ It deliberately does NOT fall back to {@code -Dassist.write.root} the way {@link #current()}
     *       does: that root is where the engine WRITES, and treating it as the base for authored paths is
     *       the re-point this accessor exists to avoid;</li>
     *   <li>a named Space with nothing registered: {@code null} (the jail then applies its own no-Space rule),
     *       mirroring {@link #forSpace}'s refusal to hand a named Space another Space's root.</li>
     * </ol>
     */
    public static Path currentConfigReadRoot() {
        return forSpaceConfigReadRoot(EventLog.currentSpaceId());
    }

    /**
     * The job keys whose run-time reader ({@code PipelineJobRunner}) resolves against
     * {@link #currentConfigReadRoot()}; every other job path key resolves against the Space config root.
     */
    public static final java.util.Set<String> CONFIG_READ_ROOT_JOB_KEYS = java.util.Set.of("pipeline_config", "data_dir");

    /**
     * <b>The ONE answer to "what does a job's relative {@code key} resolve against"</b>
     * ({@code JOB-PATH-SINGLE-TENANT-GATE-BASE-1}) — called by the job save gates AND the pipeline runner, so
     * the two cannot drift. Before this the gates judged every key from {@link #current()} (the write root)
     * while the runner read {@code pipeline_config}/{@code data_dir} from the read root: in the single-tenant
     * layout those differ, and a job that RUNS ({@code pipeline_config: orders_pipeline.toon}) was refused at
     * save. In a self-contained Space the two roots are the same directory, so nothing changes there.
     *
     * @param key       the bare job key, e.g. {@code pipeline_config}
     * @param spaceRoot the caller's Space config root, used for every key the pipeline runner does not read
     */
    public static Path jobPathBase(String key, Path spaceRoot) {
        return CONFIG_READ_ROOT_JOB_KEYS.contains(key) ? currentConfigReadRoot() : spaceRoot;
    }

    /** {@link #jobPathBase(String, Path)} with {@link #current()} as the Space config root. */
    public static Path jobPathBase(String key) {
        return jobPathBase(key, current());
    }

    /** {@link #currentConfigReadRoot()} for a caller that already holds the Space id; {@code null} means the default Space. */
    public static Path forSpaceConfigReadRoot(String spaceId) {
        String id = spaceId == null ? EventLog.DEFAULT_SPACE_ID : spaceId;
        Path read = READ_ROOTS.get(id);
        if (read != null) return read;
        Path cfg = ROOTS.get(id);
        if (cfg != null) return cfg;
        if (!EventLog.DEFAULT_SPACE_ID.equals(id)) return null;
        return Path.of("").toAbsolutePath();
    }

    /** {@link #current()}{@code /registry} — the component registry — or {@code null} when there is none. */
    public static Path currentRegistry() {
        Path root = current();
        return root == null ? null : root.resolve("registry");
    }

    /**
     * {@link #current()} or a named failure — for a caller that cannot proceed without a registry and
     * should say so in its own words.
     *
     * @param what the capability needing it, e.g. {@code "materialize"}
     */
    public static Path requireCurrent(String what) {
        Path root = current();
        if (root == null)
            throw new IllegalStateException(what + " needs a component registry: this space ('"
                    + EventLog.currentSpaceId() + "') has no config root registered, and only the default "
                    + "space falls back to -Dassist.write.root");
        return root;
    }
}
