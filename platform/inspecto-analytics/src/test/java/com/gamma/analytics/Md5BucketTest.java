package com.gamma.analytics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link Md5Bucket} against figures computed outside Java (Python hashlib: lower 64 bits of the MD5, little-endian,
 * unsigned mod N). The DuckDB agreement is held by {@code HoldoutSplitDuckDbParityTest} and LA's {@code BucketFunctionTest}.
 */
class Md5BucketTest {

    @Test
    void goldenBuckets() {
        assertEquals(4958, Md5Bucket.of("alice", 10_000));
        assertEquals(142, Md5Bucket.of("alice", 1024));
        assertEquals(8796, Md5Bucket.of("bob", 10_000));
        assertEquals(2889, Md5Bucket.of("-1:été", 10_000), "UTF-8 bytes");
        assertEquals(396, Md5Bucket.of("😀", 10_000), "a code point above U+FFFF");
        assertEquals(3193, Md5Bucket.of("", 10_000));
    }

    @Test
    void sqlTextAndBucketCount() {
        assertEquals("CAST(md5_number_lower(x) % 16 AS INTEGER)", Md5Bucket.sql("x", 16));
        assertThrows(IllegalArgumentException.class, () -> Md5Bucket.of("x", 0));
        assertThrows(IllegalArgumentException.class, () -> Md5Bucket.sql("x", 0));
    }
}
