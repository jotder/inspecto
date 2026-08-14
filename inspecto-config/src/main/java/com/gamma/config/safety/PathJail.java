package com.gamma.config.safety;

import com.gamma.api.PublicApi;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * The one answer to "is this path under this root", shared by every layer that needs it.
 *
 * <p>Before this class the codebase had <b>five</b> independent implementations of that question and
 * they disagreed on strength: the advisory validator re-checked symlink escape, the enforcing jail
 * guarding the HTTP write surface did not even absolutise, and the config layer's version was a
 * portability <em>preference</em> that silently fell back to the unjailed path. A boundary that is
 * spelled five ways is a boundary that holds in four places; this is the fifth.
 *
 * <p>The semantics here are the strongest of the five, applied in order:
 * <ol>
 *   <li>reject UNC/network paths outright — they are not meaningfully containable;</li>
 *   <li>absolutise <em>both</em> the candidate and the root, then normalise, so a relative root
 *       cannot silently fail to match an absolute candidate (or vice versa);</li>
 *   <li>{@link Path#startsWith} — component-wise, so {@code /rootX} is <b>not</b> under {@code /root};</li>
 *   <li>re-check symlink escape against the nearest existing ancestor's real path.</li>
 * </ol>
 *
 * <p><b>Relative values resolve against the working directory, not against the root.</b> That is
 * deliberate and load-bearing: every config this product ships authors its refs relative to the
 * server root ({@code schema_file: spaces/default/config/…}), so resolving them against the root
 * instead would double the prefix and break every space. See the plan's §2.
 *
 * <p>{@link #require} is defined in terms of {@link #contains}, so the enforcing and advisory
 * surfaces cannot drift apart — that shared truth is the point of this class, and is pinned by test.
 *
 * @since 4.0.0
 */
@PublicApi(since = "4.0.0")
public final class PathJail {

    private PathJail() {}

    /** Thrown when a value escapes its root. Callers at an HTTP edge map this to 403. */
    public static final class Escape extends RuntimeException {
        private final String field;
        private final String value;

        Escape(String field, String value, String detail) {
            super("path '" + value + "' declared by '" + field + "' " + detail);
            this.field = field;
            this.value = value;
        }

        /** The config field that declared the offending value, for an actionable message. */
        public String field() { return field; }

        /** The value exactly as authored — never the resolved path, which may leak layout. */
        public String value() { return value; }
    }

    /**
     * The roots that config-declared paths must resolve under: {@code -Dassist.safety.roots} (a
     * {@code ;}-separated list). ⚠ There is <b>no</b> working-directory fallback — see
     * {@link SafetyPolicy#defaultPolicy()}; blank yields an empty list and {@link #requireUnderAny}
     * throws on that. <i>(This sentence claimed a CWD fallback until 2026-08-14.)</i>
     *
     * <p>This is deliberately the <em>same</em> list {@link ConfigSafetyValidator} enforces at the 422
     * write gate, so a value refused at authoring time is refused at run time for the same reason and
     * against the same roots. ⛔ Do not introduce a second root source here — an earlier draft of this
     * reached for {@code -Dspaces.root}, which is read only by {@code ControlApi} for space
     * <em>discovery</em>, is unset in single-tenant mode and in the job runner, and carries no operator
     * override. A jail whose root disagrees with the gate's is a jail with a documented bypass.
     *
     * <p>The list is plural on purpose: a backup destination outside the server root is a legitimate
     * deployment ({@code backup_dir: /mnt/backups}), and the supported way to allow one is to declare
     * it, not to weaken the check.
     */
    public static List<Path> allowedRoots() {
        return SafetyPolicy.defaultPolicy().allowedRoots();
    }

    /**
     * Enforcing, against a set of roots: returns the contained absolute path if {@code value} lies
     * under <em>any</em> root, else throws.
     *
     * <p>Each root is tested with {@link #contains}, so this shares its verdict with the advisory
     * surface exactly as {@link #require} does — there is still one definition of containment.
     */
    public static Path requireUnderAny(List<Path> roots, String value, String field) {
        if (roots == null || roots.isEmpty())
            throw new IllegalArgumentException("no allowed roots configured for '" + field + "'");
        Escape first = null;
        for (Path root : roots) {
            try {
                return require(root, value, field);
            } catch (Escape e) {
                if (first == null) first = e;   // report against the first root, the usual one
            }
        }
        throw first;
    }

    /**
     * Enforcing: resolve {@code value} and return it only if contained by {@code root}.
     *
     * @param root  the containment root; absolutised and normalised before comparison
     * @param value the authored config value
     * @param field the config field name, used only to make the failure actionable
     * @return the resolved, absolute, normalised path
     * @throws Escape if the value is a UNC path, is unparseable, escapes the root, or reaches
     *                outside it through a symlink
     */
    public static Path require(Path root, String value, String field) {
        String s = value == null ? "" : value.trim();
        if (s.isEmpty()) throw new Escape(field, value == null ? "null" : value, "is blank");
        if (s.startsWith("\\\\") || s.startsWith("//"))
            throw new Escape(field, s, "is a UNC/network path, which is not allowed");

        Path candidate;
        try {
            candidate = Paths.get(s).toAbsolutePath().normalize();
        } catch (RuntimeException ex) {
            throw new Escape(field, s, "is not a valid path: " + ex.getMessage());
        }

        if (contains(root, candidate)) return candidate;

        // Contained() has already returned the verdict; re-derive only enough to say WHY, so the
        // decision has exactly one implementation and the message has no vote in it.
        Path base = root.toAbsolutePath().normalize();
        if (!candidate.startsWith(base))
            throw new Escape(field, s, "resolves to " + candidate + ", outside the root " + base);
        throw new Escape(field, s, "escapes the root " + base + " via a symlink (real path "
                + realPathOfNearestExisting(candidate) + ")");
    }

    /**
     * Predicate form, for advisory callers that must collect findings rather than throw, and the
     * single definition of containment — {@link #require} delegates its verdict here.
     */
    public static boolean contains(Path root, Path candidate) {
        if (root == null || candidate == null) return false;
        Path base = root.toAbsolutePath().normalize();
        Path abs  = candidate.toAbsolutePath().normalize();
        if (!abs.startsWith(base)) return false;

        // ⚠ Compare real-to-real. Resolving only the candidate would reject everything under a root
        // that is ITSELF a link (/tmp → /private/tmp, a linked deploy dir), because the candidate's
        // real path never starts with the unresolved base.
        Path real     = realPathOfNearestExisting(abs);
        Path baseReal = realPathOfNearestExisting(base);
        if (real == null || baseReal == null) return true;   // can't resolve; structural check stands
        return real.startsWith(baseReal);
    }

    /**
     * The real path of the nearest existing ancestor, or {@code null} when nothing on the chain
     * exists or the filesystem refuses to answer.
     *
     * <p>Walking up to an <em>existing</em> ancestor is what makes the symlink check work for a path
     * that has not been created yet — the common case for an output directory.
     */
    private static Path realPathOfNearestExisting(Path candidate) {
        try {
            Path existing = candidate;
            while (existing != null && !Files.exists(existing)) existing = existing.getParent();
            if (existing == null) return null;
            Path real = existing.toRealPath();
            // Re-attach the not-yet-existing tail so the comparison covers the whole path.
            Path tail = existing.relativize(candidate);
            return tail.toString().isEmpty() ? real : real.resolve(tail).normalize();
        } catch (IOException | RuntimeException ignored) {
            // Perms or a race; the normalised containment check has already passed.
            return null;
        }
    }
}
