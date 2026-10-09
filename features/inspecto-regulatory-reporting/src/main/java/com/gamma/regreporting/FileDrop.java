package com.gamma.regreporting;

import com.gamma.config.safety.PathJail;
import com.gamma.job.ApprovalFingerprint;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * The <b>file-drop</b> delivery of a submitted {@link RegulatoryReports regulatory report}: the one delivery kind built
 * (no live regulator API). The directory is jailed exactly as the {@code report} Job's {@code out_dir} is
 * ({@link PathJail#requireJobPathUnderAny} over {@link PathJail#allowedRoots()}; a relative directory resolves against
 * the Space config root). The file is written once — temp file in the same directory, then an atomic move — and
 * never overwritten: an existing file with the same SHA-256 is the same submission (a retry after a crash), any other
 * existing file refuses.
 */
final class FileDrop {

    private FileDrop() {}

    /** The jailed absolute drop directory, or {@link IllegalArgumentException} naming why it is refused. */
    static Path resolve(Path configRoot, String dir) {
        try {
            return PathJail.requireJobPathUnderAny(PathJail.allowedRoots(), configRoot, dir, "delivery.dir");
        } catch (RuntimeException refused) {
            throw new IllegalArgumentException(refused.getMessage(), refused);
        }
    }

    /** What was delivered: the absolute file and whether it was already there (an idempotent retry). */
    record Delivered(Path file, boolean alreadyPresent) {
    }

    /**
     * Write {@code content} as {@code fileName} under the (already resolved) {@code dropDir}, re-jailing it first —
     * the allowed roots may have narrowed since the draft pinned it. Throws {@link IOException} on any refusal.
     */
    static Delivered deliver(Path configRoot, String pinnedDir, String fileName, String content, String sha256)
            throws IOException {
        Path dir;
        try {
            dir = resolve(configRoot, pinnedDir);
        } catch (IllegalArgumentException refused) {
            throw new IOException("the drop directory is refused: " + refused.getMessage());
        }
        Files.createDirectories(dir);
        Path target = dir.resolve(fileName).normalize();
        if (!target.getParent().equals(dir.normalize())) throw new IOException("the file name escapes the drop directory");
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (Files.exists(target)) {
            if (ApprovalFingerprint.sha256(Files.readAllBytes(target)).equals(sha256)) return new Delivered(target, true);
            throw new IOException("a different file already sits at " + target.getFileName() + " - it is never overwritten");
        }
        Path tmp = Files.createTempFile(dir, ".rr-", ".part");
        try {
            Files.write(tmp, bytes);
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(tmp);
        }
        return new Delivered(target, false);
    }
}
