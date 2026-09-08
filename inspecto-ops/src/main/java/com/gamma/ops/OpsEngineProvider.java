package com.gamma.ops;

import com.gamma.objects.ObjectAccess;
import com.gamma.ops.link.DbLinkStore;
import com.gamma.ops.link.InMemoryLinkStore;
import com.gamma.ops.link.LinkStore;
import com.gamma.ops.note.DbNoteStore;
import com.gamma.ops.note.InMemoryNoteStore;
import com.gamma.ops.note.NoteStore;
import com.gamma.ops.queue.Queue;
import com.gamma.ops.tag.CaseRule;
import com.gamma.ops.tag.DbTagAssignmentStore;
import com.gamma.ops.tag.InMemoryTagAssignmentStore;
import com.gamma.ops.tag.Tag;
import com.gamma.ops.tag.TagAssignmentStore;
import com.gamma.ops.tag.TagRule;
import com.gamma.ops.workflow.Workflow;
import com.gamma.service.ObjectEngineProvider;
import com.gamma.service.OperationalDb;
import com.gamma.service.SpaceRoot;
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
        ObjectStore objectStore = openObjectStore(root);
        LinkStore linkStore = openLinkStore(root);
        NoteStore noteStore = openNoteStore(root);
        TagAssignmentStore tagStore = openTagAssignmentStore(root);
        ObjectService service = new ObjectService(objectStore, Map.of(), linkStore, noteStore, tagStore);
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

    /** {@code -Dobjects.backend=db} opts into durable JDBC; anything else is in-memory. */
    private static boolean durable() {
        return "db".equalsIgnoreCase(System.getProperty("objects.backend", "memory"));
    }

    private static ObjectStore openObjectStore(SpaceRoot root) {
        if (!durable()) return new InMemoryObjectStore();
        String url = OperationalDb.urlFor(OperationalDb.Family.OBJECTS, root.objectsDbUrl());
        try {
            ObjectStore db = DbObjectStore.open(url,
                    OperationalDb.userFor(OperationalDb.Family.OBJECTS),
                    OperationalDb.passwordFor(OperationalDb.Family.OBJECTS));
            log.info("Object backend: database ({})", url);
            return db;
        } catch (Exception e) {
            log.warn("Could not open object DB at {} — falling back to in-memory: {}", url, e.getMessage());
            return new InMemoryObjectStore();
        }
    }

    private static LinkStore openLinkStore(SpaceRoot root) {
        if (!durable()) return new InMemoryLinkStore();
        String url = OperationalDb.urlFor(OperationalDb.Family.LINKS, root.linksDbUrl());
        try {
            LinkStore db = DbLinkStore.open(url,
                    OperationalDb.userFor(OperationalDb.Family.LINKS),
                    OperationalDb.passwordFor(OperationalDb.Family.LINKS));
            log.info("Link backend: database ({})", url);
            return db;
        } catch (Exception e) {
            log.warn("Could not open link DB at {} — falling back to in-memory: {}", url, e.getMessage());
            return new InMemoryLinkStore();
        }
    }

    private static NoteStore openNoteStore(SpaceRoot root) {
        if (!durable()) return new InMemoryNoteStore();
        String url = OperationalDb.urlFor(OperationalDb.Family.NOTES, root.notesDbUrl());
        try {
            NoteStore db = DbNoteStore.open(url,
                    OperationalDb.userFor(OperationalDb.Family.NOTES),
                    OperationalDb.passwordFor(OperationalDb.Family.NOTES));
            log.info("Note backend: database ({})", url);
            return db;
        } catch (Exception e) {
            log.warn("Could not open note DB at {} — falling back to in-memory: {}", url, e.getMessage());
            return new InMemoryNoteStore();
        }
    }

    private static TagAssignmentStore openTagAssignmentStore(SpaceRoot root) {
        if (!durable()) return new InMemoryTagAssignmentStore();
        String url = OperationalDb.urlFor(OperationalDb.Family.TAGS, root.tagAssignmentsDbUrl());
        try {
            TagAssignmentStore db = DbTagAssignmentStore.open(url,
                    OperationalDb.userFor(OperationalDb.Family.TAGS),
                    OperationalDb.passwordFor(OperationalDb.Family.TAGS));
            log.info("Tag assignment backend: database ({})", url);
            return db;
        } catch (Exception e) {
            log.warn("Could not open tag DB at {} — falling back to in-memory: {}", url, e.getMessage());
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
        public List<BrowsableStore> browsableStores() {
            List<BrowsableStore> out = new ArrayList<>();
            for (Object s : stores) if (s instanceof BrowsableStore b) out.add(b);
            return out;
        }

        // ── the six config kinds (was ServiceBootstrap) ──────────────────────────────────────────

        @Override
        public void loadConfigs(List<Path> configPaths) {
            for (Queue q : load(configPaths, "_queue.toon", Queue::load, "queue")) service.registerQueue(q);
            // At most one escalation policy applies — FIRST valid wins, as before the move.
            for (EscalationPolicy p : load(configPaths, "_escalation.toon", EscalationPolicy::load, "escalation policy")) {
                service.escalationPolicy(p);
                break;
            }
            for (Tag t : load(configPaths, "_tag.toon", Tag::load, "tag")) service.registerTag(t);
            for (TagRule r : load(configPaths, "_tagrule.toon", TagRule::load, "tag rule")) service.registerTagRule(r);
            for (CaseRule r : load(configPaths, "_caserule.toon", CaseRule::load, "case rule")) service.registerCaseRule(r);
            // Last file wins per object type — registerWorkflow overwrites, as before the move.
            for (Workflow w : load(configPaths, "_workflow.toon", Workflow::load, "workflow")) service.registerWorkflow(w);
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
