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
 * whole inbox. With no scope open the run is unchanged. A {@link #complete} scope is the other half: files the caller
 * knows are whole pass the stability gate at once (ADD-FILE-FEED-1).
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

    private static final Set<Path> COMPLETE = ConcurrentHashMap.newKeySet();

    /**
     * While open, the stability gate releases {@code files} at once (ADD-FILE-FEED-1): the caller knows they are whole -
     * a person's Add a file lands its upload with an atomic move - so a run does not wait out the window for them. Every
     * other file is gated as before; the duplicate check still applies to these.
     */
    public static Scope complete(Set<Path> files) {
        Set<Path> mine = new java.util.HashSet<>();
        for (Path f : files) mine.add(real(f));
        COMPLETE.addAll(mine);
        return () -> COMPLETE.removeAll(mine);
    }

    /** {@code ready} plus each file of {@code discovered} an open {@link #complete} scope names, in discovery order. */
    static <T> List<T> release(List<T> discovered, List<T> ready, java.util.function.Function<T, Path> path) {
        if (COMPLETE.isEmpty() || discovered.size() == ready.size()) return ready;
        Set<T> in = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        in.addAll(ready);
        List<T> out = new ArrayList<>(discovered.size());
        for (T f : discovered) if (in.contains(f) || COMPLETE.contains(real(path.apply(f)))) out.add(f);
        return out;
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
