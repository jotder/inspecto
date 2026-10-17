package com.gamma.config.safety;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PathJail#requireObjectStore} — the object-store containment rule (SCALEOUT-OBJSTORE-DATABASE-DIR-1).
 *
 * <p>⚠ Every refusal is paired with the ACCEPTED value it was derived from, under the same allow-list, so a
 * refusal cannot pass because the rule refuses everything.
 */
class PathJailObjectStoreTest {

    private static final List<String> ALLOW = List.of("s3://lake/space-a");

    private static void refused(List<String> allow, String value) {
        PathJail.Escape ex = assertThrows(PathJail.Escape.class,
                () -> PathJail.requireObjectStore(allow, value, "dirs.database"), value);
        assertEquals("dirs.database", ex.field());
    }

    @Test
    void aLocationUnderTheAllowedPrefixIsAcceptedAndNormalised() {
        assertEquals("s3://lake/space-a", PathJail.requireObjectStore(ALLOW, "s3://lake/space-a/", "f"));
        assertEquals("s3://lake/space-a/orders/year=2026",
                PathJail.requireObjectStore(ALLOW, " s3://lake/space-a/orders/year=2026 ", "f"));
    }

    @Test
    void anEmptyAllowListRefusesEverything() {
        PathJail.requireObjectStore(ALLOW, "s3://lake/space-a/orders", "f");   // the probe that succeeds
        refused(List.of(), "s3://lake/space-a/orders");
        refused(null, "s3://lake/space-a/orders");
        PathJail.Escape ex = assertThrows(PathJail.Escape.class,
                () -> PathJail.requireObjectStore(List.of(), "s3://lake/x", "f"));
        assertTrue(ex.getMessage().contains(PathJail.OBJECT_ROOTS_PROPERTY), ex.getMessage());
    }

    @Test
    void anotherBucketOrPrefixIsRefused() {
        PathJail.requireObjectStore(ALLOW, "s3://lake/space-a/orders", "f");
        refused(ALLOW, "s3://other/space-a/orders");
        refused(ALLOW, "s3://lake/space-b/orders");
        refused(ALLOW, "s3://lake");
        // Segment-wise, not string-prefix: space-aX shares the characters but not the segment.
        refused(ALLOW, "s3://lake/space-aX/orders");
    }

    @Test
    void traversalAndEncodedTraversalAreRefusedNotCollapsed() {
        // Each of these would land under the prefix if '..' were collapsed or '%2e' decoded.
        refused(ALLOW, "s3://lake/space-a/../space-b");
        refused(ALLOW, "s3://lake/space-a/./orders");
        refused(ALLOW, "s3://lake/space-a/%2e%2e/space-b");
        refused(ALLOW, "s3://lake/space-a//orders");
        refused(ALLOW, "s3://lake/space-a\\..\\space-b");
    }

    @Test
    void anythingUnrecognisedIsRefused() {
        for (String v : new String[] {
                "S3://lake/space-a/x",                // scheme case
                "s3a://lake/space-a/x",               // another scheme
                "gs://lake/space-a/x",
                "file:///lake/space-a/x",
                "s3://user@lake/space-a/x",           // userinfo
                "s3://lake:9000/space-a/x",           // port
                "s3://LAKE/space-a/x",                // not a bucket name
                "s3://lake/space-a/x?versionId=1",    // query
                "s3://lake/space-a/x#frag",           // fragment
                "s3://lake/space-a/*",                // glob byte
                "s3://lake/space-a/x y",              // whitespace
                "s3://lake/space-a/x'y",              // quote
                "/lake/space-a/x", "", "   "}) {
            refused(ALLOW, v);
        }
    }

    @Test
    void aMalformedAllowListEntryMatchesNothing() {
        // A traversal or wildcard in the allow-list itself must not become a wide-open root.
        refused(List.of("s3://lake/space-a/.."), "s3://lake/space-b");
        refused(List.of("s3://lake/*"), "s3://lake/space-a");
        refused(List.of("s3://"), "s3://lake/space-a");
    }

    @Test
    void theAllowListIsReadFromTheOperatorPropertyAndBlankAllowsNothing() {
        String prior = System.getProperty(PathJail.OBJECT_ROOTS_PROPERTY);
        try {
            System.clearProperty(PathJail.OBJECT_ROOTS_PROPERTY);
            assertEquals(List.of(), PathJail.allowedObjectRoots());
            System.setProperty(PathJail.OBJECT_ROOTS_PROPERTY, "s3://lake/space-a/ ; s3://lake/../x ; s3://other");
            assertEquals(List.of("s3://lake/space-a", "s3://other"), PathJail.allowedObjectRoots(),
                    "the malformed entry is dropped, the others normalised");
        } finally {
            if (prior == null) System.clearProperty(PathJail.OBJECT_ROOTS_PROPERTY);
            else System.setProperty(PathJail.OBJECT_ROOTS_PROPERTY, prior);
        }
    }
}
