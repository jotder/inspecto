package com.gamma.config.safety;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Where a store that used to write under {@code <write root>/audit/<sub>} keeps its files
 * ({@code SPACE-DATA-ROOT-CONFIG-AUDIT-1}): Link Analysis snapshots and Investigations ({@code snapshots}), analyst
 * upload records ({@code la-uploads}) and the identity fact log ({@code entity-facts}).
 *
 * <p>When the write root is a Space's {@code config/} and that Space has a data root off its base
 * ({@code SPACE-DATA-ROOT-1}), the directory is {@code <data root>/audit/<sub>} — exactly where
 * {@link PathJail#onDataRoot} sends {@code <space>/audit/<sub>}, so it stays inside the Space's allowed roots.
 * Otherwise (no data root, or a write root that is not a Space {@code config/}) it is the old
 * {@code <write root>/audit/<sub>} and nothing changes.
 *
 * <p><b>The one-time move.</b> The first time a JVM opens a relocated directory, every file still under the old one
 * is moved across (copy to a temp name, rename into place, delete the original — the data root is usually another
 * volume). It never overwrites: a file present in BOTH places is left in both, logged at WARN, and the new one is
 * what reads see. Running it again moves nothing more. A fault leaves the rest where it was and is retried on the
 * next open.
 *
 * <p><b>Fallback read.</b> A read that misses the new location while the old one holds the file first runs the move
 * again ({@link #settle}), so a store converges on one place instead of splitting across two; when the move still
 * cannot take it, {@link #readable} answers the old file. {@link #list} adds old-only entries to a listing.
 */
public final class SpaceAuditDir {

    private static final System.Logger log = System.getLogger(SpaceAuditDir.class.getName());

    /** Relocated directories this JVM has finished moving (keyed by the new directory). */
    private static final Set<Path> MOVED = ConcurrentHashMap.newKeySet();
    private static final Map<Path, Object> LOCKS = new ConcurrentHashMap<>();

    private final Path dir;
    private final Path legacy;

    private SpaceAuditDir(Path dir, Path legacy) {
        this.dir = dir;
        this.legacy = legacy;
    }

    /** The audit directory {@code sub} of {@code writeRoot}, moving the old one's files across on first use. */
    public static SpaceAuditDir of(Path writeRoot, String sub) {
        Path legacy = writeRoot.resolve("audit").resolve(sub);
        Path wr = writeRoot.toAbsolutePath().normalize();
        Path name = wr.getFileName();
        if (name == null || !name.toString().equals("config") || wr.getParent() == null)
            return new SpaceAuditDir(legacy, legacy);
        Path onBase = wr.getParent().resolve("audit").resolve(sub);
        Path moved = PathJail.onDataRoot(onBase);
        if (moved.equals(onBase)) return new SpaceAuditDir(legacy, legacy);
        SpaceAuditDir a = new SpaceAuditDir(moved, legacy);
        if (!MOVED.contains(moved)) a.tryMove();
        return a;
    }

    /** {@link #move()} under this directory's lock; a fault is logged and left for the next open or missed read. */
    private void tryMove() {
        synchronized (LOCKS.computeIfAbsent(dir, k -> new Object())) {
            try {
                move();
                MOVED.add(dir);
            } catch (IOException | RuntimeException e) {
                log.log(System.Logger.Level.WARNING, "could not move " + legacy + " to " + dir
                        + " (reads fall back to the old location; retried on the next open): " + e);
            }
        }
    }

    /** Where the store reads and writes. */
    public Path dir() {
        return dir;
    }

    /** The old {@code <write root>/audit/<sub>}; equal to {@link #dir()} when nothing is relocated. */
    public Path legacy() {
        return legacy;
    }

    /**
     * Move every file under {@link #legacy()} that is not yet under {@link #dir()}, never overwriting; answer how many
     * moved. Emptied old directories are removed. A no-op when nothing is relocated or nothing is left to move.
     */
    public int move() throws IOException {
        if (dir.equals(legacy) || !Files.isDirectory(legacy)) return 0;
        List<Path> files;
        try (Stream<Path> walk = Files.walk(legacy)) {
            files = walk.filter(Files::isRegularFile).sorted().toList();
        }
        int moved = 0;
        IOException first = null;   // one stuck file must not strand the files after it
        for (Path src : files) {
            Path target = dir.resolve(legacy.relativize(src).toString());
            if (Files.exists(target)) {
                log.log(System.Logger.Level.WARNING, src + " was not moved: " + target
                        + " already exists; both are kept and the new one is read");
                continue;
            }
            Path tmp = null;
            try {
                Files.createDirectories(target.getParent());
                tmp = target.resolveSibling("." + target.getFileName() + ".moving.tmp");
                Files.copy(src, tmp, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                Files.move(tmp, target);   // no REPLACE_EXISTING: a target created meanwhile is refused, never replaced
                Files.delete(src);
                moved++;
            } catch (IOException e) {
                if (tmp != null) Files.deleteIfExists(tmp);
                if (first == null) first = e;
                else first.addSuppressed(e);
            }
        }
        try (Stream<Path> walk = Files.walk(legacy)) {
            for (Path d : walk.filter(Files::isDirectory).sorted(Comparator.reverseOrder()).toList())
                try (Stream<Path> kids = Files.list(d)) {
                    if (kids.findAny().isEmpty()) Files.delete(d);
                }
        }
        if (moved > 0)
            log.log(System.Logger.Level.INFO, "moved " + moved + " file(s) from " + legacy + " to " + dir);
        if (first != null) throw first;
        return moved;
    }

    /**
     * When {@code f} (a path under {@link #dir()}) is missing but its old-location twin exists, run the move again so
     * the store reads and writes one place; a store whose files belong together (an Investigation's directory, an
     * append-only file) calls this before touching {@code f}.
     */
    public void settle(Path f) {
        if (dir.equals(legacy) || Files.exists(f)) return;
        Path old = twin(f);
        if (old != null && Files.exists(old)) tryMove();
    }

    /** {@code f} (a path under {@link #dir()}) after {@link #settle}, or its old-location twin when only that one exists. */
    public Path readable(Path f) {
        settle(f);
        if (dir.equals(legacy) || Files.exists(f)) return f;
        Path old = twin(f);
        return old != null && Files.exists(old) ? old : f;
    }

    /**
     * The entries of {@code d} (a directory under {@link #dir()}), plus those of its old-location twin whose names
     * {@code d} does not hold: new first. An absent directory lists nothing.
     */
    public List<Path> list(Path d) throws IOException {
        Map<String, Path> out = new LinkedHashMap<>();
        for (Path p : listOne(d)) out.put(p.getFileName().toString(), p);
        Path old = dir.equals(legacy) ? null : twin(d);
        if (old != null) for (Path p : listOne(old)) out.putIfAbsent(p.getFileName().toString(), p);
        return new ArrayList<>(out.values());
    }

    private static List<Path> listOne(Path d) throws IOException {
        if (!Files.isDirectory(d)) return List.of();
        try (Stream<Path> s = Files.list(d)) {
            return s.toList();
        }
    }

    private Path twin(Path f) {
        Path n = f.toAbsolutePath().normalize();
        Path d = dir.toAbsolutePath().normalize();
        return n.startsWith(d) ? legacy.resolve(d.relativize(n).toString()) : null;
    }
}
