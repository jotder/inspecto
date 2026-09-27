package com.gamma.service;

import com.gamma.util.AtomicFiles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The undo log of ONE import (`SEC-IMPORT-ROLES-ESCALATION-1`, all-or-nothing): every file an import writes goes
 * through {@link #write}, which first records the target's prior bytes (or that it was absent) and every
 * directory the write creates. {@link #rollback} puts each file back as it was, deletes each file that did not
 * exist, and removes each directory it created once empty — so a refused import (a 409 on a pipeline id, a 422
 * on a reference, a registration failure) leaves the config tree byte-for-byte as it found it.
 *
 * <p><b>Never over a newer write</b> (`IMPORT-RESIDUALS-1` (3)): there is no config write lock, so another
 * request may save the same file between this import's write and its rollback. The journal also keeps a digest
 * of what THIS import last wrote to each file, and {@link #rollback} restores a file only while its current
 * content still equals that; a file that changed (or vanished) since is left as the other writer made it and
 * returned, so the caller can report it "changed concurrently, not rolled back". ⚠ The compare and the restore
 * are two steps, not one atomic swap: a write landing in that sub-millisecond gap is still overwritten.
 *
 * <p>Not thread-safe; one journal per request.
 */
public final class ImportJournal {

    private static final Logger log = LoggerFactory.getLogger(ImportJournal.class);

    /**
     * Test seam only: runs at the start of every {@link #rollback} with the files the journal wrote, so a test can
     * interleave a "concurrent" write deterministically. {@code null} (always, in production) ⇒ nothing runs.
     */
    public static volatile Consumer<List<Path>> beforeRollback;

    private final Map<Path, byte[]> prior = new LinkedHashMap<>();   // null value ⇒ the file did not exist
    private final Map<Path, byte[]> wrote = new LinkedHashMap<>();   // SHA-256 of what this import last wrote
    private final List<Path> createdDirs = new ArrayList<>();

    /** Write {@code bytes} to {@code target} atomically, recording what was there first. */
    public void write(Path target, byte[] bytes, String tempPrefix) throws IOException {
        Path t = target.toAbsolutePath().normalize();
        if (!prior.containsKey(t)) prior.put(t, Files.exists(t) ? Files.readAllBytes(t) : null);
        List<Path> missing = new ArrayList<>();
        for (Path d = t.getParent(); d != null && !Files.exists(d); d = d.getParent()) missing.add(d);
        createdDirs.addAll(missing);   // deepest first
        AtomicFiles.write(t, bytes, tempPrefix);
        wrote.put(t, sha256(bytes));
    }

    /** Whether nothing has been written through this journal yet. */
    public boolean isEmpty() {
        return prior.isEmpty();
    }

    /**
     * Undo every write, newest first, except a file whose content is no longer what this import wrote — that one
     * is someone else's newer write and is left alone. Best effort per file — one failure does not stop the
     * others — and the first failure is rethrown at the end so the caller can say the rollback was incomplete.
     *
     * @return the files left alone because they changed concurrently (absolute paths; empty when all rolled back)
     */
    public List<Path> rollback() throws IOException {
        Consumer<List<Path>> hook = beforeRollback;
        if (hook != null) hook.accept(List.copyOf(prior.keySet()));
        IOException first = null;
        List<Path> changedConcurrently = new ArrayList<>();
        List<Map.Entry<Path, byte[]>> entries = new ArrayList<>(prior.entrySet());
        for (int i = entries.size() - 1; i >= 0; i--) {
            Map.Entry<Path, byte[]> e = entries.get(i);
            try {
                byte[] ours = wrote.get(e.getKey());
                if (ours == null) continue;   // the write itself failed: nothing of ours is on disk
                if (!Files.exists(e.getKey()) || !Arrays.equals(ours, sha256(Files.readAllBytes(e.getKey())))) {
                    changedConcurrently.add(e.getKey());
                    log.warn("[IMPORT] {} changed after this import wrote it; left as is, not rolled back", e.getKey());
                    continue;
                }
                if (e.getValue() == null) Files.deleteIfExists(e.getKey());
                else AtomicFiles.write(e.getKey(), e.getValue(), ".rollback-");
            } catch (IOException ex) {
                if (first == null) first = ex;
            }
        }
        // deepest first: createdDirs holds each write's chain child-before-parent, and later writes' chains are
        // disjoint from earlier ones (a directory is only recorded when it did not exist yet)
        List<Path> dirs = new ArrayList<>(createdDirs);
        dirs.sort((a, b) -> Integer.compare(b.getNameCount(), a.getNameCount()));
        for (Path d : dirs) {
            try {
                Files.deleteIfExists(d);   // only when empty — never a recursive delete
            } catch (IOException notEmpty) {
                // someone else's file appeared — leave the directory rather than risk their data
            }
        }
        prior.clear();
        wrote.clear();
        createdDirs.clear();
        if (first != null) throw first;
        return changedConcurrently;
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
