package com.gamma.job;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/**
 * The data roots ONE {@code space.comparison} run may READ, keyed by Space id — an explicit grant handed
 * to the run, never a lookup the run performs itself.
 *
 * <p><b>Why a grant object and not a lookup</b> (space-comparison design §2, decided 2026-09-24). In-process
 * code can reach any hosted Space, and {@code SpaceConfigRoot} deliberately exposes no enumeration of them
 * ({@code SPACE-UNKEYED-STATICS-1}). So the engine never resolves another Space: the only way a run sees one
 * is that an <b>authorized caller</b> put its root in this grant. Today that caller is the
 * {@code canAdminister}-gated {@code POST /space-comparisons} route, which checks the capability BEFORE it
 * resolves a single Space. Every other path — an authored or scheduled {@code space.comparison} job — gets
 * {@link #ownSpaceOnly}, which a comparison (at least two Spaces) can never satisfy: no Subject, no
 * cross-Space read.
 *
 * <p>⛔ Do not add a "resolve any hosted Space" implementation here. That is the door §2 records as shut.
 */
@FunctionalInterface
public interface SpaceStorageAccess {

    /** The data root this run may read for {@code spaceId}, or empty when the run is not granted it. */
    Optional<Path> dataRoot(String spaceId);

    /** A grant of exactly these Spaces' data roots (an unlisted id is refused). */
    static SpaceStorageAccess granting(Map<String, Path> roots) {
        Map<String, Path> copy = Map.copyOf(roots);
        return id -> Optional.ofNullable(id == null ? null : copy.get(id));
    }

    /** The grant for a run with no authorized caller: only the running Space itself, and only if it has a data root. */
    static SpaceStorageAccess ownSpaceOnly(String ownSpaceId, String ownDataDir) {
        return id -> id != null && id.equals(ownSpaceId) && ownDataDir != null && !ownDataDir.isBlank()
                ? Optional.of(Path.of(ownDataDir)) : Optional.empty();
    }
}
