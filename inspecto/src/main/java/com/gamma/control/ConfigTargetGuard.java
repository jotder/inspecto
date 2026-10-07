package com.gamma.control;

import com.gamma.spi.auth.ApiException;
import com.gamma.spi.auth.ErrorCodes;
import com.gamma.config.safety.PathJail;
import java.nio.file.Path;
import com.gamma.access.WriteGates;

/**
 * The reserved-config-target guard for the {@code /config/*} write and read routes ({@code CONFIG-WRITE-REGISTRY-1}).
 * Split out of {@link WriteGates} in D-1 step 3: it needs host classes ({@code ImportPaths}, {@code ComponentStore},
 * {@code ComponentRegistry}) that the SPI layer must not name, and only the config routes use it.
 */
final class ConfigTargetGuard {
    private ConfigTargetGuard() {}

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
        // A query parameter is decoded exactly once, so `registry%252Fkpis` arrives as the literal name
        // `registry%2Fkpis`. No config path names a `%`: refuse every such segment rather than judge what
        // a later decode might make of it (fail closed; no existence oracle).
        for (String part : parts)
            if (part.indexOf('%') >= 0)
                throw new ApiException(403, ErrorCodes.PATH_JAIL_VIOLATION,
                        "a config path may not contain '%' (a percent-encoded spelling): " + rel);
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
}
