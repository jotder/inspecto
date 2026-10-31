package com.gamma.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One-shot migration of the pre-spaces <b>flat</b> layout into a {@code spaces/<id>/} directory the multi-space
 * runtime discovers — the required path off the retired single-tenant mode (there is no flat fallback).
 *
 * <p>It relocates the {@link LegacySpaceRoot default flat artifacts} (working-directory relative) into the
 * {@link DirSpaceRoot per-space} layout and writes a {@code space.toon} manifest:
 * <pre>
 *   &lt;configDir&gt;        → spaces/&lt;id&gt;/config        (the *.toon tree passed on the old CLI)
 *   database/           → spaces/&lt;id&gt;/data
 *   jobs_audit/         → spaces/&lt;id&gt;/audit
 *   inspecto-events/    → spaces/&lt;id&gt;/data/events
 *   *.duckdb / *.db     → spaces/&lt;id&gt;/duckdb/           (+ any sibling .wal)
 * </pre>
 *
 * <p><b>Space references and moved data roots</b> ({@code SPACE-DATA-ROOT-1}) — a {@code spaces/<id>.ref} is
 * followed to the folder it names (an unfollowable one is refused, never shadowed by a new {@code <id>/}), and
 * {@code data}, {@code audit}, {@code duckdb} land under the Space's {@link SpaceRoot#dataRoot() data root}: its
 * {@code space.toon data_root}, else {@code -Dspaces.data.root/<id>} — so run the migrator with the server's
 * {@code -Dspaces.data.root}. The data root is checked and claimed exactly as at boot before anything moves.
 *
 * <p><b>Idempotent</b> — a step applies only when its source exists and its target does not, so a re-run is a clean
 * no-op and an already-migrated target is never clobbered. <b>{@code --dry-run}</b> prints the plan without moving.
 * Moves are crash-tolerant across filesystems (atomic rename where possible, else recursive copy + delete).
 *
 * <p><b>Caveat:</b> a config that references a schema/grammar by an <em>absolute</em> path keeps pointing at the old
 * location after its file moves — relocation cannot rewrite paths inside configs. Author portable (relative) paths,
 * or fix them up post-migration. Non-default flat locations (custom {@code -D} dirs) must be moved by hand.
 *
 * <pre>
 *   java -cp inspecto.jar com.gamma.service.SpaceMigrator \
 *        [--id default] [--root ./spaces] [--from .] [--dry-run] &lt;configDir&gt;
 * </pre>
 */
public final class SpaceMigrator {

    /** The flat working-directory artifacts (their {@link LegacySpaceRoot} default names). */
    private static final String FLAT_DATA   = "database";
    private static final String FLAT_AUDIT  = "jobs_audit";
    private static final String FLAT_EVENTS = "inspecto-events";
    private static final List<String> FLAT_DB_FILES = List.of(
            "jobs_report.duckdb", "provenance.duckdb",
            "inspecto-ops.db", "inspecto-ops-links.db", "inspecto-ops-notes.db",
            "inspecto-status.db", "inspecto-acquisition.db");

    private static final Logger log = LoggerFactory.getLogger(SpaceMigrator.class);

    private SpaceMigrator() {}

    /** A single relocation: {@code from → to}, labelled for the printed plan. */
    public record Step(Path from, Path to, String what) {}

    /**
     * Compute the relocation steps that <em>apply</em> (source present, target absent), in a safe order
     * ({@code data} before {@code data/events} so the parent exists). Pure — does not touch the filesystem.
     */
    public static List<Step> plan(Path workingDir, Path configDir, Path spacesRoot, String id) {
        Path base = baseFor(spacesRoot, id);
        Path state = SpaceRoot.dataRootFor(base, true);   // data/, audit/, duckdb/ go where the Space will boot them
        List<Step> steps = new ArrayList<>();
        addStep(steps, configDir,                       base.resolve("config"),                "config");
        addStep(steps, workingDir.resolve(FLAT_DATA),   state.resolve("data"),                 "data");
        addStep(steps, workingDir.resolve(FLAT_AUDIT),  state.resolve("audit"),                "audit");
        addStep(steps, workingDir.resolve(FLAT_EVENTS), state.resolve("data").resolve("events"), "events");
        for (String db : FLAT_DB_FILES) {
            addStep(steps, workingDir.resolve(db),        state.resolve("duckdb").resolve(db),        "duckdb/" + db);
            addStep(steps, workingDir.resolve(db + ".wal"), state.resolve("duckdb").resolve(db + ".wal"), "duckdb/" + db + ".wal");
        }
        return steps;
    }

    /**
     * The Space folder {@code id} migrates into: {@code <spaces root>/<id>/}, or the folder its {@code <id>.ref}
     * points at (under {@link SpaceManager#resolveRef}'s rules). An unfollowable reference is refused: a new
     * {@code <id>/} folder beside it would shadow it, and the referenced Space would silently stop booting.
     */
    static Path baseFor(Path spacesRoot, String id) {
        Path root = spacesRoot.toAbsolutePath().normalize();   // the data root's stamp must name an absolute base, as at boot
        Path folder = root.resolve(SpaceId.of(id).value());
        Path ref = root.resolve(SpaceId.of(id).value() + SpaceManager.REF_SUFFIX);
        if (Files.isDirectory(folder) || !Files.isRegularFile(ref)) return folder;
        SpaceManager.RefTarget r = SpaceManager.resolveRef(root, ref);
        if (r.why() != null)
            throw new IllegalStateException("the Space reference " + ref + " " + r.why()
                    + " - fix or remove it before migrating (a new folder beside it would shadow it)");
        return r.folder();
    }

    private static void addStep(List<Step> steps, Path from, Path to, String what) {
        if (Files.exists(from) && !Files.exists(to)) steps.add(new Step(from, to, what));
    }

    /**
     * Run the migration. Prints the plan; unless {@code dryRun}, executes each move and writes the
     * {@code space.toon} manifest (if absent). Returns the steps that were applied (or would be, when dry).
     */
    public static List<Step> migrate(Path workingDir, Path configDir, Path spacesRoot, String id, boolean dryRun)
            throws IOException {
        Path base = baseFor(spacesRoot, id);
        Path state = SpaceRoot.dataRootFor(base, true);
        List<Step> steps = plan(workingDir, configDir, spacesRoot, id);

        log.info("Migrating flat layout → {}  ({})", base, dryRun ? "DRY RUN" : "apply");
        if (!state.equals(base)) log.info("  data, audit and duckdb → data root {}", state);
        if (steps.isEmpty()) log.info("  (nothing to relocate — already migrated, or no flat artifacts found)");
        for (Step s : steps) log.info("  {} {} → {}", String.format("%-22s", s.what()), s.from(), s.to());

        if (!dryRun) {
            Files.createDirectories(base);
            if (!state.equals(base)) {
                // The boot's own checks (mount present, no overlap, empty or stamped by this Space), before any byte moves.
                SpaceDataRoot.registerSpacesRoot(spacesRoot);
                SpaceDataRoot.stake(new DirSpaceRoot(base, state),
                        SpaceContext.SpaceManifest.read(base.resolve("space.toon"), id));
            }
            for (Step s : steps) moveTree(s.from(), s.to());
            Path manifest = base.resolve("space.toon");
            if (!Files.exists(manifest)) {
                new SpaceContext.SpaceManifest(id, "", Instant.now().toString()).write(manifest);
                log.info("  wrote {}", manifest);
            }
        }
        log.info("Launch with:  java -Dspaces.root={} -cp inspecto.jar com.gamma.control.ControlApi", spacesRoot);
        return steps;
    }

    /** Move {@code from} to {@code to} (file or directory tree): atomic rename where possible, else copy + delete. */
    private static void moveTree(Path from, Path to) throws IOException {
        Files.createDirectories(to.getParent());
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
            return;
        } catch (AtomicMoveNotSupportedException | DirectoryNotEmptyException crossDeviceOrNonEmpty) {
            // fall through to a recursive copy + delete (e.g. moving across filesystems)
        }
        try (Stream<Path> walk = Files.walk(from)) {
            walk.forEach(src -> {
                Path dest = to.resolve(from.relativize(src));
                try {
                    if (Files.isDirectory(src)) Files.createDirectories(dest);
                    else {
                        Files.createDirectories(dest.getParent());
                        Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
        deleteRecursively(from);
    }

    private static void deleteRecursively(Path dir) throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    public static void main(String[] args) throws IOException {
        String id = "default";
        Path spacesRoot = Paths.get("spaces");
        Path workingDir = Paths.get(".");
        boolean dryRun = false;
        Path configDir = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--id"      -> id = args[++i];
                case "--root"    -> spacesRoot = Paths.get(args[++i]);
                case "--from"    -> workingDir = Paths.get(args[++i]);
                case "--dry-run" -> dryRun = true;
                default          -> configDir = Paths.get(args[i]);
            }
        }
        if (configDir == null) {
            System.err.println("Usage: SpaceMigrator [--id default] [--root ./spaces] [--from .] [--dry-run] <configDir>");
            System.exit(1);
        }
        migrate(workingDir.toAbsolutePath().normalize(), configDir.toAbsolutePath().normalize(),
                spacesRoot.toAbsolutePath().normalize(), id, dryRun);
    }
}
