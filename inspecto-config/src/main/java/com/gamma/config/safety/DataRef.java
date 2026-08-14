package com.gamma.config.safety;

import com.gamma.api.PublicApi;

import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * The one answer to "is this a usable relative ref under a space's data root", shared by every reader
 * of a {@code physicalRef}-shaped value.
 *
 * <p>A data ref is <em>not</em> a {@link PathJail} case and must not be routed through it. {@code PathJail}
 * resolves a relative value against the <b>working directory</b> — deliberate and load-bearing for config
 * refs — whereas a data ref is meaningless except relative to the space's data root. The shape rule and the
 * containment rule therefore live here, next to the jail rather than inside it.
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
        if (ref == null || ref.contains("..") || !SAFE_REF.matcher(ref).matches())
            throw new IllegalArgumentException("unsafe " + what + " '" + ref + "'");
        return ref;
    }

    /**
     * Verdict on the shape <em>and</em> on containment under {@code dataRoot} — the check both readers of a
     * {@code physicalRef} should have been making.
     *
     * <p>The containment half is redundant given {@link #requireShape} and is kept anyway: it is what stops
     * the two rules drifting apart again, and it is the assertion that would still hold if the character set
     * were ever widened.
     *
     * @return the resolved, normalised absolute-or-relative path under {@code dataRoot}
     * @throws IllegalArgumentException if {@code dataRoot} is null, or the ref is unusable or escapes (→ 422)
     */
    public static Path requireUnder(Path dataRoot, String ref, String what) {
        if (dataRoot == null)
            throw new IllegalArgumentException("no data root for this space; cannot resolve " + what);
        requireShape(ref, what);
        Path root = dataRoot.normalize();
        Path resolved = root.resolve(ref).normalize();
        if (!resolved.startsWith(root))
            throw new IllegalArgumentException(what + " '" + ref + "' escapes the data root");
        return resolved;
    }
}
