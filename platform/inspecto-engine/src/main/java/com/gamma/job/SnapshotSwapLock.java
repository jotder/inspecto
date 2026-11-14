package com.gamma.job;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Lets a Job read a snapshot Dataset while the Job that writes it publishes a new snapshot
 * (JOB-SNAPSHOT-SWAP-READER-RACE-1): one fair read/write lock per store directory, JVM-wide.
 *
 * <p><b>Why the swap waits, rather than keeping the hidden files longer.</b> A reader resolves a store to an explicit
 * file list when it registers its view ({@code ConsignmentSelector} pins the list once an output registry exists), and
 * DuckDB opens each file by that path later: at bind, and again when the scan reaches it. The swap hides the old
 * snapshot by RENAMING it to {@code *.stale}, so its path is gone the moment the swap starts and the reader fails
 * ("No files found that match the pattern", or "Cannot open file" mid-scan) however long the {@code .stale} file is
 * kept. A reader that already has the file open is unaffected by the rename and by the delete alike. The old path
 * cannot simply stay either: new readers glob {@code *.parquet}, and two visible snapshots are doubled rows.
 *
 * <p>So a reader holds the read lock of every store it reads, from before it pins the files until its statement has
 * scanned them, and a writer holds the write lock only around hide and reveal. A reader that starts during a swap
 * waits for the reveal instead of finding no file.
 *
 * <p>⚠ Limits. Only readers that take the lock are covered ({@code sql.template} sources); any other reader of the
 * store (a BI query, Link Analysis, an Alert Rule's evaluation, a pipeline's {@code source_store}, a
 * {@code materialize} source) can still fail across a swap. A publish waits for the slowest covered reader of its
 * store; the wait is interruptible, so the writer's deadline bounds it. One JVM only: another process writing the
 * same data root is not excluded.
 */
public final class SnapshotSwapLock {

    private static final ConcurrentHashMap<Path, ReentrantReadWriteLock> LOCKS = new ConcurrentHashMap<>();

    private SnapshotSwapLock() { }

    /** A held lock; {@link #close()} releases it. */
    public interface Held extends AutoCloseable {
        @Override void close();
    }

    /**
     * Hold every store in {@code storeDirs} against a swap. Taken in path order: two readers taking several stores in
     * different orders could otherwise deadlock behind writers queued on the fair locks.
     */
    public static Held read(Collection<Path> storeDirs) throws InterruptedException {
        List<Lock> held = new ArrayList<>();
        try {
            for (Path dir : new TreeSet<>(storeDirs.stream().map(SnapshotSwapLock::key).toList())) {
                Lock l = lock(dir).readLock();
                l.lockInterruptibly();
                held.add(l);
            }
        } catch (InterruptedException | RuntimeException e) {
            held.forEach(Lock::unlock);
            throw e;
        }
        return () -> held.forEach(Lock::unlock);
    }

    /** Exclude the readers of {@code storeDir} for a swap, after the ones in flight have finished. */
    public static Held write(Path storeDir) throws IOException {
        Lock l = lock(key(storeDir)).writeLock();
        try {
            l.lockInterruptibly();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("interrupted waiting for the readers of " + storeDir + " to finish");
        }
        return l::unlock;
    }

    /** Whether a thread waits for {@code storeDir}'s lock (tests use it to see a writer blocked by a reader). */
    static boolean hasQueuedThreads(Path storeDir) {
        return lock(key(storeDir)).hasQueuedThreads();
    }

    private static Path key(Path dir) {
        return dir.toAbsolutePath().normalize();
    }

    private static ReentrantReadWriteLock lock(Path key) {
        return LOCKS.computeIfAbsent(key, k -> new ReentrantReadWriteLock(true));
    }
}
