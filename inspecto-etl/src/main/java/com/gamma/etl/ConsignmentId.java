package com.gamma.etl;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A Consignment's identity — derived from its CONTENT SHAPE, never from the clock
 * ({@code CONSIGNMENT-ID-DETERMINISTIC-1}).
 *
 * <h3>🔴 The defect this replaces</h3>
 * The id used to be {@code <runTimestamp>_<slug>_<seq>} where the timestamp was {@code LocalDateTime.now()}
 * at <b>second</b> granularity. {@code slug} and {@code seq} are deterministic from the input files, so
 * <b>only the clock differed</b> between two executors of the same work. They therefore either shared an id
 * (same second — the manifest clobbers) or did not (one second apart — two manifests, two registry rows,
 * and for a multi-file batch two differently-named output files that are <em>both</em> visible, i.e.
 * duplicate rows on read). ⚠ It flipped between clobber and duplication on clock alignment, and the clock
 * is host-zone. This bites <b>Standard</b>, not only Enterprise: a T4 standby taking over from a paused
 * owner double-executes.
 *
 * <h3>What goes into the digest, and why exactly this (operator decision, 2026-09-12)</h3>
 * Sorted <b>relative path + byte size</b> of every member. Both are already in hand at the mint site
 * ({@code Member.file()}, {@code Member.bytes()}), so this costs <b>zero new I/O</b>.
 * <ul>
 *   <li>⛔ <b>Not a content checksum.</b> Exact, but it reads every member file in full at plan time, on
 *       every run, before any work begins.</li>
 *   <li>⛔ <b>Not mtime.</b> Free to read, but not preserved across copies, restores or container mounts —
 *       so one logical batch would get different ids in different environments, reopening duplication from
 *       the other direction.</li>
 * </ul>
 *
 * <h3>⚠ The accepted residual — deliberate, not an oversight</h3>
 * A member edited <b>in place to the same byte length</b> yields the same id, so a genuine re-run over
 * changed content is treated as the same Consignment. The operator took this knowingly against the I/O
 * cost of hashing. ⛔ Do not "harden" it later by quietly adding a checksum: that reverses a decision.
 * Re-open {@code CONSIGNMENT-ID-DETERMINISTIC-1} instead.
 *
 * <h3>🔴 Why RELATIVE, and what happens outside the root</h3>
 * An <b>absolute</b> path would make the id depend on the mount point, so two pods mounting the same data
 * at different paths would compute different ids for identical work — defeating the entire purpose. Paths
 * are therefore relativized against the poll root, with {@code /} separators so a Windows and a Linux pod
 * agree. ⚠ A member that is <b>not under</b> the poll root (an {@code UnpackStage} expansion can land
 * elsewhere) falls back to its <b>basename</b>: relativizing would emit {@code ../..} segments that are
 * themselves mount-dependent, which is the very thing being avoided.
 *
 * <h3>⛔ Ordering — identity FIRST, constraints SECOND</h3>
 * Unique constraints and a CAS on the stage store are the other half of the row, and they must come
 * <b>after</b> this. Applied first, while ids still collide across different batches, a conflict would
 * silently DROP the second batch's rows — turning a visible duplication defect into silent data loss.
 */
final class ConsignmentId {

    private ConsignmentId() {}

    /** Hex characters of the digest kept in the id. 12 hex = 48 bits — ample for per-pipeline batch ids. */
    private static final int DIGEST_CHARS = 12;

    /**
     * The id for one batch: {@code <slug>_<digest>_<seq>}.
     *
     * <p>⚠ Same shape as the id it replaces ({@code X_Y_NNNN}), with the clock component swapped for the
     * content digest — so nothing that merely eyeballs the format is surprised. ⛔ Nothing PARSES a
     * Consignment id apart (verified 2026-09-12: no split/substring/regex over it anywhere), so the
     * format is free to change; the one durable name derived from it is the manifest filename.
     */
    static String of(String slug, int seq, Path pollRoot, List<Consignment.Member> members) {
        return String.format("%s_%s_%04d", slug, digest(pollRoot, members), seq);
    }

    /**
     * SHA-256 over each member's {@code <relative path>\0<bytes>\0}, members sorted by that relative path.
     *
     * <p>⚠ Sorted deliberately: membership is already deterministic, but sorting means the digest cannot
     * shift if packing order ever changes for an unrelated reason. ⚠ The {@code \0} separators keep
     * {@code ("ab", 1)} from colliding with {@code ("a", 11)}.
     */
    private static String digest(Path pollRoot, List<Consignment.Member> members) {
        List<String> parts = new ArrayList<>(members.size());
        for (Consignment.Member m : members) parts.add(relative(pollRoot, m.file()) + '\0' + m.bytes());
        Collections.sort(parts);
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every JRE", e);   // unreachable
        }
        for (String part : parts) {
            md.update(part.getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);
        }
        StringBuilder hex = new StringBuilder(DIGEST_CHARS);
        byte[] out = md.digest();
        for (int i = 0; hex.length() < DIGEST_CHARS && i < out.length; i++)
            hex.append(String.format("%02x", out[i]));
        return hex.substring(0, DIGEST_CHARS);
    }

    /**
     * {@code file} relative to {@code pollRoot} with {@code /} separators; the basename when it is not
     * under the root (or no root is known). See the class note on why absolute paths are refused.
     */
    static String relative(Path pollRoot, File file) {
        Path abs = file.toPath().toAbsolutePath().normalize();
        if (pollRoot != null) {
            Path root = pollRoot.toAbsolutePath().normalize();
            if (abs.startsWith(root)) return root.relativize(abs).toString().replace('\\', '/');
        }
        return abs.getFileName().toString();
    }
}
