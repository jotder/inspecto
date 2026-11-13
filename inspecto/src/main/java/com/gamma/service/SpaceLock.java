package com.gamma.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.gamma.config.io.ConfigLoader;
import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.LinkGraph;
import com.gamma.util.AtomicFiles;
import com.gamma.util.SpaceSecretKeys;
import com.gamma.util.ToonHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;

/**
 * SE-1a — the Space lock ({@code docs/okf/product/module/config/spec-space-lock.md}): a derived, signed record
 * of every live config in a hosted Space's {@code config/} tree (path, kind, id, permanent uid, content hash)
 * and of every link {@link LinkGraph} resolves, at {@code <space>/.inspecto/space.lock}.
 *
 * <p><b>Report-only.</b> Nothing here refuses, enforces or changes a load. A missing, unreadable or badly signed
 * lock is recomputed with no baseline, never an error. Computation runs on one shared daemon thread: scheduled
 * after a Space starts ({@link #afterBoot}), after a successful server write ({@link #writeEnded}, debounced) and on
 * the first read ({@link #report}). Kill switch {@code -Dspace.integrity=off}.
 */
public final class SpaceLock {

    private static final Logger log = LoggerFactory.getLogger(SpaceLock.class);

    /** The kill switch: {@code off} disables every computation and write; anything else (default) is on. */
    public static final String PROPERTY = "space.integrity";

    /** The lock's folder and name, beside {@code config/} — never under the config root, so no loader reads it. */
    static final String DIR = ".inspecto";
    static final String NAME = "space.lock";

    /** The Space's Pending Change key file name ({@code PendingChanges.KEY_FILE}; pinned equal by a test). */
    public static final String KEY_FILE = ".pending-changes.key";

    static final int FORMAT = 1;
    static final String MAC_DOMAIN = "space-lock\n";
    /** Debounce: a burst of writes is one computation, this long after the last of them. */
    static final long DEBOUNCE_MS = 1500;
    /** A burst may postpone a computation at most this long, so steady write traffic still gets one. */
    static final long MAX_WAIT_MS = 10_000;
    /** Slack around a write window for file systems with coarse modification times. */
    static final long SLACK_MS = 2000;
    /** Most items a report list or the {@code removedOutside} history holds. */
    static final int CAP = 200;
    /** How long closing a Space waits for a running computation before skipping the close-time flush. */
    static final long FLUSH_BUDGET_MS = 2000;

    /** SE-2a kill switch for the opt-in hand-rename rewrite: {@code off} rewrites nothing anywhere (the report
     *  stays); anything else (default) honours a Space's opt-in. */
    public static final String RENAME_REWRITE_PROPERTY = "space.rename.rewrite";
    /** The {@code space.toon} key by which one Space opts in to the hand-rename rewrite (D-SE3). Off by default. */
    public static final String RENAME_REWRITE_KEY = "rename_rewrite";

    /** Suffix → kind, the loader's suffix-named configs (Space anatomy §4.2). */
    static final Map<String, String> SUFFIX_KINDS = suffixKinds();

    private static Map<String, String> suffixKinds() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("_pipeline.toon", "pipeline");
        m.put("_schema.toon", "schema");
        m.put("_enrich.toon", "enrichment");
        m.put("_job.toon", "job");
        m.put("_job_template.toon", "job-template");
        m.put("_connection.toon", "connection");
        m.put("_grammar.toon", "grammar");
        m.put(".grammar.toon", "grammar");
        m.put("_profile.toon", "decode-profile");
        m.put("_structure.csv", "structure");
        m.put("_mapping.csv", "mapping");
        m.put("_meta.toon", "semantic-model");
        m.put("_rca.toon", "rca-template");
        m.put("_tag.toon", "tag");
        m.put("_tagrule.toon", "tag-rule");
        m.put("_workflow.toon", "workflow");
        m.put("_caserule.toon", "case-rule");
        m.put("_view.toon", "view");
        m.put("_snapshot.toon", "snapshot-import");
        return Map.copyOf(m);
    }

    /** Kinds whose id is declared inside the file; the section their keys sit in ({@code null} = the root). */
    private static final Map<String, String> DECLARED_ID_SECTION = Map.of(
            "pipeline", "", "job", "job", "enrichment", "", "connection", "connection");

    private static final ObjectMapper CANONICAL =
            new ObjectMapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    static final ObjectMapper PRETTY = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private SpaceLock() {}

    public static boolean enabled() {
        return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on").trim());
    }

    /** Where the lock of the Space whose config root is {@code configRoot} lives. */
    public static Path file(Path configRoot) {
        return configRoot.toAbsolutePath().normalize().getParent().resolve(DIR).resolve(NAME);
    }

    // ── scheduling ──────────────────────────────────────────────────────────────────────────

    private static final ScheduledThreadPoolExecutor EXEC = executor();

    private static ScheduledThreadPoolExecutor executor() {
        ScheduledThreadPoolExecutor e = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "space-lock");
            t.setDaemon(true);
            return t;
        });
        e.setRemoveOnCancelPolicy(true);
        return e;
    }

    /** A server write in progress or finished: its start and end (0 while open), in epoch millis. */
    public static final class Window {
        final long start = System.currentTimeMillis();
        volatile long end;

        boolean covers(long mtime, long now) {
            long e = end == 0 ? now : end;
            return mtime >= start - SLACK_MS && mtime <= e + SLACK_MS;
        }
    }

    static final class State {
        final String spaceId;
        final Path configRoot;
        final ReentrantLock computing = new ReentrantLock();
        final List<Window> windows = new ArrayList<>();
        ScheduledFuture<?> pending;
        long firstAsk;   // when the oldest request still waiting on the pending run was made; 0 = none
        boolean scheduledOnce;
        volatile boolean closed;
        volatile Report last;

        State(String spaceId, Path configRoot) {
            this.spaceId = spaceId;
            this.configRoot = configRoot;
        }
    }

    private static final Map<Path, State> STATES = new ConcurrentHashMap<>();
    /** Config roots of Spaces closed by {@link #forget} and not started again: no new state is made for them, so a
     *  request still bound to a deleted Space cannot recreate its folders. */
    private static final Set<Path> FORGOTTEN = ConcurrentHashMap.newKeySet();

    /** The state of {@code configRoot}; {@code null} once the Space was closed and not started again. */
    static State state(String spaceId, Path configRoot) {
        Path key = configRoot.toAbsolutePath().normalize();
        if (FORGOTTEN.contains(key)) return null;
        return STATES.computeIfAbsent(key, k -> new State(spaceId, k));
    }

    /** A Space has started: compute its lock in the background. The boot path only schedules. */
    public static void afterBoot(String spaceId, Path configRoot) {
        if (configRoot == null || !enabled()) return;
        FORGOTTEN.remove(configRoot.toAbsolutePath().normalize());   // started (again): tracked from now on
        State s = state(spaceId, configRoot);
        if (s != null) schedule(s);
    }

    /** A mutating request bound to this Space begins; {@code null} when there is nothing to track. */
    public static Window writeStarted(String spaceId, Path configRoot) {
        if (configRoot == null || !enabled()) return null;
        State s = state(spaceId, configRoot);
        if (s == null) return null;
        Window w = new Window();
        synchronized (s) {
            s.windows.add(w);
        }
        return w;
    }

    /** The request ended; a successful one schedules a (debounced) computation, a failed one wrote nothing. */
    public static void writeEnded(Path configRoot, Window w, boolean succeeded) {
        if (configRoot == null || w == null) return;
        State s = STATES.get(configRoot.toAbsolutePath().normalize());
        if (s == null) return;
        if (!succeeded) {
            synchronized (s) {
                s.windows.remove(w);
            }
            return;
        }
        w.end = System.currentTimeMillis();
        schedule(s);
    }

    private static void schedule(State s) {
        synchronized (s) {
            if (s.closed) return;
            long now = System.currentTimeMillis();
            boolean waiting = s.pending != null && !s.pending.isDone();
            // steady traffic must not postpone the computation for ever: past MAX_WAIT_MS the pending run stands
            if (waiting && s.firstAsk != 0 && now - s.firstAsk >= MAX_WAIT_MS) return;
            if (waiting) s.pending.cancel(false);
            if (!waiting || s.firstAsk == 0) s.firstAsk = now;
            s.scheduledOnce = true;
            s.pending = EXEC.schedule(() -> run(s), DEBOUNCE_MS, TimeUnit.MILLISECONDS);
        }
    }

    private static void run(State s) {
        if (s.closed || !enabled()) return;
        s.computing.lock();
        try {
            if (s.closed) return;
            computeNow(s);
        } finally {
            s.computing.unlock();
        }
    }

    /** One computation over the windows recorded so far; only the windows already ENDED when it began are
     *  consumed — a write that ends while the scan runs is still covered by the next computation. Caller holds
     *  {@code s.computing}. */
    static void computeNow(State s) {
        List<Window> taken, ended = new ArrayList<>();
        synchronized (s) {
            s.firstAsk = 0;
            taken = List.copyOf(s.windows);
            for (Window w : taken) if (w.end != 0) ended.add(w);
        }
        try {
            s.last = compute(s.spaceId, s.configRoot, taken, () -> s.closed);
            rewriteRenames(s);
        } catch (RuntimeException | IOException ex) {
            log.warn("[SPACE-LOCK] could not compute the lock of Space '{}': {}", s.spaceId, ex.toString());
            s.last = Report.failed(ex.toString());
        }
        synchronized (s) {
            s.windows.removeAll(ended);
        }
    }

    /**
     * The Space is closing (called after its service closed, so the store drain keeps its whole close budget): no new
     * state is made for this root until it starts again, its pending computation is cancelled, and a server write not
     * yet in a lock is flushed — dropped, its window would be lost and the next boot would report the file as changed
     * outside the server. A running computation is waited for at most {@link #FLUSH_BUDGET_MS}; past that the flush is
     * skipped and logged. A computation still running sees {@code closed} and writes nothing. Never throws.
     */
    public static void forget(Path configRoot) {
        if (configRoot == null) return;
        Path key = configRoot.toAbsolutePath().normalize();
        FORGOTTEN.add(key);
        State s = STATES.remove(key);
        if (s == null) return;
        synchronized (s) {
            s.closed = true;
            if (s.pending != null) s.pending.cancel(false);
        }
        try {
            boolean unrecorded;
            synchronized (s) {
                unrecorded = s.windows.stream().anyMatch(w -> w.end != 0);
            }
            if (!unrecorded || !enabled()) return;
            if (!s.computing.tryLock(FLUSH_BUDGET_MS, TimeUnit.MILLISECONDS)) {
                log.warn("[SPACE-LOCK] Space '{}' closed while its lock was computing; the close-time flush was skipped "
                        + "after {} ms, so the next start may report its last server writes as changed outside the "
                        + "server", s.spaceId, FLUSH_BUDGET_MS);
                return;
            }
            try {
                List<Window> taken;
                synchronized (s) {
                    taken = List.copyOf(s.windows);
                }
                compute(s.spaceId, s.configRoot, taken, () -> false);   // gone folder: compute writes nothing
            } finally {
                s.computing.unlock();
            }
        } catch (IOException ex) {
            log.warn("[SPACE-LOCK] could not flush the lock of Space '{}' at close: {}", s.spaceId, ex.toString());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException ex) {
            log.warn("[SPACE-LOCK] could not flush the lock of Space '{}' at close: {}", s.spaceId, ex.toString());
        }
    }

    /** Tests: block the lock's thread until the returned action runs, so nothing computes meanwhile. */
    static Runnable holdThreadForTest() {
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch held = new java.util.concurrent.CountDownLatch(1);
        EXEC.execute(() -> {
            held.countDown();
            try {
                release.await();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        });
        try {
            held.await();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        return release::countDown;
    }

    /** Tests: the computing lock of a tracked Space ({@code null} when untracked). */
    static ReentrantLock computingForTest(Path configRoot) {
        State s = STATES.get(configRoot.toAbsolutePath().normalize());
        return s == null ? null : s.computing;
    }

    /** The last report; {@code computing} until the first is ready (and the first read schedules one). */
    public static Report report(String spaceId, Path configRoot) {
        if (!enabled()) return Report.of("disabled");
        State s = state(spaceId, configRoot);
        if (s == null) return Report.of("closed");
        boolean pending;
        synchronized (s) {
            if (!s.scheduledOnce) schedule(s);
            pending = s.pending != null && !s.pending.isDone();
        }
        Report r = s.last;
        if (r == null) return Report.of("computing");
        return pending ? r.recomputing() : r;
    }

    /**
     * SE-1b: the last report of a Space this JVM tracks, without making state or scheduling anything — {@code null}
     * when the kill switch is off, the root is untracked (the single-tenant default Space, a closed Space) or no
     * report is ready yet. The activation holds read it on every scheduler tick.
     */
    public static Report latest(Path configRoot) {
        if (configRoot == null || !enabled()) return null;
        State s = STATES.get(configRoot.toAbsolutePath().normalize());
        return s == null ? null : s.last;
    }

    /** SE-1b: schedule a (debounced) computation of a tracked Space, so a hand fix or a hand edit is seen with no
     *  server write. No-op for an untracked or closed Space. */
    public static void refresh(Path configRoot) {
        if (configRoot == null || !enabled()) return;
        State s = STATES.get(configRoot.toAbsolutePath().normalize());
        if (s != null) schedule(s);
    }

    // ── computation ─────────────────────────────────────────────────────────────────────────

    /** The route's answer: a status and, when ready, the findings; {@code facts} feed the activation holds (SE-1b). */
    public record Report(String status, Map<String, Object> body, ActivationFacts facts) {
        Report(String status, Map<String, Object> body) {
            this(status, body, ActivationFacts.NONE);
        }

        static Report of(String status) {
            return new Report(status, Map.of("status", status));
        }

        static Report failed(String error) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("status", "failed");
            m.put("error", error);
            return new Report("failed", m);
        }

        Report recomputing() {
            Map<String, Object> m = new LinkedHashMap<>(body);
            m.put("recomputing", true);
            return new Report(status, m, facts);
        }
    }

    /** One live config as scanned now. */
    private record Scanned(String path, String kind, String id, String hash, long mtime) {}

    /**
     * Compute the lock of {@code configRoot} now, write it when its content changed, and return the report.
     * {@code windows} are the server writes since the previous computation (empty at boot).
     */
    public static Report compute(String spaceId, Path configRoot, List<Window> windows) throws IOException {
        return compute(spaceId, configRoot, windows, () -> false);
    }

    /**
     * {@link #compute(String, Path, List)}, writing nothing once {@code stop} answers true (the Space closed while
     * this ran) and nothing at all for a config root that no longer exists (a deleted Space): no lock, no
     * {@code .inspecto/}, no key folder is ever recreated.
     */
    static Report compute(String spaceId, Path configRoot, List<Window> windows, BooleanSupplier stop) throws IOException {
        Path root = configRoot.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) return Report.of("absent");
        Path lockFile = file(root);
        long now = System.currentTimeMillis();
        String nowIso = Instant.ofEpochMilli(now).toString();

        String[] note = {null};
        Map<String, Object> prev = readVerified(root, lockFile, note);

        List<Scanned> scanned = scan(root);
        Map<String, Map<String, Object>> prevByPath = new HashMap<>();
        Map<String, List<Map<String, Object>>> prevByHash = new HashMap<>();
        if (prev != null) for (Map<String, Object> f : maps(prev.get("files"))) {
            prevByPath.put(str(f.get("path")), f);
            prevByHash.computeIfAbsent(str(f.get("hash")), k -> new ArrayList<>()).add(f);
        }
        Set<String> livePaths = new HashSet<>();
        for (Scanned sc : scanned) livePaths.add(sc.path());
        boolean anyWindow = !windows.isEmpty();

        Set<String> usedUids = new HashSet<>();
        List<Map<String, Object>> files = new ArrayList<>();
        List<Map<String, Object>> renamesFound = new ArrayList<>();
        for (Scanned sc : scanned) {
            Map<String, Object> before = prevByPath.get(sc.path());
            String change = null;
            if (before == null && prev != null) {
                // moved, unchanged: a previous entry with this hash whose path is gone keeps its uid (plan §4.2)
                for (Map<String, Object> cand : prevByHash.getOrDefault(sc.hash(), List.of()))
                    if (!livePaths.contains(str(cand.get("path"))) && !usedUids.contains(str(cand.get("uid")))) {
                        before = cand;
                        change = "moved";
                        break;
                    }
                if (before == null) change = "added";
            } else if (before != null && !sc.hash().equals(str(before.get("hash")))) {
                change = "modified";
            }
            String uid = before != null && !usedUids.contains(str(before.get("uid"))) ? str(before.get("uid"))
                    : UUID.randomUUID().toString();
            usedUids.add(uid);

            Map<String, Object> f = new LinkedHashMap<>();
            f.put("path", sc.path());
            f.put("kind", sc.kind());
            f.put("id", sc.id());
            f.put("uid", uid);
            f.put("hash", sc.hash());
            Object outside = null;
            if (prev != null) {
                if (change == null) outside = before.get("outside");   // unchanged: keeps its mark
                else if (!byServer(change, sc.mtime(), windows, anyWindow, now))
                    outside = new LinkedHashMap<>(Map.of("change", change, "at", nowIso));
            }
            if (outside != null) f.put("outside", outside);
            files.add(f);
            // SE-2a: the same uid under a new id is a rename, whoever made it (a hand edit, or a move unchanged)
            if (before != null && uid.equals(str(before.get("uid"))) && sc.kind().equals(str(before.get("kind")))
                    && before.get("id") != null
                    && !sc.id().equals(str(before.get("id")))) {
                Map<String, Object> rn = new LinkedHashMap<>();
                rn.put("kind", sc.kind());
                rn.put("uid", uid);
                rn.put("from", before.get("id"));
                rn.put("to", sc.id());
                rn.put("path", sc.path());
                rn.put("at", nowIso);
                renamesFound.add(rn);
            }
        }
        List<Map<String, Object>> deletesFound = new ArrayList<>();
        if (prev != null) for (Map<String, Object> f : maps(prev.get("files"))) {
            if (usedUids.contains(str(f.get("uid")))) continue;
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("kind", f.get("kind"));
            d.put("uid", f.get("uid"));
            d.put("id", f.get("id"));
            d.put("path", f.get("path"));
            d.put("at", nowIso);
            deletesFound.add(d);
        }
        Set<String> liveClaims = new HashSet<>();
        for (Scanned sc : scanned) liveClaims.add(claim(sc.kind(), sc.id()));

        List<Map<String, Object>> removed = new ArrayList<>();
        if (prev != null) {
            for (Map<String, Object> r : maps(prev.get("removedOutside")))
                if (!livePaths.contains(str(r.get("path")))) removed.add(r);
            if (!anyWindow) for (Map<String, Object> f : maps(prev.get("files"))) {
                if (livePaths.contains(str(f.get("path"))) || usedUids.contains(str(f.get("uid")))) continue;
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("path", f.get("path"));
                r.put("kind", f.get("kind"));
                r.put("id", f.get("id"));
                r.put("uid", f.get("uid"));
                r.put("at", nowIso);
                removed.add(r);
            }
            if (removed.size() > CAP) removed = new ArrayList<>(removed.subList(removed.size() - CAP, removed.size()));
        }

        LinkGraph graph = LinkGraph.of(root, ReservedConfigPaths::reserved);
        List<Map<String, Object>> links = new ArrayList<>();
        for (LinkGraph.Edge e : graph.edges()) {
            Map<String, Object> l = new LinkedHashMap<>();
            l.put("from", e.file() == null ? null : rel(root, e.file()));
            l.put("fromKind", e.fromKind());
            l.put("fromName", e.fromName());
            l.put("key", e.key());
            l.put("value", e.value());
            l.put("toKind", e.toKind());
            l.put("status", e.status().name());
            links.add(l);
        }

        List<Map<String, Object>> renamed = identity(prev == null ? null : prev.get("renamed"), renamesFound, "from",
                graph, root, liveClaims);
        List<Map<String, Object>> deleted = identity(prev == null ? null : prev.get("deleted"), deletesFound, "id",
                graph, root, liveClaims);

        // SE-3a: per Schema its published column set, the consumers of each column and the changes since the
        // previous lock (ColumnContract). Absent under -Dcolumn.contract=off.
        ColumnContract.Section columns = null;
        if (ColumnContract.enabled()) {
            Set<String> outsidePaths = new HashSet<>();
            // marked in THIS computation (an older mark is carried by an unchanged file)
            for (Map<String, Object> f : files)
                if (f.get("outside") instanceof Map<?, ?> o && nowIso.equals(o.get("at"))) outsidePaths.add(str(f.get("path")));
            columns = ColumnContract.lockSection(root, graph, prev == null ? null : prev.get("columns"), outsidePaths,
                    nowIso, CAP);
        }

        Map<String, Object> lock = new LinkedHashMap<>();
        lock.put("format", FORMAT);
        lock.put("space", spaceId);
        lock.put("computedAt", nowIso);
        lock.put("files", files);
        lock.put("removedOutside", removed);
        lock.put("links", links);
        if (columns != null) lock.put("columns", columns.entries());

        lock.put("renamed", renamed);
        lock.put("deleted", deleted);

        String writtenAt;
        if (prev != null && sameContent(prev, lock)) {
            writtenAt = str(prev.get("computedAt"));
        } else if (stop.getAsBoolean() || !Files.isDirectory(root)) {
            return Report.of("closed");   // closed or deleted while this ran: write nothing, create no folder
        } else {
            lock.put("mac", mac(root, lock));
            Files.createDirectories(lockFile.getParent());
            AtomicFiles.write(lockFile, PRETTY.writeValueAsBytes(lock), ".lock-");
            writtenAt = nowIso;
        }
        SpaceLockEvents.emit(spaceId, prev, files, removed, links, nowIso);   // LC-4/LC-6: new findings only
        Report r = ready(nowIso, writtenAt, prev != null, note[0], files, removed, links);
        r.body().put("columnChanges", columns == null ? Map.of("status", "disabled") : columns.report());
        List<Map<String, Object>> renamedReport = new ArrayList<>(), deletedReport = new ArrayList<>();
        for (Map<String, Object> e : renamed) {
            Map<String, Object> m = new LinkedHashMap<>(e);
            m.put("referrers", referrers(graph, root, str(e.get("kind")), str(e.get("from"))));
            renamedReport.add(m);
        }
        for (Map<String, Object> e : deleted) {
            Map<String, Object> m = new LinkedHashMap<>(e);
            m.put("dependents", referrers(graph, root, str(e.get("kind")), str(e.get("id"))));
            deletedReport.add(m);
        }
        r.body().put("renamed", bounded(renamedReport));
        r.body().put("deleted", bounded(deletedReport));
        r.body().put("renameRewrite", renameRewriteMode(root));
        return r;
    }

    // ── SE-2a: hand renames and deletes ─────────────────────────────────────────────────────

    /**
     * The renames ({@code uid} kept, id changed) and deletes ({@code uid} gone) still worth reporting: the previous
     * lock's entries plus this computation's, each kept while something still names the old id and no live config of
     * that kind has taken the id again; at most {@link #CAP}, newest last.
     */
    private static List<Map<String, Object>> identity(Object prevEntries, List<Map<String, Object>> found, String idKey,
                                                      LinkGraph graph, Path root, Set<String> liveClaims) {
        List<Map<String, Object>> out = new ArrayList<>();
        List<Map<String, Object>> all = new ArrayList<>(maps(prevEntries));
        all.addAll(found);
        Set<String> seen = new HashSet<>();
        for (Map<String, Object> e : all) {
            String kind = str(e.get("kind")), old = str(e.get(idKey));
            String claim = claim(kind, old);
            if (old == null || liveClaims.contains(claim) || !seen.add(claim + "\n" + e.get("uid"))) continue;
            if (referrers(graph, root, kind, old).isEmpty()) continue;
            out.add(e);
        }
        return out.size() > CAP ? new ArrayList<>(out.subList(out.size() - CAP, out.size())) : out;
    }

    private static String claim(String kind, String id) {
        return (kind + ":" + id).toLowerCase(Locale.ROOT);
    }

    /**
     * The configs whose links still name {@code id} of {@code kind}: for a Pipeline the census links its rename
     * rewrites ({@link PipelineLinkRewrite#holders}, its store included), for any other kind the links whose target
     * kind is that kind and whose value is the id (trimmed, case-insensitive).
     */
    private static List<Map<String, Object>> referrers(LinkGraph graph, Path root, String kind, String id) {
        Map<List<Object>, Map<String, Object>> out = new LinkedHashMap<>();
        if ("pipeline".equals(kind)) {
            for (PipelineLinkRewrite.Holder h : PipelineLinkRewrite.holders(graph, id, k -> true)) {
                List<String> keys = new ArrayList<>();
                for (com.gamma.pipeline.LinkCensus.Link l : h.links()) keys.add(l.keyPath());
                keys.sort(null);
                out.put(List.of(h.kind(), String.valueOf(h.file()), h.name()), referrer(root, h.kind(), h.file(), h.name(), keys));
            }
        } else {
            for (LinkGraph.Edge e : graph.edges()) {
                if (!kind.equals(e.toKind()) || e.value() == null || !e.value().trim().equalsIgnoreCase(id)) continue;
                Map<String, Object> m = out.computeIfAbsent(List.of(e.fromKind(), String.valueOf(e.file()), e.fromName()),
                        k -> referrer(root, e.fromKind(), e.file(), e.fromName(), new ArrayList<>()));
                @SuppressWarnings("unchecked") List<String> keys = (List<String>) m.get("keys");
                if (!keys.contains(e.key())) keys.add(e.key());
            }
        }
        return List.copyOf(out.values());
    }

    private static Map<String, Object> referrer(Path root, String kind, Path file, String name, List<String> keys) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", kind);
        m.put("name", name);
        m.put("path", file == null ? null : rel(root, file));
        m.put("keys", keys);
        return m;
    }

    /** {@code rewrite} when this Space opted in and the kill switch allows it, {@code disabled} under the kill switch,
     *  else {@code report} (the default). */
    static String renameRewriteMode(Path configRoot) {
        if ("off".equalsIgnoreCase(System.getProperty(RENAME_REWRITE_PROPERTY, "on").trim())) return "disabled";
        return optedIn(configRoot) ? "rewrite" : "report";
    }

    /** Whether the Space's {@code space.toon} (beside {@code config/}) says {@code rename_rewrite: true}. */
    static boolean optedIn(Path configRoot) {
        Path manifest = configRoot.toAbsolutePath().normalize().getParent().resolve("space.toon");
        if (!Files.isRegularFile(manifest)) return false;
        try {
            return "true".equalsIgnoreCase(ToonHelper.opt(ToonHelper.load(manifest.toString()), RENAME_REWRITE_KEY, "").trim());
        } catch (Exception unreadable) {
            return false;   // an unreadable manifest never opts a Space in
        }
    }

    /** What the opt-in rewrite did for one rename: holders rewritten, or the governed ones that stopped it. */
    public record RewriteResult(int rewritten, List<String> held) {}

    /** The census rewrite of a renamed Pipeline's dependents, installed by the control plane (the rename route's). */
    @FunctionalInterface
    public interface Rewriter {
        RewriteResult rewrite(Path configRoot, String oldId, String newId);
    }

    private static volatile Rewriter rewriter;

    /** Install the rename route's census rewrite; until then an opted-in Space is only reported. */
    public static void installRewriter(Rewriter r) {
        rewriter = r;
    }

    /**
     * After a computation of an opted-in Space: rewrite the dependents of each Pipeline rename it reports, inside a
     * server write window (so the rewritten files are not reported as outside edits), then recompute. The result is
     * recorded on the report's rename entry and logged. Caller holds {@code s.computing}.
     */
    @SuppressWarnings("unchecked")
    private static void rewriteRenames(State s) {
        Report r = s.last;
        Rewriter rw = rewriter;
        if (rw == null || r == null || s.closed || !"rewrite".equals(r.body().get("renameRewrite"))) return;
        List<Map<String, Object>> items = (List<Map<String, Object>>) ((Map<String, Object>) r.body().get("renamed")).get("items");
        boolean wrote = false;
        List<Window> opened = new ArrayList<>();
        for (Map<String, Object> e : items) {
            if (!"pipeline".equals(e.get("kind")) || ((List<?>) e.get("referrers")).isEmpty()) continue;
            String from = str(e.get("from")), to = str(e.get("to"));
            Window w = new Window();
            opened.add(w);
            synchronized (s) {
                s.windows.add(w);
            }
            try {
                RewriteResult res = rw.rewrite(s.configRoot, from, to);
                e.put("rewrite", Map.of("rewritten", res.rewritten(), "held", res.held()));
                if (!res.held().isEmpty())
                    log.warn("[SPACE-LOCK] Space '{}': hand rename '{}' -> '{}' not rewritten: the approval policy holds {}",
                            s.spaceId, from, to, res.held());
                else log.info("[SPACE-LOCK] Space '{}': hand rename '{}' -> '{}' rewrote {} dependent(s)",
                        s.spaceId, from, to, res.rewritten());
                wrote |= res.rewritten() > 0;
            } catch (RuntimeException ex) {
                e.put("rewrite", Map.of("error", ex.toString()));
                log.warn("[SPACE-LOCK] Space '{}': rewrite of hand rename '{}' -> '{}' failed: {}", s.spaceId, from, to,
                        ex.toString());
            } finally {
                w.end = System.currentTimeMillis();
            }
        }
        if (wrote) schedule(s);
        else synchronized (s) {
            s.windows.removeAll(opened);   // nothing written: nothing to attribute
        }
    }

    private static boolean byServer(String change, long mtime, List<Window> windows, boolean anyWindow, long now) {
        if ("moved".equals(change)) return anyWindow;   // a move keeps its mtime: attribute it to any server write
        for (Window w : windows) if (w.covers(mtime, now)) return true;
        return false;
    }

    static Report ready(String checkedAt, String writtenAt, boolean baseline, String note,
                                List<Map<String, Object>> files, List<Map<String, Object>> removed,
                                List<Map<String, Object>> links) {
        List<Map<String, Object>> broken = new ArrayList<>();
        for (Map<String, Object> l : links) if ("DANGLING".equals(l.get("status"))) broken.add(l);

        Map<String, List<String>> claims = new TreeMap<>();
        for (Map<String, Object> f : files)
            claims.computeIfAbsent((f.get("kind") + ":" + f.get("id")).toLowerCase(Locale.ROOT), k -> new ArrayList<>())
                    .add(str(f.get("path")));
        List<Map<String, Object>> duplicates = new ArrayList<>();
        for (Map.Entry<String, List<String>> c : claims.entrySet())
            if (c.getValue().size() > 1) duplicates.add(Map.of("claim", c.getKey(), "paths", c.getValue()));

        List<Map<String, Object>> outside = new ArrayList<>();
        for (Map<String, Object> f : files)
            if (f.get("outside") instanceof Map<?, ?> o) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("path", f.get("path"));
                m.put("kind", f.get("kind"));
                m.put("id", f.get("id"));
                m.put("change", o.get("change"));
                m.put("at", o.get("at"));
                outside.add(m);
            }
        for (Map<String, Object> r : removed) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("path", r.get("path"));
            m.put("kind", r.get("kind"));
            m.put("id", r.get("id"));
            m.put("change", "removed");
            m.put("at", r.get("at"));
            outside.add(m);
        }

        Map<String, Object> b = new LinkedHashMap<>();
        b.put("status", "ready");
        b.put("checkedAt", checkedAt);
        b.put("lockWrittenAt", writtenAt);
        b.put("baseline", baseline ? "lock" : "none");
        if (!baseline && note != null) b.put("baselineNote", note);
        b.put("files", files.size());
        b.put("links", links.size());
        b.put("broken", bounded(broken));
        b.put("duplicates", bounded(duplicates));
        b.put("changedOutside", bounded(outside));
        return new Report("ready", b, ActivationFacts.of(files, links));
    }

    private static Map<String, Object> bounded(List<Map<String, Object>> items) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", items.size());
        m.put("items", items.size() > CAP ? List.copyOf(items.subList(0, CAP)) : List.copyOf(items));
        return m;
    }

    // ── the live-file set ───────────────────────────────────────────────────────────────────

    /** Every live config of {@code root}: registry components first, then suffix kinds, then fixed-name settings. */
    private static List<Scanned> scan(Path root) throws IOException {
        Map<String, Scanned> out = new TreeMap<>();
        Path registry = root.resolve("registry");
        for (ComponentRegistry.Component c : ComponentRegistry.scan(registry).all()) {
            if (c.path() == null || !Files.isRegularFile(c.path())) continue;
            String rel = rel(root, c.path());
            if (ReservedConfigPaths.reserved(rel)) continue;
            out.putIfAbsent(rel, scanned(rel, c.path(), c.type(), c.name()));
        }
        for (Map.Entry<String, String> k : SUFFIX_KINDS.entrySet())
            for (Path p : LinkGraph.configFiles(root, k.getKey(), ReservedConfigPaths::reserved)) {
                String rel = rel(root, p);
                if (out.containsKey(rel)) continue;
                out.put(rel, scanned(rel, p, k.getValue(), id(k.getValue(), k.getKey(), p)));
            }
        for (String name : ReservedConfigPaths.FILES) {
            Path p = root.resolve(name);
            if (!Files.isRegularFile(p)) continue;
            int dot = name.lastIndexOf('.');
            out.putIfAbsent(name, scanned(name, p, "setting", dot > 0 ? name.substring(0, dot) : name));
        }
        return List.copyOf(out.values());
    }

    private static Scanned scanned(String rel, Path p, String kind, String id) throws IOException {
        byte[] bytes = Files.readAllBytes(p);
        return new Scanned(rel, kind, id, sha256(bytes), Files.getLastModifiedTime(p).toMillis());
    }

    /** The id a file declares (for a Pipeline, Job, Enrichment or Connection), else its name without the suffix. */
    private static String id(String kind, String suffix, Path p) {
        String fileName = p.getFileName().toString();
        String stem = fileName.substring(0, fileName.length() - suffix.length());
        String section = DECLARED_ID_SECTION.get(kind);
        if (section == null) return stem;
        Map<String, Object> raw;
        try {
            raw = ConfigLoader.filesystem().decode(p.toString());
        } catch (Exception unreadable) {
            return stem;
        }
        if (raw == null) return stem;
        Map<?, ?> m = section.isEmpty() ? raw : raw.get(section) instanceof Map<?, ?> inner ? inner : raw;
        if (!blank(m.get("id"))) return String.valueOf(m.get("id")).trim();
        if (!blank(m.get("name"))) {
            String n = String.valueOf(m.get("name")).trim();
            return "pipeline".equals(kind) ? n.toLowerCase(Locale.ROOT).replace(' ', '_') : n;
        }
        return stem;
    }

    // ── reading, comparing and signing ──────────────────────────────────────────────────────

    /** The previous lock when it parses, has this format and verifies; else {@code null} and {@code note[0]} says why. */
    static Map<String, Object> readVerified(Path root, Path lockFile, String[] note) {
        if (!Files.isRegularFile(lockFile)) {
            note[0] = "no lock yet";
            return null;
        }
        Map<String, Object> m;
        try {
            m = CANONICAL.readValue(Files.readAllBytes(lockFile), new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (IOException | RuntimeException bad) {
            note[0] = "the lock could not be read";
            return null;
        }
        if (m == null || !(m.get("format") instanceof Number n) || n.intValue() != FORMAT) {
            note[0] = "the lock has another format";
            return null;
        }
        Object mac = m.get("mac");
        Map<String, Object> unsigned = new LinkedHashMap<>(m);
        unsigned.remove("mac");
        try {
            if (!(mac instanceof String s) || !MessageDigest.isEqual(s.getBytes(StandardCharsets.UTF_8),
                    mac(root, unsigned).getBytes(StandardCharsets.UTF_8))) {
                note[0] = "the lock's signature does not verify";
                return null;
            }
        } catch (IOException keyUnreadable) {
            note[0] = "the Space key could not be read";
            return null;
        }
        return m;
    }

    /** Whether {@code next} says what {@code prev} says, ignoring when each was computed and the signature. */
    private static boolean sameContent(Map<String, Object> prev, Map<String, Object> next) {
        Map<String, Object> a = new LinkedHashMap<>(prev), b = new LinkedHashMap<>(next);
        for (Map<String, Object> m : List.of(a, b)) {
            m.remove("computedAt");
            m.remove("mac");
        }
        return canonical(a).equals(canonical(b));
    }

    /** HMAC-SHA256 (hex) with the Space's Pending Change key over the domain prefix + canonical JSON. */
    static String mac(Path root, Map<String, Object> unsigned) throws IOException {
        try {
            javax.crypto.Mac m = javax.crypto.Mac.getInstance("HmacSHA256");
            byte[] key = SpaceSecretKeys.readOrCreate(SpaceSecretKeys.keyFile(root, KEY_FILE), "Pending Change key");
            m.init(new javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of().formatHex(m.doFinal((MAC_DOMAIN + canonical(unsigned)).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException e) {
            throw new IOException("HmacSHA256 unavailable", e);
        }
    }

    private static String canonical(Object o) {
        try {
            // round-trip through a tree so a value read back from disk and one built here serialise alike
            return CANONICAL.writeValueAsString(CANONICAL.readValue(CANONICAL.writeValueAsBytes(o), Object.class));
        } catch (IOException e) {
            throw new IllegalArgumentException("lock content is not JSON-serialisable", e);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> maps(Object o) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (o instanceof List<?> l) for (Object e : l) if (e instanceof Map<?, ?> m) out.add((Map<String, Object>) m);
        return out;
    }

    private static String rel(Path root, Path p) {
        return root.relativize(p.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static boolean blank(Object o) {
        return o == null || String.valueOf(o).isBlank();
    }
}
