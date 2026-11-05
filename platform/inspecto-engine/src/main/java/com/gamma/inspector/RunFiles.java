package com.gamma.inspector;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A per-run limit on which inbox files a run takes (BASIC-ONBOARD-SETTLE-1). A caller that has checked a set of files
 * itself - the 30 s inbox poll checks that every waiting file has settled - opens a scope over the inbox folder for the
 * duration of its run: the run then takes only those files from that folder and leaves anything else (a file created
 * after the check) for a later run. Keyed by the folder's real path, not by pipeline name, so two Spaces with a
 * same-named pipeline never narrow each other's run. Applied on the run path only; a pending count still sees the
 * whole inbox. With no scope open the run is unchanged.
 */
public final class RunFiles {

    private static final Map<Path, Set<Path>> ONLY = new ConcurrentHashMap<>();

    private RunFiles() {}

    /** While open, a run takes from {@code folder} (and its sub-folders) only {@code files}. */
    public static Scope only(Path folder, Set<Path> files) {
        Path root = real(folder);
        Set<Path> take = ConcurrentHashMap.newKeySet();
        for (Path f : files) take.add(real(f));
        ONLY.put(root, take);
        return () -> ONLY.remove(root, take);
    }

    /** {@code candidates} without the files an open scope leaves out. */
    static List<File> narrow(List<File> candidates) {
        if (ONLY.isEmpty() || candidates.isEmpty()) return candidates;
        List<File> out = new ArrayList<>(candidates.size());
        for (File c : candidates) {
            Path p = real(c.toPath());
            boolean taken = true;
            for (Map.Entry<Path, Set<Path>> e : ONLY.entrySet())
                if (p.startsWith(e.getKey()) && !e.getValue().contains(p)) taken = false;
            if (taken) out.add(c);
        }
        return out;
    }

    private static Path real(Path p) {
        try {
            return p.toRealPath();
        } catch (IOException gone) {
            return p.toAbsolutePath().normalize();
        }
    }

    /** Closing it ends the limit. */
    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}
