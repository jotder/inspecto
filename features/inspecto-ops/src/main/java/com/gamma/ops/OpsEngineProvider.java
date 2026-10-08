package com.gamma.ops;

import com.gamma.objects.ObjectAccess;
import com.gamma.ops.link.DbLinkStore;
import com.gamma.ops.link.InMemoryLinkStore;
import com.gamma.ops.link.LinkStore;
import com.gamma.ops.note.DbNoteStore;
import com.gamma.ops.note.InMemoryNoteStore;
import com.gamma.ops.note.NoteStore;
import com.gamma.ops.tag.DbTagAssignmentStore;
import com.gamma.ops.tag.InMemoryTagAssignmentStore;
import com.gamma.ops.tag.Tag;
import com.gamma.ops.tag.TagAssignmentStore;
import com.gamma.ops.tag.TagRule;
import com.gamma.workflow.Workflow;
import com.gamma.service.ObjectEngineProvider;
import com.gamma.service.OperationalDb;
import com.gamma.service.SpaceRoot;
import com.gamma.util.StoreHealth;
import com.gamma.util.BrowsableStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * This module's {@link ObjectEngineProvider} — the construction that used to live in mandatory core
 * (EDG-01 cell 7, 2026-09-08).
 *
 * <p>Discovered through {@code ServiceLoader}, so a Personal bundle that omits this jar simply has no
 * provider: {@code CollectorService} then holds no engine and every operational-object surface answers 503.
 *
 * <p><b>What moved in here, and the two behaviours that had to move intact:</b>
 * <ul>
 *   <li>{@code ServiceStores}' four {@code open*Store(SpaceRoot)} methods. ⚠ Each store gets its <b>own</b>
 *       DuckDB file — a file-based DuckDB holds a single-writer lock, so one file cannot serve four — and a
 *       DB that will not open <b>degrades to in-memory</b> rather than failing the boot. The Alert Center
 *       must never block service startup, and that was true before the move.</li>
 *   <li>{@code ServiceBootstrap}'s six ops config loaders. ⚠ Each keeps its own precedence rule: at most
 *       one escalation policy applies (<b>first valid wins</b>), and a {@code *_workflow} override is
 *       <b>last file wins</b> per object type. An unreadable document is warned about and skipped, never
 *       fatal. ⛔ {@code *_rca} is NOT here — {@code RcaTemplate} is core vocabulary and core still
 *       loads it.</li>
 * </ul>
 */
public final class OpsEngineProvider implements ObjectEngineProvider {

    private static final Logger log = LoggerFactory.getLogger(OpsEngineProvider.class);

    /** Space id → its data root, for {@code objects.analytics}. Module-internal; see {@link #open}. */
    private static final java.util.Map<String, String> DATA_DIRS = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * This Space's data root, or {@code null} when no engine was opened for it.
     *
     * <p>⚠ Public for {@code com.gamma.opsjob} only — the analytics Job Type is in a sibling package of
     * this same module. Core never calls it: the data root reaches this module through
     * {@code ObjectEngineProvider.open}.
     */
    public static String dataDirFor(String spaceId) {
        return DATA_DIRS.get(spaceId);
    }

    @Override
    public ObjectEngine open(SpaceRoot root, String dataDir) {
        // Boot already ran this (SpaceManager.discover → verifySelectable); repeated here for the single-tenant
        // and runtime-create paths, which never pass through discover. Cheap: property reads only.
        OperationalDb.verifyObjectsBackend();
        ObjectStore objectStore = openObjectStore(root);
        LinkStore linkStore = openLinkStore(root);
        NoteStore noteStore = openNoteStore(root);
        TagAssignmentStore tagStore = openTagAssignmentStore(root);
        ObjectService service = new ObjectService(objectStore, Map.of(), linkStore, noteStore, tagStore);
        // ASSURE-WORKFLOW-SLA-1: the Space's authored Workflows / SLA policies / Escalation Rules, re-read on change.
        // ⚠ The legacy single-tenant root has no config dir, so it runs on *_workflow.toon and the built-ins only.
        if (root.config() != null) service.useGovernance(root.config().resolve("registry"));
        // D7 phase 2: adopt tags that exist only in the legacy attributes CSV into the assignment store so
        // the two cannot disagree. Idempotent, and a no-op on a fresh Space; core logs the count.
        int adopted = service.backfillTagAssignments();
        Engine engine = new Engine(service, adopted,
                List.of(objectStore, linkStore, noteStore, tagStore));
        // ⚠ Keyed by Space so the analytics Job Type can find ITS Space's data root: JobContext exposes
        // spaceId() but no dataDir, and a JobTypeProvider is constructed with no arguments at all.
        DATA_DIRS.put(root.id(), dataDir);
        return engine;
    }

    // ── the four stores (was ServiceStores) ──────────────────────────────────────────────────────

    /**
     * Durable JDBC unless {@code -Dobjects.backend=memory} was asked for explicitly
     * ({@code OBJECTS-BACKEND-DEFAULT-MEMORY-1}: {@code db} is the default, {@code postgres} the Enterprise one).
     */
    private static boolean durable() {
        return !"memory".equals(OperationalDb.objectsBackend());
    }

    /**
     * {@code -Dobjects.backend=postgres} (Enterprise) never degrades to in-memory: a store that will not open
     * stops this Space booting instead. The {@code db} default keeps its degrade — the Alert Center must not
     * block a Personal/Professional boot — but Enterprise asked for PostgreSQL, and serving the heap would hide it.
     */
    private static IllegalStateException refuseFallback(String what, String url, Exception cause) {
        return new IllegalStateException("-Dobjects.backend=postgres: could not open the " + what + " store at " + url
                + " — Enterprise keeps it in PostgreSQL and never falls back to memory: " + cause.getMessage(), cause);
    }

    private static ObjectStore openObjectStore(SpaceRoot root) {
        if (!durable()) {
            StoreHealth.record(root.id(), "objects", StoreHealth.Status.NOT_CONFIGURED, "memory",
                    "-Dobjects.backend=memory — Incidents and Alerts are lost on restart");
            return new InMemoryObjectStore();
        }
        String url = OperationalDb.urlFor(OpsStoreFamily.OBJECTS, root, root.objectsDbUrl());
        try {
            ObjectStore db = DbObjectStore.open(url,
                    OperationalDb.userFor(OpsStoreFamily.OBJECTS),
                    OperationalDb.passwordFor(OpsStoreFamily.OBJECTS));
            log.info("Object backend: database ({})", url);
            StoreHealth.record(root.id(), "objects", StoreHealth.Status.UP, url, "open");
            return db;
        } catch (Exception e) {
            if (OperationalDb.objectsPostgresRequired()) throw refuseFallback("object", url, e);
            log.warn("Could not open object DB at {} — falling back to in-memory: {}", url, e.getMessage());
            StoreHealth.degraded(root.id(), "objects", url,
                    "Incidents and Alerts fell back to in-memory and are lost on restart: " + e.getMessage());
            return new InMemoryObjectStore();
        }
    }

    private static LinkStore openLinkStore(SpaceRoot root) {
        if (!durable()) {
            StoreHealth.record(root.id(), "links", StoreHealth.Status.NOT_CONFIGURED, "memory",
                    "-Dobjects.backend=memory");
            return new InMemoryLinkStore();
        }
        String url = OperationalDb.urlFor(OpsStoreFamily.LINKS, root, root.linksDbUrl());
        try {
            LinkStore db = DbLinkStore.open(url,
                    OperationalDb.userFor(OpsStoreFamily.LINKS),
                    OperationalDb.passwordFor(OpsStoreFamily.LINKS));
            log.info("Link backend: database ({})", url);
            StoreHealth.record(root.id(), "links", StoreHealth.Status.UP, url, "open");
            return db;
        } catch (Exception e) {
            if (OperationalDb.objectsPostgresRequired()) throw refuseFallback("link", url, e);
            log.warn("Could not open link DB at {} — falling back to in-memory: {}", url, e.getMessage());
            StoreHealth.degraded(root.id(), "links", url, "object links fell back to in-memory: " + e.getMessage());
            return new InMemoryLinkStore();
        }
    }

    private static NoteStore openNoteStore(SpaceRoot root) {
        if (!durable()) {
            StoreHealth.record(root.id(), "notes", StoreHealth.Status.NOT_CONFIGURED, "memory",
                    "-Dobjects.backend=memory");
            return new InMemoryNoteStore();
        }
        String url = OperationalDb.urlFor(OpsStoreFamily.NOTES, root, root.notesDbUrl());
        try {
            NoteStore db = DbNoteStore.open(url,
                    OperationalDb.userFor(OpsStoreFamily.NOTES),
                    OperationalDb.passwordFor(OpsStoreFamily.NOTES));
            log.info("Note backend: database ({})", url);
            StoreHealth.record(root.id(), "notes", StoreHealth.Status.UP, url, "open");
            return db;
        } catch (Exception e) {
            if (OperationalDb.objectsPostgresRequired()) throw refuseFallback("note", url, e);
            log.warn("Could not open note DB at {} — falling back to in-memory: {}", url, e.getMessage());
            StoreHealth.degraded(root.id(), "notes", url, "operator notes fell back to in-memory: " + e.getMessage());
            return new InMemoryNoteStore();
        }
    }

    private static TagAssignmentStore openTagAssignmentStore(SpaceRoot root) {
        if (!durable()) {
            StoreHealth.record(root.id(), "tags", StoreHealth.Status.NOT_CONFIGURED, "memory",
                    "-Dobjects.backend=memory");
            return new InMemoryTagAssignmentStore();
        }
        String url = OperationalDb.urlFor(OpsStoreFamily.TAGS, root, root.tagAssignmentsDbUrl());
        try {
            TagAssignmentStore db = DbTagAssignmentStore.open(url,
                    OperationalDb.userFor(OpsStoreFamily.TAGS),
                    OperationalDb.passwordFor(OpsStoreFamily.TAGS));
            log.info("Tag assignment backend: database ({})", url);
            StoreHealth.record(root.id(), "tags", StoreHealth.Status.UP, url, "open");
            return db;
        } catch (Exception e) {
            if (OperationalDb.objectsPostgresRequired()) throw refuseFallback("tag", url, e);
            log.warn("Could not open tag DB at {} — falling back to in-memory: {}", url, e.getMessage());
            StoreHealth.degraded(root.id(), "tags", url, "tag assignments fell back to in-memory: " + e.getMessage());
            return new InMemoryTagAssignmentStore();
        }
    }

    /** A live engine over one Space's four stores. */
    private static final class Engine implements ObjectEngine {

        private final ObjectService service;
        private final int adopted;
        private final List<Object> stores;

        Engine(ObjectService service, int adopted, List<Object> stores) {
            this.service = service;
            this.adopted = adopted;
            this.stores = stores;
        }

        @Override
        public ObjectAccess access() {
            return service.access();
        }

        @Override
        public int adoptedTagAssignments() {
            return adopted;
        }

        @Override
        public void sweepIncidentSla(long now) {
            service.sweepIncidentSla(now);
        }

        @Override
        public void useGovernance(Path registryRoot) {
            service.useGovernance(registryRoot);
        }

        @Override
        public List<BrowsableStore> browsableStores() {
            List<BrowsableStore> out = new ArrayList<>();
            for (Object s : stores) if (s instanceof BrowsableStore b) out.add(b);
            return out;
        }

        // ── the six config kinds (was ServiceBootstrap) ──────────────────────────────────────────

        @Override
        public void loadConfigs(List<Path> configPaths) {
            for (Tag t : load(configPaths, "_tag.toon", Tag::load, "tag")) service.registerTag(t);
            for (TagRule r : load(configPaths, "_tagrule.toon", TagRule::load, "tag rule")) service.registerTagRule(r);
            // Last file wins per object type — registerWorkflow overwrites, as before the move.
            // Validated like an authored workflow component (Workflow.problems) — an invalid file is skipped, not served.
            for (Workflow w : load(configPaths, "_workflow.toon", p -> Workflow.load(p).validated(), "workflow")) service.registerWorkflow(w);
            // Add-on object-type behaviour (MODULE-REORG-P7): an installed module loads its own config kinds here
            // (inspecto-case-management reads *_caserule.toon). Fail-soft, like every loader above.
            for (ObjectEngineExtension x : java.util.ServiceLoader.load(ObjectEngineExtension.class)) {
                try {
                    x.loadConfigs(service, configPaths);
                } catch (RuntimeException e) {
                    log.warn("Skipping object-engine extension {}: {}", x.getClass().getName(), e.getMessage());
                }
            }
        }

        /**
         * Parse every {@code configPaths} entry ending in {@code suffix}.
         *
         * <p>⚠ An unreadable or invalid document is <b>warned about and skipped</b>, never fatal: one bad
         * hand-authored file must not stop a Space from starting. That was the behaviour of all six
         * loaders in core and it is the reason they each had their own try/catch.
         */
        private static <T> List<T> load(List<Path> paths, String suffix, Loader<T> loader, String what) {
            List<T> out = new ArrayList<>();
            for (Path p : paths) {
                if (!p.getFileName().toString().endsWith(suffix)) continue;
                try {
                    out.add(loader.load(p));
                } catch (Exception e) {
                    log.warn("Skipping invalid {} config {}: {}", what, p, e.getMessage());
                }
            }
            return out;
        }

        @FunctionalInterface
        private interface Loader<T> {
            T load(Path path) throws Exception;
        }

        @Override
        public void close() {
            for (Object s : stores) {
                if (s instanceof AutoCloseable c) {
                    try {
                        c.close();
                    } catch (Exception e) {
                        log.warn("Error closing {}: {}", s.getClass().getSimpleName(), e.getMessage());
                    }
                }
            }
        }
    }
}
