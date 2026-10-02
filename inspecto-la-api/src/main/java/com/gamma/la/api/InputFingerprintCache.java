package com.gamma.la.api;

import com.gamma.la.core.InputFingerprint;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * A short-TTL cache of a Dataset's current {@link InputFingerprint} for the traversal hot path (D-3 design 5.3a): taking one
 * is a directory listing of the Dataset's files (~0.19 ms per file), too much to pay on EVERY request. Keyed by
 * (write root, dataset, mapping hash); bounded ({@link #MAX_ENTRIES}, least-recently-used out); an entry lives {@link #DEFAULT_TTL_MS}.
 * An index build that COMPLETES invalidates its dataset+mapping entries, so the next request sees the files the build read.
 *
 * <p>A cached {@code null} (the provider cannot say) is a real answer and is cached too. The trade-off is stated, not hidden:
 * a file added or removed within the TTL is noticed at most one TTL later.
 */
public final class InputFingerprintCache {

    public static final long DEFAULT_TTL_MS = 30_000L;
    public static final int MAX_ENTRIES = 256;

    private record Key(Path root, String dataset, String mapping) { }

    private record Cached(InputFingerprint value, long expiresAt) { }

    private static final Map<Key, Cached> ENTRIES = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Key, Cached> eldest) {
            return size() > MAX_ENTRIES;
        }
    };
    private static final LongAdder LOADS = new LongAdder();
    private static volatile LongSupplier clock = System::currentTimeMillis;
    private static volatile long ttlMs = DEFAULT_TTL_MS;

    private InputFingerprintCache() { }

    /** The cached fingerprint, or {@code loader}'s (counted as one listing) when absent or expired. */
    static InputFingerprint get(Path root, String dataset, String mapping, Supplier<InputFingerprint> loader) {
        Key k = new Key(root, dataset, mapping);
        long now = clock.getAsLong();
        synchronized (ENTRIES) {
            Cached e = ENTRIES.get(k);
            if (e != null && now < e.expiresAt()) return e.value();
        }
        LOADS.increment();
        InputFingerprint fresh = loader.get();                                                // outside the lock: a listing is slow
        synchronized (ENTRIES) {
            ENTRIES.put(k, new Cached(fresh, now + ttlMs));
        }
        return fresh;
    }

    /** Drops every entry of {@code dataset} + {@code mapping} (any root): an index build for it just completed. */
    static void invalidate(String dataset, String mapping) {
        synchronized (ENTRIES) {
            ENTRIES.keySet().removeIf(k -> k.dataset().equals(dataset) && k.mapping().equals(mapping));
        }
    }

    /** Test seam: a fake clock and TTL; both null/0 = the real ones. Always clears the cache. */
    public static void forTest(LongSupplier fakeClock, long ttl) {
        synchronized (ENTRIES) {
            ENTRIES.clear();
        }
        clock = fakeClock == null ? System::currentTimeMillis : fakeClock;
        ttlMs = ttl <= 0 ? DEFAULT_TTL_MS : ttl;
    }

    /** Test seam: how many fingerprints were actually taken (listings), as opposed to answered from the cache. */
    public static long loads() {
        return LOADS.sum();
    }
}
