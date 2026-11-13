package com.gamma.service;

import com.gamma.util.AtomicFiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * LC-6 of `SPACE-LINK-CENSUS-1` — accept hand edits ({@code POST /spaces/{id}/integrity/accept},
 * {@code spec-space-lock.md} §12). Under the Space's computation lock it first computes the lock afresh, so the
 * hashes accepted are the files' current ones, then drops the {@code outside} mark of each accepted file and the
 * {@code removedOutside} entry of each accepted removal, re-signs the lock and replaces the in-memory report. A
 * later computation compares against the accepted hashes, so an accepted file leaves {@code changedOutside} until
 * it changes again.
 */
public final class SpaceLockAccept {

    private SpaceLockAccept() {}

    /** What an accept did: the findings it accepted, the asked paths that were not changed outside, and what is left. */
    public record Result(List<Map<String, Object>> accepted, List<String> notChangedOutside, List<String> unknown,
                         int changedOutsideLeft) {}

    /**
     * Accept {@code paths} (config-relative, {@code /} separated), or every file currently changed outside when
     * {@code paths} is null. A path the lock does not know at all lands in {@link Result#unknown()} and nothing is
     * written — the caller answers 404. Throws {@link IOException} when no verified lock can be produced.
     */
    public static Result accept(String spaceId, Path configRoot, Collection<String> paths) throws IOException {
        SpaceLock.State s = SpaceLock.state(spaceId, configRoot);
        s.computing.lock();
        try {
            if (s.closed) throw new IOException("the Space is closing");
            SpaceLock.computeNow(s);
            if (s.last == null || !"ready".equals(s.last.status()))
                throw new IOException("the Space lock could not be computed: "
                        + (s.last == null ? "no report" : s.last.body().get("error")));
            Path root = s.configRoot;
            Path lockFile = SpaceLock.file(root);
            String[] note = {null};
            Map<String, Object> lock = SpaceLock.readVerified(root, lockFile, note);
            if (lock == null) throw new IOException("the Space lock is not readable: " + note[0]);

            List<Map<String, Object>> files = SpaceLock.maps(lock.get("files"));
            List<Map<String, Object>> removed = SpaceLock.maps(lock.get("removedOutside"));
            Set<String> asked = paths == null ? null : new LinkedHashSet<>(paths);
            Set<String> known = new HashSet<>();
            for (Map<String, Object> f : files) known.add(String.valueOf(f.get("path")));
            for (Map<String, Object> r : removed) known.add(String.valueOf(r.get("path")));
            List<String> unknown = new ArrayList<>();
            if (asked != null) for (String p : asked) if (!known.contains(p)) unknown.add(p);
            if (!unknown.isEmpty()) return new Result(List.of(), List.of(), unknown, -1);

            List<Map<String, Object>> accepted = new ArrayList<>();
            for (Map<String, Object> f : files) {
                if (!(f.get("outside") instanceof Map<?, ?> o) || (asked != null && !asked.contains(String.valueOf(f.get("path")))))
                    continue;
                f.remove("outside");
                accepted.add(finding(f.get("path"), o.get("change"), f.get("hash")));
            }
            List<Map<String, Object>> keep = new ArrayList<>();
            for (Map<String, Object> r : removed) {
                if (asked == null || asked.contains(String.valueOf(r.get("path")))) accepted.add(finding(r.get("path"), "removed", null));
                else keep.add(r);
            }
            List<String> notOutside = new ArrayList<>();
            if (asked != null) {
                Set<String> done = new HashSet<>();
                for (Map<String, Object> a : accepted) done.add(String.valueOf(a.get("path")));
                for (String p : asked) if (!done.contains(p)) notOutside.add(p);
            }
            int left = 0;
            for (Map<String, Object> f : files) if (f.get("outside") != null) left++;
            left += keep.size();
            if (accepted.isEmpty()) return new Result(accepted, notOutside, List.of(), left);

            lock.put("removedOutside", keep);
            lock.remove("mac");
            lock.put("mac", SpaceLock.mac(root, lock));
            Files.createDirectories(lockFile.getParent());
            AtomicFiles.write(lockFile, SpaceLock.PRETTY.writeValueAsBytes(lock), ".lock-");

            Map<String, Object> before = s.last.body();
            SpaceLock.Report r = SpaceLock.ready(String.valueOf(before.get("checkedAt")), java.time.Instant.now().toString(),
                    "lock".equals(before.get("baseline")), (String) before.get("baselineNote"), files, keep,
                    SpaceLock.maps(lock.get("links")));
            if (before.containsKey("columnChanges")) r.body().put("columnChanges", before.get("columnChanges"));
            s.last = r;
            return new Result(accepted, notOutside, List.of(), left);
        } finally {
            s.computing.unlock();
        }
    }

    private static Map<String, Object> finding(Object path, Object change, Object hash) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("path", path);
        m.put("change", change);
        if (hash != null) m.put("hash", hash);
        return m;
    }
}
