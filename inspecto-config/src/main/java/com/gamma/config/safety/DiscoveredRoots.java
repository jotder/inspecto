package com.gamma.config.safety;

import com.gamma.api.PublicApi;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The dynamic half of the allowed-roots union: every hosted space's base directory, pushed in by the
 * space lifecycle and read by {@link SafetyPolicy#defaultPolicy()}.
 *
 * <p>Why this exists (BACKLOG §6 PATH-2 tier 3): the write root is <b>per-space and dynamic</b>
 * ({@code writeRoot()} derives from the current space) while {@code -Dassist.safety.roots} was
 * <b>global and static</b>. Create a space and forget to extend the property, and writes into its
 * {@code config/} pass the 403 gate — it <em>is</em> that space's own config dir — while every
 * schema/grammar ref inside it is refused at load, because the base was never an allowed root. The
 * write half derived; the policy half did not. Now both do: the allowed roots are the union of every
 * discovered space base plus whatever the operator declares, and the declared list goes back to
 * meaning only what it is for — destinations <em>outside</em> the layout ({@code backup_dir:
 * /mnt/backups}).
 *
 * <p>This class lives here rather than beside the space lifecycle because the module graph points the
 * other way: {@code inspecto} (where {@code SpaceManager} lives) depends on {@code inspecto-config},
 * never the reverse — so the lifecycle <b>pushes</b> and the policy reads. The engine CLI / job-runner
 * entry points never run space discovery, and for them this set is simply empty: the operator-declared
 * property remains their only source, exactly as before. The legacy single-tenant space registers
 * nothing either — property-only behaviour there is deliberate and unchanged.
 *
 * <p>Lifecycle rules, decided 2026-08-14: a space created <b>at runtime</b> extends the roots
 * immediately ({@code defaultPolicy()} recomputes per call, so the next check sees it — no restart);
 * a <b>deleted</b> space leaves the union, so the root set cannot only ever grow within a process
 * lifetime. Registration keys on the normalised absolute base, so re-registering is idempotent.
 *
 * @since 4.0.0
 */
@PublicApi(since = "4.0.0")
public final class DiscoveredRoots {

    private DiscoveredRoots() {}

    private static final Set<Path> ROOTS = ConcurrentHashMap.newKeySet();

    /** Add a hosted space's base directory to the allowed-roots union (idempotent). */
    public static void register(Path base) {
        if (base != null) ROOTS.add(base.toAbsolutePath().normalize());
    }

    /** Remove a no-longer-hosted space's base from the union (idempotent; unknown bases are a no-op). */
    public static void unregister(Path base) {
        if (base != null) ROOTS.remove(base.toAbsolutePath().normalize());
    }

    /** The currently registered bases, for {@link SafetyPolicy#defaultPolicy()} to union in. */
    static List<Path> snapshot() {
        return List.copyOf(ROOTS);
    }

    /** Test hygiene only: the set is process-wide static, and a test that registers must not leak roots
     *  into the next test's containment verdicts. Production never calls this. */
    public static void clear() {
        ROOTS.clear();
    }
}
