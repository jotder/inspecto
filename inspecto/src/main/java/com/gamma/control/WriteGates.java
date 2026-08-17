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
final class WriteGates {
    private WriteGates() {}

    /** Gate 1 — writes disabled → 503. {@code what} names the capability (e.g. "config write"). */
    static Path requireWriteRoot(ApiContext api, String what) {
        Path root = api.writeRoot();
        if (root == null)
            throw new ApiException(503, ErrorCodes.CONTROL_PLANE_READ_ONLY,
                    what + " disabled: set -Dassist.write.root to enable");
        return root;
    }

    /** Gate 2 — a name/id unusable as a jailed filename → 422. Returns the trimmed name. */
    static String safeName(String raw, String what) {
        String safe = raw == null ? "" : raw.trim();
        if (!isSafeName(safe))
            throw new ApiException(422,
                    "unsafe " + what + " '" + raw + "' (allowed: letters, digits, '.', '_', '-')");
        return safe;
    }

    /**
     * Gate 2 as a predicate, for a caller weighing a name it is allowed to REJECT rather than refuse the
     * request over — e.g. probing whether a legacy filename candidate is even usable. A caller that must
     * have the name uses {@link #safeName} so the 422 carries the reason.
     */
    static boolean isSafeName(String raw) {
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
    static Path jail(Path root, Path target, String what) {
        Path normalized = target.normalize();
        if (!PathJail.contains(root, normalized))
            throw new ApiException(403, what + " escapes the write root");
        return normalized;
    }

    /** Gate 4 — resource conflict → 409. */
    static void conflictIf(boolean conflict, String message) {
        if (conflict) throw new ApiException(409, message);
    }
}
