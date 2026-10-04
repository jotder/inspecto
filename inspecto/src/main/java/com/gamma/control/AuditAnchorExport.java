package com.gamma.control;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * The scheduled off-box export of the signed audit anchors (ASSURE-AUDIT-CHAIN-RESIDUALS-1 (1)): append the
 * Space's {@code audit-anchors.jsonl} lines to a destination file the operator owns, byte for byte — the exporter
 * never re-signs or reformats, so the destination file verifies with the same key as the original
 * ({@link AuditAnchors.AnchorFile#firstProblem}). Anchor lines hold hashes and seq numbers only, never entity data.
 *
 * <p>Fail closed: every refusal is an exception (a FAILED run), never a quiet success.
 * <ul>
 *   <li>the SOURCE must verify (readable, valid MACs, linked, contiguous) — a broken chain is not exported over
 *       a good copy;</li>
 *   <li>the DESTINATION must be a regular file (or absent) whose lines are exactly the leading lines of the
 *       source — an edited, truncated-in-the-middle or foreign destination is refused, never repaired or
 *       overwritten. The one exception is a rebaseline: when the source now opens with a BREAK anchor and holds
 *       none of the destination's lines, the new epoch is appended after the old one;</li>
 *   <li>append-only and idempotent: only the source lines the destination lacks are appended, then forced.
 *       A re-run with nothing new writes nothing.</li>
 * </ul>
 */
final class AuditAnchorExport {

    private AuditAnchorExport() {}

    /** The file name written under the operator's {@code out_dir}. */
    static final String DEST_FILE = AuditAnchors.FILE;

    /** @return how many anchor lines were appended (0 when the destination was already current) */
    static int export(Path configRoot, Path destFile) throws IOException {
        List<String> source;
        synchronized (AuditAnchors.lock(configRoot)) {
            AuditAnchors.AnchorFile file = AuditAnchors.readFile(configRoot);
            if (!file.exists() || file.anchors().isEmpty())
                throw new IOException("there are no audit anchors to export yet");
            AuditVerifier.Bad bad = file.firstProblem();
            if (bad != null)
                throw new IOException("the audit anchor file does not verify (" + bad.reason() + "): " + bad.detail()
                        + " — nothing exported");
            source = nonBlank(Files.readAllLines(AuditAnchors.file(configRoot), StandardCharsets.UTF_8));
        }
        if (Files.exists(destFile) && !Files.isRegularFile(destFile))
            throw new IOException("the export destination " + destFile.getFileName() + " is not a regular file");
        List<String> have = new ArrayList<>();
        if (Files.exists(destFile)) {
            byte[] bytes = Files.readAllBytes(destFile);
            if (bytes.length > 0 && bytes[bytes.length - 1] != '\n')
                throw new IOException("the export destination does not end in a newline (a torn write?) — refusing to append");
            have = nonBlank(Files.readAllLines(destFile, StandardCharsets.UTF_8));
        }
        int from;
        if (have.size() <= source.size() && source.subList(0, have.size()).equals(have)) {
            from = have.size();
        } else if (isBreak(source.get(0)) && source.stream().noneMatch(have::contains)) {
            from = 0;   // rebaselined: a new epoch follows the old one
        } else {
            throw new IOException("the export destination does not match the audit anchors (edited, truncated or "
                    + "foreign) — refusing to append over it");
        }
        List<String> add = source.subList(from, source.size());
        if (add.isEmpty()) return 0;
        Files.createDirectories(destFile.toAbsolutePath().getParent());
        byte[] out = (String.join("\n", add) + "\n").getBytes(StandardCharsets.UTF_8);
        try (FileChannel ch = FileChannel.open(destFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.APPEND)) {
            ByteBuffer buf = ByteBuffer.wrap(out);
            while (buf.hasRemaining()) ch.write(buf);
            ch.force(true);
        }
        return add.size();
    }

    private static boolean isBreak(String line) {
        return line.contains("\"kind\":\"" + AuditAnchors.BREAK + "\"");
    }

    private static List<String> nonBlank(List<String> lines) {
        return lines.stream().filter(l -> !l.isBlank()).toList();
    }
}
