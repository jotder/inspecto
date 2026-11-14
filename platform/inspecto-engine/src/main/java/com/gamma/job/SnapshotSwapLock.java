package com.gamma.job;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Lets a reader read a snapshot Dataset while a writer publishes a new snapshot of it or rewrites its files
 * (JOB-SNAPSHOT-SWAP-READER-RACE-1, SNAPSHOT-SWAP-UNLOCKED-READERS-1): one fair read/write lock per store directory,
 * JVM-wide.
 *
 * <p><b>Why the swap waits, rather than keeping the hidden files longer.</b> A reader resolves a store to an explicit
 * file list when it registers its view ({@code ConsignmentSelector} pins the list once an output registry exists), and
 * DuckDB opens each file by that path later: at bind, and again when the scan reaches it. The swap hides the old
 * snapshot by RENAMING it to {@code *.stale}, so its path is gone the moment the swap starts and the reader fails
 * ("No files found that match the pattern", or "Cannot open file" mid-scan) however long the {@code .stale} file is
 * kept. A reader that already has the file open is unaffected by the rename and by the delete alike. The old path
 * cannot simply stay either: new readers glob {@code *.parquet}, and two visible snapshots are doubled rows.
 *
 * <p><b>Writers</b> hold the write lock only around the step that removes or renames a live {@code *.parquet} (hide and
 * reveal, a compaction's swap, a retention delete), never while they wait for another store's lock. {@link #write}
 * refuses a thread that still holds a read lock of the same store: that wait could never end.
 *
 * <p><b>Readers</b> take it three ways:
 * <ul>
 *   <li>Every pin ({@code ConsignmentSelector}) holds the read lock while it enumerates the store ({@link #pin}), so a
 *       pin sees the snapshot before a swap or after it, never the hidden middle.</li>
 *   <li>A Job holds its stores from before it pins until its statement has scanned them: a {@code sql.template} Job
 *       names them ({@link #read}); {@code materialize} and a pipeline's {@code source_store} hold whatever they pin
 *       inside a {@link #hold()} block. A publish waits for such a reader.</li>
 *   <li>An interactive read ({@code QueryExecutor}: BI, Link Analysis, Alert Rule evaluation) scans unlocked, so a
 *       publish never waits for a user's query; when a swap removed a file it pinned ({@link #lostPin}) it renders its
 *       relation again and scans it once more inside a {@link #hold()} block, where the new snapshot cannot be swapped
 *       away before the scan ends.</li>
 * </ul>
 *
 * <p><b>Who waits for whom:</b> a reader that holds no store yet queues behind a writer already waiting, so new readers
 * cannot starve a publish, and only a reader that already holds a store may overtake queued writers (waiting just for a
 * swap under way), so readers holding several stores cannot deadlock ({@link #pin}).
 *
 * <p>The lock key is the store directory; a pipeline-shaped store's {@code database/} read root counts as the store
 * itself, so {@code orders} and {@code orders/database} meet on one lock.
 *
 * <p>⚠ Limits. A reader that pins outside {@code ConsignmentSelector}, or scans a store without a pin or a lock, can
 * still fail across a swap; so can one that names a sub-directory of a store (a partition, an explicit deeper ref),
 * which locks that sub-directory only. A publish waits for the slowest reader holding its store when it queued, and for
 * any reader that overtook it while already holding another store; an interrupt does not stop a running DuckDB
 * statement, so the writer's deadline bounds the wait. While a publish waits, a new reader of that store waits too
 * (a BI pin included). One JVM only: another process writing the same data root is not excluded.
 */
public final class SnapshotSwapLock {

    private static final ConcurrentHashMap<Path, ReentrantReadWriteLock> LOCKS = new ConcurrentHashMap<>();

    /** The read locks this thread's open {@link #hold()} block has taken; absent outside a block. */
    private static final ThreadLocal<List<ReentrantReadWriteLock>> HOLDING = new ThreadLocal<>();

    /** How many read locks of this class this thread holds now (through {@link #read}, {@link #hold()} or a pin). */
    private static final ThreadLocal<int[]> READS = ThreadLocal.withInitial(() -> new int[1]);

    private static final Held NONE = () -> { };

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
                READS.get()[0]++;
            }
        } catch (InterruptedException | RuntimeException e) {
            release(held);
            throw e;
        }
        return () -> release(held);
    }

    private static void release(List<Lock> held) {
        held.forEach(Lock::unlock);
        READS.get()[0] -= held.size();
    }

    /**
     * Keep, until the returned handle closes, the read lock of every store this thread pins meanwhile ({@link #pin}).
     * A nested block joins the outer one, and closing a handle twice does nothing. Close it before this thread swaps a
     * store it read.
     */
    public static Held hold() {
        if (HOLDING.get() != null) return NONE;
        List<ReentrantReadWriteLock> held = new ArrayList<>();
        HOLDING.set(held);
        return () -> {
            if (HOLDING.get() != held) return;
            HOLDING.remove();
            release(held.stream().<Lock>map(ReentrantReadWriteLock::readLock).toList());
        };
    }

    /**
     * The read lock of {@code storeRoot} for a pin: the caller enumerates the store's files while holding it. Inside a
     * {@link #hold()} block the lock stays held until the block closes and the returned handle does nothing.
     *
     * <p>A thread that holds no store yet queues fairly, behind a writer already waiting, so new readers can never keep a
     * publish waiting. A thread that already holds a store overtakes queued writers and waits only while a swap holds
     * the write lock (hide and reveal, milliseconds); it must, or two such readers each holding one store and pinning
     * the other's would deadlock behind the writers queued on both. That keeps pins in any order deadlock-free: a thread
     * that waits behind a writer holds nothing anyone waits for, and a thread that holds something waits only for a
     * writer that already has its lock, which waits for nothing.
     *
     * @param storeRoot the directory the pin enumerates; a URI (an object-store location) takes no lock
     */
    public static Held pin(String storeRoot) {
        Path dir;
        try {
            if (storeRoot == null || storeRoot.contains("://")) return NONE;
            dir = key(Path.of(storeRoot));
        } catch (InvalidPathException e) {
            return NONE;
        }
        ReentrantReadWriteLock rw = lock(dir);
        List<ReentrantReadWriteLock> holding = HOLDING.get();
        if (holding != null && holding.contains(rw)) return NONE;
        try {
            if (READS.get()[0] == 0) {
                rw.readLock().lockInterruptibly();   // fair: behind a writer already waiting
            } else {
                while (!rw.readLock().tryLock()) {   // overtakes queued writers; fails only while a swap holds the lock
                    if (Thread.interrupted()) throw new InterruptedException();
                    LockSupport.parkNanos(200_000L);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted waiting for a snapshot swap of " + dir + " to finish");
        }
        READS.get()[0]++;
        if (holding == null) return () -> release(List.of(rw.readLock()));
        holding.add(rw);
        return NONE;
    }

    /** Exclude the readers of {@code storeDir} for a swap, after the ones in flight have finished. */
    public static Held write(Path storeDir) throws IOException {
        ReentrantReadWriteLock rw = lock(key(storeDir));
        if (rw.getReadHoldCount() > 0)
            throw new IllegalStateException("a swap of " + storeDir + " would wait for this thread's own read of it");
        Lock l = rw.writeLock();
        try {
            l.lockInterruptibly();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("interrupted waiting for the readers of " + storeDir + " to finish");
        }
        return l::unlock;
    }

    /**
     * Whether {@code e} is DuckDB not finding a file a read pinned: what a swap that renamed it away causes ("No files
     * found that match the pattern" at bind, "Cannot open file" once the scan reaches it).
     */
    public static boolean lostPin(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String m = t.getMessage();
            if (m != null && (m.contains("No files found that match the pattern") || m.contains("Cannot open file")))
                return true;
        }
        return false;
    }

    /** Whether a thread waits for {@code storeDir}'s lock (diagnostics; tests use it to see a writer blocked by a reader). */
    public static boolean hasQueuedThreads(Path storeDir) {
        return lock(key(storeDir)).hasQueuedThreads();
    }

    private static Path key(Path dir) {
        Path p = dir.toAbsolutePath().normalize();
        Path name = p.getFileName();
        return name != null && name.toString().equals("database") && p.getParent() != null ? p.getParent() : p;
    }

    private static ReentrantReadWriteLock lock(Path key) {
        return LOCKS.computeIfAbsent(key, k -> new ReentrantReadWriteLock(true));
    }
}
