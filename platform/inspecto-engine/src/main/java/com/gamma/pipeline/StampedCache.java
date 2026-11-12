package com.gamma.pipeline;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * A per-directory cache of something PARSED FROM the files under that directory, valid exactly while no file under it
 * changed: the key is a stamp of every regular file's relative path, size and last-modified time. Taking the stamp is
 * one directory walk that reads the attributes the walk already returns (no decode), which is what makes it cheap next
 * to a re-parse of the same files (LA-PERF-SERVER-1: the registry scan and the pipeline-schema lineage re-parsed the
 * whole config tree on every Working Set / projection request, ~80 % of an Investigation list's time).
 *
 * <p><b>Why it is safe for a masking decision:</b> there is no time-to-live. A changed, added or removed file changes the
 * stamp, so the next read parses again. The one gap a stamp has - a rewrite that keeps the size and lands in the same
 * timestamp tick - is closed the way git closes it: a directory holding any file modified within {@link #RACY_MS} of
 * the walk is never cached (it is parsed fresh and not remembered), so only a tree that has been still for two seconds
 * is served from memory. An unreadable directory is never cached either.
 */
public final class StampedCache<V> {

    /** A file modified this recently makes the stamp untrustworthy (same-tick, same-size rewrite). */
    static final long RACY_MS = 2_000L;
    static final int MAX_ROOTS = 64;

    private record Held<V>(long stamp, Object salt, V value) { }

    private final Map<Path, Held<V>> entries = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Path, Held<V>> eldest) {
            return size() > MAX_ROOTS;
        }
    };
    private final java.util.concurrent.atomic.AtomicLong loads = new java.util.concurrent.atomic.AtomicLong();

    /** Loads performed (cache misses and uncacheable reads) - tests pin how many a burst of reads costs. */
    public long loadCount() { return loads.get(); }

    /** {@code root}'s value: the remembered one while the tree is unchanged, else {@code loader}'s (remembered when the tree is quiet). */
    public V get(Path root, Supplier<V> loader) {
        return get(root, loader, v -> true);
    }

    /** {@link #get(Path, Supplier)}; a loaded value {@code cacheable} refuses (it read a file outside {@code root}) is returned but not remembered. */
    public V get(Path root, Supplier<V> loader, java.util.function.Predicate<V> cacheable) {
        return get(root, "", loader, cacheable);
    }

    /**
     * {@link #get(Path, Supplier, java.util.function.Predicate)} for a value that ALSO depends on something outside the tree
     * ({@code salt}: e.g. the Safety Policy's roots, which decide whether a file parses). A different salt is a miss;
     * a null salt means the dependency cannot be read: never cached.
     */
    public V get(Path root, Object salt, Supplier<V> loader, java.util.function.Predicate<V> cacheable) {
        Path key = root.toAbsolutePath().normalize();
        long stamp = salt == null ? 0L : stamp(key);
        if (stamp != 0L) {
            synchronized (entries) {
                Held<V> e = entries.get(key);
                if (e != null && e.stamp() == stamp && e.salt().equals(salt)) return e.value();
            }
        }
        loads.incrementAndGet();
        V fresh = loader.get();
        if (stamp != 0L && cacheable.test(fresh)) {
            // the stamp was taken BEFORE the load, so a file changed during the load makes the next stamp differ
            synchronized (entries) {
                entries.put(key, new Held<>(stamp, salt, fresh));
            }
        }
        return fresh;
    }

    /** Forgets {@code root}: a writer that just changed a file under it. */
    public void invalidate(Path root) {
        synchronized (entries) {
            entries.remove(root.toAbsolutePath().normalize());
        }
    }

    /** 64-bit FNV-1a: a rename must not collide the way a 32-bit {@code String.hashCode} can ("Aa" vs "BB"). */
    private static long fnv64(String t) {
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < t.length(); i++) h = (h ^ t.charAt(i)) * 0x100000001b3L;
        return h;
    }

    /** 0 = do not cache (missing or unreadable tree, or a file modified within {@link #RACY_MS}); else a hash of the tree. */
    static long stamp(Path root) {
        if (!Files.isDirectory(root)) return 0L;
        long now = System.currentTimeMillis();
        long[] acc = {1125899906842597L, 0L};   // hash, racy flag
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path f, BasicFileAttributes a) {
                    if (a.isSymbolicLink() || a.isOther()) {   // a link's target is not under the stamp: do not trust the tree
                        acc[1] = 1L;
                        return FileVisitResult.CONTINUE;
                    }
                    if (!a.isRegularFile()) return FileVisitResult.CONTINUE;
                    long mod = a.lastModifiedTime().toMillis();
                    if (now - mod < RACY_MS) acc[1] = 1L;
                    long h = fnv64(root.relativize(f).toString());
                    h = h * 31 + a.size();
                    h = h * 31 + a.lastModifiedTime().to(java.util.concurrent.TimeUnit.NANOSECONDS);
                    acc[0] += h * 0x9E3779B97F4A7C15L;   // a sum: independent of the walk order
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path f, IOException e) throws IOException {
                    throw e;
                }
            });
        } catch (IOException | RuntimeException e) {
            return 0L;
        }
        return acc[1] == 1L ? 0L : (acc[0] == 0L ? 1L : acc[0]);
    }
}
