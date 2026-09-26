package com.gamma.service;

import com.gamma.util.AtomicFiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The undo log of ONE import (`SEC-IMPORT-ROLES-ESCALATION-1`, all-or-nothing): every file an import writes goes
 * through {@link #write}, which first records the target's prior bytes (or that it was absent) and every
 * directory the write creates. {@link #rollback} puts each file back as it was, deletes each file that did not
 * exist, and removes each directory it created once empty — so a refused import (a 409 on a pipeline id, a 422
 * on a reference, a registration failure) leaves the config tree byte-for-byte as it found it.
 *
 * <p>Not thread-safe; one journal per request.
 */
public final class ImportJournal {

    private final Map<Path, byte[]> prior = new LinkedHashMap<>();   // null value ⇒ the file did not exist
    private final List<Path> createdDirs = new ArrayList<>();

    /** Write {@code bytes} to {@code target} atomically, recording what was there first. */
    public void write(Path target, byte[] bytes, String tempPrefix) throws IOException {
        Path t = target.toAbsolutePath().normalize();
        if (!prior.containsKey(t)) prior.put(t, Files.exists(t) ? Files.readAllBytes(t) : null);
        List<Path> missing = new ArrayList<>();
        for (Path d = t.getParent(); d != null && !Files.exists(d); d = d.getParent()) missing.add(d);
        createdDirs.addAll(missing);   // deepest first
        AtomicFiles.write(t, bytes, tempPrefix);
    }

    /** Whether nothing has been written through this journal yet. */
    public boolean isEmpty() {
        return prior.isEmpty();
    }

    /**
     * Undo every write, newest first. Best effort per file — one failure does not stop the others — and the first
     * failure is rethrown at the end so the caller can say the rollback was incomplete.
     */
    public void rollback() throws IOException {
        IOException first = null;
        List<Map.Entry<Path, byte[]>> entries = new ArrayList<>(prior.entrySet());
        for (int i = entries.size() - 1; i >= 0; i--) {
            Map.Entry<Path, byte[]> e = entries.get(i);
            try {
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
        createdDirs.clear();
        if (first != null) throw first;
    }
}
