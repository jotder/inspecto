package com.gamma.service;

import com.gamma.acquire.AcquisitionLedgers;
import com.gamma.config.safety.DiscoveredRoots;
import com.gamma.acquire.ConnectionRegistry;
import com.gamma.acquire.StabilityGate;
import com.gamma.event.EventLog;
import com.gamma.notify.NotificationPreferenceOverrides;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * The top-level container that hosts many isolated {@link SpaceContext spaces} in one process — the runtime
 * replacement for the single {@link CollectorService} that {@code ControlApi.main} used to build. Each space is
 * fully isolated (its own service, stores, scheduler, event log); cross-space routing of the per-process
 * singletons (event log / metric label / connection registry / stability gate / acquisition ledger) is by the
 * space MDC the execution paths set (Stage 3a).
 *
 * <p>Two boot modes:
 * <ul>
 *   <li>{@link #single(CollectorService)} — wrap one already-built service as the {@code default} space. The
 *       long-standing single-tenant / CLI path and every existing test; behaviour is unchanged.</li>
 *   <li>{@link #discover(Path)} — scan a container root ({@code -Dspaces.root}) for {@code spaces/<id>/} dirs
 *       (a dir with a {@code config/} subtree) and boot each via {@link SpaceBootstrap}, warning-and-skipping a
 *       bad one so one broken space never blocks the others.</li>
 * </ul>
 *
 * <p>In {@link #discover} mode the container root is remembered, so spaces can be {@link #create created} and
 * {@link #delete deleted} at runtime (no restart); {@link #single} mode hosts exactly one space and rejects both.
 * The {@code /spaces/{id}} request seam (ControlApi, Stage 4) routes a request to a chosen space; an un-scoped
 * request resolves {@link #current()} (the {@code default} or sole space).
 *
 * <p>{@link AutoCloseable}: {@link #close()} drains every space in turn (the existing drain-first
 * {@link CollectorService#close()}).
 */
public final class SpaceManager implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SpaceManager.class);
    private static final SpaceId DEFAULT = SpaceId.of(EventLog.DEFAULT_SPACE_ID);

    /** The convention subdirectories a space owns under its root (also what {@link SpaceRoot} addresses).
     *  Authored flows live under {@code config/flows/} (minted on first write), not a top-level dir. */
    private static final List<String> SPACE_SUBDIRS = List.of("config", "data", "audit", "duckdb");

    /** S7: max time to wait for one space's drain-and-close before abandoning it. A hung agent/DB close
     *  must never block a runtime {@link #delete} nor, via the shutdown hook, JVM exit. */
    private static final long CLOSE_DEADLINE_MS = 10_000;

    /** JOB-OUTLIVES-CLOSE-1: once {@link #CLOSE_DEADLINE_MS} lapses, the Space's Job Runs are interrupted and the close
     *  waits up to this much longer for them to stop before it is abandoned. Taken from the store flush's own budget
     *  (the same {@link #CLOSE_DEADLINE_MS}), so the worst case per Space is unchanged. */
    private static final long STOP_GRACE_MS = 5_000;

    /** The budgets in force; only {@link #closeBudgetsForTest} changes them. */
    private static volatile long closeDeadlineMs = CLOSE_DEADLINE_MS, stopGraceMs = STOP_GRACE_MS;

    /** Test seam: shorten the close budgets, {@code graceMs} no longer than {@code deadlineMs} (a negative value
     *  restores that budget's default). */
    static void closeBudgetsForTest(long deadlineMs, long graceMs) {
        closeDeadlineMs = deadlineMs < 0 ? CLOSE_DEADLINE_MS : deadlineMs;
        stopGraceMs = graceMs < 0 ? STOP_GRACE_MS : graceMs;
    }

    private final ConcurrentHashMap<SpaceId, SpaceContext> spaces = new ConcurrentHashMap<>();
    /** Spaces found on disk and owned by this pod whose boot FAILED, by directory name — see {@link #skipped()}. */
    private final ConcurrentHashMap<String, SkippedSpace> skipped = new ConcurrentHashMap<>();
    /** Serialises the rare create/delete admin mutations; reads ({@link #space}/{@link #current}) stay lock-free. */
    private final Object lifecycleLock = new Object();
    /** The container root ({@code -Dspaces.root}) new spaces are created under; {@code null} in single-tenant mode. */
    private volatile Path spacesRoot;

    /** The deployment's per-Subject notification preference overrides (ses-sns-adapter-design §7). ONE per
     *  deployment, not per Space, because users span Spaces; {@link #wire handed} to every Space's
     *  {@code NotificationService} as it joins so delivery consults it. */
    private volatile NotificationPreferenceOverrides notificationOverrides = NotificationPreferenceOverrides.inMemory();

    private SpaceManager() {}

    /**
     * A Space that is configured here but did not boot. {@code code} is a stable machine value
     * ({@code SPACE_SCHEMA_CREATE_FAILED} / {@code SPACE_BOOT_FAILED}); {@code detail} is a fixed sentence with
     * <b>no</b> exception text, because this is served by {@code /health/details} and a driver message can echo
     * a URL. The full cause is in the boot log.
     */
    public record SkippedSpace(String space, String code, String detail) {}

    /** Every configured-but-unbooted Space, ordered by directory name; empty when none. */
    public List<SkippedSpace> skipped() {
        return skipped.values().stream().sorted(java.util.Comparator.comparing(SkippedSpace::space)).toList();
    }

    /** The deployment's per-Subject notification preference overrides — backs {@code /notifications/preferences}. */
    public NotificationPreferenceOverrides notificationOverrides() {
        return notificationOverrides;
    }

    /**
     * Where the overrides live: {@code -Dnotify.preferences.file} when set; else
     * {@code <spaces container root>/notification-preferences.toon}; else, single-tenant,
     * {@code <-Dassist.write.root>/notification-preferences.toon}; else nowhere (in memory). ⛔ Never the
     * working directory — a default that writes into the CWD is the {@code SpaceRoot.legacy()} trap.
     */
    static Path notificationOverridesFile(Path containerRoot) {
        String explicit = System.getProperty("notify.preferences.file");
        if (explicit != null && !explicit.isBlank()) return Path.of(explicit.trim());
        if (containerRoot != null) return containerRoot.resolve(NotificationPreferenceOverrides.FILE);
        String writeRoot = System.getProperty("assist.write.root");
        if (writeRoot != null && !writeRoot.isBlank()) return Path.of(writeRoot.trim()).resolve(NotificationPreferenceOverrides.FILE);
        return null;
    }

    /** A Space joining the deployment: its delivery path consults the deployment's per-Subject overrides. */
    private void wire(SpaceContext ctx) {
        ctx.service().notificationService().preferenceOverrides(notificationOverrides);
    }

    /** Wrap a single already-built service as the {@code default} space (single-tenant / CLI / tests). */
    public static SpaceManager single(CollectorService service) {
        SpaceManager m = new SpaceManager();
        SpaceRoot legacy = SpaceRoot.legacy();
        SpaceContext ctx = new SpaceContext(DEFAULT, legacy,
                new SpaceContext.SpaceManifest(DEFAULT.value(), "", ""), service);
        m.notificationOverrides = NotificationPreferenceOverrides.open(notificationOverridesFile(null));
        m.spaces.put(DEFAULT, ctx);
        m.wire(ctx);
        // JOB-PATH-PIPELINEJOBRUNNER-SPLIT-1 (option 1, 2026-09-17): a job's relative path has always meant the
        // launch dir in this layout, NOT -Dassist.write.root — publish that explicitly so the engine holds no
        // cwd assumption of its own (SpaceBootstrap.load's per-space register() covers the multi-space case).
        com.gamma.pipeline.SpaceConfigRoot.registerConfigReadRoot(DEFAULT.value(), legacy.base());
        // WEBHOOK-EGRESS-POLICY-1: the one-time upgrade seed of the egress allowlist, at boot only.
        // The launch config's Connections are NOT under the write root the migration scans, so their object-store
        // hosts are passed in — without it a LAN MinIO a single-tenant server already used is denied after upgrade.
        // With NO writable root the launch config is trusted as an in-memory allowlist instead (session decision
        // 2026-09-27) — EgressAllowlist.bootDefaultSpace picks the branch.
        com.gamma.pipeline.exec.EgressAllowlist.bootDefaultSpace(legacy.base(),
                service.connections().values().stream()
                        .map(com.gamma.pipeline.exec.EgressAllowlist::objectStoreHost)
                        .filter(java.util.Objects::nonNull).toList());
        return m;
    }

    /** Boot every space under {@code spacesRoot} (each a dir with a {@code config/} subtree); a bad one is skipped. */
    public static SpaceManager discover(Path spacesRoot) throws IOException {
        // Before any space opens a store: an unhonourable -Dinspecto.db=postgres is one loud boot error,
        // not N per-store WARNs that leave the service "healthy" with capabilities silently switched off.
        OperationalDb.verifySelectable();
        SpaceManager m = new SpaceManager();
        m.spacesRoot = spacesRoot.toAbsolutePath().normalize();   // remembered so runtime create/delete can mint/remove dirs
        m.notificationOverrides = NotificationPreferenceOverrides.open(notificationOverridesFile(m.spacesRoot));
        if (!Files.isDirectory(spacesRoot)) {
            log.warn("Spaces root {} does not exist — no spaces booted", spacesRoot.toAbsolutePath());
            m.publishHosted();
            return m;
        }
        // §5.3: which Spaces are THIS pod's. Absent a partition.toon this hosts everything, so the
        // single-node path is unchanged; a malformed one is fatal rather than defaulted (see SpacePartition).
        SpacePartition partition = SpacePartition.load(m.spacesRoot);
        SpaceDataRoot.registerSpacesRoot(m.spacesRoot);
        List<Path> candidates;
        try (Stream<Path> dirs = Files.list(spacesRoot)) {
            candidates = dirs.map(p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(REF_SUFFIX) ? m.followRef(p) : p)
                .filter(p -> p != null && Files.isDirectory(p))
                .sorted(java.util.Comparator.comparing(p -> p.getFileName().toString()))
                .filter(d -> {
                    String why = notASpaceReason(d);
                    // only folders that look like Spaces are worth a line: a plain dir without config/ is just a dir
                    if (why != null && (Files.isDirectory(d.resolve("config")) || Files.exists(d.resolve("space.toon"))))
                        log.warn("Ignoring folder '{}' under the Spaces root — not a Space: {}", d.getFileName(), why);
                    return why == null;
                })
                .filter(d -> m.ownedByThisPod(partition, d))
                .toList();
        }
        // Every base and data root is known BEFORE any Space boots, so an overlap is refused whatever the boot order.
        candidates = m.withoutOverlaps(candidates);
        for (Path d : candidates) DiscoveredRoots.register(d.getFileName().toString(), d.toAbsolutePath().normalize());
        for (Path d : candidates) {
            com.gamma.pipeline.exec.EgressAllowlist.migrate(d.resolve("config"));   // WEBHOOK-EGRESS-POLICY-1
            m.bootQuietly(d);
        }
        // The app root is logged beside the spaces root because every relative data_root and <id>.ref hangs off it:
        // a server started from the wrong directory boots, so the one-line check must be visible (SPACE-RELOCATABLE-ROOTS-1).
        log.info("SpaceManager: {} space(s) booted from {} (app root {})",
                m.spaces.size(), spacesRoot.toAbsolutePath(), AppRoot.current());
        m.publishHosted();
        m.auditInboxOwnership();   // §5.3: two Spaces on one inbox = silent double-ingestion
        return m;
    }

    /**
     * Publish the Spaces this manager hosts ({@link com.gamma.util.SpaceFolders#hosted}) for a reader that may not
     * depend on this module - the demo sign-in reads its rosters from exactly these folders, a {@code <id>.ref}'s
     * included, and never from one discovery refused (DEMO-AUTH-ROSTER-REACH-1). Discover mode only: single-tenant
     * hosts the legacy write root, which those readers resolve themselves.
     */
    private void publishHosted() {
        if (spacesRoot == null) return;
        Map<String, Path> folders = new LinkedHashMap<>();
        spaces.forEach((id, ctx) -> folders.put(id.value(), ctx.root().base()));
        com.gamma.util.SpaceFolders.publishHosted(this, folders);
    }

    /** A Space reference: {@code <spaces root>/<id>.ref} holds the path of a Space folder kept elsewhere —
     *  absolute, or relative to {@link AppRoot} ({@code SPACE-RELOCATABLE-ROOTS-1}). */
    static final String REF_SUFFIX = ".ref";

    /**
     * The Space folder a {@code <id>.ref} points at, or {@code null} (logged, recorded as skipped) when it cannot be
     * followed: blank, relative, missing, inside the spaces root, named other than {@code <id>} (the folder name IS
     * the Space id, everywhere), or shadowed by a real {@code <id>/} folder beside the ref.
     */
    Path followRef(Path ref) {
        String file = ref.getFileName().toString();
        String id = file.substring(0, file.length() - REF_SUFFIX.length());
        if (Files.isDirectory(spacesRoot.resolve(id))) {   // the real folder boots; the Space is not skipped
            log.warn("Space reference {} is shadowed by the folder '{}' beside it - ignored", ref, id);
            return null;
        }
        RefTarget r = resolveRef(spacesRoot, ref);
        if (r.why() == null) return r.folder();
        log.error("Space reference {} {} - Space '{}' not booted", ref, r.why(), id);
        skipped.put(id, new SkippedSpace(id, "SPACE_REF_INVALID", "the Space reference " + file + " " + r.why()));
        return null;
    }

    /** A {@code <id>.ref} resolved: the Space folder it points at, or why it cannot be followed (exactly one is set). */
    record RefTarget(Path folder, String why) {}

    /**
     * {@link #followRef}'s rules without its logging or skip record, for every reader that must agree with what
     * {@link #discover} boots ({@link #isLastOnDisk}, {@link SpaceMigrator}). The shadowing folder is the caller's check.
     */
    static RefTarget resolveRef(Path spacesRoot, Path ref) {
        String file = ref.getFileName().toString();
        String id = file.substring(0, file.length() - REF_SUFFIX.length());
        String why;
        Path target = null;
        try {
            String text = Files.readString(ref).trim();
            // SPACE-RELOCATABLE-ROOTS-1: absolute, or relative to the install root — the same base spaces.ref and
            // space.toon data_root use. Every rule below is unchanged and applies to both forms alike.
            target = text.isEmpty() ? null : AppRoot.resolve(text);
            if (target == null) why = "must hold a path to a Space folder";
            else if (!target.getFileName().toString().equals(id))
                why = "points at '" + target.getFileName() + "', but the folder name must be the Space id '" + id + "'";
            else if (com.gamma.config.safety.PathJail.canonical(target).startsWith(
                    com.gamma.config.safety.PathJail.canonical(spacesRoot)))
                why = "points inside the spaces root - put the folder there directly instead";
            else if (!Files.isDirectory(target)) why = "points at " + target + ", which does not exist";
            else why = null;
        } catch (IOException | RuntimeException e) {
            why = "is unreadable: " + e.getMessage();
        }
        return why == null ? new RefTarget(target.toAbsolutePath().normalize(), null) : new RefTarget(null, why);
    }

    /**
     * {@code candidates} minus every Space whose folder or data root overlaps (is, contains, or sits inside) another
     * Space's folder or data root, or whose folder contains the spaces root — either would reach the other's
     * {@code config/} or stores through its own allowed roots (CROSS-SPACE-JAIL-1). Two overlapping folders refuse
     * both; a moved data root reaching into another Space refuses only the Space that declared it.
     */
    private List<Path> withoutOverlaps(List<Path> candidates) {
        Path root = com.gamma.config.safety.PathJail.canonical(spacesRoot);
        List<Path[]> areas = new ArrayList<>();   // per candidate: {canonical base, canonical data root}
        for (Path c : candidates) {
            SpaceRoot r = SpaceRoot.under(c);
            areas.add(new Path[]{com.gamma.config.safety.PathJail.canonical(r.base()),
                    com.gamma.config.safety.PathJail.canonical(r.dataRoot())});
        }
        List<Path> out = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            String why = null;
            Path base = areas.get(i)[0];
            if (root.startsWith(base)) why = "its folder " + base + " contains the spaces root";
            for (int j = 0; j < candidates.size() && why == null; j++) {
                if (j == i) continue;
                Path theirBase = areas.get(j)[0];
                for (Path mine : areas.get(i))
                    for (Path theirs : areas.get(j))
                        // j's moved data root reaching into i's folder is j's fault: j is refused, not i
                        if (why == null && !(mine.equals(base) && !theirs.equals(theirBase))
                                && (mine.startsWith(theirs) || theirs.startsWith(mine)))
                            why = mine + " overlaps Space '" + candidates.get(j).getFileName() + "' at " + theirs;
            }
            if (why == null) {
                out.add(candidates.get(i));
                continue;
            }
            String id = candidates.get(i).getFileName().toString();
            log.error("Space '{}' not booted: {}", id, why);
            skipped.put(id, new SkippedSpace(id, "SPACE_OVERLAP", why));
        }
        return out;
    }

    /** Whether {@code id} is taken on disk: a folder or a {@code <id>.ref} under the spaces root. */
    private boolean takenOnDisk(SpaceId id) {
        return Files.exists(spacesRoot.resolve(id.value())) || Files.exists(spacesRoot.resolve(id.value() + REF_SUFFIX));
    }

    /**
     * Why {@code dir} (a child of the Spaces root) is NOT a Space, or {@code null} when it is one — the shared rule
     * {@link com.gamma.util.SpaceFolders#notASpaceReason}, which the demo sign-in's rosters follow too.
     */
    static String notASpaceReason(Path dir) {
        return com.gamma.util.SpaceFolders.notASpaceReason(dir);
    }

    /**
     * Whether {@code dir}'s Space belongs to this pod (§5.3). ⚠ The id used here is the <b>directory
     * name</b>, which is what {@code partition.toon} maps — the {@link SpaceContext#id()} is not known
     * until the Space loads, and loading it is exactly what we are deciding whether to do.
     *
     * <p>An unassigned Space is skipped with a WARN rather than failing boot: zero owners stalls one
     * Space, a boot failure takes down every other Space on this pod. See {@link SpacePartition}.
     */
    private boolean ownedByThisPod(SpacePartition partition, Path dir) {
        if (partition.hostsEverything()) return true;
        String id = dir.getFileName().toString();
        if (partition.isUnassigned(id)) {
            log.warn("Space '{}' is present here but appears in NO partition.toon entry — not booting it. "
                    + "⚠ It has no owning pod at all: add it to the partition map, or it never runs.", id);
            return false;
        }
        boolean mine = partition.hosts(id);
        if (!mine) log.info("Space '{}' belongs to another pod — not booting it here", id);
        return mine;
    }

    /**
     * Warn when two hosted Spaces declare the same {@code dirs.poll} (§5.3). ⚠ WARN, never refuse — the
     * blast-radius reasoning of {@link SpacePartition}'s unassigned-Space case applies: a config smell that
     * may predate this check must not take down every Space on the pod.
     *
     * <p>⚠ With {@code -Dinbox.registry.backend} set, this pod publishes what it hosts to the shared
     * registry and audits the <b>fleet's</b> roster ({@code INBOX-REGISTRY-CROSS-POD-1}). Without it — the
     * default — it sees only the Spaces THIS pod hosts, and two Spaces on different pods sharing an inbox
     * stays invisible. ⛔ Absence is never reported as health: see {@link DbInboxRegistry}.
     *
     * @return the findings, so a caller (and the test) can assert on them rather than scrape the log
     */
    List<String> auditInboxOwnership() {
        Map<String, List<SpaceInboxAudit.InboxDecl>> mine = new LinkedHashMap<>();
        for (SpaceContext ctx : spaces.values()) {
            // ⚠ Seeded even when the Space declares nothing — publish() must then DELETE its old rows, or a
            // removed dirs.poll goes on being reported by every other pod for ever.
            List<SpaceInboxAudit.InboxDecl> forSpace =
                    mine.computeIfAbsent(ctx.id().value(), k -> new ArrayList<>());
            try {
                for (com.gamma.etl.PipelineConfig cfg : ctx.service().loadedPipelines()) {
                    if (cfg.dirs() == null) continue;
                    forSpace.add(new SpaceInboxAudit.InboxDecl(
                            ctx.id().value(), cfg.identity().pipelineName(), cfg.dirs().poll()));
                }
            } catch (RuntimeException e) {
                // A space whose configs will not enumerate is already warned about at boot; an audit
                // must never be the thing that fails a boot that otherwise succeeded.
                log.debug("Inbox audit skipped space {}: {}", ctx.id().value(), e.getMessage());
            }
        }
        List<SpaceInboxAudit.InboxDecl> declared = new ArrayList<>();
        mine.values().forEach(declared::addAll);

        List<SpaceInboxAudit.InboxDecl> audited = declared;
        boolean fleetWide = false;
        // ⚠ Opened and closed per audit, not held: this runs once at boot, and a connection kept open for
        // the life of the pod would be one more thing to fence during a restart for no gain.
        // ⚠ The null guard is on spacesRoot, not on the feature flag: the argument is evaluated before the
        // opener can decide the registry is off, and a manager built without a spaces root (tests, the
        // legacy single-tenant path) would NPE on a default-off feature.
        try (DbInboxRegistry registry = spacesRoot == null
                ? null : ServiceStores.openInboxRegistry(SpaceRoot.under(spacesRoot))) {
            if (registry != null) {
                mine.forEach(registry::publish);
                List<SpaceInboxAudit.InboxDecl> fleet = registry.declarations();
                // ⛔ An empty read means the registry could not be read (it has just been written to), so
                // fall back to what this pod knows rather than auditing nothing and calling it healthy.
                if (!fleet.isEmpty()) {
                    audited = fleet;
                    fleetWide = true;
                }
            }
        }
        List<String> findings = SpaceInboxAudit.sharedInboxFindings(audited);
        for (String f : findings) log.warn("[SPACES] {}{}", fleetWide ? "(fleet-wide) " : "", f);
        return findings;
    }

    private void bootQuietly(Path dir) {
        // Registered BEFORE load: booting the space is exactly when its schema/grammar refs meet the
        // jail, so a root registered after would refuse the very configs it exists to allow.
        SpaceRoot root = SpaceRoot.under(dir);
        DiscoveredRoots.register(root.id(), root.base());
        try {
            SpaceContext ctx = loadAsSpace(root);
            spaces.put(ctx.id(), ctx);
            wire(ctx);
        } catch (Exception e) {
            DiscoveredRoots.unregister(root.id());   // a space that never joined must not leave a root behind
            // ERROR, not WARN (ASSURE-OPERABILITY-1): a configured Space that did not boot is a node serving less
            // than it was configured for - the 2026-10-10 drill's node came up with 0 Spaces behind a WARN.
            log.error("Skipping space dir {} — failed to load: {}", dir, e.getMessage());
            String name = dir.getFileName().toString();
            skipped.put(name, e instanceof OperationalDb.SchemaCreateException
                    ? new SkippedSpace(name, "SPACE_SCHEMA_CREATE_FAILED",
                            "could not create the Space's PostgreSQL schema (credentials or reachability) - see the boot log")
                    : new SkippedSpace(name, "SPACE_BOOT_FAILED", "the Space failed to load - see the boot log"));
        }
    }

    /**
     * Load + start a space at {@code base} with its base registered as an allowed root <em>first</em>
     * (see {@link #bootQuietly}), deregistering again on any failure. The shared tail of the three
     * runtime create paths.
     */
    private static SpaceContext bootStarted(Path base, boolean created) throws IOException {
        SpaceRoot root = SpaceRoot.under(base);
        DiscoveredRoots.register(root.id(), root.base());
        try {
            // a new Space's seeded data/ goes to its data_root (SPACE-DATA-ROOT-1); a Space already made never moves at boot
            if (created) SpaceDataRoot.adoptSeeded(root);
            SpaceContext ctx = loadAsSpace(root);
            try {
                ctx.start();
                return ctx;
            } catch (RuntimeException e) {
                ctx.close();   // don't leak a started-but-unregistered service
                throw e;
            }
        } catch (IOException | RuntimeException e) {
            DiscoveredRoots.unregister(root.id());
            throw e;
        }
    }

    /**
     * {@link SpaceBootstrap#load} with the boot thread bound to the Space being loaded
     * ({@code CROSS-SPACE-JAIL-1}). Loading is where the Space's schema/grammar refs meet the jail, and the
     * jail's roots are now the <b>bound</b> Space's base plus the operator roots — unbound, the boot thread
     * is the {@code default} Space and a named Space's own refs would be judged against someone else's base.
     */
    private static SpaceContext loadAsSpace(SpaceRoot root) throws IOException {
        String prev = MDC.get(EventLog.SPACE_MDC_KEY);
        MDC.put(EventLog.SPACE_MDC_KEY, root.id());
        try {
            return SpaceBootstrap.load(root);
        } finally {
            if (prev == null) MDC.remove(EventLog.SPACE_MDC_KEY);
            else MDC.put(EventLog.SPACE_MDC_KEY, prev);
        }
    }

    /** Arm every hosted space's poll loop / schedules. */
    public void startAll() {
        spaces.values().forEach(SpaceContext::start);
    }

    // ── runtime CRUD (no restart) ─────────────────────────────────────────────

    /** Whether this manager can mint/remove spaces at runtime (false for the single-tenant {@link #single} mode). */
    public boolean supportsCrud() {
        return spacesRoot != null;
    }

    /** The container root ({@code -Dspaces.root}) hosted spaces live under, or {@code null} in single-tenant
     *  mode. The Exchange ({@code spaces/_shared/}) is rooted here; a {@code null} disables cross-Space sharing. */
    public Path containerRoot() {
        return spacesRoot;
    }

    /**
     * Create a new space under {@code spaces/<id>/}: make its convention dirs + {@code space.toon} manifest, boot it
     * via {@link SpaceBootstrap}, {@link SpaceContext#start() start} it, and register it — all without a restart.
     * Serialised with {@link #delete}.
     *
     * @throws IllegalStateException when this manager hosts a single space ({@link #single}; no container root), or a
     *                               space with this id (or its directory) already exists
     */
    public SpaceContext create(SpaceId id, String displayName, String description) throws IOException {
        return create(id, displayName, description, null);
    }

    /**
     * {@link #create(SpaceId, String, String)} by a signed-in person: {@code createdBy} (their Subject id) is recorded in
     * {@code space.toon} and the Space is created closed ({@link SpaceContext.SpaceManifest#CLOSED}), so from its first
     * request only the people it admits may use it. {@code null} (no sign-in) records nothing: the Space is open.
     */
    public SpaceContext create(SpaceId id, String displayName, String description, String createdBy) throws IOException {
        return create(id, displayName, description, createdBy, null, null);
    }

    /**
     * {@link #create(SpaceId, String, String, String)} into chosen folders ({@code SPACE-LOCATIONS-1} D6): {@code folder}
     * (a Space folder named {@code <id>}, absent or empty) instead of {@code <spaces root>/<id>}, with a {@code <id>.ref}
     * written once it boots; {@code dataRoot} (absent or empty) written as {@code data_root:} before it boots. Either may
     * be null. Both are checked ({@link SpaceLocations}) before anything is made.
     */
    public SpaceContext create(SpaceId id, String displayName, String description, String createdBy,
                               Path folder, Path dataRoot) throws IOException {
        if (spacesRoot == null)
            throw new IllegalStateException("This server hosts a single space; set -Dspaces.root to manage many");
        synchronized (lifecycleLock) {
            if (spaces.containsKey(id))
                throw new IllegalStateException("Space already exists: " + id.value());
            Path base = folder == null ? spacesRoot.resolve(id.value()) : folder.toAbsolutePath().normalize();
            if (takenOnDisk(id))
                throw new IllegalStateException("Space directory already exists: " + spacesRoot.resolve(id.value()));
            requireFolders(id, folder, dataRoot, base);
            for (String sub : SPACE_SUBDIRS) Files.createDirectories(base.resolve(sub));
            String name = (displayName == null || displayName.isBlank()) ? id.value() : displayName.trim();
            new SpaceContext.SpaceManifest(name, description == null ? "" : description.trim(), Instant.now().toString(),
                    dataRoot == null ? "" : SpaceLocations.written(dataRoot),
                    createdBy, admissionFor(createdBy)).write(base.resolve("space.toon"));

            com.gamma.pipeline.exec.EgressAllowlist.recordEmpty(base.resolve("config"));   // a NEW Space is never seeded
            SpaceContext ctx = bootStarted(base, true);
            if (folder != null) writeRef(id.value(), base);
            spaces.put(id, ctx);
            wire(ctx);
            publishHosted();
            log.info("Created space '{}' at {}", id.value(), base);
            return ctx;
        }
    }

    /**
     * Create a new space seeded from a bundle zip: mint its convention dirs, unpack the bundle's config files into
     * {@code config/} (jailed against zip-slip) and its {@code space.toon} (or a default), then boot + register it —
     * no restart. The fresh {@link SpaceBootstrap} boot discovers every config uniformly, so pipelines, connections
     * and jobs are all live. Serialised with {@link #create}/{@link #delete}.
     *
     * @throws IllegalArgumentException if the zip is not a valid bundle (delegated from {@link BundleImporter#parse})
     * @throws IllegalStateException    single-tenant mode, or a space with this id / directory already exists
     */
    public SpaceContext createFromBundle(SpaceId id, byte[] zip) throws IOException {
        return createFromBundle(id, zip, java.util.function.UnaryOperator.identity());
    }

    /**
     * {@link #createFromBundle(SpaceId, byte[])} with {@code guard} applied to the parsed bundle before any directory
     * is created — the control plane's per-kind content gate (a Decision Rule's invoke-api gate and stamped makers,
     * {@code ASSURE-ACTION-REQUESTS-1}); it throws to refuse the whole bundle.
     */
    public SpaceContext createFromBundle(SpaceId id, byte[] zip, java.util.function.UnaryOperator<BundleImporter.Bundle> guard)
            throws IOException {
        return createFromBundle(id, zip, guard, null);
    }

    /** {@link #createFromBundle(SpaceId, byte[], java.util.function.UnaryOperator)} by a signed-in person: recorded and
     *  closed as {@link #create(SpaceId, String, String, String)} does. */
    public SpaceContext createFromBundle(SpaceId id, byte[] zip, java.util.function.UnaryOperator<BundleImporter.Bundle> guard,
                                         String createdBy) throws IOException {
        if (spacesRoot == null)
            throw new IllegalStateException("This server hosts a single space; set -Dspaces.root to manage many");
        BundleImporter.Bundle bundle = guard.apply(BundleImporter.parse(zip));   // validates the manifest before touching disk
        synchronized (lifecycleLock) {
            if (spaces.containsKey(id))
                throw new IllegalStateException("Space already exists: " + id.value());
            Path base = spacesRoot.resolve(id.value());
            if (takenOnDisk(id))
                throw new IllegalStateException("Space directory already exists: " + base);
            for (String sub : SPACE_SUBDIRS) Files.createDirectories(base.resolve(sub));
            try {
                BundleImporter.writeConfig(bundle, base.resolve("config"), false, new ImportJournal());   // a NEW Space: nothing to overwrite
            } catch (UnknownClassificationException unknown) {
                try {
                    deleteRecursively(base);   // or the directory just made answers the corrected bundle "already exists"
                } catch (IOException leftBehind) {
                    unknown.addSuppressed(leftBehind);   // the refusal stays the answer
                }
                throw unknown;
            }
            Path manifest = base.resolve("space.toon");
            if (bundle.spaceToon() != null) {
                // The exporting Space's data_root belongs to it: the new Space takes the convention default instead.
                // So do its creator and its admission: a bundle never says who may use the Space it is imported as.
                Files.write(manifest, bundle.spaceToon());
                SpaceContext.SpaceManifest m = SpaceContext.SpaceManifest.read(manifest, id.value());
                if (!m.dataRoot().isBlank() || !m.createdBy().isBlank() || !m.admission().isBlank() || createdBy != null)
                    new SpaceContext.SpaceManifest(m.displayName(), m.description(), m.createdAt(), "",
                            createdBy, admissionFor(createdBy)).write(manifest);
            }
            else new SpaceContext.SpaceManifest(id.value(), "", Instant.now().toString(), "",
                    createdBy, admissionFor(createdBy)).write(manifest);

            com.gamma.pipeline.exec.EgressAllowlist.recordEmpty(base.resolve("config"));   // a NEW Space is never seeded
            SpaceContext ctx = bootStarted(base, true);
            spaces.put(id, ctx);
            wire(ctx);
            publishHosted();
            log.info("Created space '{}' from bundle at {} ({} config file(s))",
                    id.value(), base, bundle.configEntries().size());
            return ctx;
        }
    }

    /** A Space a signed-in person creates is closed; one made with no sign-in records nothing and stays open. */
    private static String admissionFor(String createdBy) {
        return createdBy == null || createdBy.isBlank() ? "" : SpaceContext.SpaceManifest.CLOSED;
    }

    /** The shipped-template catalog root ({@code <spacesRoot>/_templates}) — an underscore sentinel like
     *  {@code _shared}, so space discovery never admits it. {@code null} in single-tenant mode. */
    private Path templatesRoot() {
        return spacesRoot == null ? null : spacesRoot.resolve("_templates");
    }

    /**
     * The space templates this server ships: one gallery entry per {@code _templates/<id>/template.toon}
     * ({@code {id, name, tagline, description, icon, contents[], creatable, missingFeatures[]}} — the UI's
     * {@code SpaceTemplateInfo} shape; {@link #missingFeatures}).
     * Empty in single-tenant mode or when no templates directory exists; an unreadable template is warned
     * and skipped (the rest still list).
     */
    public List<Map<String, Object>> templates() throws IOException {
        Path root = templatesRoot();
        if (root == null || !Files.isDirectory(root)) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        try (Stream<Path> dirs = Files.list(root)) {
            for (Path dir : dirs.filter(Files::isDirectory).sorted().toList()) {
                if (dir.getFileName().toString().startsWith(".")) continue;   // PA-7d: an App install's .staging-* / .old-* folders
                Path meta = dir.resolve("template.toon");
                if (!Files.exists(meta)) continue;
                try {
                    Map<String, Object> m = com.gamma.util.ToonHelper.load(meta.toString());
                    Map<String, Object> info = new LinkedHashMap<>();
                    info.put("id", dir.getFileName().toString());
                    info.put("name", com.gamma.util.ToonHelper.opt(m, "name", dir.getFileName().toString()));
                    info.put("tagline", com.gamma.util.ToonHelper.opt(m, "tagline", ""));
                    info.put("description", com.gamma.util.ToonHelper.opt(m, "description", ""));
                    info.put("icon", com.gamma.util.ToonHelper.opt(m, "icon", "heroicons_outline:cube"));
                    info.put("contents", m.get("contents") instanceof List<?> l ? l : List.of());
                    info.put("version", SpaceOrigin.templateVersion(dir));   // SE-5a: "unversioned" without version:
                    List<Map<String, String>> missing = missingFeatures(dir.resolve("config"));
                    info.put("creatable", missing.isEmpty());
                    info.put("missingFeatures", missing);
                    info.put("samples", SpaceSamples.shipped(dir));   // POST /spaces {loadSamples} accepts it (OB-12)
                    out.add(info);
                } catch (Exception bad) {
                    log.warn("Skipping unreadable space template {}: {}", dir, bad.toString());
                }
            }
        }
        return out;
    }

    /**
     * SE-5a: the folder of template {@code templateId} ({@code <spaces-root>/_templates/<id>/}), or empty when no such
     * template exists, the id would leave the catalog, or this server hosts a single space.
     */
    public Optional<Path> templateDir(String templateId) {
        Path root = templatesRoot();
        if (root == null || templateId == null || templateId.isBlank()) return Optional.empty();
        Path tpl = root.resolve(templateId).normalize();
        return tpl.startsWith(root) && !tpl.equals(root) && Files.isRegularFile(tpl.resolve("template.toon"))
                ? Optional.of(tpl) : Optional.empty();
    }

    /**
     * Whether the shipped template {@code templateId} carries sample data ({@link SpaceSamples#shipped}).
     *
     * @throws IllegalArgumentException when no such template ships (or this server hosts a single space)
     */
    public boolean templateShipsSamples(String templateId) throws IOException {
        Path root = templatesRoot();
        Path tpl = root == null || templateId == null ? null : root.resolve(templateId).normalize();
        if (tpl == null || !tpl.startsWith(root) || !Files.exists(tpl.resolve("template.toon")))
            throw new IllegalArgumentException("no such space template '" + templateId + "'");
        return SpaceSamples.shipped(tpl);
    }

    /**
     * Whether the shipped template {@code templateId} has a Basic tier ({@link #hasBasicTier}): the seed gate then leaves
     * its Alert Rules unarmed instead of refusing them on a build without Alert Rules.
     *
     * @throws IllegalArgumentException when no such template ships (or this server hosts a single space)
     */
    public boolean templateHasBasicTier(String templateId) {
        Path root = templatesRoot();
        Path tpl = root == null || templateId == null ? null : root.resolve(templateId).normalize();
        if (tpl == null || !tpl.startsWith(root) || !Files.exists(tpl.resolve("template.toon")))
            throw new IllegalArgumentException("no such space template '" + templateId + "'");
        return hasBasicTier(tpl.resolve("config"));
    }

    /** A template's Demo User roster, and the gallery's name for the sign-in it needs ({@link #missingFeatures}). */
    static final String DEMO_USERS = "demo-users.toon", DEMO_AUTH = "demo-auth";

    /**
     * Whether people can sign in as the Demo Users of {@code config}: the installed Authenticator names its principals
     * from a Space's config (only Demo Auth does; OIDC cannot say who exists). A roster it cannot read still counts -
     * the sign-in is there, and the Space's own load reports the table.
     */
    private static boolean demoUsersSignIn(Path config) {
        return com.gamma.spi.auth.Authenticators.active().map(a -> {
            try {
                return a.principals(config).isPresent();
            } catch (RuntimeException unreadableRoster) {
                return true;
            }
        }).orElse(false);
    }

    /**
     * Whether the template that owns {@code config} has a Basic tier: its {@code template.toon} says {@code minEdition: basic}.
     * Its Demo User roster is then the template's Advanced tier, inert where nobody signs in (the showcase plan's Edition
     * rule, operator 2026-11-08: "allow on Basic"), so the gallery does not refuse it for the missing Demo Auth; its Alert
     * Rules ship unarmed where Alert Rules are absent ("A: allow, unarmed"). A Space
     * (no template.toon) or an unreadable template.toon has no Basic tier.
     */
    private static boolean hasBasicTier(Path config) {
        Path tpl = config.getParent() == null ? null : config.getParent().resolve("template.toon");
        if (tpl == null || !Files.isRegularFile(tpl)) return false;
        try {
            return "basic".equalsIgnoreCase(String.valueOf(com.gamma.util.ToonHelper.load(tpl.toString()).get("minEdition")).trim());
        } catch (Exception unreadable) {
            return false;
        }
    }

    /**
     * The edition features a template's {@code config/} uses that THIS build lacks — the same refusals the seed
     * gate answers with a 422 on {@code POST /spaces}, so the gallery can say so before the operator names a
     * Space: a registry Alert Rule or a Decision Rule {@code create-alert} consequence ({@code alert.dispatch}),
     * and a pipeline's archive / DuckLake use ({@link com.gamma.etl.EditionFeatures#pipelineRefusals(Map)}, which
     * the pipeline loader refuses on this build). One entry per feature, {@code {feature, message}}; empty when
     * this build can create the template. ⚠ No legacy per-file alert config is read here: nothing loads one at
     * runtime (only registry {@code alert-rules/} are armed) and the seed gate does not refuse one, so counting it
     * would mark a creatable template uncreatable. A template carrying a Demo User roster ({@code demo-users.toon}) needs
     * the Demo Auth sign-in ({@code demo-auth}, {@code LA-SAMPLE-GALLERY-DEMOAUTH-1}); the gallery alone reports that one -
     * the seed gate does not refuse it, so such a Space can still be created through the API without its sign-in. A
     * template with a Basic tier ({@code minEdition: basic}) is not refused for its roster, nor for its Alert Rules
     * ({@code alert-rules/}, registry or pending), which ship unarmed where Alert Rules are absent: the boot leaves each one
     * unarmed with a WARN, and the seed gate judges their content only ({@link #hasBasicTier}; operator 2026-11-08,
     * "A: allow, unarmed"). A Decision Rule's {@code create-alert} still counts.
     */
    static List<Map<String, String>> missingFeatures(Path config) throws IOException {
        java.util.TreeMap<String, String> missing = new java.util.TreeMap<>();
        if (!Files.isDirectory(config)) return List.of();
        boolean basicTier = hasBasicTier(config);
        if (Files.isRegularFile(config.resolve(DEMO_USERS)) && !demoUsersSignIn(config)   // LA-SAMPLE-GALLERY-DEMOAUTH-1
                && !basicTier)
            missing.put(DEMO_AUTH, "Demo User sign-in (config/" + DEMO_USERS + ") needs a demo build with Demo Auth — "
                    + "this server does not include it");
        String alert = com.gamma.etl.EditionFeatures.ALERT_DISPATCH;
        try (Stream<Path> s = Files.walk(config)) {
            for (Path p : s.filter(Files::isRegularFile).filter(f -> f.toString().endsWith(".toon")).toList()) {
                String name = p.getFileName().toString();
                String parent = p.getParent().getFileName().toString();
                // a Basic-tier template's Alert Rules ship unarmed (operator 2026-11-08, "A: allow, unarmed"); a Decision
                // Rule's create-alert consequence is not an Alert Rule the boot can leave unarmed, so it is still counted
                if (!com.gamma.etl.EditionFeatures.present(alert)
                        && !(basicTier && "alert-rules".equals(parent))
                        && ("alert-rules".equals(parent)   // registry/alert-rules/ and a pending/alert-rules/ deferred seed
                            || ("decision-rules".equals(parent) && Files.readString(p).contains("create-alert"))))
                    missing.putIfAbsent(alert, com.gamma.etl.EditionFeatures.refusal(alert));
                if (name.endsWith("_pipeline.toon")) {
                    try {
                        for (com.gamma.etl.EditionFeatures.Refusal r
                                : com.gamma.etl.EditionFeatures.pipelineRefusals(com.gamma.util.ToonHelper.load(p.toString())))
                            missing.putIfAbsent(r.feature(), r.message());
                    } catch (Exception unreadable) {
                        log.warn("Space template pipeline {} is unreadable: {}", p, unreadable.toString());
                    }
                }
            }
        }
        List<Map<String, String>> out = new ArrayList<>();
        missing.forEach((f, msg) -> out.add(Map.of("feature", f, "message", msg)));
        return out;
    }

    /**
     * Create a new space seeded from a shipped template: mint the convention dirs, copy the template's
     * {@code config/} tree with every {@code ${SPACE}} token in a {@code .toon} rewritten to the new id
     * (a data path needs no token — {@code data/…} already resolves under the new Space,
     * {@code DATA-DIRS-RESOLVE-AGAINST-CWD-1}), copy {@code data/} verbatim
     * (pristine samples), then boot + register — the fresh {@link SpaceBootstrap} discovers every copied
     * config uniformly, exactly like {@link #createFromBundle}. Serialised with {@link #create}/{@link #delete}.
     *
     * @throws IllegalArgumentException when no template with {@code templateId} exists
     * @throws IllegalStateException    single-tenant mode, or a space with this id / directory already exists
     */
    public SpaceContext createFromTemplate(SpaceId id, String displayName, String description,
                                           String templateId) throws IOException {
        return createFromTemplate(id, displayName, description, templateId, base -> {});
    }

    /**
     * {@link #createFromTemplate(SpaceId, String, String, String)} with {@code seedGate} run over the new Space's
     * directory once the template's WHOLE tree is copied (so every Dataset it carries is on disk) and before it boots
     * — the control plane's per-kind save gate for seeded components ({@code kpi},
     * {@code ASSURE-KPI-DEFINITIONS-RESIDUALS-1}). It throws to refuse the template: the half-made directory is
     * deleted and nothing is registered.
     */
    public SpaceContext createFromTemplate(SpaceId id, String displayName, String description,
                                           String templateId, java.util.function.Consumer<Path> seedGate)
            throws IOException {
        return createFromTemplate(id, displayName, description, templateId, seedGate, null);
    }

    /** {@link #createFromTemplate(SpaceId, String, String, String, java.util.function.Consumer)} by a signed-in person:
     *  recorded and closed as {@link #create(SpaceId, String, String, String)} does. */
    public SpaceContext createFromTemplate(SpaceId id, String displayName, String description,
                                           String templateId, java.util.function.Consumer<Path> seedGate, String createdBy)
            throws IOException {
        return createFromTemplate(id, displayName, description, templateId, seedGate, createdBy, null, null);
    }

    /** {@link #createFromTemplate(SpaceId, String, String, String, java.util.function.Consumer, String)} into chosen
     *  folders, as {@link #create(SpaceId, String, String, String, Path, Path)} does; the template's seeded data goes
     *  to {@code dataRoot}. */
    public SpaceContext createFromTemplate(SpaceId id, String displayName, String description, String templateId,
                                           java.util.function.Consumer<Path> seedGate, String createdBy,
                                           Path folder, Path dataRoot) throws IOException {
        if (spacesRoot == null)
            throw new IllegalStateException("This server hosts a single space; set -Dspaces.root to manage many");
        Path tpl = templatesRoot().resolve(templateId);
        Path meta = tpl.resolve("template.toon");
        if (!Files.isDirectory(tpl) || !Files.exists(meta))
            throw new IllegalArgumentException("no such space template '" + templateId + "'");
        Map<String, Object> tplMeta = com.gamma.util.ToonHelper.load(meta.toString());
        synchronized (lifecycleLock) {
            if (spaces.containsKey(id))
                throw new IllegalStateException("Space already exists: " + id.value());
            Path base = folder == null ? spacesRoot.resolve(id.value()) : folder.toAbsolutePath().normalize();
            if (takenOnDisk(id))
                throw new IllegalStateException("Space directory already exists: " + spacesRoot.resolve(id.value()));
            requireFolders(id, folder, dataRoot, base);
            // Seeded and gated in a unique staging dir under the `_staging` sentinel (discovery boots only a
            // `<root>/<id>/config`, so it never admits one), then renamed into place. A refusal whose cleanup
            // fails (a Windows lock) leaves only that staging dir: it never blocks the id and never boots.
            Path staging = spacesRoot.resolve("_staging").resolve(id.value() + "-" + System.nanoTime());
            for (String sub : SPACE_SUBDIRS) Files.createDirectories(staging.resolve(sub));
            copyTemplateTree(tpl.resolve("config"), staging.resolve("config"), id.value(), true);
            copyTemplateTree(tpl.resolve("data"), staging.resolve("data"), id.value(), false);
            try {
                seedGate.accept(staging);
            } catch (RuntimeException refused) {
                try {
                    deleteRecursively(staging);   // a refused template leaves no Space behind
                } catch (IOException | RuntimeException cleanup) {
                    log.warn("Could not remove refused template staging dir {}: {}", staging, cleanup.toString());
                    refused.addSuppressed(cleanup);
                }
                throw refused;                    // the refusal stays the answer, never the cleanup failure
            }
            if (folder == null) Files.move(staging, base);
            else {   // a chosen folder may exist (empty, checked) and is usually on another volume: copy, then delete
                SpaceDataRoot.moveTree(staging, base);
            }
            String name = (displayName == null || displayName.isBlank())
                    ? com.gamma.util.ToonHelper.opt(tplMeta, "name", id.value()) : displayName.trim();
            String desc = (description == null || description.isBlank())
                    ? com.gamma.util.ToonHelper.opt(tplMeta, "tagline", "") : description.trim();
            new SpaceContext.SpaceManifest(name, desc, Instant.now().toString(),
                    dataRoot == null ? "" : SpaceLocations.written(dataRoot), createdBy, admissionFor(createdBy))
                    .write(base.resolve("space.toon"));
            com.gamma.pipeline.exec.EgressAllowlist.recordEmpty(base.resolve("config"));   // a NEW Space is never seeded
            recordProvenance(tpl, base, templateId);   // after every file the server writes at creation, so the hashes are the created state
            SpaceContext ctx = bootStarted(base, true);
            if (folder != null) writeRef(id.value(), base);
            spaces.put(id, ctx);
            wire(ctx);
            publishHosted();
            log.info("Created space '{}' from template '{}' at {}", id.value(), templateId, base);
            return ctx;
        }
    }

    /**
     * PRODUCT-ASSEMBLY-1 PA-7d (D-PA25): a Space made from a template an installed App owns (its {@code .installed-by} marker)
     * records {@code .inspecto/created-from.toon} - the template, the App and its version - so an upgrade of that App can offer
     * the Space the difference. SE-5a: every template records its origin (spec-space-origin.md); a shipped one writes no App fields. Best effort: the Space is created either way.
     */
    private static void recordProvenance(Path tpl, Path base, String templateId) {
        // SE-5a (spec-space-origin.md): every template now records its origin - template, templateVersion and each config
        // file's SHA-256 as the Space now holds it (seeded and stamped); an installed App's template also its app + version.
        Path marker = tpl.resolve(".installed-by");
        try {
            String app = "", version = "";
            if (Files.isRegularFile(marker))
                for (String line : Files.readAllLines(marker)) {
                    if (line.startsWith("app=")) app = line.substring(4).trim();
                    if (line.startsWith("version=")) version = line.substring(8).trim();
                }
            SpaceOrigin.recordCreated(tpl, base, templateId, app, version);
        } catch (IOException e) {
            log.warn("Could not record the origin of Space {}: {}", base, e.toString());
        }
    }

    /** Copy {@code src/**} to {@code dst/**}; when {@code rewriteToon}, each {@code .toon} is copied as text
     *  with {@code ${SPACE}} replaced by {@code spaceId} (all other files byte-for-byte). No-op if absent. */
    private static void copyTemplateTree(Path src, Path dst, String spaceId, boolean rewriteToon) throws IOException {
        if (!Files.isDirectory(src)) return;
        try (Stream<Path> walk = Files.walk(src)) {
            for (Path p : walk.sorted().toList()) {
                Path target = dst.resolve(src.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(target);
                } else if (rewriteToon && p.getFileName().toString().endsWith(".toon")) {
                    Files.createDirectories(target.getParent());
                    Files.writeString(target, Files.readString(p).replace("${SPACE}", spaceId));
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /**
     * Update a hosted space's display metadata (name + description) in place — rewrite its {@code space.toon} and
     * swap the in-memory manifest. The id, directory tree and running service are untouched (the id/folder is
     * immutable). Serialised with {@link #create}/{@link #delete}.
     *
     * @return the updated space, or {@code null} when no space with {@code id} is hosted
     * @throws IllegalStateException when this manager hosts a single space ({@link #single}; no container root)
     */
    public SpaceContext update(SpaceId id, String displayName, String description) throws IOException {
        if (spacesRoot == null)
            throw new IllegalStateException("This server hosts a single space; set -Dspaces.root to manage many");
        synchronized (lifecycleLock) {
            SpaceContext ctx = spaces.get(id);
            if (ctx == null) return null;
            String name = (displayName == null || displayName.isBlank()) ? id.value() : displayName.trim();
            // the creator and the admission are not display metadata: a rename keeps a closed Space closed
            SpaceContext.SpaceManifest updated = new SpaceContext.SpaceManifest(
                    name, description == null ? "" : description.trim(), ctx.manifest().createdAt(), ctx.manifest().dataRoot(),
                    ctx.manifest().createdBy(), ctx.manifest().admission());
            updated.write(ctx.root().base().resolve("space.toon"));   // a referenced Space lives outside the spaces root
            ctx.updateManifest(updated);
            log.info("Updated space '{}' display metadata", id.value());
            return ctx;
        }
    }

    /**
     * True when {@code id}'s directory is the last one under the container root that {@link #discover} would boot —
     * i.e. purging it would leave an empty spaces root (bootable since 2026-09-25, but the last copy is gone). Uses the
     * same "dir with a {@code config/} subtree" predicate as discovery, so a deregistered-but-still-on-disk space
     * (deleted without {@code purge}) correctly counts as a survivor; {@code false} in single-tenant mode. A
     * {@code <id>.ref} counts only when discovery would boot it: not shadowed, followable, and pointing at a Space.
     */
    public boolean isLastOnDisk(SpaceId id) throws IOException {
        if (spacesRoot == null || !Files.isDirectory(spacesRoot)) return false;
        try (Stream<Path> dirs = Files.list(spacesRoot)) {
            return dirs.filter(d -> Files.isDirectory(d) ? notASpaceReason(d) == null : bootableRef(d))
                    .allMatch(d -> d.getFileName().toString().equals(id.value())
                            || d.getFileName().toString().equals(id.value() + REF_SUFFIX));
        }
    }

    /** Whether {@code file} under the spaces root is a {@code <id>.ref} that {@link #discover} would boot. */
    private boolean bootableRef(Path file) {
        String name = file.getFileName().toString();
        if (!name.endsWith(REF_SUFFIX) || !Files.isRegularFile(file)) return false;
        if (Files.isDirectory(spacesRoot.resolve(name.substring(0, name.length() - REF_SUFFIX.length())))) return false;
        Path folder = resolveRef(spacesRoot, file).folder();
        return folder != null && notASpaceReason(folder) == null;
    }

    /**
     * Remove a hosted space: deregister it first (new requests {@code 404} at once), then drain-and-close its service
     * (the existing {@link CollectorService#close()}). When {@code purge} is set, the space's directory tree
     * ({@code config/data/audit/duckdb/flows} + manifest) is then deleted from disk; otherwise the files are left for
     * a later manual cleanup or re-discovery. Serialised with {@link #create}.
     *
     * @return {@code false} when no space with {@code id} is hosted (nothing to do)
     * @throws IllegalStateException when this manager hosts a single space ({@link #single})
     */
    public boolean delete(SpaceId id, boolean purge) throws IOException {
        if (spacesRoot == null)
            throw new IllegalStateException("This server hosts a single space; set -Dspaces.root to manage many");
        SpaceContext ctx;
        synchronized (lifecycleLock) {
            ctx = spaces.remove(id);
            if (ctx != null) publishHosted();
        }
        if (ctx == null) return false;
        // S7: the space is already deregistered — a hung or failing drain-and-close must not abort the
        // rest of teardown, or the process-wide per-space registries below would leak (half-removed space).
        closeWithDeadline(ctx);
        AcquisitionLedgers.unregister(id.value());   // release the per-space ledger SpaceBootstrap registered (+ its DB handle)
        ConnectionRegistry.forget(id.value());        // drop the space's connection profiles (process-wide static map)
        StabilityGate.forget(id.value());             // drop the space's file-stability gate + its retained sightings
        com.gamma.acquire.CircuitBreaker.forgetSpace(id.value()); // SPACE-UNKEYED-STATICS-1: both gained a space
        com.gamma.acquire.GapTracker.forgetSpace(id.value());     // dimension 2026-09-10, so both must be dropped here
        com.gamma.etl.IngestProgress.forgetSpace(id.value());     // the two live progress snapshots gained the
        com.gamma.etl.StepProgress.forgetSpace(id.value());       // same dimension via CurrentSpace
        com.gamma.inspector.DryRunRejects.forgetSpace(id.value()); // X4 dry-run rejects: same key
        com.gamma.pipeline.SpaceConfigRoot.forget(id.value());   // drop the space's config root (rules + jobs)
        // CollectorService.close() already releases this, but per the S7 note above a hung close must not leak
        // it: the registry's DuckDB file lives under the space dir, so on Windows a retained handle would make
        // the purge below fail outright. unregister is idempotent, so the double call is free.
        com.gamma.consignment.ConsignmentOutputStores.unregister(id.value());
        com.gamma.consignment.FileStages.unregister(id.value());
        com.gamma.consignment.DedupLedgers.unregister(id.value());
        com.gamma.pipeline.exec.ProvenanceStores.unregister(id.value());
        DiscoveredRoots.unregister(id.value());   // a deleted space's base leaves with it
        if (purge) {
            Path base = spacesRoot.resolve(id.value()).normalize();   // SpaceId is jailed: no separators/.. can escape
            // A referenced Space's folder (and data root) lives outside the spaces root: purge drops only the reference.
            Path ref = spacesRoot.resolve(id.value() + REF_SUFFIX);
            if (Files.isRegularFile(ref) && !ctx.root().base().equals(base)) {   // only when the ref is what booted
                Files.delete(ref);
                log.info("Deleted space '{}' - removed its reference {}; the folder {} was left on disk",
                        id.value(), ref, ctx.root().base());
                return true;
            }
            // ⛔ The containment verdict must not be reported as a purge. Both roots are absolute and
            // normalized (spacesRoot at discover()), so this holds today by construction — but the old
            // form logged "Deleted + purged" whether the delete ran, was skipped for escaping, or found
            // no directory at all. A guard whose failure reads as success is not a guard.
            if (!base.startsWith(spacesRoot))
                throw new IOException("refusing to purge space '" + id.value() + "': " + base
                        + " escapes the spaces root " + spacesRoot);
            if (Files.isDirectory(base)) {
                deleteRecursively(base);
                log.info("Deleted + purged space '{}' ({})", id.value(), base);
                // A data_root off the base is purged too, but only when its stamp names this Space (SPACE-DATA-ROOT-1).
                Path dataRoot = ctx.root().dataRoot();
                if (!dataRoot.equals(base) && SpaceDataRoot.ownedBy(dataRoot, ctx.root().base())) {
                    deleteRecursively(dataRoot);
                    log.info("Purged space '{}' data root ({})", id.value(), dataRoot);
                }
            } else {
                log.info("Deleted space '{}' — nothing to purge, no directory at {}", id.value(), base);
            }
        } else {
            log.info("Deleted space '{}' (files left on disk)", id.value());
        }
        return true;
    }

    // ── Space locations (SPACE-LOCATIONS-1): attach, detach, change data folder, create into folders ─────────

    private void requireCrud() {
        if (spacesRoot == null)
            throw new IllegalStateException("This server hosts a single space; set -Dspaces.root to manage many");
    }

    /** Every hosted Space: id to {Space folder, data folder}. */
    private Map<String, Path[]> hostedAreas() {
        Map<String, Path[]> out = new LinkedHashMap<>();
        spaces.forEach((id, ctx) -> out.put(id.value(), new Path[]{ctx.root().base(), ctx.root().dataRoot()}));
        return out;
    }

    /** Whether {@code name} is hosted, or taken on disk by a folder or a {@code <id>.ref}. */
    private boolean idTaken(String name) {
        return SpaceId.isValid(name) && (spaces.containsKey(SpaceId.of(name)) || takenOnDisk(SpaceId.of(name)));
    }

    /**
     * The checks {@code POST /spaces/_locations/check} reports for {@code path} ({@link SpaceLocations}); nothing is
     * written. {@code id} names the Space a data folder is for (a hosted one: its stamp may already name it).
     */
    public List<SpaceLocations.Check> checkLocation(Path path, SpaceLocations.Use use, String id) {
        requireCrud();
        SpaceContext ctx = id == null || !SpaceId.isValid(id) ? null : spaces.get(SpaceId.of(id));
        Path base = use == SpaceLocations.Use.DATA && ctx != null ? ctx.root().base() : null;
        return SpaceLocations.checks(path, use, spacesRoot, hostedAreas(), id, base, this::idTaken);
    }

    private void requireLocation(Path path, SpaceLocations.Use use, String id, Path base) {
        SpaceLocations.refuseFirst(SpaceLocations.checks(path, use, spacesRoot, hostedAreas(), id, base, this::idTaken));
    }

    /** D6: the chosen folders of a Space being created, checked before anything is made. */
    private void requireFolders(SpaceId id, Path folder, Path dataRoot, Path base) {
        if (folder != null) requireLocation(folder, SpaceLocations.Use.CREATE, id.value(), null);
        if (dataRoot != null) requireLocation(dataRoot, SpaceLocations.Use.DATA, id.value(), base);
    }

    /** Write {@code <spaces root>/<id>.ref} naming {@code folder}: relative to the app root when it lies under it. */
    private void writeRef(String id, Path folder) throws IOException {
        com.gamma.util.AtomicFiles.write(spacesRoot.resolve(id + REF_SUFFIX),
                (SpaceLocations.written(folder) + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8), ".ref-");
    }

    /** Whether {@code ctx} was booted through a {@code <id>.ref}: its folder is not {@code <spaces root>/<id>}. */
    public boolean isReferenced(SpaceContext ctx) {
        return spacesRoot != null && !ctx.root().base().equals(spacesRoot.resolve(ctx.id().value()));
    }

    /** Whether {@code ctx} keeps files in its data folder's {@code data/} or {@code audit/} (D5). */
    public static boolean holdsData(SpaceContext ctx) {
        try {
            return SpaceDataRoot.holdsData(ctx.root().dataRoot());
        } catch (IOException unreadable) {
            return true;   // unknown counts as holding data: never moved on a guess
        }
    }

    /**
     * D3: attach the Space in {@code folder}, kept outside the Spaces root, live: write {@code <id>.ref} (the folder name
     * is the id), then boot it as {@link #create} does, so it is booted again at every restart. The folder and the data
     * folder its {@code space.toon} names are checked first ({@link SpaceLocations}). The Space keeps the admission its
     * {@code space.toon} says.
     *
     * @throws SpaceLocations.Refused a check failed (403/409), or the Space did not boot (422: its reference is removed)
     */
    public SpaceContext attach(Path folder) throws IOException {
        requireCrud();
        synchronized (lifecycleLock) {
            requireLocation(folder, SpaceLocations.Use.ATTACH, null, null);
            Path base = folder.toAbsolutePath().normalize();
            String id = base.getFileName().toString();
            Path data = SpaceRoot.dataRootFor(base);
            if (!data.equals(base)) requireLocation(data, SpaceLocations.Use.ATTACHED_DATA, id, base);
            writeRef(id, base);
            SpaceContext ctx;
            try {
                com.gamma.pipeline.exec.EgressAllowlist.migrate(base.resolve("config"));   // as discover does at boot
                ctx = bootStarted(base, false);
            } catch (IOException | RuntimeException e) {
                Files.deleteIfExists(spacesRoot.resolve(id + REF_SUFFIX));
                throw new SpaceLocations.Refused(422, "CONFIG_VALIDATION_FAILED", "the Space in " + base
                        + " did not start: " + e.getMessage() + " - nothing was attached");
            }
            spaces.put(ctx.id(), ctx);
            skipped.remove(id);
            wire(ctx);
            publishHosted();
            log.info("Attached space '{}' from {}", id, base);
            return ctx;
        }
    }

    /**
     * D4: stop a Space booted through a {@code <id>.ref} and delete the reference - the same teardown as
     * {@link #delete delete(id, false)}. Its Space folder and data folder stay on disk and can be attached again.
     *
     * @return {@code false} when no such Space is hosted
     * @throws IllegalStateException the Space lives inside the Spaces root (removed with {@code DELETE} instead)
     */
    public boolean detach(SpaceId id) throws IOException {
        requireCrud();
        synchronized (lifecycleLock) {
            SpaceContext ctx = spaces.get(id);
            if (ctx == null) return false;
            Path ref = spacesRoot.resolve(id.value() + REF_SUFFIX);
            if (!isReferenced(ctx) || !Files.isRegularFile(ref))
                throw new IllegalStateException("Space '" + id.value() + "' lives inside the Spaces root, not in a Space "
                        + "folder kept elsewhere - remove it with DELETE /spaces/" + id.value() + " instead");
            delete(id, false);
            Files.delete(ref);
            log.info("Detached space '{}' - its folder {} and data folder {} stay on disk",
                    id.value(), ctx.root().base(), ctx.root().dataRoot());
            return true;
        }
    }

    /**
     * D5: point a Space that holds no data yet at {@code dataRoot} ({@code null}: back under its Space folder). The
     * Space is stopped, {@code space.toon} gets {@code data_root:} (relative to the app root when under it), the stores
     * every boot opens ({@code duckdb/}) move with it, and it is booted again, which claims and stamps the folder. On a
     * failure the Space is put back as it was.
     *
     * @return the Space as booted, or {@code null} when no such Space is hosted
     * @throws SpaceLocations.Refused {@code SPACE_HOLDS_DATA} (409), a check failed (403/409), or it did not start (422)
     */
    public SpaceContext changeDataRoot(SpaceId id, Path dataRoot) throws IOException {
        requireCrud();
        synchronized (lifecycleLock) {
            SpaceContext ctx = spaces.get(id);
            if (ctx == null) return null;
            Path base = ctx.root().base();
            Path before = ctx.root().dataRoot();
            if (holdsData(ctx))
                throw new SpaceLocations.Refused(409, SpaceLocations.HOLDS_DATA, "Space '" + id.value() + "' holds data in "
                        + before + ", so its data folder cannot be changed here - move it by hand with the server stopped "
                        + "(see the Space anatomy guide, Config tree vs data root) and set data_root in space.toon");
            if (dataRoot != null) requireLocation(dataRoot, SpaceLocations.Use.DATA, id.value(), base);
            SpaceContext.SpaceManifest old = ctx.manifest();
            SpaceContext.SpaceManifest next = new SpaceContext.SpaceManifest(old.displayName(), old.description(),
                    old.createdAt(), dataRoot == null ? "" : SpaceLocations.written(dataRoot), old.createdBy(), old.admission());
            Path manifest = base.resolve("space.toon");
            delete(id, false);   // stopped with the same teardown as DELETE without purge; nothing is removed from disk
            Path after = null;
            boolean staked = false;
            try {
                next.write(manifest);
                SpaceRoot r = SpaceRoot.under(base);
                after = r.dataRoot();
                if (!after.equals(base) && !Files.exists(after.resolve(SpaceDataRoot.STAMP))) {
                    SpaceDataRoot.stake(r, next);
                    staked = true;
                }
                SpaceDataRoot.moveState(before, after);
                SpaceContext booted = bootStarted(base, false);
                if (!before.equals(base) && !before.equals(after)) Files.deleteIfExists(before.resolve(SpaceDataRoot.STAMP));
                spaces.put(id, booted);
                wire(booted);
                publishHosted();
                log.info("Space '{}' now keeps its data in {} (was {})", id.value(), after, before);
                return booted;
            } catch (IOException | RuntimeException e) {
                putBack(id, old, manifest, before, after, staked, e);
                throw new SpaceLocations.Refused(422, "CONFIG_VALIDATION_FAILED", "the data folder of Space '" + id.value()
                        + "' could not be changed: " + e.getMessage() + " - the Space was put back as it was");
            }
        }
    }

    /** {@link #changeDataRoot}'s undo: the old {@code space.toon}, the stores moved back, the new stamp removed, booted. */
    private void putBack(SpaceId id, SpaceContext.SpaceManifest old, Path manifest, Path before, Path after, boolean staked,
                         Exception cause) {
        try {
            old.write(manifest);
            if (after != null) SpaceDataRoot.moveState(after, before);
            if (staked) Files.deleteIfExists(after.resolve(SpaceDataRoot.STAMP));
            SpaceContext again = bootStarted(manifest.getParent(), false);
            spaces.put(id, again);
            wire(again);
        } catch (IOException | RuntimeException undo) {
            cause.addSuppressed(undo);
            log.error("Space '{}' could not be put back after a failed data folder change: {}", id.value(), undo.toString());
            skipped.put(id.value(), new SkippedSpace(id.value(), "SPACE_BOOT_FAILED", "the Space failed to load - see the boot log"));
        }
        publishHosted();
    }

    /** Recursively delete {@code dir} (deepest entries first). */
    private static void deleteRecursively(Path dir) throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    /** The hosted space with {@code id}, if present. */
    public Optional<SpaceContext> space(SpaceId id) {
        return Optional.ofNullable(spaces.get(id));
    }

    /**
     * The space served for an un-scoped request: the {@code default} space, or — when discovery booted no
     * {@code default} — the space with the smallest id. The {@code /spaces/{id}} seam (later stage) supersedes this.
     *
     * <p>⚠ Smallest id, not "first registered": {@code spaces} is a {@link ConcurrentHashMap}, whose iteration order is
     * hash order, while the SPA falls back to the first space of the id-sorted {@code GET /spaces} list. With several
     * spaces and no {@code default}, a fresh browser's first un-scoped read (the landing guard's Menu read) could hit
     * a different space from the one the SPA then selects.
     *
     * @throws NoSpaceHostedException when no spaces are hosted
     */
    public SpaceContext current() {
        SpaceContext c = spaces.get(DEFAULT);
        if (c != null) return c;
        return spaces.entrySet().stream().min(java.util.Map.Entry.comparingByKey(java.util.Comparator.comparing(SpaceId::value)))
                .map(java.util.Map.Entry::getValue)
                .orElseThrow(NoSpaceHostedException::new);
    }

    /**
     * {@link #current()} on a manager hosting ZERO Spaces — a normal state, not a fault: bundles ship no Spaces
     * (operator decision 2026-09-25), so a fresh install boots empty until a Space folder is dropped into the
     * spaces root or one is created with {@code POST /spaces}. The control plane answers it as a clear 503, not
     * a 500. Still an {@link IllegalStateException}, so callers that already degrade on one keep doing so.
     */
    public static final class NoSpaceHostedException extends IllegalStateException {
        public NoSpaceHostedException() {
            super("No Space is attached - drop a Space folder into the spaces root (-Dspaces.root / SPACES_ROOT) "
                    + "and restart, or create one in Settings -> Spaces (POST /spaces)");
        }
    }

    /** Every hosted space. */
    public Collection<SpaceContext> all() {
        return spaces.values();
    }

    public int size() {
        return spaces.size();
    }

    /** JOB-SHUTDOWN-FAILED-1: tell every Space it is about to close, before the control plane's close hooks stop the
     *  services a Job Run may call (see {@link CollectorService#beginClose}). Never throws. */
    public void beginClose() {
        for (SpaceContext c : spaces.values()) {
            try {
                c.service().beginClose();
            } catch (RuntimeException e) {
                log.warn("Error marking space '{}' as closing: {}", c.id(), e.getMessage());
            }
        }
    }

    @Override
    public void close() {
        for (SpaceContext c : spaces.values()) {
            closeWithDeadline(c);   // S7: bound each drain so one hung space can't stall JVM shutdown
        }
        spaces.clear();
        com.gamma.util.SpaceFolders.withdrawHosted(this);
    }

    /**
     * S7: drain-and-close one space's service under {@link #closeDeadlineMs}, best-effort. The close runs
     * on a daemon thread; this returns as soon as it finishes or the deadline lapses. A close still running
     * past the deadline is abandoned — being a daemon, it can never hold the JVM open — and any exception it
     * throws is logged, not propagated, so teardown always continues. An abandoned close still closes the
     * Space's stores ({@link CollectorService#closeStoresNow}), so a busy Space leaves no DuckDB {@code .wal}
     * (DEMO-BAKE-WAL-RACE-1). Never throws.
     *
     * <p>JOB-OUTLIVES-CLOSE-1: a drain that overruns its budget is not abandoned at once. The Space's Job Runs are
     * interrupted first ({@link CollectorService#interruptRuns}) and the drain gets {@link #STOP_GRACE_MS} more to
     * finish, its own close of the stores included; before this a Run still writing its Dataset kept writing after
     * {@link #close()} had returned. Only a drain still busy after that (a Run that ignores both the cancel and the
     * interrupt, such as a native statement still in flight) is abandoned, and the flush gets what is left of the
     * second budget. Worst case per Space: two budgets, as before.
     */
    private void closeWithDeadline(SpaceContext ctx) {
        Thread drain = startDaemon(ctx, "space-close-", ctx::close, "closing");
        if (finishedWithin(drain, closeDeadlineMs)) return;
        log.warn("Close of space '{}' did not finish within {} ms; interrupting its Job Runs and waiting up to {} ms "
                + "more for them to stop", ctx.id(), closeDeadlineMs, stopGraceMs);
        try {
            ctx.service().interruptRuns();
        } catch (RuntimeException e) {
            log.warn("Error interrupting the Job Runs of space '{}': {}", ctx.id(), e.getMessage());
        }
        if (finishedWithin(drain, stopGraceMs)) return;
        log.warn("Close of space '{}' did not finish within {} ms; abandoning its drain and closing its stores now — "
                + "a run thread that has not stopped still holds its run lease, which is deliberately NOT released "
                + "(a survivor would become a second writer); it expires after its TTL",
                ctx.id(), closeDeadlineMs + stopGraceMs);
        // DEMO-BAKE-WAL-RACE-1: the abandoned drain would reach the store closes only after the JVM had exited,
        // leaving every DuckDB store's .wal behind. Close them now, under a budget of their own: a store waits only
        // for the statement in flight on it.
        long flushMs = closeDeadlineMs - stopGraceMs;
        if (!finishedWithin(startDaemon(ctx, "space-flush-", () -> ctx.service().closeStoresNow(), "flushing the stores of"),
                flushMs))
            log.warn("Stores of space '{}' did not close within {} ms; a store still busy keeps its .wal, which "
                    + "DuckDB replays at the next start", ctx.id(), flushMs);
    }

    /** Run {@code body} on a started daemon thread (a daemon never holds the JVM open); what it throws is logged. */
    private static Thread startDaemon(SpaceContext ctx, String threadPrefix, Runnable body, String what) {
        Map<String, String> mdc = MDC.getCopyOfContextMap();   // preserve space-log routing on the worker thread
        Thread t = new Thread(() -> {
            if (mdc != null) MDC.setContextMap(mdc);
            try {
                body.run();
            } catch (Exception e) {
                log.warn("Error {} space '{}': {}", what, ctx.id(), e.getMessage());
            }
        }, threadPrefix + ctx.id());
        t.setDaemon(true);
        t.start();
        return t;
    }

    /** Wait up to {@code ms} for {@code t}; {@code true} when it finished. A thread still running is left to run on
     *  (abandoned). Never throws. */
    private static boolean finishedWithin(Thread t, long ms) {
        try {
            t.join(Math.max(1L, ms));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        return !t.isAlive();
    }
}
