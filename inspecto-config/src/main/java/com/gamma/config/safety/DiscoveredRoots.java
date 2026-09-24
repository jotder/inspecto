package com.gamma.config.safety;

import com.gamma.api.PublicApi;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The dynamic half of the allowed roots: each hosted Space's base directory, keyed by Space id, pushed in
 * by the space lifecycle and read by {@link SafetyPolicy#forSpace(String)}.
 *
 * <p>Why this exists (BACKLOG §6 PATH-2 tier 3): the write root is <b>per-space and dynamic</b>
 * ({@code writeRoot()} derives from the current space) while {@code -Dassist.safety.roots} was
 * <b>global and static</b>. Create a space and forget to extend the property, and writes into its
 * {@code config/} pass the 403 gate — it <em>is</em> that space's own config dir — while every
 * schema/grammar ref inside it is refused at load, because the base was never an allowed root. The
 * write half derived; the policy half did not. Now both do, and the declared list goes back to meaning
 * only what it is for — destinations <em>outside</em> the layout ({@code backup_dir: /mnt/backups}).
 *
 * <p>🔴 <b>Per Space, not a union</b> ({@code CROSS-SPACE-JAIL-1}, 2026-09-24). Until then this was a flat
 * set and {@code defaultPolicy()} unioned <em>every</em> hosted base into every check, so a job saved in
 * Space A with an absolute {@code backup_dir} inside Space B's base passed the 422 gate and the run-time
 * jail: B was an allowed root for everyone. A Space's allowed roots are now <b>its own base plus the
 * operator-declared roots</b>, and nothing else — which is why registration carries the Space id.
 *
 * <p>This class lives here rather than beside the space lifecycle because the module graph points the
 * other way: {@code inspecto} (where {@code SpaceManager} lives) depends on {@code inspecto-config},
 * never the reverse — so the lifecycle <b>pushes</b> and the policy reads. The engine CLI / job-runner
 * entry points never run space discovery, and for them this map is simply empty: the operator-declared
 * property remains their only source. The legacy single-tenant space registers nothing either —
 * property-only behaviour there is deliberate and unchanged.
 *
 * <p>Lifecycle rules, decided 2026-08-14: a space created <b>at runtime</b> is an allowed root for itself
 * immediately (the policy recomputes per call — no restart); a <b>deleted</b> space's base leaves with it.
 * Bases are stored absolute + normalised, and re-registering a Space replaces its base.
 *
 * @since 4.0.0
 */
@PublicApi(since = "4.0.0")
public final class DiscoveredRoots {

    private DiscoveredRoots() {}

    private static final Map<String, Path> ROOTS = new ConcurrentHashMap<>();

    /** Publish (or replace) a hosted Space's base directory; {@code null}s are ignored. */
    public static void register(String spaceId, Path base) {
        if (spaceId != null && base != null) ROOTS.put(spaceId, base.toAbsolutePath().normalize());
    }

    /** Remove a no-longer-hosted Space's base (idempotent; an unknown id is a no-op). */
    public static void unregister(String spaceId) {
        if (spaceId != null) ROOTS.remove(spaceId);
    }

    /** The base registered for {@code spaceId}, for {@link SafetyPolicy#forSpace(String)}. */
    static Optional<Path> baseOf(String spaceId) {
        return spaceId == null ? Optional.empty() : Optional.ofNullable(ROOTS.get(spaceId));
    }

    /** Test hygiene only: the map is process-wide static, and a test that registers must not leak roots
     *  into the next test's containment verdicts. Production never calls this. */
    public static void clear() {
        ROOTS.clear();
    }
}
