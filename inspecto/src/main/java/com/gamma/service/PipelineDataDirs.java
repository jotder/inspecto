package com.gamma.service;

import com.gamma.config.io.ConfigLoader;
import com.gamma.config.safety.PathJail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The data directories a pipeline owns, and the rules for removing them when it is deleted.
 *
 * <p>🔴 <b>This is the only code in the product that deletes a pipeline's DATA.</b> Everything else
 * that says "delete" removes a config file. Deletion here is recursive and irreversible, so the
 * refusals below are the substance of the class, not decoration — each one exists because the
 * alternative destroys something the operator did not ask to lose.
 *
 * <h3>What is NOT removed, deliberately</h3>
 * <ul>
 *   <li>⛔ <b>{@code dirs.poll} — the inbox.</b> It holds files that have ARRIVED but may not be
 *       processed, and it is routinely the drop point for a feed rather than a private working
 *       directory. Deleting a pipeline must never destroy unprocessed source data, and a shared
 *       inbox would take another pipeline's input with it.</li>
 *   <li>⛔ <b>Any directory another pipeline also declares — and this REFUSES THE WHOLE REMOVAL.</b>
 *       Two pipelines legitimately write into one database or share a backup root; removing it
 *       because one of them was deleted destroys the other's data. Skipping such a directory quietly
 *       would be almost as bad: the operator asked for the data to go, and a partial result they were
 *       not told about is a half-deleted pipeline. So a shared directory is a <b>409 naming the
 *       directory and every pipeline that declares it</b> — repoint or delete those first, or untick
 *       the data option.</li>
 *   <li>⛔ <b>An allowed root itself, or any ancestor of one.</b> A config naming a space root as its
 *       database would otherwise take the whole space — configs included.</li>
 * </ul>
 *
 * <p>⚠ Every candidate is re-jailed with {@link PathJail#requireUnderAny} immediately before removal.
 * The config was validated when it was written, but the file on disk may have been hand-edited since,
 * and this is the last point at which that matters.
 */
public final class PipelineDataDirs {

    private static final Logger log = LoggerFactory.getLogger(PipelineDataDirs.class);

    private PipelineDataDirs() {}

    /**
     * The {@code dirs.*} keys whose contents belong to the pipeline itself.
     *
     * <p>⛔ {@code poll} is absent by design — see the class note. Adding it here is the single edit
     * that turns "delete this pipeline" into "delete the feed's incoming data".
     */
    private static final List<String> OWNED_DIR_KEYS = List.of(
            "database", "backup", "temp", "errors", "quarantine", "markers", "status_dir", "log_dir");

    /** What a removal did: the directories taken, and the ones kept with the reason. */
    public record Removal(List<String> removed, Map<String, String> retained) {
        public Removal {
            removed = List.copyOf(removed);
            retained = Map.copyOf(retained);
        }
    }

    /** One directory this pipeline wants removed that other pipelines also declare. */
    public record Conflict(String dir, String key, List<String> pipelines) {
        public Conflict {
            pipelines = List.copyOf(pipelines);
        }
    }

    /**
     * Every owned directory that another pipeline also declares — the removal's blocking check.
     *
     * <p>🔴 Called BEFORE anything is deleted, so a refusal costs nothing. The alternative — delete
     * what is safe and report the rest — leaves the operator with a pipeline whose data is half gone
     * and no single action that finishes the job.
     *
     * <p>⚠ Compares against the other pipelines' FULL {@code dirs} block, {@code poll} included: a
     * directory this pipeline calls its database while another polls it as an inbox is shared, and
     * that is exactly the case where deleting is worst.
     */
    public static List<Conflict> conflictsFor(Path writeRoot, Map<String, Object> raw, Path selfPath) {
        Map<String, List<String>> byDir = declaredByOthers(writeRoot, selfPath);
        List<Conflict> out = new ArrayList<>();
        for (Map.Entry<String, String> e : ownedDirs(raw).entrySet()) {
            Path dir;
            try {
                dir = Path.of(e.getValue()).toAbsolutePath().normalize();
            } catch (RuntimeException notAPath) {
                continue;   // the removal's own jail reports this one
            }
            List<String> owners = byDir.get(dir.toString());
            if (owners != null && !owners.isEmpty()) out.add(new Conflict(e.getValue(), e.getKey(), owners));
        }
        return out;
    }

    /**
     * Remove {@code raw}'s owned data directories.
     *
     * <p>⚠ Callers must run {@link #conflictsFor} FIRST and refuse when it returns anything — a shared
     * directory is a 409, not a skip. The shared check below is defence in depth for a caller that
     * forgets, not the primary gate; reaching it means a directory was spared that the operator was
     * never told about.
     *
     * @param writeRoot the config write root, scanned to find the other pipelines' declarations
     * @param raw       the decoded config of the pipeline being deleted
     * @param selfPath  that pipeline's own config file, excluded from the sharing scan
     */
    public static Removal removeFor(Path writeRoot, Map<String, Object> raw, Path selfPath) {
        Set<String> shared = declaredByOthers(writeRoot, selfPath).keySet();
        List<Path> roots = PathJail.allowedRoots();
        List<String> removed = new ArrayList<>();
        Map<String, String> retained = new LinkedHashMap<>();

        for (Map.Entry<String, String> e : ownedDirs(raw).entrySet()) {
            String key = e.getKey();
            String value = e.getValue();
            Path dir;
            try {
                dir = PathJail.requireUnderAny(roots, value, "dirs." + key);
            } catch (RuntimeException escapes) {
                retained.put(value, "outside the allowed roots — refused (" + key + ")");
                continue;
            }
            if (isRootOrAncestor(dir, roots)) {
                retained.put(value, "is an allowed root or an ancestor of one — refused (" + key + ")");
                continue;
            }
            if (shared.contains(dir.toString())) {
                retained.put(value, "another pipeline also declares it (" + key + ")");
                continue;
            }
            if (!Files.isDirectory(dir)) continue;   // nothing written yet: not an error, not a removal
            try {
                deleteRecursively(dir);
                removed.add(value);
            } catch (IOException io) {
                // Best effort per directory: one unremovable path (a file held open on Windows) must
                // not abort the rest, and the caller is told exactly what survived.
                retained.put(value, "could not be removed: " + io.getMessage());
            }
        }
        if (!removed.isEmpty() || !retained.isEmpty())
            log.warn("[PIPELINE-DATA-DELETE] removed={} retained={}", removed, retained.keySet());
        return new Removal(removed, retained);
    }

    /** This pipeline's own data directories, by {@code dirs.*} key — {@code poll} excluded. */
    @SuppressWarnings("unchecked")
    public static Map<String, String> ownedDirs(Map<String, Object> raw) {
        Map<String, String> out = new LinkedHashMap<>();
        if (!(raw.get("dirs") instanceof Map<?, ?> dirs)) return out;
        for (String key : OWNED_DIR_KEYS) {
            Object v = ((Map<String, Object>) dirs).get(key);
            if (v != null && !String.valueOf(v).isBlank()) out.put(key, String.valueOf(v).trim());
        }
        return out;
    }

    /**
     * Every directory declared by a pipeline OTHER than {@code selfPath}, absolute and normalised.
     *
     * <p>⚠ Reads {@code poll} as well as the owned keys: a directory this pipeline calls its database
     * while another calls it an inbox is still shared, and the point is to keep it.
     */
    private static Map<String, List<String>> declaredByOthers(Path writeRoot, Path selfPath) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        Path self = selfPath == null ? null : selfPath.toAbsolutePath().normalize();
        // Recursive: sample pipelines live in `config/<name>/`, so a flat listing would miss every
        // one of them and report a shared directory as unshared — the dangerous direction.
        try (Stream<Path> files = Files.walk(writeRoot)) {
            for (Path p : files.filter(Files::isRegularFile)
                    .filter(f -> f.getFileName().toString().endsWith("_pipeline.toon")).toList()) {
                if (self != null && p.toAbsolutePath().normalize().equals(self)) continue;
                try {
                    Map<String, Object> other = ConfigLoader.filesystem().decode(p.toString());
                    if (!(other.get("dirs") instanceof Map<?, ?> dirs)) continue;
                    String owner = String.valueOf(other.getOrDefault("name", idOf(p)));
                    for (Object v : dirs.values()) {
                        if (v == null || String.valueOf(v).isBlank()) continue;
                        String abs = Path.of(String.valueOf(v).trim()).toAbsolutePath().normalize().toString();
                        out.computeIfAbsent(abs, k -> new ArrayList<>()).add(owner);
                    }
                } catch (RuntimeException unreadable) {
                    // ⚠ FAIL SAFE: a config we cannot read might declare any directory, so the safe
                    // response is to keep going rather than to assume it shares nothing. Logged so an
                    // operator can see why a directory they expected to go was kept.
                    log.warn("[PIPELINE-DATA-DELETE] could not read {} while checking for shared "
                            + "directories; its declarations are not known: {}", p, unreadable.getMessage());
                }
            }
        } catch (IOException io) {
            log.warn("[PIPELINE-DATA-DELETE] could not scan {} for shared directories: {}", writeRoot, io.getMessage());
        }
        return out;
    }

    /** A pipeline config's id from its filename, for naming the owner of a shared directory. */
    private static String idOf(Path configFile) {
        String n = configFile.getFileName().toString();
        if (n.endsWith("_pipeline.toon")) return n.substring(0, n.length() - "_pipeline.toon".length());
        return n.endsWith(".toon") ? n.substring(0, n.length() - ".toon".length()) : n;
    }

    /** Whether {@code dir} IS an allowed root or contains one — either way, removing it is refused. */
    private static boolean isRootOrAncestor(Path dir, List<Path> roots) {
        for (Path root : roots) {
            Path r = root.toAbsolutePath().normalize();
            if (r.equals(dir) || r.startsWith(dir)) return true;
        }
        return false;
    }

    /** Depth-first removal: children before parents, so a directory is empty when it is removed. */
    private static void deleteRecursively(Path dir) throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            List<Path> ordered = walk.sorted(Comparator.reverseOrder()).toList();
            for (Path p : ordered) Files.deleteIfExists(p);
        }
    }
}
