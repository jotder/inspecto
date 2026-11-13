package com.gamma.service;

import com.gamma.config.safety.DiscoveredRoots;
import com.gamma.config.safety.PathJail;
import com.gamma.util.AtomicFiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Load-time checks for a Space whose {@link SpaceRoot#dataRoot()} is off its base ({@code SPACE-DATA-ROOT-1}).
 * A refusal fails the Space's boot with a named reason; it never falls back to {@code <space>/data}, which would
 * silently split one Space's data across two places.
 */
final class SpaceDataRoot {

    /** The file in a data root naming the Space directory that owns it. */
    static final String STAMP = ".inspecto-space";

    private static final String[] STATE_DIRS = {"data", "audit", "duckdb"};

    /** Every spaces root this process discovers from: no data root may be, contain or sit inside one. */
    private static final Set<Path> SPACES_ROOTS = java.util.concurrent.ConcurrentHashMap.newKeySet();

    static void registerSpacesRoot(Path spacesRoot) {
        if (spacesRoot != null) SPACES_ROOTS.add(spacesRoot.toAbsolutePath().normalize());
    }

    private SpaceDataRoot() {}

    /**
     * Refuse an unusable data root, else claim it (see {@link #stake}) and create its {@code data/}, {@code audit/},
     * {@code duckdb/}. Also refused: files still under the Space's own {@code data/}, {@code audit/} or {@code duckdb/}
     * — nothing is moved at boot.
     */
    static void claim(SpaceRoot root, SpaceContext.SpaceManifest manifest) throws IOException {
        Path base = root.base();
        Path dataRoot = root.dataRoot();
        if (dataRoot.equals(base)) return;
        for (String sub : STATE_DIRS)
            if (holdsFiles(base.resolve(sub)))
                throw new IllegalStateException("the Space still holds " + base.resolve(sub) + " but its data_root is "
                        + dataRoot + " - move " + sub + "/ there first (nothing is moved at boot)");
        stake(root, manifest);
        for (String sub : STATE_DIRS) Files.createDirectories(dataRoot.resolve(sub));
    }

    /**
     * For a Space being CREATED (never one booting): claim the data root, then move what creation seeded under the
     * base ({@code data/} from a template or bundle, the empty convention dirs) onto it. Copy-then-delete, since the
     * data root is usually another volume. A no-op when the data root is the base.
     */
    static void adoptSeeded(SpaceRoot root) throws IOException {
        Path base = root.base();
        Path dataRoot = root.dataRoot();
        if (dataRoot.equals(base)) return;
        stake(root, SpaceContext.SpaceManifest.read(base.resolve("space.toon"), root.id()));   // before any byte is copied
        for (String sub : STATE_DIRS) {
            Path src = base.resolve(sub);
            if (!Files.isDirectory(src)) continue;
            Path dst = dataRoot.resolve(sub);
            try (Stream<Path> walk = Files.walk(src)) {
                for (Path p : walk.sorted().toList()) {
                    Path target = dst.resolve(src.relativize(p).toString());
                    if (Files.isDirectory(p)) Files.createDirectories(target);
                    else Files.copy(p, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
            try (Stream<Path> walk = Files.walk(src)) {
                for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(p);
            }
        }
    }

    /** Whether the stamp in {@code dataRoot} names the Space at {@code base} (canonical paths, so a case or alias
     *  change of the spaces root does not orphan a Space's own data root). */
    static boolean ownedBy(Path dataRoot, Path base) throws IOException {
        Path stamp = dataRoot.resolve(STAMP);
        if (!Files.isRegularFile(stamp)) return false;
        String claimed = Files.readString(stamp, StandardCharsets.UTF_8).trim();
        if (claimed.isEmpty()) return false;
        // A RELATIVE stamp (SPACE-RELOCATABLE-ROOTS-1) names the Space from the data root, so it survives a move of
        // the whole install; an absolute one is the pre-existing form and is compared as it always was.
        Path owner = Path.of(claimed);
        if (!AppRoot.rooted(owner)) owner = dataRoot.resolve(owner);
        return PathJail.canonical(owner).equals(PathJail.canonical(base));
    }

    /**
     * The checks every use of a moved data root shares, then the ownership stamp:
     * <ul>
     *   <li>{@code space.toon data_root} may be written rooted or relative (relative resolves against
     *       {@link AppRoot}); every check below applies to both, except where a bullet says otherwise.</li>
     *   <li>It may not overlap the spaces root or any Space directory (its own included): containing one, or sitting
     *       inside one, would hand this Space another Space's {@code config/} as an allowed root. Not a {@code *.secrets} dir.</li>
     *   <li>For an ABSOLUTE root, its parent (the mount) must already exist: an unmounted volume is refused, never
     *       created on the root disk. A relative root cannot name a mount, so its parents are created on first boot.</li>
     *   <li>It must be empty or already stamped by this Space. A stamp naming another Space is refused — a copied Space
     *       folder still pointing at the original's data root would otherwise write into and drain the original's inbox
     *       (the 2026-10-27 MFS rehearsal). An unstamped directory holding anything but {@code data/}, {@code audit/},
     *       {@code duckdb/} is refused — {@code data_root: /mnt/bigdisk} without the {@code /<id>} must never be claimed,
     *       since purge deletes what a Space owns. One holding only those is a Space's state moved there by hand.
     *       ⚠ The stamp is written RELATIVE (the Space's path from the data root) when {@code data_root} is relative
     *       ({@code SPACE-RELOCATABLE-ROOTS-1}). An absolute stamp is what refuses every Space after the install
     *       folder moves — the 2026-11-13 relocation failure — while a relative one moves with the install and still
     *       names a different folder for every install, so ownership is NOT weakened. ⛔ Do not "simplify" this by
     *       skipping the stamp for relative roots: two installs CAN resolve relative roots onto one directory (a
     *       shared disk, a second server), and an unstamped root is then silently claimed and purged by whichever
     *       Space reaches it first.</li>
     * </ul>
     */
    static void stake(SpaceRoot root, SpaceContext.SpaceManifest manifest) throws IOException {
        Path base = root.base();
        Path dataRoot = root.dataRoot();
        String declared = manifest.dataRoot() == null ? "" : manifest.dataRoot().trim();
        // Blank counts as rooted: no data_root but a data root off the base means the -Dspaces.data.root mount.
        // ⚠ AppRoot.rooted, never Path.isAbsolute: on Windows "/mnt/bigdisk" is rooted but NOT absolute, and taking
        // an operator's POSIX path for a relative one would skip the mount check and land it on the current drive.
        boolean rooted = declared.isBlank() || AppRoot.rooted(Path.of(declared));
        Path d = PathJail.canonical(dataRoot);
        Set<Path> spaces = new java.util.LinkedHashSet<>(DiscoveredRoots.all());
        spaces.add(base);
        spaces.addAll(SPACES_ROOTS);   // a referenced Space's parent is not the spaces root, so it is registered
        if (base.getParent() != null) spaces.add(base.getParent());
        DiscoveredRoots.dataRoots().forEach((id, other) -> {
            if (!id.equals(root.id())) spaces.add(other);   // another Space's data root, never shared or nested
        });
        for (Path s : spaces) {
            Path c = PathJail.canonical(s);
            if (d.startsWith(c) || c.startsWith(d))
                throw new IllegalStateException("data_root " + dataRoot + " overlaps " + s
                        + " - a data root must be outside the spaces root and every Space directory");
        }
        try {
            PathJail.refuseSecrets(d, dataRoot.toString(), "data_root");
        } catch (PathJail.Escape e) {
            throw new IllegalStateException("data_root " + dataRoot + " is a secrets directory");
        }
        // Reachability is a MOUNT check: a missing parent under a rooted data_root means the volume is not mounted,
        // and creating it would plant a data tree on the root disk instead. A relative one names a path under the app
        // root, which no mount can be, so there a missing parent only means first boot and it is created below.
        if (rooted && (dataRoot.getParent() == null || !Files.isDirectory(dataRoot.getParent())))
            throw new IllegalStateException("data_root " + dataRoot + " is not reachable: its parent "
                    + dataRoot.getParent() + " does not exist (is the volume mounted?)");

        Path stamp = dataRoot.resolve(STAMP);
        if (Files.exists(stamp)) {
            if (!ownedBy(dataRoot, base))
                throw new IllegalStateException("data_root " + dataRoot + " belongs to the Space at "
                        + Files.readString(stamp, StandardCharsets.UTF_8).trim() + ", not " + base
                        + " - a copied Space folder must get its own data_root");
            return;
        }
        if (Files.isDirectory(dataRoot)) {
            // A Space's own state moved here by hand (only data/, audit/, duckdb/) is the migration path, so it may be claimed.
            try (Stream<Path> kids = Files.list(dataRoot)) {
                if (kids.anyMatch(k -> !Files.isDirectory(k) || !Set.of(STATE_DIRS).contains(k.getFileName().toString())))
                    throw new IllegalStateException("data_root " + dataRoot + " is not empty and no Space has claimed it"
                            + " - point data_root at a new or empty directory (e.g. <mount>/" + root.id() + ")");
            }
        }
        Files.createDirectories(dataRoot);
        AtomicFiles.write(stamp, (stampText(base, dataRoot, rooted) + "\n").getBytes(StandardCharsets.UTF_8), ".stamp-");
    }

    /**
     * What the stamp records as the owning Space: the base's path FROM the data root when {@code data_root} was
     * written relative, so the pair moves together and the Space still owns its data at the new location; otherwise
     * the absolute base, the long-standing form. Falls back to absolute when the two have no common root (different
     * drives), which cannot happen for a relative {@code data_root} resolved against the app root.
     */
    private static String stampText(Path base, Path dataRoot, boolean rooted) {
        if (rooted) return base.toString();
        try {
            return dataRoot.relativize(base).toString();
        } catch (IllegalArgumentException e) {
            return base.toString();
        }
    }

    private static boolean holdsFiles(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return false;
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.anyMatch(Files::isRegularFile);
        }
    }
}
