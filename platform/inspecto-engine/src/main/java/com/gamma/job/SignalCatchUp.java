package com.gamma.job;

import com.gamma.audit.Event;
import com.gamma.audit.EventQuery;
import com.gamma.audit.EventStore;
import com.gamma.audit.EventType;
import com.gamma.signal.Signal;
import com.gamma.signal.Signals;
import com.gamma.util.ToonHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.TreeSet;
import java.util.function.BiConsumer;

/**
 * Catch-up at boot for {@code on_signal} Jobs (SIGNAL-BOARD-1 SB-2b, G8, D-SB5). The Job service reacts to a Signal as
 * it is stored and holds the queued Run in memory, so a stop between the two lost the reaction. This keeps, per Job, a
 * durable <b>mark</b>: every Signal of the Job's type at or before the mark has been handled — its Run finished, or a
 * guard decided it needs none. At boot {@link #replay} reads the Space's ledger after each Job's mark and hands every
 * Signal the Job missed back to the Job service's own reaction, so the same guards apply.
 *
 * <p><b>The mark is a low-water mark.</b> Runs finish out of order, so the mark advances only up to the oldest Signal
 * whose Run is still in flight. A crash therefore replays that Signal and every later one of the Job: a Signal whose
 * Run had already finished after it may run again (at least once, never zero times). A Run that a planned stop
 * skipped or cancelled is not handled: it stays in flight, the mark stops before it, and the next boot replays it.
 *
 * <p><b>New Job, new listening.</b> A Job with no mark starts at "now" and replays no history: at boot (a Job added
 * while the server was down, or the first boot with this feature) and on a save that adds the Job, enables it or
 * changes its {@code on_signal}.
 *
 * <p><b>Age limit.</b> A missed Signal older than the Space's limit (24 hours unless {@value #SETTINGS_FILE} says
 * otherwise) is not replayed; each Job with such Signals gets one {@code job.signal.missed} Signal naming them, which
 * the Signal Board (SB-3) lists. The marks live in {@value #MARKS_FILE} in the Job audit directory.
 */
final class SignalCatchUp {

    private static final Logger log = LoggerFactory.getLogger(SignalCatchUp.class);

    static final String MARKS_FILE = "signal-marks.tsv";
    /** The per-Space setting, in the Space's config root: {@code max_age_hours: 24}. */
    static final String SETTINGS_FILE = "signal-catch-up.toon";
    static final String MAX_AGE_KEY = "max_age_hours";
    /** D-SB5: the default Run deadline ceiling ({@code job.deadlineCeilingSeconds}), 24 hours. */
    static final Duration DEFAULT_MAX_AGE = Duration.ofHours(24);
    /** The boot read stops after this many ledger rows; a longer backlog is logged and its oldest part skipped. */
    static final int MAX_SCAN_ROWS = 100_000;
    /** At most this many Signal ids are named in one {@code job.signal.missed} Signal; the count is always whole. */
    static final int MAX_NAMED_MISSED = 100;

    /** A position on the ledger: the Signal's time, then its id, so two Signals in one millisecond still order. */
    record Mark(long atMs, String signalId) implements Comparable<Mark> {
        static Mark of(Signal s) { return new Mark(s.at().toEpochMilli(), String.valueOf(s.signalId())); }
        static Mark at(Instant t) { return new Mark(t.toEpochMilli(), ""); }

        @Override public int compareTo(Mark o) {
            int c = Long.compare(atMs, o.atMs);
            return c != 0 ? c : signalId.compareTo(o.signalId);
        }
    }

    /** The missed Signals of one Job that were too old to replay. */
    record Missed(String job, String onSignal, int count, Instant oldest, Instant newest, List<String> signalIds) {}

    /** The Job service's own reaction to one Signal for one Job; the same code path as a live Signal. */
    interface Reaction { void react(JobConfig job, Signal signal); }

    private record InFlight(String job, Mark mark) {}

    private final Path file;
    private final Map<String, Mark> marks = new HashMap<>();
    private final Map<String, NavigableSet<Mark>> inFlight = new HashMap<>();
    private final Map<String, NavigableSet<Mark>> settled = new HashMap<>();
    private final Map<String, InFlight> runs = new HashMap<>();

    /** {@code auditDir} null or blank keeps the marks in memory only (the bare test constructors). */
    SignalCatchUp(String auditDir) {
        this.file = auditDir == null || auditDir.isBlank() ? null : Path.of(auditDir).resolve(MARKS_FILE);
        load();
    }

    /** The Space's age limit: {@code max_age_hours} of {@value #SETTINGS_FILE} in {@code configRoot}, a positive
     *  whole number; missing, unreadable or not positive reads as {@link #DEFAULT_MAX_AGE}. */
    static Duration maxAgeFor(Path configRoot) {
        if (configRoot == null) return DEFAULT_MAX_AGE;
        Path f = configRoot.resolve(SETTINGS_FILE);
        if (!Files.isRegularFile(f)) return DEFAULT_MAX_AGE;
        try {
            String raw = ToonHelper.opt(ToonHelper.load(f.toString()), MAX_AGE_KEY, "").trim();
            long hours = raw.isEmpty() ? 0 : Long.parseLong(raw);
            return hours > 0 ? Duration.ofHours(hours) : DEFAULT_MAX_AGE;
        } catch (Exception e) {
            log.warn("[JOB] {} could not be read ({}); the Signal catch-up age limit stays {}", f, e.toString(),
                    DEFAULT_MAX_AGE);
            return DEFAULT_MAX_AGE;
        }
    }

    // ── live bookkeeping ────────────────────────────────────────────────────────────────────────────────

    /** A guard decided {@code job} needs no Run for {@code s} (or folded it into a queued one): handled now. */
    synchronized void handled(String job, Signal s) {
        settle(job, Mark.of(s));
    }

    /** A Run {@code runId} of {@code job} was queued for {@code s}; it is handled when {@link #ended} says so. Call it
     *  before the Run is submitted, so a fast Run cannot end first. */
    synchronized void queued(String job, Signal s, String runId) {
        Mark m = Mark.of(s);
        Mark cur = marks.get(job);
        if (cur != null && m.compareTo(cur) <= 0) return;   // behind the mark: nothing to guard
        inFlight.computeIfAbsent(job, k -> new TreeSet<>()).add(m);
        runs.put(runId, new InFlight(job, m));
    }

    /** The Run {@code runId} left the worker. {@code handled} false (a planned stop skipped or cancelled it) pins its
     *  Signal in flight, so the mark never passes it and the next boot replays it. Other Run ids are ignored. */
    synchronized void ended(String runId, boolean handled) {
        InFlight f = runs.remove(runId);
        if (f == null || !handled) return;
        NavigableSet<Mark> open = inFlight.get(f.job());
        if (open != null) open.remove(f.mark());
        settle(f.job(), f.mark());
    }

    /** {@code job} was saved: a new Job, a Job just enabled, or a changed {@code on_signal} starts at {@code now};
     *  any other edit keeps its mark. */
    synchronized void saved(JobConfig previous, JobConfig current, Instant now) {
        if (!current.hasSignal()) {   // listens to no Signal: nothing to mark
            if (marks.containsKey(current.name())) removed(current.name());
            return;
        }
        boolean freshListening = previous == null || previous.enabled() != current.enabled()
                || !java.util.Objects.equals(previous.onSignal(), current.onSignal());
        if (!freshListening && marks.containsKey(current.name())) return;
        startAt(current.name(), Mark.at(now));
        save();
    }

    /** {@code job} was removed: forget its mark, so a Job saved later under the name starts at "now". */
    synchronized void removed(String job) {
        marks.remove(job);
        inFlight.remove(job);
        settled.remove(job);
        runs.values().removeIf(f -> f.job().equals(job));
        save();
    }

    /** The mark of {@code job}, or null — test visibility. */
    synchronized Mark markOf(String job) { return marks.get(job); }

    private void startAt(String job, Mark m) {
        marks.put(job, m);
        settled.remove(job);
        NavigableSet<Mark> open = inFlight.get(job);
        if (open != null) open.headSet(m, true).clear();
    }

    /** Record {@code m} handled and move the mark up to the newest handled Signal older than every one in flight. */
    private void settle(String job, Mark m) {
        Mark cur = marks.get(job);
        if (cur != null && m.compareTo(cur) <= 0) return;
        settled.computeIfAbsent(job, k -> new TreeSet<>()).add(m);
        advance(job);
    }

    private void advance(String job) {
        if (held.contains(job)) return;   // held for the boot replay: release() advances
        NavigableSet<Mark> done = settled.get(job);
        if (done == null) return;
        NavigableSet<Mark> open = inFlight.get(job);
        NavigableSet<Mark> passable = open == null || open.isEmpty() ? done : done.headSet(open.first(), false);
        if (passable.isEmpty()) return;
        Mark next = passable.last();
        passable.clear();
        marks.put(job, next);
        save();
    }

    // ── boot ────────────────────────────────────────────────────────────────────────────────────────────

    /** Jobs whose mark is held at the cut until {@link #replay} ends. */
    private final java.util.Set<String> held = new java.util.HashSet<>();

    /**
     * Call before the live subscriber attaches. A Job with no mark starts at {@code cut} (it replays no history); every
     * other enabled {@code on_signal} Job is held: its mark does not move until {@link #replay} ends, so a live Signal
     * handled before or during the replay (even one stamped just before the cut) cannot move it past the backlog. A
     * live Signal stamped before the cut may then be handled twice (at least once). The cut also sits in its in-flight
     * set as a sentinel, so the release cannot pass a Signal stamped after the cut whose Run is still going.
     */
    synchronized void hold(List<JobConfig> jobs, Instant cut) {
        Mark sentinel = Mark.at(cut);   // every backlog Signal is before it, every live one after
        for (JobConfig c : jobs) {
            if (!c.enabled() || !c.hasSignal()) continue;
            if (!marks.containsKey(c.name())) { startAt(c.name(), sentinel); continue; }   // new: replays no history
            inFlight.computeIfAbsent(c.name(), k -> new TreeSet<>()).add(sentinel);
            held.add(c.name());
        }
        save();
    }

    /** Drop the sentinels {@link #hold} placed and let each mark move up to what was handled meanwhile. */
    private synchronized void release(Instant cut) {
        Mark sentinel = Mark.at(cut);
        List<String> jobs = List.copyOf(held);
        held.clear();
        for (String job : jobs) {
            NavigableSet<Mark> open = inFlight.get(job);
            if (open != null) open.remove(sentinel);
            advance(job);
        }
    }

    /**
     * Replay what {@code jobs} missed. Reads the ledger's Signals stored after the oldest mark and before {@code cut}
     * (the moment the live subscriber was attached), oldest first, and for each enabled {@code on_signal} Job whose mark
     * is behind the Signal and whose type matches: a Signal within {@code maxAge} of {@code cut} goes to
     * {@code reaction}; an older one is counted for {@code missed}. Only the Jobs {@link #hold} held take part, and
     * their sentinels are released at the end, whatever happens.
     */
    void replay(EventStore store, List<JobConfig> jobs, Instant cut, Duration maxAge, Reaction reaction,
                BiConsumer<JobConfig, Missed> missed) {
        try {
            replayHeld(store, jobs, cut, maxAge, reaction, missed);
        } finally {
            release(cut);
        }
    }

    private void replayHeld(EventStore store, List<JobConfig> jobs, Instant cut, Duration maxAge, Reaction reaction,
                            BiConsumer<JobConfig, Missed> missed) {
        List<JobConfig> listening = new ArrayList<>();
        Mark from = null;
        synchronized (this) {
            for (JobConfig c : jobs) {
                Mark m = marks.get(c.name());
                if (!held.contains(c.name()) || m == null) continue;
                listening.add(c);
                if (from == null || m.compareTo(from) < 0) from = m;
            }
        }
        if (listening.isEmpty() || store == null) return;
        List<Signal> backlog = read(store, from.atMs(), cut.toEpochMilli());
        long oldestReplayable = cut.minus(maxAge).toEpochMilli();
        Map<String, List<Signal>> tooOld = new LinkedHashMap<>();
        int replayed = 0;
        for (Signal s : backlog) {
            Mark sm = Mark.of(s);
            for (JobConfig c : listening) {
                Mark m;
                synchronized (this) { m = marks.get(c.name()); }
                if (m == null || sm.compareTo(m) <= 0 || !Signals.matchesType(s.type(), c.onSignal())) continue;
                if (sm.atMs() < oldestReplayable) {
                    tooOld.computeIfAbsent(c.name(), k -> new ArrayList<>()).add(s);
                    handled(c.name(), s);
                } else {
                    reaction.react(c, s);
                    replayed++;
                }
            }
        }
        if (replayed > 0) log.info("[JOB] Signal catch-up: {} missed reaction(s) replayed at boot", replayed);
        for (JobConfig c : listening) {
            List<Signal> old = tooOld.get(c.name());
            if (old == null) continue;
            log.warn("[JOB] '{}' missed {} Signal(s) older than the catch-up age limit {}; not replayed",
                    c.name(), old.size(), maxAge);
            missed.accept(c, new Missed(c.name(), c.onSignal(), old.size(), old.getFirst().at(), old.getLast().at(),
                    old.stream().limit(MAX_NAMED_MISSED).map(s -> String.valueOf(s.signalId())).toList()));
        }
    }

    /** The Signals stored in {@code [fromMs, toMs)}, oldest first. The store pages newest first. */
    private static List<Signal> read(EventStore store, long fromMs, long toMs) {
        List<Signal> out = new ArrayList<>();
        int offset = 0;
        while (offset < MAX_SCAN_ROWS) {
            List<Event> page = store.query(EventQuery.builder().type(EventType.SIGNAL).from(fromMs).to(toMs - 1)
                    .limit(EventQuery.MAX_LIMIT).offset(offset).build());
            for (Event e : page) out.add(Signal.fromEvent(e));
            if (page.size() < EventQuery.MAX_LIMIT) break;
            offset += page.size();
        }
        if (offset >= MAX_SCAN_ROWS)
            log.warn("[JOB] Signal catch-up read stopped at {} ledger rows; older missed Signals are not replayed",
                    MAX_SCAN_ROWS);
        out.sort(Comparator.comparing(Mark::of));
        return out;
    }

    // ── file ────────────────────────────────────────────────────────────────────────────────────────────

    /** {@code job<TAB>atMs<TAB>signalId} per line. An unreadable file reads as no marks: every Job starts at "now". */
    private void load() {
        if (file == null || !Files.isRegularFile(file)) return;
        try {
            for (String line : Files.readAllLines(file)) {
                String[] p = line.split("\t", -1);
                if (p.length != 3 || p[0].isEmpty()) continue;
                marks.put(p[0], new Mark(Long.parseLong(p[1]), p[2]));
            }
        } catch (java.io.IOException | RuntimeException e) {
            marks.clear();
            log.warn("[JOB] Signal marks {} could not be read ({}); every Job starts at now", file, e.toString());
        }
    }

    /** Temp file, then an atomic move; a failure is a WARN, never thrown into a reaction. Caller holds the monitor. */
    private void save() {
        if (file == null) return;
        try {
            List<String> lines = new ArrayList<>();
            marks.forEach((job, m) -> lines.add(job + '\t' + m.atMs() + '\t' + m.signalId()));
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(tmp, lines);
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.io.IOException | RuntimeException e) {
            log.warn("[JOB] Signal marks {} could not be written ({}); a restart may replay or miss", file, e.toString());
        }
    }
}
