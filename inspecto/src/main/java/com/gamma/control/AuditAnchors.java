package com.gamma.control;

import com.fasterxml.jackson.core.type.TypeReference;
import com.gamma.event.AuditChain;
import com.gamma.event.Event;
import com.gamma.event.EventStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The audit chain's signed anchors (ASSURE-AUDIT-CHAIN-1): a record {@code {day, firstSeq, lastSeq, lastHash,
 * count}} that pins a contiguous run of the chain ({@link AuditChain}), MAC'd with the Space's key.
 *
 * <h3>When one is written</h3>
 * A {@code daily} anchor closes each UTC day that has chained records, once the day is over — written by
 * {@link #rollIfDue} on the first request the Space serves after midnight (in the background), or by
 * {@link #onDemand} before it writes its own. An {@code on-demand} anchor ({@code POST /audit/anchors}) pins
 * everything since the previous anchor up to the head. Anchors partition the chain: each starts at the seq after
 * the previous one's {@code lastSeq}, so {@code count = lastSeq - firstSeq + 1}. Neither kind is written over a
 * chain that does not verify — the roll stops before the first bad record, and on-demand refuses (409).
 *
 * <h3>Where, and why there</h3>
 * {@code <config root>.secrets/audit-anchors.jsonl} — beside the Pending Change key ({@link PendingChanges#keyFile}),
 * outside the config tree: no import can write there (an import writes only under the config root, and
 * {@link com.gamma.service.ReservedConfigPaths} could not name a path outside it), no export or Exchange reads
 * it, and {@code BackupTask} skips every {@code *.secrets} directory. One JSON line per anchor, appended and
 * forced.
 *
 * <h3>The MAC</h3>
 * HMAC-SHA256 with the Space's Pending Change key ({@link PendingChanges#key}), over {@code "audit-anchor\n"}
 * followed by the anchor's canonical JSON (its MAC excluded). The prefix separates the domains: a Pending Change
 * MAC is over a bare canonical JSON object, which starts with {@code {}, so no MAC of one kind is ever a valid
 * MAC of the other. ⚠ Like the Pending Change MAC it defends against a writer who cannot READ the key — an
 * import, a forged upload, someone editing the store — not against a local administrator who can read both;
 * that is what exporting the anchors off the box ({@code GET /audit/anchors}) is for. HMAC is symmetric, so an
 * offline checker holding only the export compares {@code lastHash} values; it does not re-check the MAC.
 */
final class AuditAnchors {

    private static final Logger log = LoggerFactory.getLogger(AuditAnchors.class);

    private AuditAnchors() {}

    static final String FILE = "audit-anchors.jsonl";
    private static final String MAC_DOMAIN = "audit-anchor\n";
    private static final TypeReference<LinkedHashMap<String, Object>> MAP = new TypeReference<>() {};
    private static final ConcurrentHashMap<Path, Object> LOCKS = new ConcurrentHashMap<>();

    /** One stored anchor and whether its MAC verifies against this Space's key. */
    record Anchor(String kind, String day, long firstSeq, long lastSeq, String lastHash, long count,
                  long createdAt, String mac, boolean macValid) {
        Map<String, Object> toMap() {
            Map<String, Object> m = fields(kind, day, firstSeq, lastSeq, lastHash, count, createdAt);
            m.put("mac", mac);
            m.put("integrity", macValid ? "valid" : "invalid");
            return m;
        }
    }

    /** The anchors file for the Space whose config root is {@code root}. */
    static Path file(Path root) {
        return PendingChanges.keyFile(root).getParent().resolve(FILE);
    }

    /** Every anchor, in file order, each with its MAC verdict. A line that does not parse (a torn write) is skipped. */
    static List<Anchor> read(Path root) throws IOException {
        Path f = file(root);
        List<Anchor> out = new ArrayList<>();
        if (!Files.isRegularFile(f)) return out;
        for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            Map<String, Object> m;
            try {
                m = ApiContext.JSON.readValue(line, MAP);
            } catch (IOException torn) {
                log.warn("Audit anchors {}: skipped an unreadable line", f);
                continue;
            }
            Object claimed = m.remove("mac");
            String expected = mac(root, m);
            boolean ok = claimed instanceof String c && java.security.MessageDigest.isEqual(
                    c.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
            out.add(new Anchor(str(m.get("kind")), str(m.get("day")), num(m.get("firstSeq")), num(m.get("lastSeq")),
                    str(m.get("lastHash")), num(m.get("count")), num(m.get("createdAt")),
                    claimed == null ? null : claimed.toString(), ok));
        }
        return out;
    }

    /** The day-boundary roll, per config root: the last UTC day it completed through, so it runs once a day. */
    private static final ConcurrentHashMap<Path, LocalDate> ROLLED = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Path, Boolean> ROLLING = new ConcurrentHashMap<>();

    /**
     * Called on every request the Space serves: when the UTC day has turned since the last roll, anchor the
     * finished days in the background. Cheap otherwise (a map lookup). Never throws.
     */
    static void rollIfDue(EventStore store, Path root) {
        if (store == null || root == null) return;
        try {
            Path key = root.toAbsolutePath().normalize();
            LocalDate today = LocalDate.now(ZoneOffset.UTC);
            LocalDate done = ROLLED.get(key);
            if (done != null && !done.isBefore(today)) return;
            if (ROLLING.putIfAbsent(key, Boolean.TRUE) != null) return;
            Thread.ofVirtual().name("audit-anchor-roll").start(() -> {
                try {
                    roll(store, key, today);
                    ROLLED.put(key, today);
                } catch (Exception e) {
                    log.warn("Audit anchor roll for {} failed; retried on a later request: {}", key, e.getMessage());
                } finally {
                    ROLLING.remove(key);
                }
            });
        } catch (RuntimeException ignore) {
            // best effort — anchoring must never break the request
        }
    }

    /**
     * Write a {@code daily} anchor for every UTC day before {@code today} that has chained records after the
     * last anchor. Walks the chain through {@link AuditVerifier}, so a day is anchored only as far as it verifies,
     * and a day in which a bad record is found is not anchored at all. Returns the number written.
     */
    static int roll(EventStore store, Path root, LocalDate today) throws IOException {
        synchronized (lock(root)) {
            List<Anchor> anchors = read(root);
            Anchor last = lastValid(store, anchors);
            Long from = last == null ? null : last.lastSeq() + 1;
            Event head = store.chainHead();
            if (head == null || (last != null && AuditChain.seq(head) <= last.lastSeq())) return 0;
            long[] dayFirst = {-1};
            Event[] prev = {null};
            LocalDate[] curDay = {null};
            int[] written = {0};
            IOException[] failed = {null};
            AuditVerifier.Result r = AuditVerifier.verify(store, anchors, from, null, Long.MAX_VALUE, e -> {
                LocalDate d = day(e);
                if (!d.isBefore(today)) return false;
                if (curDay[0] != null && !d.equals(curDay[0])) {
                    try {
                        append(root, "daily", curDay[0], dayFirst[0], prev[0]);
                        written[0]++;
                    } catch (IOException io) {
                        failed[0] = io;
                        return false;
                    }
                    dayFirst[0] = AuditChain.seq(e);
                }
                if (curDay[0] == null) dayFirst[0] = AuditChain.seq(e);
                curDay[0] = d;
                prev[0] = e;
                return true;
            });
            if (failed[0] != null) throw failed[0];
            if (!r.ok()) {
                log.warn("Audit chain does not verify at seq {} ({}); anchors stop before it", r.bad().seq(),
                        r.bad().reason());
                return written[0];
            }
            if (curDay[0] != null) {   // the walk ran out, or reached today: the day it was in is over
                append(root, "daily", curDay[0], dayFirst[0], prev[0]);
                written[0]++;
            }
            return written[0];
        }
    }

    /** What {@link #onDemand} did: the anchor that now covers the head (null on an empty chain), and whether
     *  this call wrote any anchor. */
    record OnDemand(Anchor anchor, boolean created) {}

    /**
     * {@code POST /audit/anchors}: roll the finished days, then anchor everything since the last anchor up to the
     * head. When the roll alone reached the head (every record is from a finished day), its last daily anchor is
     * the answer; when nothing was chained since the last anchor, that anchor is, with {@code created: false}.
     *
     * @throws ApiException 409 when the chain does not verify — an anchor over a broken chain would sign it
     */
    static OnDemand onDemand(EventStore store, Path root) throws IOException {
        synchronized (lock(root)) {
            int rolled = roll(store, root, LocalDate.now(ZoneOffset.UTC));
            List<Anchor> anchors = read(root);
            Anchor last = lastValid(store, anchors);
            Event head = store.chainHead();
            if (head == null) return new OnDemand(null, false);
            if (last != null && AuditChain.seq(head) <= last.lastSeq()) return new OnDemand(last, rolled > 0);
            Event[] tip = {null};
            AuditVerifier.Result r = AuditVerifier.verify(store, anchors,
                    last == null ? null : last.lastSeq() + 1, AuditChain.seq(head), Long.MAX_VALUE,
                    e -> { tip[0] = e; return true; });
            if (!r.ok())
                throw new ApiException(409, ErrorCodes.CONFLICT, "the audit chain does not verify at seq "
                        + r.bad().seq() + " (" + r.bad().reason() + "): " + r.bad().detail());
            return new OnDemand(append(root, "on-demand", day(tip[0]), r.from(), tip[0]), true);
        }
    }

    /** The anchor with the highest {@code lastSeq}; refuses to build on one whose MAC fails, or whose hash no
     *  longer matches — a new anchor chained onto a forged one would carry the forgery forward. */
    private static Anchor lastValid(EventStore store, List<Anchor> anchors) {
        Anchor last = null;
        for (Anchor a : anchors) if (last == null || a.lastSeq() > last.lastSeq()) last = a;
        if (last == null) return null;
        if (!last.macValid())
            throw new ApiException(409, ErrorCodes.CONFLICT, "the last audit anchor (" + last.day()
                    + ") does not carry a valid MAC; no anchor is written after it");
        List<Event> at = store.chainPage(last.lastSeq(), 1);
        if (at.isEmpty() || AuditChain.seq(at.get(0)) != last.lastSeq()
                || !last.lastHash().equals(AuditChain.storedHash(at.get(0)))
                || !last.lastHash().equals(AuditChain.hash(at.get(0))))
            throw new ApiException(409, ErrorCodes.CONFLICT, "the audit chain no longer holds the record the last "
                    + "anchor (" + last.day() + ") names at seq " + last.lastSeq() + "; no anchor is written after it");
        return last;
    }

    private static Anchor append(Path root, String kind, LocalDate day, long firstSeq, Event lastRec) throws IOException {
        long lastSeq = AuditChain.seq(lastRec);
        Map<String, Object> m = fields(kind, day.toString(), firstSeq, lastSeq, AuditChain.storedHash(lastRec),
                lastSeq - firstSeq + 1, System.currentTimeMillis());
        Map<String, Object> normal = ApiContext.JSON.readValue(ApiContext.JSON.writeValueAsBytes(m), MAP);
        String mac = mac(root, normal);
        normal.put("mac", mac);
        Path f = file(root);
        Files.createDirectories(f.getParent());
        try (FileChannel ch = FileChannel.open(f, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.APPEND)) {
            ch.write(ByteBuffer.wrap((ContentHash.canonicalJson(normal) + "\n").getBytes(StandardCharsets.UTF_8)));
            ch.force(true);
        }
        return new Anchor(kind, day.toString(), firstSeq, lastSeq, AuditChain.storedHash(lastRec),
                lastSeq - firstSeq + 1, num(normal.get("createdAt")), mac, true);
    }

    private static Map<String, Object> fields(String kind, String day, long firstSeq, long lastSeq, String lastHash,
                                              long count, long createdAt) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("v", 1);
        m.put("kind", kind);
        m.put("day", day);
        m.put("firstSeq", firstSeq);
        m.put("lastSeq", lastSeq);
        m.put("lastHash", lastHash);
        m.put("count", count);
        m.put("createdAt", createdAt);
        return m;
    }

    /** HMAC-SHA256 (hex) with the Space's key over the domain prefix + the anchor's canonical JSON. */
    private static String mac(Path root, Map<String, Object> anchor) throws IOException {
        try {
            javax.crypto.Mac m = javax.crypto.Mac.getInstance("HmacSHA256");
            m.init(new javax.crypto.spec.SecretKeySpec(PendingChanges.key(root), "HmacSHA256"));
            return java.util.HexFormat.of().formatHex(m.doFinal(
                    (MAC_DOMAIN + ContentHash.canonicalJson(anchor)).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException e) {
            throw new IOException("HmacSHA256 unavailable", e);
        }
    }

    private static LocalDate day(Event e) {
        return Instant.ofEpochMilli(e.ts()).atZone(ZoneOffset.UTC).toLocalDate();
    }

    private static Object lock(Path root) {
        return LOCKS.computeIfAbsent(file(root).toAbsolutePath().normalize(), p -> new Object());
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static long num(Object o) {
        return o instanceof Number n ? n.longValue() : -1;
    }
}
