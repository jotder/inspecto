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
 * count, prevAnchorMac}} that pins a contiguous run of the chain ({@link AuditChain}), MAC'd with the Space's key.
 *
 * <h3>The anchors are a chain of their own</h3>
 * Each anchor carries the MAC of the anchor before it ({@code prevAnchorMac}, {@code ""} for the first) inside its
 * own MAC input, and starts at the seq after the previous one's {@code lastSeq}. So the anchor FILE is verified end
 * to end ({@link AuditVerifier}): a garbled line, a deleted or reordered anchor, or a gap between two anchors is a
 * failure, never skipped. Nothing is ever re-signed over a broken or missing file: when the file is missing or
 * broken while chained rows exist from a day that should already be anchored, the roll and
 * {@code POST /audit/anchors} both REFUSE (409) — an operator has to see and resolve that.
 *
 * <h3>When one is written</h3>
 * A {@code daily} anchor closes each UTC day that has chained records, once the day is over — by the control
 * plane's scheduled roll ({@link #rollIfDue}, every few minutes, once a day per Space, backing off after a
 * failure), or by {@link #onDemand} before it writes its own. An {@code on-demand} anchor pins everything since the
 * previous anchor up to the head, and is rate-limited. Both walk only from the previous anchor's {@code lastSeq}.
 *
 * <h3>Where</h3>
 * {@code <config root>.secrets/audit-anchors.jsonl} — beside the Pending Change key, outside the config tree (no
 * import, export or Exchange reaches it; {@code BackupTask} skips {@code *.secrets}), created owner-only like the
 * key. One JSON line per anchor, appended and forced.
 *
 * <h3>The MAC, and what it cannot defend against</h3>
 * HMAC-SHA256 with the Space's Pending Change key over {@code "audit-anchor\n"} + the anchor's canonical JSON (MAC
 * excluded) — domain-separated from the Pending Change MAC, whose input starts with {@code {}. ⛔ An attacker who
 * can READ the key (a local administrator) can rewrite the store AND re-sign every anchor. On-box anchors do not
 * defend against that party; the anchors exported OFF the box ({@code GET /audit/anchors}, kept elsewhere on a
 * schedule) do.
 */
final class AuditAnchors {

    private static final Logger log = LoggerFactory.getLogger(AuditAnchors.class);

    private AuditAnchors() {}

    static final String FILE = "audit-anchors.jsonl";
    private static final String MAC_DOMAIN = "audit-anchor\n";
    private static final TypeReference<LinkedHashMap<String, Object>> MAP = new TypeReference<>() {};
    private static final ConcurrentHashMap<Path, Object> LOCKS = new ConcurrentHashMap<>();

    /** The least time between two on-demand anchors for one Space. */
    static final long ON_DEMAND_MIN_INTERVAL_MS = 10_000;

    /** One stored anchor and whether its MAC verifies against this Space's key. {@code extra} holds a BREAK anchor's
     *  own fields (reason, problem, lastGoodMac, lastGoodSeq); empty otherwise. */
    record Anchor(String kind, String day, long firstSeq, long lastSeq, String lastHash, long count,
                  long createdAt, String prevAnchorMac, String mac, boolean macValid, Map<String, Object> extra) {
        Map<String, Object> toMap() {
            Map<String, Object> m = fields(kind, day, firstSeq, lastSeq, lastHash, count, createdAt, prevAnchorMac);
            m.putAll(extra);
            m.put("mac", mac);
            m.put("integrity", macValid ? "valid" : "invalid");
            return m;
        }

        boolean isBreak() {
            return BREAK.equals(kind);
        }
    }

    /** The kind of the anchor {@code POST /audit/anchors/rebaseline} writes. */
    static final String BREAK = "break";

    /**
     * The durable "anchoring started" record, beside the key: the first anchor of the current anchor file and the
     * latest anchor written. Once it exists the young-install grace is gone for good — a missing or truncated
     * anchor file is ALWAYS a failure. ⚠ It is as strong as the secrets directory: whoever can write there can
     * also read the key, so it stops deletion of the anchor file, not a key holder (the off-box export does).
     */
    record Started(long firstSeq, String firstMac, String latestMac) {}

    static final String STARTED_FILE = "audit-anchoring.json";

    /** The whole anchor file: the anchors that parsed, in file order, the 1-based numbers of lines that did not,
     *  and the "anchoring started" record ({@code null} before the first anchor was ever written). */
    record AnchorFile(List<Anchor> anchors, List<Integer> unreadableLines, boolean exists, Started started, Path root) {
        static final AnchorFile NONE = new AnchorFile(List.of(), List.of(), false, null, null);

        /** The seq the current anchor epoch starts at: a leading BREAK anchor's new start, else 1. */
        long epochStart() {
            return !anchors.isEmpty() && anchors.get(0).isBreak() && anchors.get(0).macValid()
                    ? anchors.get(0).firstSeq() : 1;
        }

        Anchor epochBreak() {
            return epochStart() > 1 || (!anchors.isEmpty() && anchors.get(0).isBreak()) ? anchors.get(0) : null;
        }

        /** The first integrity problem of the file itself, or {@code null}: a missing or truncated file once
         *  anchoring has started, unreadable lines, a bad MAC, a broken link between two anchors, or a gap/overlap
         *  between their ranges. A leading BREAK anchor opens the file: its prevAnchorMac names the last good
         *  anchor of the file it replaced, not a line of this one. */
        AuditVerifier.Bad firstProblem() {
            if (!unreadableLines.isEmpty())
                return new AuditVerifier.Bad(0, "anchor-unreadable", "the anchor file has unreadable line(s) "
                        + unreadableLines);
            if (started != null) {
                if (anchors.isEmpty() && unreadableLines.isEmpty())
                    return new AuditVerifier.Bad(0, "anchor-file-missing", "anchoring started (first anchor "
                            + "at seq " + started.firstSeq() + ") but the anchor file is " + (exists ? "empty" : "missing"));
                if (!anchors.isEmpty() && (!started.firstMac().equals(anchors.get(0).mac())
                        || anchors.stream().noneMatch(a -> started.latestMac().equals(a.mac()))))
                    return new AuditVerifier.Bad(0, "anchor-file-truncated", "the anchor file does not hold the first "
                            + "and latest anchors the anchoring record names (lines were removed or the file replaced)");
            }
            if (!unreadableLines.isEmpty())
                return new AuditVerifier.Bad(0, "anchor-unreadable", "the anchor file has unreadable line(s) "
                        + unreadableLines);
            String prevMac = "";
            long prevLast = -1;
            for (int i = 0; i < anchors.size(); i++) {
                Anchor a = anchors.get(i);
                if (!a.macValid())
                    return new AuditVerifier.Bad(a.lastSeq(), "anchor-mismatch", "the anchor for " + a.day()
                            + " ending at seq " + a.lastSeq() + " does not carry a valid MAC");
                boolean opening = i == 0 && a.isBreak();
                if (!opening && !prevMac.equals(a.prevAnchorMac()))
                    return new AuditVerifier.Bad(a.lastSeq(), "anchor-chain-broken", "the anchor for " + a.day()
                            + " does not link onto the anchor before it (one was removed, reordered or replaced)");
                if (a.isBreak() && !opening)
                    return new AuditVerifier.Bad(a.lastSeq(), "anchor-chain-broken", "a break anchor is not the first "
                            + "line of the anchor file");
                if (prevLast >= 0 && a.firstSeq() != prevLast + 1)
                    return new AuditVerifier.Bad(a.lastSeq(), "anchor-chain-broken", "the anchor for " + a.day()
                            + " starts at seq " + a.firstSeq() + ", not at " + (prevLast + 1));
                if (a.count() != a.lastSeq() - a.firstSeq() + 1)
                    return new AuditVerifier.Bad(a.lastSeq(), "anchor-mismatch", "the anchor for " + a.day()
                            + " claims " + a.count() + " records over seq " + a.firstSeq() + ".." + a.lastSeq());
                prevMac = a.mac();
                prevLast = a.lastSeq();
            }
            return null;
        }

        Anchor last() {
            return anchors.isEmpty() ? null : anchors.get(anchors.size() - 1);
        }
    }

    /** The anchors file for the Space whose config root is {@code root}. */
    static Path file(Path root) {
        return PendingChanges.keyFile(root).getParent().resolve(FILE);
    }

    static Path startedFile(Path root) {
        return PendingChanges.keyFile(root).getParent().resolve(STARTED_FILE);
    }

    /** The anchors that parsed (convenience for callers that only list them). */
    static List<Anchor> read(Path root) throws IOException {
        return readFile(root).anchors();
    }

    private static final java.util.Set<String> BASE_KEYS = java.util.Set.of("v", "kind", "day", "firstSeq",
            "lastSeq", "lastHash", "count", "createdAt", "prevAnchorMac");

    /** Every line of the anchor file — a line that does not parse is RECORDED, never skipped. */
    static AnchorFile readFile(Path root) throws IOException {
        return readFileAt(root, file(root), readStarted(root));
    }

    /** {@link #readFile} over a given anchor file — a replaced one, for the prior epochs. */
    static AnchorFile readFileAt(Path root, Path f, Started started) throws IOException {
        if (!Files.isRegularFile(f)) return new AnchorFile(List.of(), List.of(), false, started, root);
        List<Anchor> out = new ArrayList<>();
        List<Integer> bad = new ArrayList<>();
        List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank()) continue;
            Map<String, Object> m;
            try {
                m = ApiContext.JSON.readValue(line, MAP);
            } catch (IOException torn) {
                bad.add(i + 1);
                continue;
            }
            Object claimed = m.remove("mac");
            String expected = mac(root, m);
            boolean ok = claimed instanceof String c && java.security.MessageDigest.isEqual(
                    c.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
            Map<String, Object> extra = new LinkedHashMap<>(m);
            extra.keySet().removeAll(BASE_KEYS);
            out.add(new Anchor(str(m.get("kind")), str(m.get("day")), num(m.get("firstSeq")), num(m.get("lastSeq")),
                    str(m.get("lastHash")), num(m.get("count")), num(m.get("createdAt")),
                    m.get("prevAnchorMac") == null ? null : m.get("prevAnchorMac").toString(),
                    claimed == null ? null : claimed.toString(), ok, Map.copyOf(extra)));
        }
        return new AnchorFile(List.copyOf(out), List.copyOf(bad), true, started, root);
    }

    private static Started readStarted(Path root) throws IOException {
        Path f = startedFile(root);
        if (!Files.isRegularFile(f)) return null;
        Map<String, Object> m;
        try {
            m = ApiContext.JSON.readValue(Files.readAllBytes(f), MAP);
        } catch (IOException unreadable) {
            // an unreadable record still means anchoring started: fail closed with nothing to match
            return new Started(-1, "", "");
        }
        return new Started(num(m.get("firstSeq")), str(m.get("firstMac")), str(m.get("latestMac")));
    }

    /** Record an anchor in the "anchoring started" record: created first-writer-wins and owner-only on the first
     *  anchor ever; its latestMac replaced atomically after every later one (a {@code restart} replaces both). */
    private static void recordStarted(Path root, Anchor a, boolean restart) throws IOException {
        Path f = startedFile(root);
        Started now = readStarted(root);
        Map<String, Object> m = new LinkedHashMap<>();
        boolean fresh = now == null || restart;
        m.put("firstSeq", fresh ? a.firstSeq() : now.firstSeq());
        m.put("firstMac", fresh ? a.mac() : now.firstMac());
        m.put("latestMac", a.mac());
        byte[] body = ApiContext.JSON.writeValueAsBytes(m);
        if (now == null) {
            try {
                createOwnerOnly(f, true);
                Files.write(f, body, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
                return;
            } catch (java.nio.file.FileAlreadyExistsException raced) {
                // another writer created it first; fall through to replace its latestMac
            }
        }
        Path tmp = f.resolveSibling(STARTED_FILE + ".tmp");
        Files.deleteIfExists(tmp);
        createOwnerOnly(tmp, true);
        Files.write(tmp, body, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
        Files.move(tmp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    // -- the scheduled day-boundary roll --

    /** Per config root: the last UTC day the roll completed through, and when it may next be attempted. */
    private static final ConcurrentHashMap<Path, LocalDate> ROLLED = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Path, long[]> BACKOFF = new ConcurrentHashMap<>();   // {notBefore, delay}
    private static final long FIRST_BACKOFF_MS = 5 * 60_000L;
    private static final long MAX_BACKOFF_MS = 6 * 3_600_000L;

    /**
     * Called by the control plane's scheduler (NOT per request, so an unauthenticated caller cannot drive it): when
     * the UTC day has turned since the last successful roll, anchor the finished days. After a failure it waits
     * 5 min, doubling to 6 h, before trying again. Never throws.
     */
    static void rollIfDue(EventStore store, Path root, long nowMs) {
        if (store == null || root == null) return;
        Path key = root.toAbsolutePath().normalize();
        LocalDate today = Instant.ofEpochMilli(nowMs).atZone(ZoneOffset.UTC).toLocalDate();
        LocalDate done = ROLLED.get(key);
        if (done != null && !done.isBefore(today)) return;
        long[] b = BACKOFF.get(key);
        if (b != null && nowMs < b[0]) return;
        try {
            roll(store, key, today);
            ROLLED.put(key, today);
            BACKOFF.remove(key);
        } catch (Exception e) {
            long delay = b == null ? FIRST_BACKOFF_MS : Math.min(MAX_BACKOFF_MS, b[1] * 2);
            BACKOFF.put(key, new long[] {nowMs + delay, delay});
            log.error("Audit anchor roll for {} failed; next attempt in {} min: {}", key, delay / 60_000, e.getMessage());
        }
    }

    /**
     * Write a {@code daily} anchor for every UTC day before {@code today} that has chained records after the last
     * anchor, walking only from that anchor. A day is anchored only as far as it verifies; the day a bad record is
     * found in is not anchored at all. Refuses (409) over a broken or missing anchor file (see class doc).
     */
    static int roll(EventStore store, Path root, LocalDate today) throws IOException {
        synchronized (lock(root)) {
            AnchorFile file = readFile(root);
            Anchor last = lastValid(store, file, today);
            Long from = last == null ? null : last.lastSeq() + 1;
            Event head = store.chainHead();
            if (head == null || (last != null && AuditChain.seq(head) <= last.lastSeq())) return 0;
            long[] dayFirst = {-1};
            Event[] prev = {null};
            LocalDate[] curDay = {null};
            int[] written = {0};
            String[] prevMac = {last == null ? "" : last.mac()};
            IOException[] failed = {null};
            AuditVerifier.Result r = AuditVerifier.verify(store, AnchorFile.NONE, from, null, Long.MAX_VALUE, e -> {
                LocalDate d = day(e);
                if (!d.isBefore(today)) return false;
                if (curDay[0] != null && !d.equals(curDay[0])) {
                    try {
                        prevMac[0] = append(root, "daily", curDay[0], dayFirst[0], prev[0], prevMac[0]).mac();
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
            }, new AuditVerifier.Policy(today, false, null));
            if (failed[0] != null) throw failed[0];
            if (!r.ok()) {
                log.error("Audit chain does not verify at seq {} ({}); anchors stop before it", r.bad().seq(),
                        r.bad().reason());
                return written[0];
            }
            if (curDay[0] != null) {   // the walk ran out, or reached today: the day it was in is over
                append(root, "daily", curDay[0], dayFirst[0], prev[0], prevMac[0]);
                written[0]++;
            }
            return written[0];
        }
    }

    /** What {@link #onDemand} did: the anchor that now covers the head (null on an empty chain), and whether
     *  this call wrote any anchor. */
    record OnDemand(Anchor anchor, boolean created) {}

    private static final ConcurrentHashMap<Path, Long> LAST_ON_DEMAND = new ConcurrentHashMap<>();

    /**
     * {@code POST /audit/anchors}: roll the finished days, then anchor everything since the last anchor up to the
     * head. When the roll alone reached the head, its last daily anchor is the answer; when nothing was chained
     * since the last anchor, that anchor is, with {@code created: false}.
     *
     * @throws ApiException 429 within {@link #ON_DEMAND_MIN_INTERVAL_MS} of the last call for this Space; 409 when
     *         the chain or the anchor file does not verify — an anchor over either would sign it
     */
    static OnDemand onDemand(EventStore store, Path root) throws IOException {
        Path key = root.toAbsolutePath().normalize();
        long now = System.currentTimeMillis();
        Long prior = LAST_ON_DEMAND.get(key);
        if (prior != null && now - prior < ON_DEMAND_MIN_INTERVAL_MS)
            throw new ApiException(429, ErrorCodes.RATE_LIMITED, "an audit anchor was requested for this Space "
                    + (now - prior) + " ms ago; wait " + ON_DEMAND_MIN_INTERVAL_MS / 1000 + " s between on-demand anchors");
        LAST_ON_DEMAND.put(key, now);
        synchronized (lock(root)) {
            LocalDate today = LocalDate.now(ZoneOffset.UTC);
            int rolled = roll(store, root, today);
            AnchorFile file = readFile(root);
            Anchor last = lastValid(store, file, today);
            Event head = store.chainHead();
            if (head == null) return new OnDemand(null, false);
            if (last != null && AuditChain.seq(head) <= last.lastSeq()) return new OnDemand(last, rolled > 0);
            Event[] tip = {null};
            AuditVerifier.Result r = AuditVerifier.verify(store, AnchorFile.NONE,
                    last == null ? null : last.lastSeq() + 1, AuditChain.seq(head), Long.MAX_VALUE,
                    e -> { tip[0] = e; return true; }, new AuditVerifier.Policy(today, false, null));
            if (!r.ok())
                throw new ApiException(409, ErrorCodes.CONFLICT, "the audit chain does not verify at seq "
                        + r.bad().seq() + " (" + r.bad().reason() + "): " + r.bad().detail());
            return new OnDemand(append(root, "on-demand", day(tip[0]), r.from(), tip[0],
                    last == null ? "" : last.mac()), true);
        }
    }

    /** Test seam: forget the on-demand rate limit for {@code root}. */
    static void resetRateLimit(Path root) {
        LAST_ON_DEMAND.remove(root.toAbsolutePath().normalize());
    }

    /**
     * The last anchor, after refusing (409) everything a new anchor must not be built on: an anchor file with any
     * problem ({@link AnchorFile#firstProblem}), a last anchor whose record the chain no longer holds or no longer
     * hashes to what it names, and a MISSING or empty file while the chain holds rows from a day that should
     * already have been anchored (before yesterday) — a deleted file must never be silently re-signed.
     */
    private static Anchor lastValid(EventStore store, AnchorFile file, LocalDate today) {
        AuditVerifier.Bad p = file.firstProblem();
        if (p != null)
            throw new ApiException(409, ErrorCodes.CONFLICT, "the audit anchor file does not verify (" + p.reason()
                    + ": " + p.detail() + "); no anchor is written until an operator resolves it");
        Anchor last = file.last();
        if (last == null) {
            List<Event> first = store.chainPage(1, 1);
            if (!first.isEmpty() && day(first.get(0)).isBefore(today.minusDays(1)))
                throw new ApiException(409, ErrorCodes.CONFLICT, "the audit anchor file is "
                        + (file.exists() ? "empty" : "missing") + " but the chain holds rows from "
                        + day(first.get(0)) + ", which should already be anchored; no anchor is written until an "
                        + "operator resolves it");
            return null;
        }
        if (last.isBreak() && last.lastSeq() < 1) return last;   // a break over an empty chain names no record
        List<Event> at = store.chainPage(last.lastSeq(), 1);
        if (at.isEmpty() || AuditChain.seq(at.get(0)) != last.lastSeq()
                || !last.lastHash().equals(AuditChain.storedHash(at.get(0)))
                || !last.lastHash().equals(AuditChain.hash(at.get(0))))
            throw new ApiException(409, ErrorCodes.CONFLICT, "the audit chain no longer holds the record the last "
                    + "anchor (" + last.day() + ") names at seq " + last.lastSeq() + "; no anchor is written after it");
        return last;
    }

    private static Anchor append(Path root, String kind, LocalDate day, long firstSeq, Event lastRec, String prevMac)
            throws IOException {
        long lastSeq = AuditChain.seq(lastRec);
        return write(root, fields(kind, day.toString(), firstSeq, lastSeq, AuditChain.storedHash(lastRec),
                lastSeq - firstSeq + 1, System.currentTimeMillis(), prevMac), Map.of(), null);
    }

    /** MAC and append one anchor line (a {@code rotate} starts a NEW file with it, moving the old one aside), then
     *  bring the "anchoring started" record up to date. */
    private static Anchor write(Path root, Map<String, Object> base, Map<String, Object> extra, String rotateTo)
            throws IOException {
        Map<String, Object> m = new LinkedHashMap<>(base);
        m.putAll(extra);
        Map<String, Object> normal = ApiContext.JSON.readValue(ApiContext.JSON.writeValueAsBytes(m), MAP);
        String mac = mac(root, normal);
        normal.put("mac", mac);
        Path f = file(root);
        Files.createDirectories(f.getParent());
        if (rotateTo != null && Files.exists(f)) Files.move(f, f.resolveSibling(rotateTo));
        createOwnerOnly(f, false);
        try (FileChannel ch = FileChannel.open(f, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            ch.write(ByteBuffer.wrap((ContentHash.canonicalJson(normal) + "\n").getBytes(StandardCharsets.UTF_8)));
            ch.force(true);
        }
        Map<String, Object> ex = new LinkedHashMap<>(normal);
        ex.keySet().removeAll(BASE_KEYS);
        ex.remove("mac");
        Anchor a = new Anchor(str(normal.get("kind")), str(normal.get("day")), num(normal.get("firstSeq")),
                num(normal.get("lastSeq")), str(normal.get("lastHash")), num(normal.get("count")),
                num(normal.get("createdAt")), str(normal.get("prevAnchorMac")), mac, true, Map.copyOf(ex));
        recordStarted(root, a, rotateTo != null);
        return a;
    }

    // -- rebaseline: the acknowledged break --

    private static final ConcurrentHashMap<Path, Long> LAST_REBASELINE = new ConcurrentHashMap<>();
    static final long REBASELINE_MIN_INTERVAL_MS = 60_000;

    /**
     * {@code POST /audit/anchors/rebaseline}: when — and ONLY when — verify reports a problem, start a new anchor
     * epoch with a signed BREAK anchor that records the operator's reason, the problem found, the last good anchor
     * and the new start seq (the head + 1). The old anchor file is moved aside (kept, never deleted). Nothing is
     * repaired: rows before the new start are simply no longer covered by the current epoch, and verify names the
     * break whenever a range reaches back over it.
     *
     * @throws ApiException 409 when the chain and anchors verify (nothing to rebaseline); 429 within a minute of the
     *         last rebaseline for this Space
     */
    static Anchor rebaseline(EventStore store, Path root, String reason, AuditVerifier.Policy policy, String by)
            throws IOException {
        Path key = root.toAbsolutePath().normalize();
        long now = System.currentTimeMillis();
        Long prior = LAST_REBASELINE.get(key);
        if (prior != null && now - prior < REBASELINE_MIN_INTERVAL_MS)
            throw new ApiException(429, ErrorCodes.RATE_LIMITED, "a rebaseline was made for this Space "
                    + (now - prior) / 1000 + " s ago");
        LAST_REBASELINE.put(key, now);
        synchronized (lock(root)) {
            AnchorFile file = readFile(root);
            // judged on the CURRENT epoch: an earlier, already acknowledged break is not a new problem
            AuditVerifier.Policy current = new AuditVerifier.Policy(policy.today(), policy.requireAnchors(),
                    policy.retentionCutoff(), true, policy.retentionSource());
            AuditVerifier.Result r = AuditVerifier.verify(store, file, null, null, Long.MAX_VALUE, e -> true, current);
            if (r.ok())
                throw new ApiException(409, ErrorCodes.CONFLICT, "the audit chain and its anchors verify; there is "
                        + "nothing to rebaseline");
            // the last anchor of the file's valid prefix
            Anchor lastGood = null;
            String prevMac = "";
            for (int i = 0; i < file.anchors().size(); i++) {
                Anchor a = file.anchors().get(i);
                if (!a.macValid() || (!(i == 0 && a.isBreak()) && !prevMac.equals(a.prevAnchorMac()))) break;
                lastGood = a;
                prevMac = a.mac();
            }
            Event head = store.chainHead();
            long newStart = head == null ? 1 : AuditChain.seq(head) + 1;
            Map<String, Object> base = fields(BREAK, policy.today().toString(), newStart, newStart - 1,
                    head == null ? "" : AuditChain.storedHash(head), 0, now,
                    lastGood == null ? "" : lastGood.mac());
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("reason", reason);
            extra.put("problem", r.bad().reason() + " at seq " + r.bad().seq() + ": " + r.bad().detail());
            extra.put("lastGoodSeq", lastGood == null ? 0 : lastGood.lastSeq());
            extra.put("by", by);
            // the file this break replaces is KEPT and pinned by digest: verify checks it as the prior epoch
            Path old = file(root);
            String rotateTo = "audit-anchors." + now + ".replaced.jsonl";
            if (Files.exists(old)) {
                extra.put("replacedFile", rotateTo);
                extra.put("replacedSha256", sha256(old));
            }
            Anchor a = write(root, base, extra, rotateTo);
            log.warn("Audit anchors REBASELINED for {} at seq {}: {} (problem: {})", key, newStart, reason,
                    extra.get("problem"));
            return a;
        }
    }

    static String sha256(Path f) throws IOException {
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(f)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * The acknowledged breaks, newest first, walking back through each break's replaced anchor file — which must
     * still exist with the digest the break recorded. Fills {@code breaks}; returns the first problem, or null.
     */
    static AuditVerifier.Bad priorEpochs(AnchorFile file, List<Map<String, Object>> breaks) {
        AnchorFile cur = file;
        for (int guard = 0; guard < 1000 && cur.root() != null && !cur.anchors().isEmpty()
                && cur.anchors().get(0).isBreak(); guard++) {
            Anchor b = cur.anchors().get(0);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("at", b.firstSeq());
            m.put("day", b.day());
            m.put("reason", b.extra().get("reason"));
            m.put("problem", b.extra().get("problem"));
            m.put("lastGoodSeq", b.extra().get("lastGoodSeq"));
            m.put("by", b.extra().get("by"));
            m.put("replacedFile", b.extra().get("replacedFile"));
            breaks.add(m);
            Object name = b.extra().get("replacedFile");
            if (name == null) return null;
            Path prior = file(cur.root()).resolveSibling(name.toString());
            try {
                if (!Files.isRegularFile(prior))
                    return new AuditVerifier.Bad(b.firstSeq(), "prior-epoch-missing", "the anchor file the break at seq "
                            + b.firstSeq() + " replaced (" + name + ") is gone");
                if (!sha256(prior).equals(b.extra().get("replacedSha256")))
                    return new AuditVerifier.Bad(b.firstSeq(), "prior-epoch-altered", "the anchor file the break at seq "
                            + b.firstSeq() + " replaced (" + name + ") no longer has the digest the break recorded");
                cur = readFileAt(cur.root(), prior, null);
            } catch (IOException e) {
                return new AuditVerifier.Bad(b.firstSeq(), "prior-epoch-missing", "could not read " + name + ": "
                        + e.getMessage());
            }
        }
        return null;
    }

    /** Test seam: forget the rebaseline rate limit for {@code root}. */
    static void resetRebaselineLimit(Path root) {
        LAST_REBASELINE.remove(root.toAbsolutePath().normalize());
    }

    /** Create the file owner-only (POSIX {@code rw-------}, else an owner-only ACL) — the key's own permissions.
     *  With {@code mustBeNew}, an existing file is a {@code FileAlreadyExistsException} (first writer wins). */
    private static void createOwnerOnly(Path f, boolean mustBeNew) throws IOException {
        if (!mustBeNew && Files.exists(f)) return;
        boolean posix = f.getFileSystem().supportedFileAttributeViews().contains("posix");
        try {
            if (posix) {
                Files.createFile(f, java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                        java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")));
            } else {
                Files.createFile(f);
                com.gamma.util.SpaceSecretKeys.ownerOnlyAcl(f);
            }
        } catch (java.nio.file.FileAlreadyExistsException raced) {
            if (mustBeNew) throw raced;
            // another writer created it; it did so owner-only too
        }
    }

    private static Map<String, Object> fields(String kind, String day, long firstSeq, long lastSeq, String lastHash,
                                              long count, long createdAt, String prevAnchorMac) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("v", 1);
        m.put("kind", kind);
        m.put("day", day);
        m.put("firstSeq", firstSeq);
        m.put("lastSeq", lastSeq);
        m.put("lastHash", lastHash);
        m.put("count", count);
        m.put("createdAt", createdAt);
        m.put("prevAnchorMac", prevAnchorMac);
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

    static LocalDate day(Event e) {
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
