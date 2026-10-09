package com.gamma.screening;

import com.gamma.entitystore.EntityFactLog;
import com.gamma.entitystore.EntityTypes;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Test fixture: Entity Lists written straight into a Space's fact log (no HTTP), plus the test seam for hits. */
public final class ScreeningLists {

    private ScreeningLists() {}

    /** No masking in the Space at {@code root} (a list renders its raw entries). */
    public static void unmasked(Path root) throws Exception {
        Files.createDirectories(root);
        Files.writeString(root.resolve("link-analysis.toon"), "masking_mode: none\n");
    }

    /** Create list {@code id} of Entity Type {@code type} (sealed normaliser {@code normaliser}). */
    public static void create(Path root, String id, String purpose, String type, String normaliser) throws Exception {
        append(root, "list.created", id, Map.of("title", id, "purpose", purpose, "entityType", type, "normaliser", normaliser));
    }

    /** Add raw values (normalised here under {@code normaliser}, as the members route does). */
    public static void add(Path root, String id, String normaliser, List<String> raw) throws Exception {
        append(root, "list.member.added", id,
                Map.of("keys", raw.stream().map(v -> EntityTypes.normalise(normaliser, v)).toList()));
    }

    /** Add already-normalised keys that expire at {@code expiresAt}. */
    public static void addExpiring(Path root, String id, List<String> keys, String expiresAt) throws Exception {
        append(root, "list.member.added", id, Map.of("keys", keys, "expiresAt", expiresAt));
    }

    /** Add canonical range entries ({@code prefix:+4478}, {@code range:lo..hi}, {@code cidr:…}). */
    public static void addRanges(Path root, String id, List<String> ranges) throws Exception {
        append(root, "list.range.added", id, Map.of("ranges", ranges));
    }

    public static void retire(Path root, String id) throws Exception {
        append(root, "list.retired", id, Map.of());
    }

    private static void append(Path root, String kind, String id, Map<String, Object> payload) throws Exception {
        EntityFactLog log = new EntityFactLog(root);
        log.append(log.read(), "analyst-1", "test", kind, id, payload);
    }

    /** Raise and save one open hit (the test seam the HTTP tests in other packages use). */
    public static String seedHit(Path root, String subjectKey, String name, String listId, String entry, double score)
            throws Exception {
        Map<String, Object> hit = ScreeningHits.raise(new Screener.Subject(subjectKey, name, null),
                new Screener.Match(listId, "block", entry, "name", score), Screener.DEFAULT_THRESHOLD,
                Map.of("job", "nightly", "runId", "r1", "dataset", "customers"), "job:screening.run:nightly");
        ScreeningHits.save(root, hit);
        return String.valueOf(hit.get("id"));
    }

    /** The on-disk document of hit {@code id} (to forge it in a test). */
    public static Path hitFile(Path root, String id) {
        return root.resolve(ScreeningHits.DIR).resolve(id + ".json");
    }
}
