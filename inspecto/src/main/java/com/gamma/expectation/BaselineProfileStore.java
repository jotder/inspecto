package com.gamma.expectation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.util.AtomicFiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Durable <b>profile history + accepted-profile store</b> for the {@code baseline} Expectation kind
 * (DUCKLE-C8). One JSON document per Expectation at {@code <write-root>/expectation-baselines/<name>.json}:
 * <pre>
 *   { "nextSeq": 7,
 *     "profiles": [ { "id": "p-6", "recordedAt": ms, "outcome": "PASSED|FAILED", "accepted": bool,
 *                     "acceptedBy": "run|&lt;actor&gt;", "groups": { "&lt;groupKey&gt;": { "row_count": n,
 *                     "&lt;column&gt;.null_count": n, … } } } ],
 *     "ops": [ { "op": "accept|clear", "actor", "at", "profileId", "replaced": … } ] }
 * </pre>
 *
 * <p>EVERY evaluation records its profile — a refused (FAILED) run included — with {@code accepted:false};
 * acceptance is a separate step, taken by the route only when the whole run succeeded, or by an explicit
 * audited {@link #accept}/{@link #clear} op whose {@code ops[]} entry carries the value it replaced.
 *
 * <p>Fail closed: there is no in-memory fallback. The caller holds a write root or gets a 503 before it
 * reaches this class, and an unreadable document is an {@link IOException}, never "no history" — reading a
 * corrupt file as empty would silently reset the baseline and pass whatever the next input looks like.
 * Writes are temp-file + atomic move ({@link AtomicFiles}); read-modify-write is serialised on one lock.
 */
public final class BaselineProfileStore {

    /** Refused (never-accepted) profiles kept per Expectation — oldest evicted first. */
    public static final int MAX_UNACCEPTED = 100;
    /** Accepted profiles kept — the largest window an Expectation may ask for. */
    public static final int MAX_ACCEPTED = Expectation.Baseline.MAX_WINDOW;
    /** Audited ops kept per Expectation. */
    public static final int MAX_OPS = 200;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Object LOCK = new Object();

    private final Path dir;

    public BaselineProfileStore(Path writeRoot) {
        this.dir = writeRoot.resolve("expectation-baselines").toAbsolutePath().normalize();
    }

    /** The last {@code n} accepted profiles, oldest first. */
    public List<Map<String, Object>> acceptedWindow(String name, int n) throws IOException {
        synchronized (LOCK) {
            return window(profiles(read(name)), n);
        }
    }

    /** Record a profile (never accepted here). Returns its id. */
    public String record(String name, Map<String, Map<String, Double>> groups, String outcome, long at)
            throws IOException {
        synchronized (LOCK) {
            Map<String, Object> doc = read(name);
            long seq = ((Number) doc.getOrDefault("nextSeq", 1)).longValue();
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("id", "p-" + seq);
            p.put("recordedAt", at);
            p.put("outcome", outcome);
            p.put("accepted", false);
            p.put("groups", groups);
            List<Map<String, Object>> profiles = profiles(doc);
            profiles.add(p);
            evict(profiles);
            doc.put("nextSeq", seq + 1);
            doc.put("profiles", profiles);
            write(name, doc);
            return (String) p.get("id");
        }
    }

    /** Accept profiles because the run that recorded them succeeded as a whole (not an audited op). */
    public void acceptByRun(String name, String profileId) throws IOException {
        synchronized (LOCK) {
            Map<String, Object> doc = read(name);
            List<Map<String, Object>> profiles = profiles(doc);
            Map<String, Object> p = find(profiles, profileId);
            p.put("accepted", true);
            p.put("acceptedBy", "run");
            evict(profiles);
            doc.put("profiles", profiles);
            write(name, doc);
        }
    }

    /**
     * Audited explicit accept of a recorded profile ({@code profileId} null = the most recent one).
     *
     * @return the op entry, carrying the baseline window it replaced
     * @throws NoSuchElementException no such profile (→ 404)
     * @throws IllegalStateException  already accepted (→ 409)
     */
    public Map<String, Object> accept(String name, String profileId, String actor, int window) throws IOException {
        synchronized (LOCK) {
            Map<String, Object> doc = read(name);
            List<Map<String, Object>> profiles = profiles(doc);
            if (profileId == null) {
                if (profiles.isEmpty()) throw new NoSuchElementException("expectation '" + name + "' has no recorded profile");
                profileId = (String) profiles.getLast().get("id");
            }
            Map<String, Object> p = find(profiles, profileId);
            if (Boolean.TRUE.equals(p.get("accepted")))
                throw new IllegalStateException("profile '" + profileId + "' is already accepted");
            List<String> before = ids(window(profiles, window));
            p.put("accepted", true);
            p.put("acceptedBy", actor);
            evict(profiles);
            Map<String, Object> op = op("accept", actor, profileId);
            op.put("replaced", Map.of("window", before));
            op.put("window", ids(window(profiles, window)));
            return commit(name, doc, profiles, op);
        }
    }

    /**
     * Audited clear: every accepted profile becomes un-accepted (history kept), so the next successful run
     * starts a fresh baseline. The op entry carries the accepted ids it replaced.
     */
    public Map<String, Object> clear(String name, String actor) throws IOException {
        synchronized (LOCK) {
            Map<String, Object> doc = read(name);
            List<Map<String, Object>> profiles = profiles(doc);
            List<String> was = new ArrayList<>();
            for (Map<String, Object> p : profiles)
                if (Boolean.TRUE.equals(p.get("accepted"))) {
                    was.add((String) p.get("id"));
                    p.put("accepted", false);
                    p.remove("acceptedBy");
                }
            evict(profiles);
            Map<String, Object> op = op("clear", actor, null);
            op.put("replaced", Map.of("accepted", was));
            return commit(name, doc, profiles, op);
        }
    }

    /** Drop the whole history — the Expectation it belonged to was deleted, and a re-created one of the
     *  same name must not inherit its baseline. */
    public void delete(String name) throws IOException {
        synchronized (LOCK) {
            Files.deleteIfExists(file(name));
        }
    }

    // ── internals ─────────────────────────────────────────────────────────────

    private Map<String, Object> commit(String name, Map<String, Object> doc, List<Map<String, Object>> profiles,
                                       Map<String, Object> op) throws IOException {
        List<Map<String, Object>> ops = list(doc.get("ops"));
        ops.add(op);
        while (ops.size() > MAX_OPS) ops.removeFirst();
        doc.put("profiles", profiles);
        doc.put("ops", ops);
        write(name, doc);
        return op;
    }

    private static Map<String, Object> op(String op, String actor, String profileId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("op", op);
        m.put("actor", actor);
        m.put("at", System.currentTimeMillis());
        if (profileId != null) m.put("profileId", profileId);
        return m;
    }

    private static List<Map<String, Object>> window(List<Map<String, Object>> profiles, int n) {
        List<Map<String, Object>> accepted = profiles.stream()
                .filter(p -> Boolean.TRUE.equals(p.get("accepted"))).toList();
        return List.copyOf(accepted.subList(Math.max(0, accepted.size() - n), accepted.size()));
    }

    private static List<String> ids(List<Map<String, Object>> profiles) {
        return profiles.stream().map(p -> (String) p.get("id")).toList();
    }

    /** Bound both populations separately, oldest first, so refused runs can never evict the baseline. */
    private static void evict(List<Map<String, Object>> profiles) {
        trim(profiles, true, MAX_ACCEPTED);
        trim(profiles, false, MAX_UNACCEPTED);
    }

    private static void trim(List<Map<String, Object>> profiles, boolean accepted, int cap) {
        long count = profiles.stream().filter(p -> accepted == Boolean.TRUE.equals(p.get("accepted"))).count();
        var it = profiles.iterator();
        while (count > cap && it.hasNext()) {
            if (accepted == Boolean.TRUE.equals(it.next().get("accepted"))) {
                it.remove();
                count--;
            }
        }
    }

    private static Map<String, Object> find(List<Map<String, Object>> profiles, String id) {
        return profiles.stream().filter(p -> id.equals(p.get("id"))).findFirst()
                .orElseThrow(() -> new NoSuchElementException("unknown baseline profile '" + id + "'"));
    }

    private static List<Map<String, Object>> profiles(Map<String, Object> doc) {
        return list(doc.get("profiles"));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Object v) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (v instanceof List<?> l) for (Object o : l) out.add(new LinkedHashMap<>((Map<String, Object>) o));
        return out;
    }

    private Map<String, Object> read(String name) throws IOException {
        Path f = file(name);
        if (!Files.exists(f)) return new LinkedHashMap<>();
        try {
            return JSON.readValue(f.toFile(), new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (IOException | RuntimeException corrupt) {
            throw new IOException("baseline profile store for expectation '" + name + "' is unreadable ("
                    + corrupt.getMessage() + ") — refusing to treat it as an empty history", corrupt);
        }
    }

    private void write(String name, Map<String, Object> doc) throws IOException {
        Path f = file(name);
        Files.createDirectories(dir);
        AtomicFiles.write(f, JSON.writeValueAsBytes(doc), ".baseline-");
    }

    private Path file(String name) {
        if (name == null || !name.matches("[A-Za-z0-9][A-Za-z0-9._-]*") || name.contains(".."))
            throw new IllegalArgumentException("unsafe expectation name '" + name + "'");
        Path f = dir.resolve(name + ".json").normalize();
        if (!f.startsWith(dir)) throw new IllegalArgumentException("unsafe expectation name '" + name + "'");
        return f;
    }
}
