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
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED,
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

    /**
     * Gate 3b for the {@code /config/*} write routes ({@code CONFIG-WRITE-REGISTRY-1}, 2026-09-27) — a target
     * that is not a config file those routes own → 403. Two classes of file sit under the same write root:
     * <ul>
     *   <li><b>{@code registry/}</b> — every component kind. Its own door ({@code /components/<kind>},
     *       {@code /access/*}, {@code /alerts/rules}) carries the kind's capability ({@code canManageIncidents},
     *       {@code canConfigureAccess}, …), its {@code fromMap} validation and its maker-checker key. A caller
     *       {@code subdir} of {@code registry/<dir>} used to land a {@code meta} file there (or overwrite, patch
     *       or delete one — deleting {@code registry/access-profiles/role-<r>.toon} WIDENS that role) under
     *       {@code canAuthorWorkbench} and the {@code meta} approval rule. No shipped caller writes there.</li>
     *   <li>the reserved Space documents ({@link com.gamma.service.ReservedConfigPaths}: {@code roles.toon},
     *       {@code approval.toon}, …) — a {@code meta} named {@code roles} WAS {@code roles.toon}.</li>
     * </ul>
     * Judged on the normalised path AND its real path, case-insensitively and with the trailing dots/spaces
     * Windows drops, so {@code Registry/}, {@code registry./}, {@code x/../registry/} and a link to the
     * registry all count. Call it on the FINAL target, before any existence check (no existence oracle).
     */
    /** {@link #refuseReservedConfigTarget} as a predicate — for a file the SERVER found (a satellite scan hit),
     *  which is skipped rather than refused, so the scan cannot say what exists under {@code registry/}. */
    static boolean isReservedConfigTarget(Path root, Path target) {
        try {
            refuseReservedConfigTarget(root, target);
            return false;
        } catch (ApiException refused) {
            return true;
        }
    }

    static void refuseReservedConfigTarget(Path root, Path target) {
        Path base = root.toAbsolutePath().normalize();
        Path abs = target.toAbsolutePath().normalize();
        refuseRegistry(base.relativize(abs));
        Path realBase = realOfNearestExisting(base), realAbs = realOfNearestExisting(abs);
        if (realBase != null && realAbs != null && realAbs.startsWith(realBase))
            refuseRegistry(realBase.relativize(realAbs));
        String reserved = com.gamma.service.ImportPaths.reservedRefusal(base,
                base.relativize(abs).toString().replace('\\', '/'));
        if (reserved != null)
            throw new ApiException(403, ErrorCodes.PATH_JAIL_VIOLATION,
                    "the config routes do not write this file: it is " + reserved + ", owned by its own route");
    }

    private static void refuseRegistry(Path rel) {
        // Split on BOTH separators, whatever the OS: on Linux `\` is an ordinary name character, so
        // `registry\kpis` is ONE segment to Path — yet it is normalised into `registry/kpis` further on.
        String[] parts = java.util.Arrays.stream(rel.toString().split("[/\\\\]+"))
                .filter(s -> !s.isEmpty()).toArray(String[]::new);
        if (parts.length == 0 || !"registry".equalsIgnoreCase(windowsName(parts[0]))) return;
        String dir = parts.length > 2 ? windowsName(parts[1]) : null;
        String kind = null;
        if (dir != null) {
            java.util.Set<String> kinds = new java.util.TreeSet<>(com.gamma.pipeline.ComponentStore.WRITABLE_TYPES);
            kinds.add("connection");
            for (String k : kinds)
                if (com.gamma.pipeline.ComponentRegistry.dirForType(k).filter(dir::equalsIgnoreCase).isPresent()) kind = k;
        }
        throw new ApiException(403, ErrorCodes.PATH_JAIL_VIOLATION,
                "registry/ is the component registry, not a config directory — write "
                        + (kind == null ? "a component through /components/<kind>" : "a " + kind + " through /components/" + kind)
                        + ", which applies its own capability, validation and approval");
    }

    /** A path segment as Windows resolves it: trailing dots and spaces dropped ({@code "registry. "} IS {@code registry}). */
    private static String windowsName(String segment) {
        return segment.replaceAll("[. ]+$", "");
    }

    /** The real path of {@code p}'s nearest existing ancestor with the missing tail re-attached, or {@code null}. */
    private static Path realOfNearestExisting(Path p) {
        try {
            Path existing = p;
            while (existing != null && !java.nio.file.Files.exists(existing)) existing = existing.getParent();
            if (existing == null) return null;
            return existing.toRealPath().resolve(existing.relativize(p)).normalize();
        } catch (java.io.IOException | RuntimeException e) {
            return null;   // the lexical verdict above and ImportPaths' fail-closed real-path check still stand
        }
    }

    /** Gate 4 — resource conflict → 409. */
    public static void conflictIf(boolean conflict, String message) {
        if (conflict) throw new ApiException(409, ErrorCodes.CONFLICT, message);
    }
}
