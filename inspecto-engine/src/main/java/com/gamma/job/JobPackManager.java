package com.gamma.job;

import com.gamma.signal.Severity;
import com.gamma.pipeline.PipelineNodeType;
import com.gamma.pipeline.PipelineNodeTypes;
import com.gamma.pipeline.exec.PipelineNodeExecutor;
import com.gamma.pipeline.exec.PipelineNodeExecutors;
import com.gamma.pipeline.exec.PackRunLeases;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.security.CodeSigner;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;

/**
 * Hot-deployable Job Packs (R8, {@code docs/job-framework-design.md} §12). A Pack is a single jar
 * dropped into {@code -Djobs.packs.dir} bundling one or more providers plus their shaded deps —
 * {@link JobTypeProvider}s (SPI + {@link JobTypeMeta}), {@link ExpressionProvider}s, and since
 * 2026-08-31 {@link PipelineNodeType}s and {@link PipelineNodeExecutor}s (pipeline spec gap 7, whose
 * remaining half was exactly that a contributed node type had to sit on the classpath at boot). A pack
 * carrying only ONE of those kinds is valid; the property keeps its {@code jobs.} name so no deployment's
 * configuration changes. Each jar loads in its own parent-first
 * {@link URLClassLoader} — SPI/API types resolve from the engine, pack-private deps stay isolated — and
 * its providers register into the shared {@link JobTypeRegistry} keyed by the jar filename (the pack's
 * "owner"), so unload/reload touches only that pack's types and never a built-in.
 *
 * <p>{@link #rescan()} is the idempotent reconciler: new jar ⇒ load, removed jar ⇒ unload, changed jar
 * (content hash) ⇒ reload. It runs once at construction (before Jobs are built, so a Job authored against
 * a pack type resolves) and again on every {@link WatchService} event after a settle delay, and is also
 * reachable via {@code POST /jobs/packs/rescan}. A load failure rejects the <em>whole</em> pack (no
 * partial registration) and emits {@code job.pack.rejected}; success emits {@code job.pack.loaded}.
 *
 * <p><b>Fail-closed & scope (§12.3):</b> absent flag ⇒ feature entirely off (no dynamic code loading).
 *
 * <p><b>Trust gate T1 (parser-plugins-trust-design.md slice P1, operator D2/D3 2026-09-25):</b> a jar loads
 * ONLY when the SHA-256 of the staged bytes the loader reads is listed in the operator-owned
 * {@code -Djobs.packs.allowlist} file ({@link PackAllowlist}). Packs dir set and no allowlist configured ⇒
 * <em>every</em> jar is refused. The allowlist is re-read on every {@link #rescan()} (approve = edit the file
 * and rescan; revoke = remove the line and rescan, which unloads the pack), and boot refuses an allowlist
 * that sits inside the packs dir or under a control-plane write root (threat A3: a dir writer or a config
 * writer must not also be able to approve). ⛔ There is deliberately no API route that approves a jar.
 * {@code -Djobs.packs.requireSignature} (default off) is an additional INTEGRITY check — it rejects jars with
 * any unsigned class entry but never looks at who signed; signer anchoring ({@code -Djobs.packs.trustStore},
 * T2 / SEC-7) is not built.
 *
 * <p><b>In-flight-Run quiesce (§12.2, 2026-07-20 SHIPPED the classloader half):</b> {@link #acquireRun}/
 * {@link #releaseRun} let {@code JobService} pin a pack's active-run count for the duration of
 * {@code Job.run(ctx)}; {@link #unload} still deregisters the pack's types immediately (a reload's new
 * types register right away), but defers actually {@linkplain URLClassLoader#close() closing} the old
 * loader and deleting its staged jar copy until that count drops to zero, so a Run already executing pack
 * code never has its classloader's resources yanked out from under a lazy class-load/reflection/resource
 * read mid-run. {@code unload} also notifies an optional {@link UnloadListener} with the pack's owner key
 * right after deregistering its types, so {@code JobService} can flip any already-built authored {@code Job}
 * sourced from that pack to unavailable — a *later* Run on the same config then fails fast (REJECTED)
 * instead of running the stale cached {@code Job} instance.
 */
final class JobPackManager implements AutoCloseable, PackRunLeases.Leaser {

    private static final Logger log = LoggerFactory.getLogger(JobPackManager.class);

    /** Signals a pack transition to the space ledger; a no-op sink before the event log is wired. */
    @FunctionalInterface
    interface SignalSink { void emit(String type, Severity sev, Map<String, Object> payload); }

    /** Notified with a pack's owner key (jar filename) the moment its types are deregistered by
     *  {@link #unload}, so the caller ({@code JobService}) can flip any already-built {@link Job} instance
     *  sourced from that pack to unavailable — a later Run on the same config then fails fast instead of
     *  running whatever {@code Job} was cached at registration time. */
    @FunctionalInterface
    interface UnloadListener { void onUnload(String owner); }

    /**
     * One loaded pack: manifest identity, content hash, contributed type ids, owning loader and the
     * private {@code staged} copy the loader actually reads. We load from a staged copy so the watched-dir
     * jar stays unlocked — an admin can delete/replace it (essential on Windows, where a
     * {@link URLClassLoader} would otherwise pin the file), which is exactly what {@link #rescan()} needs
     * to detect a removal/change.
     */
    record LoadedPack(String id, String version, Path jar, String hash, List<String> types,
                      URLClassLoader loader, Path staged) {}

    private final Path dir;                         // null ⇒ feature off
    private final JobTypeRegistry registry;
    private final ExpressionRegistry expressions;
    private final SignalSink signals;
    private final UnloadListener unloadListener;      // nullable — no-op when not wired
    private final boolean requireSignature;
    /** {@code -Djobs.packs.allowlist}, absolute; {@code null} ⇒ not configured ⇒ every jar refused. */
    private final Path allowlistFile;
    /** Jars refused by the last {@link #rescan()} (jar filename → inventory row), cleared on load/removal. */
    private final Map<String, Map<String, Object>> rejectedRows = new ConcurrentHashMap<>();
    private final long settleMillis;
    private final Map<String, LoadedPack> loaded = new ConcurrentHashMap<>();   // jar filename -> pack
    /** In-flight Run count per pack (jar filename/owner key), incremented for the duration of one
     *  {@code Job.run(ctx)} built from that pack's classes. Only packs with pack-owned Jobs appear here. */
    private final Map<String, AtomicInteger> activeRuns = new ConcurrentHashMap<>();
    /** Packs whose {@link #unload} was requested while {@link #activeRuns} was still positive — their
     *  loader/staged file close is deferred to {@link #releaseRun} once the count drops to zero. */
    private final Map<String, LoadedPack> draining = new ConcurrentHashMap<>();

    private volatile boolean running;
    private WatchService watcher;
    private Thread watchThread;
    private Path stagingDir;                         // lazily created; holds the locked copies we load from
    /** Test seams around {@link #stage}: run just before / just after the watched jar is copied, so a test
     *  can swap the watched file inside the TOCTOU window. No-ops in production. */
    Runnable beforeStage = () -> {};
    Runnable afterStage = () -> {};

    JobPackManager(String packsDir, JobTypeRegistry registry, ExpressionRegistry expressions,
                   SignalSink signals) {
        this(packsDir, registry, expressions, signals, null);
    }

    JobPackManager(String packsDir, JobTypeRegistry registry, ExpressionRegistry expressions,
                   SignalSink signals, UnloadListener unloadListener) {
        this.dir = (packsDir == null || packsDir.isBlank()) ? null : Path.of(packsDir).toAbsolutePath().normalize();
        this.registry = registry;
        this.expressions = expressions;
        this.signals = signals;
        this.unloadListener = unloadListener;
        this.requireSignature = Boolean.getBoolean("jobs.packs.requireSignature");
        this.settleMillis = Long.getLong("jobs.packs.settleMillis", 500L);
        this.allowlistFile = dir == null ? null : PackAllowlist.configuredFile(dir);   // refuses boot if writable (A3)
        // S2-0: a pipeline run using a pack's node type pins the pack through the same counter as a Job run.
        if (dir != null) PackRunLeases.install(this);
    }

    boolean enabled() { return dir != null; }

    /** The loaded pack's manifest version, by owner key (jar filename) — the {@code version} a pack-owned
     *  Job Type reports as provenance (§7.3). Empty for an owner that is not a currently loaded pack. */
    Optional<String> versionOf(String owner) {
        LoadedPack pack = owner == null ? null : loaded.get(owner);
        return Optional.ofNullable(pack).map(LoadedPack::version);
    }

    /** Load the packs already present at startup (before Jobs are built). No-op when the feature is off. */
    void scanAtStartup() {
        if (!enabled()) return;
        if (!Files.isDirectory(dir)) {
            log.warn("[PACKS] jobs.packs.dir '{}' is not a directory — no Job Packs loaded", dir);
            return;
        }
        log.info("[PACKS] scanning Job Pack dir {} (allowlist={}, requireSignature={})", dir,
                allowlistFile == null ? "NOT CONFIGURED - every jar will be refused" : allowlistFile,
                requireSignature);
        rescan();
    }

    /**
     * Reconcile the registry with the packs dir: load new jars, unload removed jars, reload changed jars
     * (by content hash). Idempotent and synchronized. Returns a summary for {@code POST /jobs/packs/rescan}.
     */
    synchronized Map<String, Object> rescan() {
        List<String> loadedNow = new ArrayList<>(), reloaded = new ArrayList<>(),
                unloaded = new ArrayList<>(), rejected = new ArrayList<>();
        if (!enabled() || !Files.isDirectory(dir))
            return summary(loadedNow, reloaded, unloaded, rejected);

        Map<String, Path> present = new LinkedHashMap<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.jar")) {
            for (Path p : ds) if (Files.isRegularFile(p)) present.put(p.getFileName().toString(), p);
        } catch (IOException e) {
            log.warn("[PACKS] cannot list {}: {}", dir, e.toString());
            return summary(loadedNow, reloaded, unloaded, rejected);
        }

        // Removed jars ⇒ unload.
        for (String name : List.copyOf(loaded.keySet()))
            if (!present.containsKey(name)) { unload(name); unloaded.add(name); }
        rejectedRows.keySet().retainAll(present.keySet());

        // Re-read on EVERY rescan: approving or revoking a jar is a host-level edit of this file.
        PackAllowlist trust = PackAllowlist.read(allowlistFile);

        // New, changed, or no-longer-trusted jars ⇒ (re)load — a revoked hash unloads, then is refused.
        for (var e : present.entrySet()) {
            String name = e.getKey();
            String hash = hash(e.getValue());
            LoadedPack cur = loaded.get(name);
            if (cur != null && cur.hash().equals(hash) && trust.allows(hash)) continue;   // unchanged + trusted
            boolean isReload = cur != null;
            if (isReload) unload(name);                                // reload = unload old + load new
            if (load(name, e.getValue(), hash, trust)) (isReload ? reloaded : loadedNow).add(name);
            else rejected.add(name);
        }
        return summary(loadedNow, reloaded, unloaded, rejected);
    }

    /** Load one pack atomically: stage → hash + verify the staged bytes → discover → validate → register
     *  all, or reject the whole jar. {@code hash} is the watched jar's (the change trigger); the recorded
     *  fingerprint is re-taken from the staged copy. */
    private boolean load(String name, Path jar, String hash, PackAllowlist trust) {
        URLClassLoader loader = null;
        Path staged = null;
        try {
            // 🔴 TOCTOU: stage FIRST, then hash + verify + load exactly the staged bytes. Verifying the watched
            // jar and copying it afterwards let a jar swapped between the two load unverified. The staging
            // dir/file are owner-only (createTempDirectory/createTempFile), so the watched-dir writer cannot
            // touch the copy once it is taken.
            beforeStage.run();
            staged = stage(name, jar);                // load from a private copy; keep the watched jar unlocked
            afterStage.run();
            hash = hash(staged);                      // fingerprint of the bytes actually verified and loaded
            trust.check(hash);                        // T1: the STAGED bytes' hash must be listed, before any class loads
            if (requireSignature) verifySignature(staged);
            loader = new URLClassLoader(new URL[]{staged.toUri().toURL()}, JobPackManager.class.getClassLoader());

            List<JobTypeProvider> providers = new ArrayList<>();
            for (JobTypeProvider p : ServiceLoader.load(JobTypeProvider.class, loader))
                if (p.getClass().getClassLoader() == loader) providers.add(p);   // only this pack's own types
            // A pack may also contribute Expression tokens (job-parameter-contract §4.2, step 5) — and may
            // contribute *only* tokens, so the "empty" check below spans both kinds rather than demanding a
            // Job Type from a pack whose whole purpose is vocabulary.
            List<ExpressionProvider> exprProviders = new ArrayList<>();
            for (ExpressionProvider p : ServiceLoader.load(ExpressionProvider.class, loader))
                if (p.getClass().getClassLoader() == loader) exprProviders.add(p);
            // A pack may also contribute PIPELINE node types and/or their executors (pipeline spec gap 7,
            // whose remainder was exactly that these were classpath-only). Same rules as the two above:
            // only this pack's own providers, and the "empty" check spans all four kinds so a pack whose
            // whole purpose is a node type is not made to ship a Job Type it does not have.
            List<PipelineNodeType> nodeTypes = new ArrayList<>();
            for (PipelineNodeType t : ServiceLoader.load(PipelineNodeType.class, loader))
                if (t.getClass().getClassLoader() == loader) nodeTypes.add(t);
            List<PipelineNodeExecutor> nodeExecutors = new ArrayList<>();
            for (PipelineNodeExecutor e : ServiceLoader.load(PipelineNodeExecutor.class, loader))
                if (e.getClass().getClassLoader() == loader) nodeExecutors.add(e);
            if (providers.isEmpty() && exprProviders.isEmpty() && nodeTypes.isEmpty() && nodeExecutors.isEmpty())
                throw new IllegalStateException(
                        "no JobTypeProvider, ExpressionProvider, PipelineNodeType or PipelineNodeExecutor "
                                + "in META-INF/services");

            List<String> ids = new ArrayList<>();
            for (JobTypeProvider p : providers) {
                String id = p.descriptor().id();
                if (id == null || id.isBlank())
                    throw new IllegalStateException(p.getClass().getName() + " has a blank descriptor id");
                JobTypeMeta meta = p.getClass().getAnnotation(JobTypeMeta.class);
                if (meta != null && !meta.id().equals(id))
                    throw new IllegalStateException("@JobTypeMeta id '" + meta.id()
                            + "' != descriptor id '" + id + "' in " + p.getClass().getName());
                if (registry.has(id))
                    throw new IllegalStateException("job type id '" + id + "' already registered");
                ids.add(id);
            }
            for (JobTypeProvider p : providers) registry.register(p, name);   // owner = jar filename
            // Expression tokens register under the same owner, so an unload takes them back with the types.
            // ExpressionRegistry.register is itself fail-closed on a colliding token, and the catch below
            // deregisters both registries — so a pack whose token clashes is rejected whole, never half-in.
            for (ExpressionProvider p : exprProviders) expressions.register(p, name);
            // Node types before executors: a type refused (a built-in discriminator, or one another pack
            // owns) must reject the pack BEFORE any executor is registered, and the catch below takes both
            // overlays back — so a pack is never half-registered into the pipeline vocabulary.
            for (PipelineNodeType t : nodeTypes) PipelineNodeTypes.register(t, name);
            for (PipelineNodeExecutor e : nodeExecutors) PipelineNodeExecutors.register(e, name);

            String[] mf = manifest(staged);
            LoadedPack pack = new LoadedPack(mf[0] != null ? mf[0] : name, mf[1] != null ? mf[1] : "?",
                    jar, hash, List.copyOf(ids), loader, staged);
            loaded.put(name, pack);
            rejectedRows.remove(name);
            log.info("[PACKS] loaded {} v{} ({}): {}", pack.id(), pack.version(), name, ids);
            signals.emit("job.pack.loaded", Severity.INFO, packPayload(pack));
            return true;
            // 🔴 LinkageError is NOT optional here. The four ServiceLoader loops above run over an
            // OPERATOR-SUPPLIED pack loader, and a pack that is present but UNLOADABLE — a class compiled
            // for a newer Java, a truncated/corrupt class, a missing shaded dependency — raises a
            // LinkageError that ServiceLoader does NOT wrap in ServiceConfigurationError: it comes straight
            // out of hasNext(). Catching only Exception|ServiceConfigurationError let that Error escape
            // load(), escape rescan()'s per-pack loop, and so kill discovery of EVERY OTHER pack — and at
            // startup the whole boot. Catching it here restores the blast radius the design intends: one
            // bad jar rejects itself. ⛔ Not a bare Error: OutOfMemoryError/StackOverflowError must escape.
        } catch (Exception | ServiceConfigurationError | LinkageError ex) {
            registry.deregister(name);                                  // roll back any partial registration
            expressions.deregister(name);                               // all four registries, or the pack
            PipelineNodeTypes.deregister(name);                         // half-loads — a refused node type
            PipelineNodeExecutors.deregister(name);                     // must not leave an executor behind
            if (loader != null) try { loader.close(); } catch (IOException ignore) { /* best effort */ }
            if (staged != null) try { Files.deleteIfExists(staged); } catch (IOException ignore) { /* best effort */ }
            log.warn("[PACKS] rejected {}: {}", name, ex.toString());
            String cause = String.valueOf(ex.getMessage());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("file", name);
            row.put("hash", hash);
            row.put("state", "rejected");
            row.put("cause", cause);
            rejectedRows.put(name, row);
            signals.emit("job.pack.rejected", Severity.WARN, Map.of("file", name, "hash", hash, "cause", cause));
            return false;
        }
    }

    /** Deregister a pack's types immediately, but only close its loader (release the jar file handle)
     *  once no Run is still executing its code — see {@link #acquireRun}/{@link #releaseRun}. */
    private void unload(String name) {
        LoadedPack pack = loaded.remove(name);
        if (pack == null) return;
        List<String> removed = registry.deregister(name);
        List<String> removedTokens = expressions.deregister(name);
        // ⚠ A stored pipeline naming an unloaded node type stops loading — the same exposure a Job typed
        // on an unloaded pack already has, which is why a pack is normally REPLACED rather than removed.
        PipelineNodeTypes.deregister(name);
        PipelineNodeExecutors.deregister(name);
        log.info("[PACKS] unloaded {} ({}): {}{}", pack.id(), name, removed,
                removedTokens.isEmpty() ? "" : " + tokens " + removedTokens);
        signals.emit("job.pack.unloaded", Severity.INFO, packPayload(pack));
        if (unloadListener != null) unloadListener.onUnload(name);
        closeOrDefer(name, pack);
    }

    /** Close a pack's loader/staged copy now, or — if a Run is still in flight on it — mark it draining
     *  so {@link #releaseRun} finishes the close once that Run completes. */
    private void closeOrDefer(String name, LoadedPack pack) {
        AtomicInteger count = activeRuns.get(name);
        if (count != null && count.get() > 0) {
            draining.put(name, pack);
            log.info("[PACKS] deferring classloader close for {} ({} in-flight run(s))", name, count.get());
            return;
        }
        try { pack.loader().close(); } catch (IOException ignore) { /* best effort */ }
        try { Files.deleteIfExists(pack.staged()); } catch (IOException ignore) { /* best effort */ }
    }

    /** Pin {@code owner}'s (a pack's jar filename) active-run count for the duration of one Run's
     *  {@code Job.run(ctx)} — call {@link #releaseRun} in a {@code finally}. No-op for {@code null}
     *  (built-in/permanent job types have no owning pack). Also called by {@link PackRunLeases} for the
     *  duration of a pipeline run that uses one of the pack's node types (S2-0). */
    @Override
    public void acquireRun(String owner) {
        if (owner == null) return;
        activeRuns.computeIfAbsent(owner, k -> new AtomicInteger()).incrementAndGet();
    }

    /** The counterpart to {@link #acquireRun}: when the count drops to zero, finish closing a pack whose
     *  unload was deferred while this Run (or a sibling) was still executing. No-op for {@code null}. */
    @Override
    public void releaseRun(String owner) {
        if (owner == null) return;
        AtomicInteger count = activeRuns.get(owner);
        if (count == null) return;
        if (count.decrementAndGet() <= 0) {
            LoadedPack pending = draining.remove(owner);
            if (pending != null) {
                try { pending.loader().close(); } catch (IOException ignore) { /* best effort */ }
                try { Files.deleteIfExists(pending.staged()); } catch (IOException ignore) { /* best effort */ }
                log.info("[PACKS] closed deferred classloader for {} (last in-flight run finished)", owner);
            }
        }
    }

    /** Start watching the dir; each settled batch of changes triggers a {@link #rescan()}. No-op when off. */
    void startWatching() {
        if (!enabled() || !Files.isDirectory(dir)) return;
        try {
            watcher = FileSystems.getDefault().newWatchService();
            dir.register(watcher, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE);
        } catch (IOException e) {
            log.warn("[PACKS] cannot watch {} — packs are load-once at startup: {}", dir, e.toString());
            return;
        }
        running = true;
        watchThread = new Thread(this::watchLoop, "job-packs-watch");
        watchThread.setDaemon(true);
        watchThread.start();
    }

    private void watchLoop() {
        while (running) {
            WatchKey key;
            try {
                key = watcher.take();
            } catch (InterruptedException | ClosedWatchServiceException e) {
                return;
            }
            boolean any = !key.pollEvents().isEmpty();
            key.reset();
            if (!any) continue;
            try {
                Thread.sleep(settleMillis);                              // settle: let the jar finish copying
            } catch (InterruptedException e) {
                return;
            }
            WatchKey k;                                                 // drain events that arrived while settling
            while ((k = watcher.poll()) != null) { k.pollEvents(); k.reset(); }
            try {
                rescan();
            } catch (RuntimeException e) {
                log.warn("[PACKS] rescan failed: {}", e.toString());
            }
        }
    }

    /** Whether {@code name}'s pack is unloaded-but-not-yet-closed, pinned open by an in-flight Run
     *  (test/introspection only — not on any HTTP surface). */
    boolean isDraining(String name) { return draining.containsKey(name); }

    /** Pack inventory for {@code GET /jobs/packs}: every loaded pack (id, version, file, hash, types,
     *  {@code state: loaded}) plus every jar the last rescan refused (file, hash, {@code state: rejected},
     *  cause) - so the audit trail shows what was turned away and why, not only what runs. */
    List<Map<String, Object>> inventory() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (LoadedPack p : loaded.values()) out.add(packPayload(p));
        out.addAll(rejectedRows.values());
        return out;
    }

    @Override
    public synchronized void close() {
        running = false;
        PackRunLeases.uninstall(this);
        if (watchThread != null) watchThread.interrupt();
        if (watcher != null) try { watcher.close(); } catch (IOException ignore) { /* best effort */ }
        for (LoadedPack p : loaded.values()) {
            try { p.loader().close(); } catch (IOException ignore) { /* best effort */ }
            try { Files.deleteIfExists(p.staged()); } catch (IOException ignore) { /* best effort */ }
        }
        loaded.clear();
        // Process is going down regardless of any Run still in flight — close deferred packs too rather
        // than leaking their staged jar copies.
        for (LoadedPack p : draining.values()) {
            try { p.loader().close(); } catch (IOException ignore) { /* best effort */ }
            try { Files.deleteIfExists(p.staged()); } catch (IOException ignore) { /* best effort */ }
        }
        draining.clear();
        activeRuns.clear();
        if (stagingDir != null) try { Files.deleteIfExists(stagingDir); } catch (IOException ignore) { /* best effort */ }
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────────

    private static Map<String, Object> packPayload(LoadedPack p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.id());
        m.put("version", p.version());
        m.put("file", p.jar().getFileName().toString());
        m.put("hash", p.hash());
        m.put("types", p.types());
        m.put("state", "loaded");
        return m;
    }

    private static Map<String, Object> summary(List<String> loaded, List<String> reloaded,
                                                List<String> unloaded, List<String> rejected) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("loaded", loaded);
        m.put("reloaded", reloaded);
        m.put("unloaded", unloaded);
        m.put("rejected", rejected);
        return m;
    }

    /** Copy the watched jar into a private staging dir and return the copy the loader will lock. A fresh
     *  uniquely-named file per load, so a reload never overwrites a draining pack's copy. */
    private Path stage(String name, Path jar) throws IOException {
        if (stagingDir == null) stagingDir = Files.createTempDirectory("job-packs-");
        Path dest = Files.createTempFile(stagingDir, "pack-", "-" + name);
        try {
            Files.copy(jar, dest, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Files.deleteIfExists(dest);
            throw e;
        }
        return dest;
    }

    /** SHA-256 hex of the jar's bytes — the reload trigger and the audit fingerprint. */
    private static String hash(Path jar) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(Files.readAllBytes(jar));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
            return sb.toString();
        } catch (Exception e) {
            return "?" + jar.getFileName();   // unreadable ⇒ treat as always-changed (will re-attempt/reject)
        }
    }

    private static String[] manifest(Path jar) {
        try (JarFile jf = new JarFile(jar.toFile())) {
            Manifest mf = jf.getManifest();
            if (mf == null) return new String[]{null, null};
            Attributes a = mf.getMainAttributes();
            return new String[]{a.getValue("Pack-Id"), a.getValue("Pack-Version")};
        } catch (IOException e) {
            return new String[]{null, null};
        }
    }

    /**
     * Reject a jar with any unsigned class entry (JDK jar-signature model). Reads every entry so the
     * verifier populates {@link JarEntry#getCodeSigners()}. INTEGRITY only - the trust decision is the
     * allowlist ({@link PackAllowlist}); signer anchoring against {@code -Djobs.packs.trustStore} (T2) is not built.
     */
    private static void verifySignature(Path jar) throws IOException, SecurityException {
        try (JarFile jf = new JarFile(jar.toFile(), true)) {   // verify=true
            byte[] buf = new byte[8192];
            boolean anySigned = false;
            var entries = jf.entries();
            while (entries.hasMoreElements()) {
                JarEntry e = entries.nextElement();
                try (InputStream in = jf.getInputStream(e)) { while (in.read(buf) != -1) { /* force verify */ } }
                if (e.isDirectory() || e.getName().startsWith("META-INF/")) continue;
                CodeSigner[] signers = e.getCodeSigners();
                if (signers == null || signers.length == 0)
                    throw new SecurityException("unsigned entry: " + e.getName());
                anySigned = true;
            }
            if (!anySigned) throw new SecurityException("jar has no signed entries");
        } catch (ServiceConfigurationError e) {
            throw new SecurityException("signature verification failed: " + e.getMessage());
        }
    }
}
