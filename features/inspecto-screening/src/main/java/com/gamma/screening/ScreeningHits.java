package com.gamma.screening;

import com.fasterxml.jackson.core.type.TypeReference;
import com.gamma.audit.Event;
import com.gamma.audit.EventType;
import com.gamma.config.safety.PathJail;
import com.gamma.control.PendingChanges;
import com.gamma.event.EventLog;
import com.gamma.spi.auth.ApiException;
import com.gamma.spi.auth.ErrorCodes;
import com.gamma.spi.http.ApiContext;
import com.gamma.util.AtomicFiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The Space's <b>Screening Hits</b> (SCR-D10 … SCR-D12, {@code docs/superpower/screening-addon-plan.md}): one
 * HMAC-signed JSON document per hit at {@code <write-root>/screening-hits/<id>.json} — the Action Requests storage
 * pattern exactly (atomic temp + move, jailed under the write root, signed with the Space's Pending Change key under
 * domain {@code screening-hit}). A document no server wrote — forged or edited — reads back
 * {@code integrity: invalid} and can never be decided. Not an OperationalDb family: the documents sit in the config
 * tree a Space backup carries; every import refuses the directory and a whole-Space export skips it.
 *
 * <p><b>States</b>: {@code open → confirmed | dismissed | escalated}, {@code escalated → confirmed | dismissed};
 * {@code confirmed} and {@code dismissed} are final. A decision names the hit's {@code version} (stale → 409) and a
 * reason; every transition is appended to {@code history}.
 *
 * <p><b>Identity</b> (SCR-D11): {@code dedupeKey = sha256(listId, entry, subjectKey)}. A Run never raises a second
 * hit for a key that already has one, whatever its state.
 */
public final class ScreeningHits {

    private ScreeningHits() {}

    static final String DIR = "screening-hits";
    static final String DOMAIN = "screening-hit";
    static final String INDEX = "dedupe-index.json", INDEX_DOMAIN = "screening-hit-index";
    static final Pattern SAFE_ID = Pattern.compile("sh-\\d{14}-[0-9a-f]{6}");

    static final String OPEN = "open", CONFIRMED = "confirmed", DISMISSED = "dismissed", ESCALATED = "escalated",
            INVALID = "invalid";
    static final Set<String> STATES = Set.of(OPEN, CONFIRMED, DISMISSED, ESCALATED);

    /** A decision verb → the state it moves to. */
    static final Map<String, String> DECISIONS = Map.of("confirm", CONFIRMED, "dismiss", DISMISSED, "escalate", ESCALATED);

    private static final String MAC = "mac";
    private static final Object LOCK = new Object();
    private static final TypeReference<LinkedHashMap<String, Object>> MAP = new TypeReference<>() {};
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC);

    static Object lock() { return LOCK; }

    /** Whether {@code from} may move to {@code to}. */
    static boolean allowed(String from, String to) {
        return OPEN.equals(from) ? !OPEN.equals(to) : ESCALATED.equals(from) && (CONFIRMED.equals(to) || DISMISSED.equals(to));
    }

    static String dedupeKey(String listId, String entry, String subjectKey) {
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            for (String part : new String[]{listId, entry, subjectKey}) {
                d.update(String.valueOf(part).getBytes(StandardCharsets.UTF_8));
                d.update((byte) 0);
            }
            return HexFormat.of().formatHex(d.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A fresh {@code open} hit (not yet saved). */
    static Map<String, Object> raise(Screener.Subject subject, Screener.Match match, double threshold,
                                     Map<String, Object> source, String by) {
        Instant at = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("id", newId(at));
        rec.put("recordType", DOMAIN);
        rec.put("state", OPEN);
        rec.put("version", 1);
        rec.put("dedupeKey", dedupeKey(match.listId(), match.entry(), subject.key()));
        rec.put("listId", match.listId());
        rec.put("purpose", match.purpose());
        rec.put("entry", match.entry());
        rec.put("method", match.method());
        rec.put("score", match.score());
        rec.put("threshold", threshold);
        rec.put("subjectKey", subject.key());
        rec.put("subjectName", subject.name());
        rec.put("subjectIdentifier", subject.identifier());
        rec.put("source", source);
        rec.put("raisedAt", at.toString());
        rec.put("raisedBy", by);
        rec.put("history", new ArrayList<>(List.of(step(OPEN, by, at.toString(), null))));
        return rec;
    }

    /** {@code sh-<UTC second>-<24 random bits>}: unique only by chance, so a Run takes one with {@link #freshId}. */
    static String newId(Instant at) {
        return id(at, random.getAsInt());
    }

    /** The 24 random bits of an id; a test pins it to force a same-second collision. */
    static volatile java.util.function.IntSupplier random = () -> ThreadLocalRandom.current().nextInt(1 << 24);

    private static String id(Instant at, int random) {
        return "sh-" + STAMP.format(at) + "-" + String.format("%06x", random & 0xffffff);
    }

    /** {@code id} unless {@code taken} already holds it (saving it would overwrite that hit), else a new one. */
    static String freshId(Map<String, ?> taken, String id, Instant at, java.util.function.IntSupplier random) {
        while (taken.containsKey(id)) id = id(at, random.getAsInt());
        return id;
    }

    /** Move {@code rec} to {@code state}: bump the version, record who / when / why, append to history. */
    @SuppressWarnings("unchecked")
    static void transition(Map<String, Object> rec, String state, String by, String reason) {
        String at = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
        rec.put("state", state);
        rec.put("version", ((Number) rec.get("version")).intValue() + 1);
        rec.put("decidedBy", by);
        rec.put("decidedAt", at);
        rec.put("reason", reason);
        List<Object> h = rec.get("history") instanceof List<?> l ? new ArrayList<>((List<Object>) l) : new ArrayList<>();
        h.add(step(state, by, at, reason));
        rec.put("history", h);
    }

    private static Map<String, Object> step(String state, String by, String at, String reason) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("state", state);
        m.put("by", by);
        m.put("at", at);
        if (reason != null) m.put("reason", reason);
        return m;
    }

    /** Who moved the hit to {@code escalated} (the latest such step), or null. */
    static String escalatedBy(Map<String, Object> rec) {
        String by = null;
        if (rec.get("history") instanceof List<?> h)
            for (Object o : h)
                if (o instanceof Map<?, ?> m && ESCALATED.equals(m.get("state"))) by = m.get("by") == null ? null : String.valueOf(m.get("by"));
        return by;
    }

    static boolean invalid(Map<String, Object> rec) {
        return INVALID.equals(rec.get("integrity"));
    }

    private static Path dir(Path root) {
        return root.resolve(DIR);
    }

    private static Path file(Path root, String id) {
        if (id == null || !SAFE_ID.matcher(id).matches())
            throw new ApiException(404, ErrorCodes.NOT_FOUND, "screening hit '" + id + "' not found");
        Path f = dir(root).resolve(id + ".json");
        if (!PathJail.contains(root.toAbsolutePath().normalize(), f))
            throw new ApiException(403, ErrorCodes.PERMISSION_DENIED, "screening hit path escapes the config root");
        return f;
    }

    /** Persist {@code rec}, signed. ⛔ Never on a record that read back invalid — that would sign a forgery. */
    static void save(Path root, Map<String, Object> rec) throws IOException {
        if (invalid(rec)) throw new IllegalStateException("refusing to re-sign a screening hit that failed its integrity check");
        Path f = file(root, String.valueOf(rec.get("id")));
        Files.createDirectories(f.getParent());
        Map<String, Object> clean = new LinkedHashMap<>(rec);
        clean.remove(MAC);
        Map<String, Object> normal = ApiContext.JSON.readValue(ApiContext.JSON.writeValueAsBytes(clean), MAP);
        normal.put(MAC, PendingChanges.domainMac(root, DOMAIN, normal));
        AtomicFiles.write(f, ApiContext.JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(normal), ".sh-");
        rec.put(MAC, normal.get(MAC));
    }

    /**
     * One hit, or {@code null}. Fail closed: an unreadable document is an IOException; a document whose MAC does not
     * verify, or that names another id or record type than its file, comes back {@code integrity: invalid}.
     */
    static Map<String, Object> read(Path root, String id) throws IOException {
        Path f = file(root, id);
        if (!Files.isRegularFile(f)) return null;
        Map<String, Object> rec = ApiContext.JSON.readValue(Files.readAllBytes(f), MAP);
        Object claimed = rec.remove(MAC);
        String expected = PendingChanges.domainMac(root, DOMAIN, rec);
        boolean ok = claimed instanceof String c && MessageDigest.isEqual(
                c.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8))
                && id.equals(rec.get("id")) && DOMAIN.equals(rec.get("recordType"));
        if (claimed != null) rec.put(MAC, claimed);
        if (!ok) {
            rec.put("id", id);
            rec.put("integrity", INVALID);
        }
        return rec;
    }

    /** Every hit of the Space, newest first. */
    static List<Map<String, Object>> list(Path root) throws IOException {
        Path d = dir(root);
        if (!Files.isDirectory(d)) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(d)) {
            for (Path p : s.filter(p -> p.getFileName().toString().endsWith(".json")).toList()) {
                String id = p.getFileName().toString().replaceFirst("\\.json$", "");
                if (!SAFE_ID.matcher(id).matches()) continue;
                Map<String, Object> rec = read(root, id);
                if (rec != null) out.add(rec);
            }
        }
        out.sort(Comparator.comparing((Map<String, Object> r) -> String.valueOf(r.get("id"))).reversed());
        return out;
    }

    /**
     * The hit index (SCREENING-1 D6, D8): {@code <write-root>/screening-hits/dedupe-index.json}, hit id →
     * {@code {key: dedupeKey, state, mtime}}, signed under its own domain. {@code state} is the state the document
     * claims and {@code mtime} its file's modification time (µs). A Run dedupes from it and the listing pages and
     * filters from it, each reading this one file plus only the documents it does not cover, instead of every
     * document (measured 2026-11-01 at ~12.5 ms a document cold on Windows: 20 000 hits took 253 s). A document
     * whose file time differs from its entry's — rewritten by a decision whose index write was lost, or edited by
     * hand — is not covered and is read again, so the index can lag a document but never misfile it for long. An
     * index whose MAC does not verify, or that cannot be parsed, is rebuilt from the documents; an id whose document
     * is gone is dropped. Lives inside {@code screening-hits/}, so import, export and backup treat it as they treat
     * the hits. Reading needs no lock; a writer calls it under {@link #lock()} and saves it back with
     * {@link #saveIndex}.
     */
    static Map<String, Object> index(Path root) throws IOException {
        return index(root, new int[1]);
    }

    /** {@link #index(Path)}, counting in {@code reread[0]} the documents it had to read. */
    private static Map<String, Object> index(Path root, int[] reread) throws IOException {
        Path d = dir(root);
        if (!Files.isDirectory(d)) return new LinkedHashMap<>();
        Map<String, Long> stamps = new java.util.HashMap<>();
        // One directory pass that also yields each file's time (cached by the directory read on Windows).
        Files.walkFileTree(d, Set.of(), 1, new java.nio.file.SimpleFileVisitor<>() {
            @Override
            public java.nio.file.FileVisitResult visitFile(Path p, java.nio.file.attribute.BasicFileAttributes a) {
                String name = p.getFileName().toString();
                String id = name.endsWith(".json") ? name.substring(0, name.length() - 5) : "";
                if (SAFE_ID.matcher(id).matches()) stamps.put(id, a.lastModifiedTime().to(java.util.concurrent.TimeUnit.MICROSECONDS));
                return java.nio.file.FileVisitResult.CONTINUE;
            }

            @Override
            public java.nio.file.FileVisitResult visitFileFailed(Path p, IOException e) throws IOException {
                // A save's temp file renamed away mid-walk (POSIX reads attributes per entry): not a hit, skip it.
                if (e instanceof java.nio.file.NoSuchFileException) return java.nio.file.FileVisitResult.CONTINUE;
                throw e;
            }
        });
        Map<String, Object> entries = readIndex(root);
        entries.keySet().retainAll(stamps.keySet());
        for (Map.Entry<String, Long> s : stamps.entrySet()) {
            if (entries.get(s.getKey()) instanceof Map<?, ?> e && e.get("mtime") instanceof Number t
                    && t.longValue() == s.getValue()) continue;
            Map<String, Object> rec = read(root, s.getKey());
            reread[0]++;
            if (rec == null) entries.remove(s.getKey());
            else entries.put(s.getKey(), entry(rec, s.getValue()));
        }
        return entries;
    }

    /** The index entry of {@code rec} just saved: its key, state and file time. */
    static Map<String, Object> entry(Path root, Map<String, Object> rec) throws IOException {
        return entry(rec, Files.getLastModifiedTime(file(root, String.valueOf(rec.get("id"))))
                .to(java.util.concurrent.TimeUnit.MICROSECONDS));
    }

    private static Map<String, Object> entry(Map<String, Object> rec, long mtime) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("key", rec.get("dedupeKey"));
        e.put("state", rec.get("state"));
        e.put("mtime", mtime);
        return e;
    }

    /** The dedupe keys an index holds. */
    static Set<Object> keys(Map<String, Object> index) {
        Set<Object> out = new java.util.HashSet<>();
        for (Object e : index.values()) if (e instanceof Map<?, ?> m) out.add(m.get("key"));
        return out;
    }

    /** Record {@code rec}, just saved, in the index. Call under {@link #lock()}. */
    static void reindex(Path root, Map<String, Object> rec) throws IOException {
        Map<String, Object> index = index(root);
        index.put(String.valueOf(rec.get("id")), entry(root, rec));
        saveIndex(root, index);
    }

    /** One page of hits: the documents themselves, read and verified, and the id to resume after (null = last). */
    record Page(List<Map<String, Object>> hits, String next, int total) {}

    /**
     * Hits newest first (by id), optionally only those whose index state is {@code state}, after the id
     * {@code after} (null = from the newest), at most {@code limit}. Only the page's documents are read; each is
     * verified as {@link #read} does, and one whose document no longer claims {@code state} is left out (a decision
     * landed between the index and the read). {@code total} counts the matching hits by the index. When the index had
     * to read documents (it was lost, or lagged), the listing saves it back, best effort and without the lock: an
     * entry a concurrent writer adds and this save drops is only read again next time, never misfiled.
     */
    static Page page(Path root, String state, String after, int limit) throws IOException {
        int[] reread = new int[1];
        Map<String, Object> index = index(root, reread);
        if (reread[0] > 0) {
            try {
                saveIndex(root, index);
            } catch (IOException | RuntimeException ignored) {
                // the next read re-reads those documents again; nothing is wrong, only slower
            }
        }
        List<String> ids = new ArrayList<>();
        for (Map.Entry<String, Object> e : index.entrySet())
            if (state == null || e.getValue() instanceof Map<?, ?> m && state.equals(m.get("state"))) ids.add(e.getKey());
        ids.sort(Comparator.reverseOrder());
        int from = 0;
        if (after != null) {
            int at = java.util.Collections.binarySearch(ids, after, Comparator.reverseOrder());
            from = at >= 0 ? at + 1 : -at - 1;
        }
        int to = Math.min(ids.size(), from + limit);
        List<Map<String, Object>> hits = new ArrayList<>();
        for (String id : ids.subList(from, to)) {
            Map<String, Object> rec = read(root, id);
            if (rec != null && (state == null || state.equals(rec.get("state")))) hits.add(rec);
        }
        return new Page(hits, to < ids.size() ? ids.get(to - 1) : null, ids.size());
    }

    /** The index's entries, or empty when it is absent, unreadable or fails its MAC (then it is rebuilt). */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> readIndex(Path root) throws IOException {
        Path f = dir(root).resolve(INDEX);
        if (!Files.isRegularFile(f)) return new LinkedHashMap<>();
        Map<String, Object> doc;
        try {
            doc = ApiContext.JSON.readValue(Files.readAllBytes(f), MAP);
        } catch (IOException unreadable) {
            return new LinkedHashMap<>();
        }
        if (doc == null) return new LinkedHashMap<>();   // a file holding just `null`
        Object claimed = doc.remove(MAC);
        String expected = PendingChanges.domainMac(root, INDEX_DOMAIN, doc);
        boolean ok = claimed instanceof String c && MessageDigest.isEqual(
                c.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8))
                && INDEX_DOMAIN.equals(doc.get("recordType")) && doc.get("keys") instanceof Map<?, ?>;
        return ok ? new LinkedHashMap<>((Map<String, Object>) doc.get("keys")) : new LinkedHashMap<>();
    }

    /** Persist the hit index, signed. */
    static void saveIndex(Path root, Map<String, Object> keys) throws IOException {
        Path f = dir(root).resolve(INDEX);
        Files.createDirectories(f.getParent());
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("recordType", INDEX_DOMAIN);
        doc.put("keys", keys);
        Map<String, Object> normal = ApiContext.JSON.readValue(ApiContext.JSON.writeValueAsBytes(doc), MAP);
        normal.put(MAC, PendingChanges.domainMac(root, INDEX_DOMAIN, normal));
        AtomicFiles.write(f, ApiContext.JSON.writeValueAsBytes(normal), ".shi-");
    }

    /** The wire shape: the record without its MAC. */
    static Map<String, Object> render(Map<String, Object> rec) {
        Map<String, Object> out = new LinkedHashMap<>(rec);
        out.remove(MAC);
        return out;
    }

    /** One AUDIT row (SCR-D14): ids, list, states and counts — never the screened name. Best effort. */
    static void audit(String actor, String actorType, String action, String message, Map<String, Object> attrs) {
        try {
            EventLog log = EventLog.current();
            if (log == null) return;
            Event.Builder b = Event.builder(EventType.AUDIT).source("audit").message(message)
                    .actor(actor).actorType(actorType).action(action).actionCategory("operation");
            attrs.forEach(b::attr);
            log.emit(b);
        } catch (RuntimeException ignored) {
            // best effort, like every audit emit — the record is what matters
        }
    }
}
