package com.gamma.geolink;

import com.gamma.control.ApiContext;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The per-Space, append-only <b>identity fact log</b> (LA-17, {@code docs/superpower/link-analysis-entity-model-design.md}
 * §4.2): {@code <Space config root>/audit/entity-facts/}, beside {@link SnapshotStore}'s {@code audit/snapshots/}.
 *
 * <p><b>Format.</b> One immutable JSON file per fact, named by its 12-digit zero-padded sequence number
 * ({@code 000000000001.json}, …). Fields, in this order: {@code seq, at, actor, reason, kind, listId}, the kind's
 * payload, then {@code prevHash} — the SHA-256 hex of the PREVIOUS fact file's exact bytes ({@code ""} for seq 1).
 * The log's head hash is the SHA-256 of the last file, so a Dossier citing {@code {headHash, seq}} pins the whole
 * prefix. Any other file in the directory (a staging {@code .tmp}, the Entity List {@code mask.key}) is not a fact.
 *
 * <p><b>Write.</b> Bytes are staged in a sibling temp file, forced to disk, then published onto the seq name by
 * {@link #publishNew} — a hard link, which the OS creates atomically or refuses with
 * {@code FileAlreadyExistsException} — so a crash leaves either no fact or a whole one, and an existing seq is
 * never overwritten, even by a writer in ANOTHER process. ⚠ {@code com.gamma.util.AtomicFiles.write} was not
 * reused: it always passes {@code REPLACE_EXISTING}, which is exactly the overwrite a sealed fact must refuse.
 * Writers in one JVM serialise on {@link #lock()} — the {@code InvestigationRoutes.lock(...)} idiom — and read the
 * head inside it, so the seq they append is the one after the head they verified.
 *
 * <p><b>Read.</b> {@link #read()} verifies the WHOLE chain every time: seqs contiguous from 1, each file's
 * {@code seq} equal to its name, each {@code prevHash} equal to the hash of the file before. Any mismatch throws
 * {@link BrokenChainException} — never a silent fold of what happens to be readable. ⚠ Truncation of the TAIL is
 * not detectable from the log alone (the prefix is still a valid chain); a cited head hash is what catches it.
 */
final class EntityFactLog {

    static final String DIR = "entity-facts";
    private static final Pattern FACT_FILE = Pattern.compile("(\\d{12})\\.json");
    private static final ConcurrentHashMap<Path, Object> LOCKS = new ConcurrentHashMap<>();

    /** One verified fact: its sequence number, parsed body and the SHA-256 of its file bytes. */
    record Fact(long seq, Map<String, Object> body, String hash) {
        String kind() { return String.valueOf(body.get("kind")); }
        String listId() { return String.valueOf(body.get("listId")); }
    }

    /** The verified log: every fact in seq order, the head seq ({@code 0} when empty) and head hash ({@code ""}). */
    record Log(List<Fact> facts, long headSeq, String headHash) {}

    /** The chain does not verify. The route answers 500 {@code INTEGRITY_VIOLATION}; nothing is folded. */
    static final class BrokenChainException extends IllegalStateException {
        BrokenChainException(String message) {
            super(message);
        }
    }

    private final Path dir;

    EntityFactLog(Path writeRoot) {
        this.dir = writeRoot.resolve("audit").resolve(DIR);
    }

    Path directory() {
        return dir;
    }

    /** The JVM-wide monitor writers to this log hold across read-head → append. */
    Object lock() {
        return LOCKS.computeIfAbsent(dir.toAbsolutePath().normalize(), k -> new Object());
    }

    /** Every fact, chain-verified. An absent directory is an empty log. */
    Log read() throws IOException {
        if (!Files.isDirectory(dir)) return new Log(List.of(), 0, "");
        List<Long> seqs = new ArrayList<>();
        try (var s = Files.list(dir)) {
            s.forEach(p -> {
                Matcher m = FACT_FILE.matcher(p.getFileName().toString());
                if (m.matches()) seqs.add(Long.parseLong(m.group(1)));
            });
        }
        seqs.sort(null);
        List<Fact> facts = new ArrayList<>(seqs.size());
        String prev = "";
        for (int i = 0; i < seqs.size(); i++) {
            long seq = seqs.get(i);
            if (seq != i + 1)
                throw new BrokenChainException("identity fact log is broken: expected seq " + (i + 1)
                        + ", found " + seq + " (a fact is missing)");
            byte[] bytes = Files.readAllBytes(file(seq));
            Map<String, Object> body;
            try {
                @SuppressWarnings("unchecked") Map<String, Object> m = ApiContext.JSON.readValue(bytes, LinkedHashMap.class);
                body = m;
            } catch (IOException unreadable) {
                throw new BrokenChainException("identity fact log is broken: fact " + seq + " is not JSON");
            }
            if (!(body.get("seq") instanceof Number n) || n.longValue() != seq)
                throw new BrokenChainException("identity fact log is broken: fact " + seq
                        + " records seq " + body.get("seq"));
            if (!prev.equals(body.get("prevHash")))
                throw new BrokenChainException("identity fact log is broken at seq " + seq
                        + ": prevHash does not match the previous fact's bytes");
            String hash = sha256(bytes);
            facts.add(new Fact(seq, body, hash));
            prev = hash;
        }
        return new Log(List.copyOf(facts), facts.size(), prev);
    }

    /**
     * Append one fact after {@code head} (which the caller read under {@link #lock()}) and answer the new log.
     * {@code payload} goes between {@code listId} and {@code prevHash}.
     */
    Log append(Log head, String actor, String reason, String kind, String listId, Map<String, Object> payload)
            throws IOException {
        long seq = head.headSeq() + 1;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("seq", seq);
        body.put("at", Instant.now().toString());
        body.put("actor", actor);
        body.put("reason", reason);
        body.put("kind", kind);
        body.put("listId", listId);
        body.putAll(payload);
        body.put("prevHash", head.headHash());
        byte[] bytes = ApiContext.JSON.writeValueAsBytes(body);

        Files.createDirectories(dir);
        Path tmp = Files.createTempFile(dir, ".fact-", ".tmp");
        try {
            try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ch.write(java.nio.ByteBuffer.wrap(bytes));
                ch.force(true);
            }
            publishNew(tmp, file(seq));
        } finally {
            Files.deleteIfExists(tmp);
        }
        @SuppressWarnings("unchecked") Map<String, Object> parsed = ApiContext.JSON.readValue(bytes, LinkedHashMap.class);
        List<Fact> facts = new ArrayList<>(head.facts());
        String hash = sha256(bytes);
        facts.add(new Fact(seq, parsed, hash));
        return new Log(List.copyOf(facts), seq, hash);
    }

    /**
     * Publish the fully written {@code tmp} as {@code target}, which must not exist yet: throws
     * {@code FileAlreadyExistsException} (and leaves {@code target} untouched) when it does. The caller deletes
     * {@code tmp} afterwards.
     *
     * <p>⚠ Not {@code Files.move} without {@code REPLACE_EXISTING}: on Linux that is check-then-{@code rename(2)},
     * and {@code rename} silently replaces, so two processes can both pass the check and the second overwrites the
     * first. {@code link(2)} is atomic and never replaces. The move is kept ONLY as the fallback for a file store
     * without hard links ({@code UnsupportedOperationException}), where the cross-process guarantee is then the
     * weaker check-then-rename — in-JVM writers are still serialised by their callers' locks.
     *
     * <p>The directory is then fsynced, best effort, so the new name survives a crash; platforms that cannot open a
     * directory as a channel (Windows) throw {@code IOException} there, which is ignored.
     */
    static void publishNew(Path tmp, Path target) throws IOException {
        try {
            Files.createLink(target, tmp);
        } catch (UnsupportedOperationException noHardLinks) {
            Files.move(tmp, target);   // no REPLACE_EXISTING: see above
        }
        try (FileChannel d = FileChannel.open(target.getParent(), StandardOpenOption.READ)) {
            d.force(true);
        } catch (IOException notSupported) {
            // best effort — e.g. Windows cannot open a directory as a channel
        }
    }

    private Path file(long seq) {
        return dir.resolve(String.format("%012d.json", seq));
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
