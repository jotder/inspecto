package com.gamma.control;

import com.gamma.config.safety.PathJail;

import java.nio.file.Path;

/**
 * The shared fail-closed write-gate chain (write-root 503 → unsafe name 422 → path jail 403 →
 * conflict 409), extracted from the write-capable route modules so each gate has one
 * implementation — and so the Standard-edition security module can prepend AuthN/AuthZ gates in
 * one place instead of six (docs/superpower/api-contract-design.md §8). Behaviour-preserving:
 * statuses and message shapes match the previous inline checks.
 */
@com.gamma.api.PublicApi(since = "4.0.0")
public final class WriteGates {
    private WriteGates() {}

    /** Gate 1 — writes disabled → 503. {@code what} names the capability (e.g. "config write"). */
    public static Path requireWriteRoot(ApiContext api, String what) {
        Path root = api.writeRoot();
        if (root == null)
            throw new ApiException(503, ErrorCodes.CONTROL_PLANE_READ_ONLY,
                    what + " disabled: set -Dassist.write.root to enable");
        return root;
    }

    /** Gate 2 — a name/id unusable as a jailed filename → 422. Returns the trimmed name. */
    public static String safeName(String raw, String what) {
        if (!isSafeName(raw))
            throw new ApiException(422,
                    "unsafe " + what + " '" + raw + "' (allowed: letters, digits, '.', '_', '-')");
        return raw.trim();
    }

    /**
     * Gate 2 as a predicate, for a caller weighing a name it is allowed to REJECT rather than refuse the
     * request over — e.g. probing whether a legacy filename candidate is even usable. A caller that must
     * have the name uses {@link #safeName} so the 422 carries the reason.
     */
    public static boolean isSafeName(String raw) {
        String safe = raw == null ? "" : raw.trim();
        return !safe.isEmpty() && !safe.contains("..") && safe.matches("[A-Za-z0-9][A-Za-z0-9._-]*");
    }

    /**
     * Gate 3 — a resolved path escaping the write root → 403. Returns the normalised path.
     *
     * <p>⚠ This was the <b>weakest</b> of the codebase's five containment checks — a bare
     * {@code normalize()} + {@code startsWith()} with no absolutisation and no symlink re-check —
     * despite guarding the HTTP write surface. It now delegates the verdict to
     * {@link PathJail#contains}, so the gate a caller hits at the edge and the one the config
     * validator applies at authoring time cannot disagree.
     */
    public static Path jail(Path root, Path target, String what) {
        Path normalized = target.normalize();
        if (!PathJail.contains(root, normalized))
            throw new ApiException(403, ErrorCodes.PATH_JAIL_VIOLATION, what + " escapes the write root");
        return normalized;
    }

    /**
     * Gate 3 against the SAFETY roots ({@link PathJail#allowedRoots()}) rather than the write root — for a
     * caller-named file a read-shaped route opens (a {@code /validate} config, a preview's reference data
     * file). Escape, or no roots configured → 403, with one message whether or not the file exists (no
     * existence oracle, and the resolved path is never echoed). Returns the contained absolute path.
     */
    public static Path jailToAllowedRoots(String value, String field) {
        try {
            return PathJail.requireUnderAny(PathJail.allowedRoots(), value, field);
        } catch (PathJail.Escape | IllegalArgumentException refused) {
            throw new ApiException(403, ErrorCodes.PATH_JAIL_VIOLATION, "'" + field + "' is outside the allowed roots");
        }
    }

    /** Gate 4 — resource conflict → 409. */
    public static void conflictIf(boolean conflict, String message) {
        if (conflict) throw new ApiException(409, message);
    }
}
