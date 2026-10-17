package com.gamma.analytics.split;

import com.gamma.util.SqlIdent;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

/**
 * Deterministic holdout split (ANALYTICS-CORE-1 S5d): train / validation / test, reusable by any model, as JVM functions
 * and DuckDB SQL that give the same answer ({@code HoldoutSplitDuckDbParityTest}).
 *
 * <p>Fractions are held in <b>basis points</b> (1/10,000; a fraction is rounded to the nearest one), so both forms cut
 * with integer arithmetic and no floating boundary can differ between them.
 *
 * <ul>
 *   <li>{@link TimeSplit} — by time, no shuffle and no seed (it is deterministic by construction): the newest buckets are
 *       test, the ones before them validation, the rest train.</li>
 *   <li>{@link HashSplit} — by entity: {@code bucket(key) = md5_number_lower(seed ':' key) mod 10000}, then
 *       {@code bucket < train} → train, {@code < train + validation} → validation, else test. Every row of one entity
 *       lands in the same role; changing the seed reshuffles entities, the same seed never does.</li>
 * </ul>
 *
 * <p>Why MD5 and not DuckDB's {@code hash()}: {@code hash()} is DuckDB's internal hash, with no published definition
 * and no promise to stay the same across versions, so the JVM cannot reproduce it and a DuckDB upgrade could move
 * entities between roles. {@code md5_number_lower(VARCHAR)} is the lower 64 bits of the MD5 of the UTF-8 bytes (digest
 * bytes 8..15, little-endian, unsigned) — a fixed algorithm the JVM computes exactly; the same reading the LA index's
 * {@code BucketFunction} uses.
 */
public final class HoldoutSplit {

    /** Basis points in a whole. */
    public static final int WHOLE = 10_000;

    private HoldoutSplit() {}

    /** A row's role; {@link #wire()} is the SQL value. */
    public enum Role {
        TRAIN, VALIDATION, TEST;

        public String wire() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /** A fraction in [0, 1] as basis points, rounded to the nearest. */
    public static int basisPoints(double fraction) {
        if (!(fraction >= 0 && fraction <= 1)) throw new IllegalArgumentException("a fraction must be in [0, 1]: " + fraction);
        return (int) Math.round(fraction * WHOLE);
    }

    // ---- by time ----------------------------------------------------------------------------------------------------

    /**
     * Split by time over the distinct buckets of a series, newest last.
     *
     * @param byFraction {@code false}: {@code validation} and {@code test} are bucket counts (the last k buckets);
     *                   {@code true}: they are basis points of the bucket count, rounded DOWN
     *                   ({@code ⌊N · bp / 10000⌋}), so a share never takes more than its fraction
     */
    public record TimeSplit(boolean byFraction, int validation, int test) {
        public TimeSplit {
            if (validation < 0 || test < 0) throw new IllegalArgumentException("validation and test must be >= 0");
            if (byFraction && validation + test >= WHOLE) throw new IllegalArgumentException("validation + test must leave a train share");
        }

        /** The last {@code test} buckets are test, the {@code validation} before them validation. */
        public static TimeSplit lastBuckets(int validationBuckets, int testBuckets) {
            return new TimeSplit(false, validationBuckets, testBuckets);
        }

        /** Shares of the bucket count, each rounded down after the fraction is taken to basis points. */
        public static TimeSplit fractions(double validation, double test) {
            return new TimeSplit(true, basisPoints(validation), basisPoints(test));
        }

        /** {train, validation, test} bucket counts for {@code buckets} distinct buckets; fails when train would be empty. */
        public int[] counts(int buckets) {
            if (buckets < 0) throw new IllegalArgumentException("buckets must be >= 0");
            long v = byFraction ? (long) buckets * validation / WHOLE : validation;
            long t = byFraction ? (long) buckets * test / WHOLE : test;
            long train = buckets - v - t;
            if (train < 1) throw new IllegalArgumentException("time split: " + buckets + " buckets leave no train bucket");
            return new int[] {(int) train, (int) v, (int) t};
        }

        /** The role of the bucket at {@code index} (0 = oldest) among {@code buckets} distinct buckets. */
        public Role role(int index, int buckets) {
            if (index < 0 || index >= buckets) throw new IllegalArgumentException("index " + index + " outside 0.." + (buckets - 1));
            int[] c = counts(buckets);
            return index < c[0] ? Role.TRAIN : index < c[0] + c[1] ? Role.VALIDATION : Role.TEST;
        }

        /**
         * {@code SELECT *, <role> AS <splitColumn> FROM relation}: the role of each row's bucket among the relation's
         * distinct non-NULL {@code bucketColumn} values (one global cut, not per entity). A NULL bucket gets a NULL
         * role. Fails through {@code error()} when train would be empty. Filter with {@code WHERE <splitColumn> = 'test'}.
         */
        public String sql(String relation, String bucketColumn, String splitColumn) {
            String b = SqlIdent.q(bucketColumn);
            String v = byFraction ? "(__nb * " + validation + " // " + WHOLE + ")" : String.valueOf(validation);
            String t = byFraction ? "(__nb * " + test + " // " + WHOLE + ")" : String.valueOf(test);
            return "SELECT * EXCLUDE (__rk, __nb), CASE WHEN " + b + " IS NULL THEN NULL"
                    + " WHEN __nb - " + v + " - " + t + " < 1 THEN error(" + SqlIdent.sqlStr("time split: no train bucket left") + ")"
                    + " WHEN __rk <= " + t + " THEN 'test' WHEN __rk <= " + t + " + " + v + " THEN 'validation' ELSE 'train' END AS "
                    + SqlIdent.q(splitColumn)
                    + " FROM (SELECT *, max(CASE WHEN " + b + " IS NOT NULL THEN __rk END) OVER () AS __nb FROM"
                    + " (SELECT *, dense_rank() OVER (ORDER BY " + b + " DESC NULLS LAST) AS __rk FROM " + SqlIdent.q(relation) + "))";
        }
    }

    // ---- by entity hash ---------------------------------------------------------------------------------------------

    /**
     * Split by a stable hash of the entity key.
     *
     * @param seed       any number; part of the hashed text
     * @param train      train share in basis points
     * @param validation validation share in basis points; test is the rest
     */
    public record HashSplit(long seed, int train, int validation) {
        public HashSplit {
            if (train < 0 || validation < 0 || train + validation > WHOLE)
                throw new IllegalArgumentException("train + validation must be within 0.." + WHOLE + " basis points");
        }

        /** From fractions that sum to 1 once each is taken to basis points. */
        public static HashSplit of(long seed, double train, double validation, double test) {
            int tr = basisPoints(train), va = basisPoints(validation), te = basisPoints(test);
            if (tr + va + te != WHOLE) throw new IllegalArgumentException("split fractions must sum to 1: " + train + " + " + validation + " + " + test);
            return new HashSplit(seed, tr, va);
        }

        /** The hashed text: {@code <seed>:<key>}. */
        public String hashed(String key) {
            return seed + ":" + Objects.requireNonNull(key, "key");
        }

        /** {@code md5_number_lower(seed ':' key) mod 10000} — in [0, 10000). */
        public int bucket(String key) {
            byte[] d;
            try {
                d = MessageDigest.getInstance("MD5").digest(hashed(key).getBytes(StandardCharsets.UTF_8));
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
            long lower = 0;
            for (int i = 15; i >= 8; i--) lower = (lower << 8) | (d[i] & 0xFFL);
            return (int) Long.remainderUnsigned(lower, WHOLE);
        }

        public Role role(String key) {
            int b = bucket(key);
            return b < train ? Role.TRAIN : b < train + validation ? Role.VALIDATION : Role.TEST;
        }

        /** DuckDB SQL for {@link #bucket} of {@code keyColumn} read as VARCHAR; NULL for a NULL key. */
        public String bucketSql(String keyColumn) {
            return "CAST(md5_number_lower(" + SqlIdent.sqlStr(seed + ":") + " || CAST(" + SqlIdent.q(keyColumn)
                    + " AS VARCHAR)) % " + WHOLE + " AS INTEGER)";
        }

        /** DuckDB SQL for {@link #role} as {@code 'train'}, {@code 'validation'} or {@code 'test'}; NULL for a NULL key. */
        public String roleSql(String keyColumn) {
            String b = bucketSql(keyColumn);
            return "CASE WHEN " + b + " < " + train + " THEN 'train' WHEN " + b + " < " + (train + validation)
                    + " THEN 'validation' WHEN " + b + " IS NOT NULL THEN 'test' END";
        }

        /** A DuckDB predicate true exactly for the rows whose key has {@code role} (false/NULL for a NULL key). */
        public String predicateSql(String keyColumn, Role role) {
            String b = bucketSql(keyColumn);
            return switch (role) {
                case TRAIN -> "(" + b + " < " + train + ")";
                case VALIDATION -> "(" + b + " >= " + train + " AND " + b + " < " + (train + validation) + ")";
                case TEST -> "(" + b + " >= " + (train + validation) + ")";
            };
        }
    }
}
