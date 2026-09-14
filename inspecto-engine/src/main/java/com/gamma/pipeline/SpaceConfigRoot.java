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

    private SpaceConfigRoot() {
    }

    /** Publish (or replace) a Space's config root; {@code null}s are ignored. */
    public static void register(String spaceId, Path configRoot) {
        if (spaceId != null && configRoot != null) ROOTS.put(spaceId, configRoot);
    }

    /** Drop a Space's registration (on Space deletion). */
    public static void forget(String spaceId) {
        if (spaceId != null) ROOTS.remove(spaceId);
    }

    /** Clear every registration (tests). */
    public static void clear() {
        ROOTS.clear();
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
