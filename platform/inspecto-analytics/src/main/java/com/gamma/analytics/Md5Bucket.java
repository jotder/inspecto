package com.gamma.analytics;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * The one definition of the MD5 bucket: {@code bucket(text) = md5_number_lower(text) mod N}, as a JVM function and as
 * DuckDB SQL that agree. Used by the holdout split ({@code HoldoutSplit.HashSplit}) and Link Analysis' index bucket
 * ({@code BucketFunction}).
 *
 * <p>DuckDB's {@code md5_number_lower(VARCHAR)} is the LOWER 64 bits of the MD5 digest of the text's UTF-8 bytes, as an
 * unsigned 64-bit integer: digest bytes 8..15 read little-endian. {@code mod N} is an unsigned remainder
 * ({@link Long#remainderUnsigned}), because the value is a UBIGINT. MD5 is a fixed algorithm, so a bucket cannot drift
 * with a DuckDB upgrade the way DuckDB's internal {@code hash()} could.
 *
 * <p>The text must be valid Unicode (a database VARCHAR always is); an unpaired surrogate would be encoded as '?' by
 * Java and differ from DuckDB.
 */
public final class Md5Bucket {

    private Md5Bucket() {}

    /** {@code md5_number_lower(text)}: the lower 64 bits of the MD5 of the UTF-8 bytes, little-endian, as an unsigned long. */
    public static long lower64(String text) {
        byte[] d;
        try {
            d = MessageDigest.getInstance("MD5").digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        long lower = 0;
        for (int i = 15; i >= 8; i--) lower = (lower << 8) | (d[i] & 0xFFL);
        return lower;
    }

    /** The bucket of {@code text} among {@code buckets}: in {@code [0, buckets)}. */
    public static int of(String text, int buckets) {
        check(buckets);
        return (int) Long.remainderUnsigned(lower64(text), buckets);
    }

    /** DuckDB SQL for {@link #of} over the VARCHAR expression {@code textExpr}: an INTEGER; NULL for a NULL text. */
    public static String sql(String textExpr, int buckets) {
        check(buckets);
        return "CAST(md5_number_lower(" + textExpr + ") % " + buckets + " AS INTEGER)";
    }

    private static void check(int buckets) {
        if (buckets < 1) throw new IllegalArgumentException("buckets must be >= 1: " + buckets);
    }
}
