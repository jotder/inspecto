package com.gamma.etl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Consignment identity — {@code CONSIGNMENT-ID-DETERMINISTIC-1}.
 *
 * <p>🔴 The property under test is the one the old id lacked: <b>two executors of the same work agree.</b>
 * The id was {@code <now()>_<slug>_<seq>} at second granularity, so identical work either shared an id
 * (clobber) or did not (duplicate) purely on clock alignment.
 */
class ConsignmentIdTest {

    private static Consignment.Member member(Path dir, String name, long bytes) throws Exception {
        Path f = dir.resolve(name);
        Files.createDirectories(f.getParent());
        Files.write(f, new byte[(int) bytes]);
        return new Consignment.Member(f.toFile(), 0, bytes, null);
    }

    /** 🔴 THE FIX: the clock is gone, so the same files mint the same id however far apart they run. */
    @Test
    void theSameFilesMintTheSameIdRegardlessOfWhenTheyRun(@TempDir Path root) throws Exception {
        List<Consignment.Member> members = List.of(member(root, "a.csv", 10), member(root, "b.csv", 20));

        String first = ConsignmentId.of("orders", 1, root, members);
        String second = ConsignmentId.of("orders", 1, root, members);

        assertEquals(first, second, "identical work must mint one id — this is the whole row");
        assertFalse(first.matches(".*\\d{8}_\\d{6}.*"),
                "⛔ no timestamp may appear in the id: " + first);
    }

    /**
     * 🔴 MOUNT INDEPENDENCE. Two pods mounting the same data at different paths must agree — an absolute
     * path in the digest would make them disagree and defeat the entire purpose.
     */
    @Test
    void twoMountPointsOfTheSameDataAgree(@TempDir Path a, @TempDir Path b) throws Exception {
        List<Consignment.Member> onA = List.of(member(a, "x.csv", 7), member(a, "sub/y.csv", 9));
        List<Consignment.Member> onB = List.of(member(b, "x.csv", 7), member(b, "sub/y.csv", 9));

        assertEquals(ConsignmentId.of("t", 1, a, onA), ConsignmentId.of("t", 1, b, onB),
                "⛔ the same batch mounted at two paths is ONE Consignment — if these differ, a failover "
                        + "pod re-executes work the original already did");
    }

    /** ⛔ The discriminator: different content shape must NOT collide, or the id is useless. */
    @Test
    void aDifferentSizeIsADifferentConsignment(@TempDir Path root) throws Exception {
        String small = ConsignmentId.of("t", 1, root, List.of(member(root, "a.csv", 10)));
        String large = ConsignmentId.of("t", 1, root, List.of(member(root, "a.csv", 11)));
        assertNotEquals(small, large, "one byte of difference is a different batch");
    }

    /** ⛔ …and so must a different FILE, at the same size. */
    @Test
    void aDifferentFileNameIsADifferentConsignment(@TempDir Path root) throws Exception {
        String one = ConsignmentId.of("t", 1, root, List.of(member(root, "a.csv", 10)));
        String two = ConsignmentId.of("t", 1, root, List.of(member(root, "b.csv", 10)));
        assertNotEquals(one, two);
    }

    /** ⛔ …and the same files under a different table slug or sequence stay distinct. */
    @Test
    void slugAndSeqStillSeparateBatches(@TempDir Path root) throws Exception {
        List<Consignment.Member> m = List.of(member(root, "a.csv", 10));
        assertNotEquals(ConsignmentId.of("t1", 1, root, m), ConsignmentId.of("t2", 1, root, m));
        assertNotEquals(ConsignmentId.of("t1", 1, root, m), ConsignmentId.of("t1", 2, root, m));
    }

    /** Member order must not change the id — the digest sorts, so packing order cannot shift it. */
    @Test
    void memberOrderDoesNotChangeTheId(@TempDir Path root) throws Exception {
        Consignment.Member a = member(root, "a.csv", 10);
        Consignment.Member b = member(root, "b.csv", 20);
        assertEquals(ConsignmentId.of("t", 1, root, List.of(a, b)),
                ConsignmentId.of("t", 1, root, List.of(b, a)));
    }

    /**
     * ⚠ THE ACCEPTED RESIDUAL, pinned so it is a KNOWN behaviour rather than a surprise: a member edited
     * in place to the SAME byte length yields the same id.
     *
     * <p>The operator took this knowingly (2026-09-12) against the I/O cost of hashing file contents.
     * ⛔ If this test ever starts failing, someone has added a content checksum — that reverses a
     * decision; re-open {@code CONSIGNMENT-ID-DETERMINISTIC-1} rather than "fixing" the test.
     */
    @Test
    void anInPlaceSameSizeEditIsTheSameConsignment_ACCEPTED_RESIDUAL(@TempDir Path root) throws Exception {
        Path f = root.resolve("a.csv");
        Files.write(f, "AAAA".getBytes());
        List<Consignment.Member> before = List.of(new Consignment.Member(f.toFile(), 0, 4, null));
        String idBefore = ConsignmentId.of("t", 1, root, before);

        Files.write(f, "BBBB".getBytes());   // different content, identical length
        List<Consignment.Member> after = List.of(new Consignment.Member(f.toFile(), 0, 4, null));

        assertEquals(idBefore, ConsignmentId.of("t", 1, root, after),
                "documented residual of the paths+sizes digest — see the class note");
    }

    /**
     * ⚠ A member outside the poll root falls back to its basename ({@code UnpackStage} expansions can land
     * elsewhere). ⛔ Relativizing would emit {@code ../..} segments that are themselves mount-dependent —
     * the exact thing being avoided.
     */
    @Test
    void aMemberOutsideThePollRootUsesItsBasename(@TempDir Path root, @TempDir Path elsewhere)
            throws Exception {
        File outside = elsewhere.resolve("expanded.csv").toFile();
        Files.write(outside.toPath(), new byte[5]);

        assertEquals("expanded.csv", ConsignmentId.relative(root, outside));
        assertEquals("expanded.csv", ConsignmentId.relative(null, outside), "no root = basename too");
    }

    /** Relative paths use {@code /} on every OS, so a Windows pod and a Linux pod agree. */
    @Test
    void relativePathsAreSlashSeparatedOnEveryOs(@TempDir Path root) throws Exception {
        Path nested = root.resolve("sub").resolve("deep.csv");
        Files.createDirectories(nested.getParent());
        Files.write(nested, new byte[1]);

        assertEquals("sub/deep.csv", ConsignmentId.relative(root, nested.toFile()),
                "⛔ a backslash here would make two OSes disagree on one batch");
    }
}
