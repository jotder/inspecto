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
 * <p><b>{@link #require} resolves a relative value against the working directory, not against the
 * root</b> — it is a containment verdict, not a resolver. A caller holding a relative value resolves it
 * FIRST, against the base that value means: {@link #resolveConfigRef} (a config's refs to other config
 * files, beside the referring config), {@link #resolveDataPath} (a config's data paths, under its Space
 * directory) or {@link #resolveJobPath} (a job's paths, against the Space config root). ⚠ Until 2026-09-23 the shipped configs spelled their refs from the server root
 * ({@code schema_file: spaces/default/config/…}) and relied on this working-directory reading, so a server
 * launched from any other directory could not load them ({@code SCHEMA-FILE-RESOLVES-AGAINST-CWD-1}).
 *
 * <p>{@link #require} is defined in terms of {@link #contains}, so the enforcing and advisory
 * surfaces cannot drift apart — that shared truth is the point of this class, and is pinned by test.
 *
 * @since 4.0.0
 */
@PublicApi(since = "4.0.0")
public final class PathJail {

    private PathJail() {}

    /**
     * A URI scheme followed by {@code ://} — {@code s3://bucket/x}, {@code gs://…}, {@code file://…}.
     *
     * <p>⚠ The scheme is required to be <b>two or more</b> characters so a Windows drive letter
     * ({@code C:/data}, {@code C:\data}) is not mistaken for one, and {@code ://} is required so an
     * ordinary POSIX path that merely contains a colon ({@code out/my:file}) stays legal.
     */
    private static final java.util.regex.Pattern URI_SCHEME =
            java.util.regex.Pattern.compile("^[A-Za-z][A-Za-z0-9+.\\-]+://");

    /**
     * Whether {@code value} is a URI rather than a filesystem path — the one definition, so the
     * enforcing and advisory surfaces cannot disagree about it.
     *
     * <p>🔴 This exists because {@code Paths.get} answers the question <b>differently on different
     * platforms</b>, and the divergence favours the wrong side on the deployment target. Measured
     * 2026-09-14 with {@code "s3://bucket/data"}:
     * <ul>
     *   <li><b>Windows</b> — {@code InvalidPathException: Illegal char &lt;:&gt; at index 2}, so
     *       {@link #require} caught it and refused. Fail-closed, by accident.</li>
     *   <li><b>Linux</b> (JDK 26, the shipped {@code linux_amd64} target) — <b>no throw</b>. It
     *       yields {@code /s3:/bucket/data}: a real local directory literally named {@code s3:},
     *       resolved against the working directory. {@link #contains} then returns a confident,
     *       wrong answer about a path the operator never meant.</li>
     * </ul>
     * ⛔ So the pre-existing behaviour was not "object stores are unsupported" — it was "unsupported
     * on the machine developers probe from, silently accepted on the machine it ships to." That is the
     * same shape as a connection string with no recognised backend quietly becoming a local file.
     *
     * <p>⚠ When object-store paths ARE supported (scale-out plan §5.4 bullets 1 and 6), the fix is to
     * dispatch on this predicate — <b>not</b> to delete it. A path jail cannot contain a bucket URI by
     * {@link Path} comparison at all, so an object-store path needs its own containment rule.
     */
    public static boolean isUri(String value) {
        return value != null && URI_SCHEME.matcher(value.trim()).find();
    }

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
    /**
     * <b>Resolve a JOB's path value (`JOB-DIR-CWD-CONTAINMENT-1`, operator 2026-09-16).</b> A relative
     * path in a job config resolves against the <b>Space's config root</b>, never the process working
     * directory — a job whose meaning depends on how the server happened to be launched is the defect
     * this closes.
     *
     * <p>⛔ <b>This is the ONE place that rule lives.</b> The 422 write gate
     * ({@code ConfigSafetyValidator.checkJob}) and the run-time tasks both call it, because a validator
     * that resolved against the Space root while the tasks kept resolving against the CWD would pass a
     * draft the run then jails — the exact split this method exists to end.
     *
     * <p>⚠ <b>The ambiguous case is REFUSED, not silently relocated</b> (operator's call): when the
     * value does not exist under the Space root but DOES exist where the old working-directory rule
     * would have put it, this throws and names BOTH paths. That turns a change in what every existing
     * job's relative path MEANS into something an operator fixes deliberately, rather than a job that
     * quietly starts reading somewhere else. ⛔ Do not "helpfully" fall back — the fallback is the bug.
     *
     * @param base the Space config root; {@code null} leaves the legacy working-directory behaviour in
     *             place for a context that genuinely has no Space (the job runner outside a Space).
     */
    public static Path resolveJobPath(Path base, String value, String field) {
        return resolveAgainst(base, value, field, "a job's relative path now resolves against the Space config root");
    }

    /**
     * <b>Resolve a config file's reference to ANOTHER config file</b> — {@code schema_file},
     * {@code schemas[].schema_file}, {@code mapping_file}, {@code grammar}, a {@code segments} value,
     * {@code ingester_config.grammar} ({@code SCHEMA-FILE-RESOLVES-AGAINST-CWD-1}, 2026-09-23). A relative
     * ref resolves against {@code configDir}, the referring config's OWN directory, and never the process
     * working directory: a Pipeline whose satellites resolve only when the server was launched from the
     * repo root is a Pipeline that fails to register from any bundle directory.
     *
     * <p>⛔ Same rule — and the same code — as {@link #resolveJobPath}, with a different base: the ambiguous
     * case (nothing beside the config, but the old working-directory spelling exists) is REFUSED, naming
     * both paths, rather than silently read from the CWD. That was the fallback this replaces.
     *
     * @param configDir the referring config's directory; {@code null} for an in-memory draft with no home,
     *                  which keeps the working-directory reading (there is nothing else to resolve against)
     */
    public static Path resolveConfigRef(Path configDir, String value, String field) {
        return resolveAgainst(configDir, value, field, "a relative config reference resolves beside its own config file");
    }

    /**
     * <b>Resolve a config's DATA path</b> — {@code dirs.*}, {@code processing.duckdb.temp_directory},
     * {@code output.ducklake.data_path}, {@code sinks[].database} (+ its {@code ducklake.data_path}),
     * {@code route.branches[].database}, a join step's {@code reference} path, an enrichment's
     * {@code input.database} / {@code output.database} / {@code references.<n>.path}, and a {@code local}
     * connection's {@code base_path} ({@code DATA-DIRS-RESOLVE-AGAINST-CWD-1}, operator decision
     * 2026-09-23). A relative data path resolves under the <b>Space directory</b> ({@code spaces/<id>/}) the
     * config belongs to — so a config says {@code data/orders/database} — and never against the process
     * working directory.
     *
     * <p>⛔ Same rule — and the same code — as {@link #resolveConfigRef} and {@link #resolveJobPath}, with a
     * third base: the ambiguous case (nothing under the Space dir, but the old working-directory spelling
     * exists) is REFUSED, naming both paths.
     *
     * <p><b>No Space</b> ({@link #spaceDirOf} is {@code null}: a single-tenant example or bundle, a draft with
     * no home) keeps the working-directory reading. That is the Space-dir equivalent of a single-tenant
     * deployment, by the same rule {@code SpaceConfigRoot.jobPathBase} gives a job's {@code data_dir} there:
     * the launch directory ({@code serve-example.sh} / {@code run-example.sh} {@code cd} into the example).
     *
     * @param configDir the directory of the config file that authored {@code value}; the Space dir is derived
     *                  from it by {@link #spaceDirOf}
     */
    public static Path resolveDataPath(Path configDir, String value, String field) {
        return resolveAgainst(spaceDirOf(configDir), value, field,
                "a relative data path resolves under its Space directory");
    }

    /**
     * {@link #resolveDataPath} for a config READER that carries the value on as a string: the resolved
     * absolute path, or {@code value} exactly as authored when there is nothing to resolve against — blank,
     * a URI (a DuckLake {@code data_path} may be an object-store location; containment refuses a URI where it
     * must), or no Space ({@link #spaceDirOf} is {@code null}). Leaving it relative there IS the
     * working-directory reading, byte-identically, and keeps a draft's lift/lower round-trip showing what the
     * author wrote.
     */
    public static String dataPath(Path configDir, String value, String field) {
        if (value == null || value.isBlank() || isUri(value) || spaceDirOf(configDir) == null) return value;
        return resolveDataPath(configDir, value.trim(), field).toString();
    }

    /**
     * The Space directory a config in {@code configDir} belongs to — the parent of the nearest
     * ancestor-or-self directory named {@code config}, which is how a Space is discovered
     * ({@code SpaceManager.discover}: a directory with a {@code config/} subtree) and how
     * {@code SpaceRoot.under} lays one out ({@code <base>/config}). {@code null} when there is none (a
     * single-tenant example directory, an in-memory draft) — see {@link #resolveDataPath}.
     *
     * <p>⚠ The NEAREST {@code config} wins, so a Space nested under some unrelated {@code …/config/…}
     * directory still resolves to its own base.
     */
    public static Path spaceDirOf(Path configDir) {
        if (configDir == null) return null;
        for (Path p = configDir.toAbsolutePath().normalize(); p != null; p = p.getParent()) {
            Path name = p.getFileName();
            if (name != null && "config".equals(name.toString())) return p.getParent();
        }
        return null;
    }

    /** The one relative-path rule behind {@link #resolveJobPath}, {@link #resolveConfigRef} and {@link #resolveDataPath}. */
    private static Path resolveAgainst(Path base, String value, String field, String rule) {
        String s = value == null ? "" : value.trim();
        if (s.isEmpty()) throw new Escape(field, value == null ? "null" : value, "is blank");
        Path authored;
        try {
            authored = Paths.get(s);
        } catch (RuntimeException ex) {
            throw new Escape(field, s, "is not a valid path: " + ex.getMessage());
        }
        if (base == null || authored.isAbsolute()) return authored.toAbsolutePath().normalize();

        Path baseRelative = base.toAbsolutePath().normalize().resolve(authored).normalize();
        if (Files.exists(baseRelative)) return baseRelative;

        Path cwdRelative = authored.toAbsolutePath().normalize();
        if (!baseRelative.equals(cwdRelative) && Files.exists(cwdRelative))
            throw new Escape(field, s, "is relative, and " + rule + " (" + baseRelative
                    + ") — but nothing exists there, while " + cwdRelative + " does, resolved against the "
                    + "working directory the way it used to be. Make it absolute, or respell it relative to "
                    + base.toAbsolutePath().normalize() + "; it is not resolved silently either way");
        return baseRelative;
    }

    /** {@link #resolveJobPath} + the containment check, for a run-time task holding the Space root. */
    public static Path requireJobPathUnderAny(List<Path> roots, Path base, String value, String field) {
        if (roots == null || roots.isEmpty())
            throw new IllegalArgumentException("no allowed roots configured for '" + field + "'");
        Path resolved = resolveJobPath(base, value, field);
        return requireUnderAny(roots, resolved.toString(), field);
    }

    public static Path require(Path root, String value, String field) {
        String s = value == null ? "" : value.trim();
        if (s.isEmpty()) throw new Escape(field, value == null ? "null" : value, "is blank");
        if (s.startsWith("\\\\") || s.startsWith("//"))
            throw new Escape(field, s, "is a UNC/network path, which is not allowed");
        // Refused on EVERY platform, because Paths.get does not (see isUri): on Linux this value
        // becomes a local directory named after the scheme instead of failing.
        if (isUri(s))
            throw new Escape(field, s, "is a URI, not a filesystem path — object-store locations "
                    + "are not supported here, and on Linux this would silently become a local "
                    + "directory named '" + s.substring(0, s.indexOf(':')) + ":'");

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
        // DELIBERATE (pinned 2026-08-14, PATH-2 tier 4): when the filesystem will not answer, the
        // structural verdict above stands. This skips only the symlink RE-check — startsWith has already
        // passed — and the null means perms or a race, which says nothing about which way to fail;
        // refusing here would turn transient IO noise into a refusal of every legitimate config.
        if (real == null || baseReal == null) return true;
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
