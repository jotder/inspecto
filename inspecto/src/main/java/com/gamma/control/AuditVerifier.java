package com.gamma.control;

import com.gamma.event.AuditChain;
import com.gamma.event.Event;
import com.gamma.event.EventStore;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Recomputes a Space's audit hash chain ({@link AuditChain}) over a seq range and names the FIRST bad record
 * (ASSURE-AUDIT-CHAIN-1). Streams the chain in {@link #PAGE}-record pages from {@link EventStore#chainPage}, holding
 * one page and one record of look-ahead — never the range.
 *
 * <h3>Checks, per record, in this order (the first failure stops the walk)</h3>
 * <ol>
 *   <li>{@code duplicate} — a second record claims a seq already seen (an inserted forgery, or a replayed row);</li>
 *   <li>{@code gap} — the next seq present is past the expected one: a record inside the chain is gone;</li>
 *   <li>{@code reorder} — the record is later in time than the record after it (or earlier than the one before):
 *       an honest chain never goes back in time, because linking raises a record's ts to its predecessor's;</li>
 *   <li>{@code broken-link} — its prevHash is not the previous record's hash (a record before it was replaced);</li>
 *   <li>{@code hash-mismatch} — the hash recomputed from its fields is not the one it carries (it was edited);</li>
 *   <li>{@code anchor-mismatch} — an anchor ending at this seq has a MAC that does not verify, or names a
 *       different hash, first seq or count than the chain holds;</li>
 * </ol>
 * and, once the records run out, {@code missing} — an anchor covers seqs the store no longer holds (the tail was
 * truncated after it was anchored).
 *
 * <p>⚠ What this cannot see on its own: a chain REWRITTEN end to end after the last anchor (SHA-256 needs no key,
 * so anyone who can write the store can recompute it) and records deleted from the FRONT, which look exactly like
 * retention ({@code prune} drops whole old days, so the lowest retained seq is legitimately above 1 — the result
 * says {@code fromGenesis: false}). Both are what an anchor exported off the box is for.
 */
final class AuditVerifier {

    private AuditVerifier() {}

    /**
     * What the walk is judged against: today's UTC date, whether every finished day must be anchored (a Space
     * with a key), and the configured event retention cutoff ({@code null} when no {@code event_prune} runs).
     */
    record Policy(LocalDate today, boolean requireAnchors, LocalDate retentionCutoff) {}

    /** Records fetched per store read. */
    static final int PAGE = 1000;

    /** The first bad record, and why. */
    record Bad(long seq, String reason, String detail) {
        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("seq", seq);
            m.put("reason", reason);
            m.put("detail", detail);
            return m;
        }
    }

    /**
     * The walk's result. {@code checked} records passed; {@code lastSeq}/{@code lastHash} is the last of them;
     * {@code complete} is false when {@code maxRecords} stopped the walk before {@code to}, and {@code next} is the
     * seq to resume from.
     */
    record Result(long from, long to, long checked, boolean fromGenesis, boolean complete, Long next,
                  long lastSeq, String lastHash, int anchorsChecked, Bad bad) {
        boolean ok() { return bad == null; }

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ok", ok());
            m.put("from", from);
            m.put("to", to);
            m.put("checked", checked);
            m.put("fromGenesis", fromGenesis);
            m.put("complete", complete);
            m.put("next", next);
            m.put("lastSeq", lastSeq);
            m.put("lastHash", lastHash);
            m.put("anchorsChecked", anchorsChecked);
            m.put("firstBad", bad == null ? null : bad.toMap());
            return m;
        }
    }

    /**
     * Verify {@code [from, to]} (inclusive; {@code from = null} = the lowest seq the store holds, {@code to = null}
     * = the head, or the highest anchored seq when an anchor claims more than the store holds), walking at most
     * {@code maxRecords} records.
     *
     * @param anchors    the Space's anchors with their MAC verdict; only those whose range ends inside the walk
     *                   are checked
     * @param onGood     called with each record that passed, in seq order; returning false stops the walk there
     *                   (the anchor roll uses it to stop at today)
     */
    static Result verify(EventStore store, AuditAnchors.AnchorFile anchorFile, Long from, Long to, long maxRecords,
                         Predicate<Event> onGood, Policy policy) {
        LocalDate today = policy.today();
        boolean requireAnchors = policy.requireAnchors();
        List<AuditAnchors.Anchor> anchors = anchorFile.anchors();
        Event head = store.chainHead();
        long headSeq = head == null ? 0 : AuditChain.seq(head);
        long anchoredTo = anchors.stream().mapToLong(AuditAnchors.Anchor::lastSeq).max().orElse(0);
        List<Event> firstRec = store.chainPage(1, 1);
        long lowest = firstRec.isEmpty() ? 1 : AuditChain.seq(firstRec.get(0));
        long epoch = anchorFile.epochStart();
        long start = from != null ? from : Math.max(lowest, epoch);
        long end = to != null ? to : Math.max(headSeq, anchoredTo);
        boolean complete = true;
        Long next = null;
        if (end - start + 1 > maxRecords) {
            end = start + maxRecords - 1;
            complete = false;
            next = end + 1;
        }
        boolean fromGenesis = start == 1;
        final long s0 = start, e0 = end;
        final boolean c0 = complete;
        final Long n0 = next;
        java.util.function.Function<Bad, Result> fail =
                b -> new Result(s0, e0, 0, fromGenesis, c0, n0, s0 - 1, null, 0, b);

        // ---- the anchor FILE first: unreadable lines, MACs, the anchor chain, contiguity ----
        Bad fileBad = anchorFile.firstProblem();
        if (fileBad != null) return fail.apply(fileBad);

        // ---- a range reaching back over an acknowledged break is never ok over the gap ----
        if (epoch > 1 && start < epoch) {
            AuditAnchors.Anchor b = anchorFile.anchors().get(0);
            return fail.apply(new Bad(epoch, "acknowledged-break", "the anchors were rebaselined at seq " + epoch
                    + " on " + b.day() + " (reason: " + b.extra().get("reason") + "; problem: "
                    + b.extra().get("problem") + "); rows before it are outside the current anchor epoch"));
        }

        // ---- anchors that end BELOW the first retained record (from = the lowest): the anchored rows are gone.
        // Retention removes whole old days, so an anchor whose day is wholly before the first retained record's
        // day is what a prune leaves behind; one whose day still has retained rows was truncated.
        // Only the configured event retention (an event_prune job's retention_days) may remove anchored rows, and
        // only days before its cutoff: an anchored day at or after the cutoff — or ANY anchored day when no prune
        // is configured — whose rows are gone was truncated.
        if (from == null) {
            LocalDate cutoff = policy.retentionCutoff();
            for (AuditAnchors.Anchor a : anchors)
                if (!a.isBreak() && a.count() > 0 && (firstRec.isEmpty() || a.firstSeq() < lowest)
                        && (cutoff == null || !LocalDate.parse(a.day()).isBefore(cutoff)))
                    return fail.apply(new Bad(a.firstSeq(), "truncated-before-anchor", "the anchor for " + a.day()
                            + " covers seq " + a.firstSeq() + ".." + a.lastSeq() + " but those rows are gone; "
                            + (cutoff == null ? "no event retention is configured"
                            : "the retention cutoff is " + cutoff)));
        }

        // ---- audit rows that are NOT on the chain, written since it began: every one is a hole ----
        if (!firstRec.isEmpty()) {
            long unlinked = store.unlinkedSince(firstRec.get(0).ts());
            if (unlinked > 0)
                return fail.apply(new Bad(0, "unlinked", unlinked + " audit row(s) written since the chain began "
                        + "carry no chain link"));
        }

        // The base the first record links onto: genesis at seq 1, else the stored hash of the record before `start`.
        String expectedPrev = null;
        long prevTs = Long.MIN_VALUE;
        if (start == 1) {
            expectedPrev = AuditChain.GENESIS;
        } else {
            List<Event> before = store.chainPage(start - 1, 1);
            if (!before.isEmpty() && AuditChain.seq(before.get(0)) == start - 1) {
                expectedPrev = AuditChain.storedHash(before.get(0));
                prevTs = before.get(0).ts();
            }
        }

        List<AuditAnchors.Anchor> inRange = new ArrayList<>();
        for (AuditAnchors.Anchor a : anchors) if (a.lastSeq() >= start && a.lastSeq() <= end) inRange.add(a);
        inRange.sort(Comparator.comparingLong(AuditAnchors.Anchor::lastSeq));
        int anchorIdx = 0;
        // a day before yesterday must be anchored (the scheduled roll has had a whole day to do it)
        LocalDate mustBeAnchored = today.minusDays(1);

        long expected = start;
        long checked = 0;
        long lastSeq = start - 1;
        String lastHash = expectedPrev;
        boolean stopped = false;
        java.util.Map<String, Long> seenIds = new java.util.HashMap<>();
        Cursor records = new Cursor(store, start, end);
        Event cur = records.next();
        while (cur != null) {
            Event ahead = records.next();
            Bad bad = check(cur, ahead, expected, expectedPrev, prevTs, inRange, anchorIdx);
            long s = AuditChain.seq(cur);
            if (bad == null && seenIds.containsKey(cur.eventId()))
                bad = new Bad(s, "duplicate-event", "event " + cur.eventId() + " is stored at seq "
                        + seenIds.get(cur.eventId()) + " and again at seq " + s);
            if (bad == null && requireAnchors && s > anchoredTo && AuditAnchors.day(cur).isBefore(mustBeAnchored))
                bad = new Bad(s, "anchor-missing", "seq " + s + " is from " + AuditAnchors.day(cur)
                        + ", a finished day no anchor covers");
            if (bad != null)
                return new Result(start, end, checked, fromGenesis, complete, next, lastSeq, lastHash, anchorIdx, bad);
            seenIds.put(cur.eventId(), s);
            while (anchorIdx < inRange.size() && inRange.get(anchorIdx).lastSeq() == s) anchorIdx++;
            checked++;
            expected = s + 1;
            expectedPrev = AuditChain.storedHash(cur);
            prevTs = cur.ts();
            lastSeq = s;
            lastHash = expectedPrev;
            if (!onGood.test(cur)) {
                stopped = true;
                break;
            }
            cur = ahead;
        }
        if (!stopped && anchorIdx < inRange.size()) {
            AuditAnchors.Anchor a = inRange.get(anchorIdx);
            return new Result(start, end, checked, fromGenesis, complete, next, lastSeq, lastHash, anchorIdx,
                    new Bad(expected, "missing", "the anchor for " + a.day() + " covers seq " + a.firstSeq() + ".."
                            + a.lastSeq() + " but the trail ends at seq " + (expected - 1)));
        }
        List<String> unreadable = store.unreadableUnits();
        if (!unreadable.isEmpty())
            return new Result(start, end, checked, fromGenesis, complete, next, lastSeq, lastHash, anchorIdx,
                    new Bad(0, "unreadable-file", "the event store could not read " + unreadable
                            + "; rows in them are not verified"));
        return new Result(start, end, checked, fromGenesis, complete, next, lastSeq, lastHash, anchorIdx, null);
    }

    /**
     * The chain's records in {@code [from, end]}, in (seq, eventId) order, one page in memory at a time. A page
     * that comes back full is re-read from its LAST seq (skipping what was already yielded), so a second record
     * claiming that seq on the far side of the page boundary is still seen.
     */
    private static final class Cursor {
        private final EventStore store;
        private final long end;
        private long nextFrom;
        private long lastSeq = Long.MIN_VALUE;
        private String lastId = "";
        private final java.util.ArrayDeque<Event> buf = new java.util.ArrayDeque<>();
        private boolean exhausted;

        Cursor(EventStore store, long from, long end) {
            this.store = store;
            this.nextFrom = from;
            this.end = end;
        }

        Event next() {
            while (buf.isEmpty()) {
                if (exhausted || nextFrom > end) return null;
                List<Event> page = store.chainPage(nextFrom, PAGE);
                if (page.size() < PAGE) exhausted = true;
                for (Event e : page) {
                    if (AuditChain.seq(e) > end) {
                        exhausted = true;
                        break;
                    }
                    if (after(e)) buf.add(e);
                }
                if (!exhausted) {
                    long l = AuditChain.seq(page.get(page.size() - 1));
                    nextFrom = l == nextFrom ? l + 1 : l;   // a page of one seq alone must still advance
                }
            }
            Event e = buf.poll();
            lastSeq = AuditChain.seq(e);
            lastId = e.eventId() == null ? "" : e.eventId();
            return e;
        }

        private boolean after(Event e) {
            long s = AuditChain.seq(e);
            String id = e.eventId() == null ? "" : e.eventId();
            return s > lastSeq || (s == lastSeq && id.compareTo(lastId) > 0);
        }
    }

    /** The checks for one record, in the documented order; {@code null} when it passes. */
    private static Bad check(Event r, Event next, long expected, String expectedPrev, long prevTs,
                             List<AuditAnchors.Anchor> anchors, int anchorIdx) {
        long s = AuditChain.seq(r);
        if (s < expected || (s == expected && next != null && AuditChain.seq(next) == s))
            return new Bad(s, "duplicate", "two records claim seq " + s);
        if (s > expected)
            return new Bad(expected, "gap", expected == s - 1 ? "seq " + expected + " is absent"
                    : "seq " + expected + ".." + (s - 1) + " are absent");
        if (r.ts() < prevTs)
            return new Bad(s, "reorder", "seq " + s + " is earlier in time than seq " + (s - 1));
        if (next != null && AuditChain.seq(next) == s + 1 && next.ts() < r.ts())
            return new Bad(s, "reorder", "seq " + s + " is later in time than seq " + (s + 1));
        String prev = AuditChain.prevHash(r);
        if (prev == null || (expectedPrev != null && !expectedPrev.equals(prev)))
            return new Bad(s, "broken-link", "its prevHash is not the hash of seq " + (s - 1));
        String stored = AuditChain.storedHash(r);
        String actual = AuditChain.hash(r);
        if (stored == null || !java.security.MessageDigest.isEqual(stored.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                actual.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
            return new Bad(s, "hash-mismatch", "the record's fields no longer hash to the hash it carries");
        for (int i = anchorIdx; i < anchors.size() && anchors.get(i).lastSeq() == s; i++) {
            AuditAnchors.Anchor a = anchors.get(i);
            if (!a.macValid())
                return new Bad(s, "anchor-mismatch", "the anchor for " + a.day() + " ending at seq " + s
                        + " does not carry a valid MAC");
            if (!stored.equals(a.lastHash()))
                return new Bad(s, "anchor-mismatch", "the anchor for " + a.day() + " names a different hash for seq " + s);
            if (a.count() != a.lastSeq() - a.firstSeq() + 1)
                return new Bad(s, "anchor-mismatch", "the anchor for " + a.day() + " claims " + a.count()
                        + " records over seq " + a.firstSeq() + ".." + a.lastSeq());
        }
        return null;
    }
}
