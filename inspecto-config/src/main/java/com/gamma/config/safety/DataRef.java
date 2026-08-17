package com.gamma.config.safety;

import com.gamma.api.PublicApi;

import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * The one answer to "is this a usable relative ref under a space's data root", shared by every reader
 * of a {@code physicalRef}-shaped value.
 *
 * <p>A data ref must not be routed through {@link PathJail#require}: the jail <em>resolves</em> a relative
 * value against the <b>working directory</b> — deliberate and load-bearing for config refs — whereas a data
 * ref is meaningless except relative to the space's data root. Resolution therefore lives here; the
 * containment <em>verdict</em> is still the jail's single {@link PathJail#contains} definition (which also
 * re-checks symlink escape) — unify the verdict, never the resolution.
 *
 * <p>Before this class there were <b>two</b> copies of the shape rule — {@code DatasetRelation} and
 * {@code ExpectationEvaluator}, each with its own {@code SAFE_REF} pattern, one of whose Javadoc admitted
 * it was the "same shape as" the other. Two readers of one persisted value had drifted: both checked the
 * shape, but only one re-checked containment after resolving. Nothing was reachable through the gap (see
 * {@link #requireShape}), but a boundary spelled twice is a boundary that drifts, and it had.
 *
 * <p>Violations throw {@link IllegalArgumentException} — not {@code PathJail.Escape} — because every caller
 * sits behind a route that maps that to <b>422</b>: an unusable ref is a bad request about a dataset, not a
 * containment incident.
 *
 * @since 4.0.0
 */
@PublicApi(since = "4.0.0")
public final class DataRef {

    private DataRef() {}

    /**
     * A ref usable as a relative path under a data root: it must start alphanumeric and may then carry only
     * alphanumerics, {@code .}, {@code _}, {@code /} and {@code -}.
     *
     * <p>That character set is what makes the refs structurally safe rather than merely filtered. It admits
     * no {@code \} (so no UNC {@code \\host\share} and no Windows separator), and no {@code :} (so no
     * {@code C:} drive prefix); requiring an alphanumeric first character rejects a leading {@code /},
     * {@code -} or {@code .}. Traversal is excluded separately by a {@code ".."} substring test rather than
     * by the class, because {@code .} must stay legal inside a segment (a store named {@code orders.v2}).
     */
    private static final Pattern SAFE_REF = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._/-]*");

    /**
     * Verdict on the ref's shape alone, for a caller that resolves it somewhere other than a data root
     * (an Exchange {@code shared/<owner>/<item>} snapshot, say).
     *
     * @param ref  the ref as authored in config
     * @param what what to call it in the failure message, e.g. {@code "dataset physicalRef"}
     * @return {@code ref} unchanged, for use in an expression
     * @throws IllegalArgumentException if {@code ref} is null, traverses, or is not ref-shaped (→ 422)
     */
    public static String requireShape(String ref, String what) {
        if (!isSafeShape(ref))
            throw new IllegalArgumentException("unsafe " + what + " '" + ref + "'");
        return ref;
    }

    /**
     * The same shape verdict as {@link #requireShape}, without the throw — for a gate that
     * <em>collects</em> findings rather than failing on the first one (the 422 write validator).
     */
    public static boolean isSafeShape(String ref) {
        return ref != null && !ref.contains("..") && SAFE_REF.matcher(ref).matches();
    }

    /**
     * Verdict on the shape <em>and</em> on containment under {@code dataRoot} — the check both readers of a
     * {@code physicalRef} should have been making.
     *
     * <p>The containment verdict is {@link PathJail#contains} — resolution stays here (a ref resolves against
     * the <b>data root</b>, never the working directory), the verdict is the jail's single definition (unify
     * the verdict, never the resolution). That closes the symlink half of PATH-2 tier 4 for data refs: the
     * shape rule makes traversal and absolute refs structurally impossible, but it cannot see a link
     * <em>inside</em> the data root pointing out of it — only the real-path re-check can.
     *
     * @return the resolved, normalised absolute-or-relative path under {@code dataRoot}
     * @throws IllegalArgumentException if {@code dataRoot} is null, or the ref is unusable or escapes
     *                                  (structurally or through a symlink) (→ 422)
     */
    public static Path requireUnder(Path dataRoot, String ref, String what) {
        if (dataRoot == null)
            throw new IllegalArgumentException("no data root for this space; cannot resolve " + what);
        requireShape(ref, what);
        Path resolved = dataRoot.normalize().resolve(ref).normalize();
        if (!PathJail.contains(dataRoot, resolved))
            throw new IllegalArgumentException(what + " '" + ref + "' escapes the data root");
        return resolved;
    }
}
