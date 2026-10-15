package com.gamma.la.storage;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * THE definition of the index bucket (D-3 Decision 3, amended 2026-10-02): {@code bucket(id) = md5_number_lower(id) % N}.
 *
 * <p>Two renderings of that one definition live here and nowhere else, and {@code BucketFunctionTest} proves they agree:
 * <ul>
 *   <li>{@link #sql(String, int)} - what the builder emits into the {@code COPY ... PARTITION_BY (bucket)} statement;</li>
 *   <li>{@link #bucketOf(String, int)} - what the engine computes in Java, so a lookup needs no round trip.</li>
 * </ul>
 * DuckDB's {@code md5_number_lower(VARCHAR)} is the LOWER 64 bits of the MD5 digest of the id's UTF-8 bytes, as an
 * unsigned 64-bit integer: digest bytes 8..15 read little-endian. {@code % N} is an unsigned remainder
 * ({@code Long.remainderUnsigned}), because the value is a UBIGINT. MD5 is a fixed algorithm, so the bucket cannot drift
 * with a DuckDB upgrade; that DuckDB keeps THIS function's byte order is checked by the golden test and recorded in the
 * manifest ({@link #NAME}).
 *
 * <p>An id must be valid Unicode (a database VARCHAR always is); an unpaired surrogate would be encoded as '?' by Java and
 * differ from DuckDB.
 */
public final class BucketFunction {

    /** The manifest's {@code bucketFn} value. */
    public static final String NAME = "md5_number_lower";

    public static final int MIN_BUCKETS = 16;
    public static final int MAX_BUCKETS = 1024;
    /** Edges per bucket the bucket count aims for (the plan's "a bucket near 10^6-10^7 edges", measured at 4*10^6). */
    public static final long EDGES_PER_BUCKET = 4_000_000L;

    private BucketFunction() { }

    /** The SQL for the bucket of the VARCHAR expression {@code idExpr}: an INTEGER in {@code [0, buckets)}; NULL for a NULL id. */
    public static String sql(String idExpr, int buckets) {
        checkBuckets(buckets);
        return "CAST(md5_number_lower(" + idExpr + ") % " + buckets + " AS INTEGER)";
    }

    /** Java twin of {@link #sql(String, int)}. */
    public static int bucketOf(String id, int buckets) {
        checkBuckets(buckets);
        byte[] d;
        try {
            d = MessageDigest.getInstance("MD5").digest(id.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        long lower = 0;
        for (int i = 15; i >= 8; i--) lower = (lower << 8) | (d[i] & 0xFFL);
        return (int) Long.remainderUnsigned(lower, buckets);
    }

    /** {@code clamp(pow2(ceil(edges / 4e6)), 16, 1024)} - the signed bucket-count formula at the shipped {@link Sizing#DEFAULT}. */
    public static int bucketsFor(long edges) {
        return Sizing.DEFAULT.bucketsFor(edges);
    }

    /**
     * The bucket-count sizing ({@code LA-CONFIG-LIMITS-1} CL-9: the install-wide {@code index.edges_per_bucket},
     * {@code index.min_buckets}, {@code index.max_buckets}, passed in by the caller - this module reads no settings). The
     * manifest records the {@link #signature} a FULL build used, and {@code IndexPlan} requires a FULL rebuild once the
     * sizing in force differs ({@code bucket_sizing_changed}): an append keeps its parent's bucket count, so only a full
     * build can re-bucket. A {@code maxBuckets} below {@code minBuckets} reads as {@code minBuckets}.
     */
    public record Sizing(long edgesPerBucket, int minBuckets, int maxBuckets) {
        /** The shipped sizing (the catalogue defaults). */
        public static final Sizing DEFAULT = new Sizing(EDGES_PER_BUCKET, MIN_BUCKETS, MAX_BUCKETS);

        public Sizing {
            if (edgesPerBucket < 1 || minBuckets < 1 || maxBuckets < 1)
                throw new IllegalArgumentException("bucket sizing must be positive: " + edgesPerBucket + "/" + minBuckets + "/" + maxBuckets);
            maxBuckets = Math.max(minBuckets, maxBuckets);
        }

        /** {@code clamp(pow2(ceil(edges / edgesPerBucket)), minBuckets, maxBuckets)}; pow2 rounds UP to a power of two. */
        public int bucketsFor(long edges) {
            long want = Math.max(1, edges / edgesPerBucket + (edges % edgesPerBucket == 0 ? 0 : 1));
            long pow2 = Long.highestOneBit(want) == want ? want : Long.highestOneBit(want) << 1;
            return (int) Math.max(minBuckets, Math.min(maxBuckets, pow2));
        }

        /** The manifest's {@code bucketSizing} value: {@code <edgesPerBucket>/<minBuckets>/<maxBuckets>}. */
        public String signature() {
            return edgesPerBucket + "/" + minBuckets + "/" + maxBuckets;
        }
    }

    private static void checkBuckets(int buckets) {
        if (buckets < 1) throw new IllegalArgumentException("buckets must be >= 1: " + buckets);
    }
}
