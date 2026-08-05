package com.gamma.service;

import com.gamma.etl.StatusStore;
import com.gamma.event.EventStore;
import com.gamma.event.InMemoryEventStore;
import com.gamma.event.ParquetEventStore;
import com.gamma.pipeline.PipelineStore;
import com.gamma.job.DbJobRunStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * Backend selection for the pluggable persistence stores {@link CollectorService} hosts.
 *
 * <p>Each {@code open*} method reads its {@code -D} backend toggle, opens the chosen backend, and
 * <b>degrades gracefully</b> — a DB/Parquet backend that fails to open is logged and falls back to the
 * lean in-memory default (or {@code null} where the capability is simply off), so observability and the
 * Alert Center never block service startup. This is purely the "how to open a store" half of the service;
 * {@code CollectorService} still owns how the opened stores are wired together (EventLog install, bus
 * subscriptions, the ObjectService composition).
 *
 * <p>The backend <em>toggle</em> ({@code *.backend}) stays a process-global {@code -D} flag — it chooses
 * memory-vs-db/parquet uniformly — but the <em>location</em> (URL/dir) defaults come from the per-space
 * {@link SpaceRoot}, so each space's stores live under its own root. An explicit location {@code -D} flag
 * still overrides the space default.
 */
final class ServiceStores {

    // Log under CollectorService's category so existing log configuration/filtering is unaffected.
    private static final Logger log = LoggerFactory.getLogger(CollectorService.class);

    private ServiceStores() {}

    /**
     * Authored-pipeline store at {@link SpaceRoot#pipelinesDir()}, or {@code null} when no write root is
     * configured. Lets the deletion fence (T32) see pipeline jobs as store producers/consumers; without a
     * write root a configured pipeline job fails closed at build time with a clear message.
     */
    static PipelineStore openPipelineStore(SpaceRoot root) {
        Path pipelines = root.pipelinesDir();
        return pipelines == null ? null : new PipelineStore(pipelines);
    }

    /**
     * Job-run reporting DB, gated by {@code -Djobs.backend}: {@code duckdb} (the bundled default engine,
     * URL from {@code -Djobs.db.url} / the space default), {@code postgres}/{@code postgresql} (resolves the
     * same {@code jobs.db.url} property but expects a {@code jdbc:postgresql://…} URL with the PG driver on
     * the classpath — see inspecto-connectors), or a raw {@code jdbc:} URL. Any other value ⇒ {@code null} ⇒
     * job reporting off and {@code /jobs/metrics} 404s. Percentile SQL is dialect-aware (see {@link DbJobRunStore}).
     */
    static DbJobRunStore openJobRunStore(SpaceRoot root) {
        String backend = System.getProperty("jobs.backend", "none").trim().toLowerCase();
        boolean pg = "postgres".equals(backend) || "postgresql".equals(backend);
        if (!"duckdb".equals(backend) && !pg && !backend.startsWith("jdbc:")) return null;
        String url = backend.startsWith("jdbc:")
                ? backend
                : System.getProperty("jobs.db.url", root.jobRunDbUrl());
        try {
            return DbJobRunStore.open(url);
        } catch (Exception e) {
            log.warn("Could not open job-run DB ({}) — job reporting disabled: {}", url, e.getMessage());
            return null;
        }
    }

    /**
     * Data-plane provenance store for PIPELINE jobs (T21), gated by {@code -Dprovenance.backend}: {@code duckdb}
     * (the bundled default engine), {@code postgres}/{@code postgresql} (resolves {@code -Dprovenance.db.url},
     * which must be a {@code jdbc:postgresql://…} URL with the PG driver on the classpath), or a raw {@code jdbc:}
     * URL. Any other value ⇒ {@code null} ⇒ flow runs record no per-edge counts and {@code /provenance} 404s.
     * Mirrors {@link #openJobRunStore(SpaceRoot)}.
     */
    static com.gamma.pipeline.exec.DbProvenanceStore openProvenanceStore(SpaceRoot root) {
        String backend = System.getProperty("provenance.backend", "none").trim().toLowerCase();
        boolean pg = "postgres".equals(backend) || "postgresql".equals(backend);
        if (!"duckdb".equals(backend) && !pg && !backend.startsWith("jdbc:")) return null;
        String url = backend.startsWith("jdbc:")
                ? backend
                : System.getProperty("provenance.db.url", root.provenanceDbUrl());
        try {
            return com.gamma.pipeline.exec.DbProvenanceStore.open(url);
        } catch (Exception e) {
            log.warn("Could not open provenance DB ({}) — data-plane provenance disabled: {}", url, e.getMessage());
            return null;
        }
    }

    /**
     * Consignment output-file registry (consignment-elt plan §11.3), gated by
     * {@code -Dconsignment.outputs.backend}: {@code duckdb} (the bundled default engine),
     * {@code postgres}/{@code postgresql} (resolves {@code -Dconsignment.outputs.db.url}, which must be a
     * {@code jdbc:postgresql://…} URL with the PG driver on the classpath), or a raw {@code jdbc:} URL. Any
     * other value ⇒ {@code null} ⇒ no per-output-file registry is kept. Mirrors {@link #openProvenanceStore(SpaceRoot)}.
     *
     * <p><b>Absence is not degraded correctness.</b> Output files are still revealed and still recorded in the
     * per-Consignment JSON manifest, which stays the artifact of record for <em>existence</em>; this registry
     * adds the queryable per-file index with lifecycle <em>state</em>. That split is what makes fail-open safe
     * here — see {@link com.gamma.consignment.DbConsignmentOutputStore}.
     */
    static com.gamma.consignment.DbConsignmentOutputStore openConsignmentOutputStore(SpaceRoot root) {
        String backend = System.getProperty("consignment.outputs.backend", "none").trim().toLowerCase();
        boolean pg = "postgres".equals(backend) || "postgresql".equals(backend);
        if (!"duckdb".equals(backend) && !pg && !backend.startsWith("jdbc:")) return null;
        String url = backend.startsWith("jdbc:")
                ? backend
                : System.getProperty("consignment.outputs.db.url", root.consignmentOutputsDbUrl());
        try {
            return com.gamma.consignment.DbConsignmentOutputStore.open(url);
        } catch (Exception e) {
            log.warn("Could not open consignment-outputs DB ({}) — output registry disabled: {}", url, e.getMessage());
            return null;
        }
    }

    /**
     * Per-file stage-progression registry (Phase 4 §2.4), gated by {@code -Dfile.stages.backend}: same
     * three-value contract as {@link #openConsignmentOutputStore(SpaceRoot)} — {@code duckdb}, {@code postgres}/
     * {@code postgresql} ({@code -Dfile.stages.db.url}), or a raw {@code jdbc:} URL. Any other value ⇒
     * {@code null} ⇒ no per-file stage index is kept; the crash-safe commit ordering
     * {@code BatchProcessor.finalizeSource} enforces is unchanged either way.
     */
    static com.gamma.consignment.DbFileStageStore openFileStageStore(SpaceRoot root) {
        String backend = System.getProperty("file.stages.backend", "none").trim().toLowerCase();
        boolean pg = "postgres".equals(backend) || "postgresql".equals(backend);
        if (!"duckdb".equals(backend) && !pg && !backend.startsWith("jdbc:")) return null;
        String url = backend.startsWith("jdbc:")
                ? backend
                : System.getProperty("file.stages.db.url", root.fileStagesDbUrl());
        try {
            return com.gamma.consignment.DbFileStageStore.open(url);
        } catch (Exception e) {
            log.warn("Could not open file-stages DB ({}) — stage registry disabled: {}", url, e.getMessage());
            return null;
        }
    }

    /**
     * Select the Phase-1 event-store backend (v4.2.0): {@code -Devents.backend=memory} (default — a
     * bounded in-memory ring; the lean fat-JAR keeps no extra files and tests stay light) or
     * {@code -Devents.backend=parquet} (durable rolling Hive-partitioned Parquet under
     * {@code -Devents.dir}, default {@link SpaceRoot#eventsDir()}, queried via DuckDB). A parquet
     * backend that fails to open is logged and degrades to in-memory — observability must never block
     * the service.
     */
    static EventStore openEventStore(SpaceRoot root) {
        String backend = System.getProperty("events.backend", "memory");
        if (!"parquet".equalsIgnoreCase(backend)) return new InMemoryEventStore();
        String ev = System.getProperty("events.dir");
        Path dir = (ev == null) ? root.eventsDir() : Path.of(ev);
        try {
            EventStore store = ParquetEventStore.open(dir);
            log.info("Event backend: rolling Parquet ({})", dir.toAbsolutePath());
            return store;
        } catch (RuntimeException e) {
            log.warn("Could not open Parquet event store at {} — falling back to in-memory: {}",
                    dir, e.getMessage());
            return new InMemoryEventStore();
        }
    }

    /**
     * Select the Phase-2 object-store backend (v4.3.0): {@code -Dobjects.backend=memory} (default — an
     * in-memory map; the lean fat-JAR keeps no extra files and tests stay light) or
     * {@code -Dobjects.backend=db} (durable JDBC, engine chosen by {@code -Dobjects.db.url}, default
     * {@link SpaceRoot#objectsDbUrl()} — the bundled DuckDB; point at {@code jdbc:postgresql://…} with
     * the PG driver on the classpath for a distributed deployment). A DB backend that fails to open is
     * logged and degrades to in-memory — the Alert Center must never block service startup.
     */
    static com.gamma.ops.ObjectStore openObjectStore(SpaceRoot root) {
        String backend = System.getProperty("objects.backend", "memory");
        if (!"db".equalsIgnoreCase(backend)) return new com.gamma.ops.InMemoryObjectStore();
        String url = System.getProperty("objects.db.url", root.objectsDbUrl());
        try {
            com.gamma.ops.ObjectStore db = com.gamma.ops.DbObjectStore.open(url,
                    System.getProperty("objects.db.user"), System.getProperty("objects.db.password"));
            log.info("Object backend: database ({})", url);
            return db;
        } catch (Exception e) {
            log.warn("Could not open object DB at {} — falling back to in-memory: {}", url, e.getMessage());
            return new com.gamma.ops.InMemoryObjectStore();
        }
    }

    /**
     * Select the Phase-4 link-store backend, mirroring {@link #openObjectStore(SpaceRoot)}: in-memory by
     * default, or durable JDBC under {@code -Dobjects.backend=db}. The link URL is its own
     * {@code -Dobjects.links.db.url} (default {@link SpaceRoot#linksDbUrl()}) — a <em>separate</em> DuckDB
     * file, because a file-based DuckDB holds a single-writer lock and the object store already owns
     * {@code inspecto-ops.db}; point both at one {@code jdbc:postgresql://…} for a distributed deployment.
     * A DB open that fails degrades to in-memory — the graph must never block service startup.
     */
    static com.gamma.ops.link.LinkStore openLinkStore(SpaceRoot root) {
        String backend = System.getProperty("objects.backend", "memory");
        if (!"db".equalsIgnoreCase(backend)) return new com.gamma.ops.link.InMemoryLinkStore();
        String url = System.getProperty("objects.links.db.url", root.linksDbUrl());
        try {
            com.gamma.ops.link.LinkStore db = com.gamma.ops.link.DbLinkStore.open(url,
                    System.getProperty("objects.db.user"), System.getProperty("objects.db.password"));
            log.info("Link backend: database ({})", url);
            return db;
        } catch (Exception e) {
            log.warn("Could not open link DB at {} — falling back to in-memory: {}", url, e.getMessage());
            return new com.gamma.ops.link.InMemoryLinkStore();
        }
    }

    /**
     * Select the Phase-4-follow-up note-store backend, mirroring {@link #openLinkStore(SpaceRoot)}: in-memory by
     * default, or durable JDBC under {@code -Dobjects.backend=db} in its own DuckDB file
     * ({@code -Dobjects.notes.db.url}, default {@link SpaceRoot#notesDbUrl()}) — a separate file for the
     * same single-writer-lock reason as the link store; point all three at one Postgres for a distributed
     * deployment. A DB open that fails degrades to in-memory.
     */
    static com.gamma.ops.note.NoteStore openNoteStore(SpaceRoot root) {
        String backend = System.getProperty("objects.backend", "memory");
        if (!"db".equalsIgnoreCase(backend)) return new com.gamma.ops.note.InMemoryNoteStore();
        String url = System.getProperty("objects.notes.db.url", root.notesDbUrl());
        try {
            com.gamma.ops.note.NoteStore db = com.gamma.ops.note.DbNoteStore.open(url,
                    System.getProperty("objects.db.user"), System.getProperty("objects.db.password"));
            log.info("Note backend: database ({})", url);
            return db;
        } catch (Exception e) {
            log.warn("Could not open note DB at {} — falling back to in-memory: {}", url, e.getMessage());
            return new com.gamma.ops.note.InMemoryNoteStore();
        }
    }

    /**
     * Select the cross-entity tag-assignment backend (BACKLOG D7), mirroring {@link #openNoteStore(SpaceRoot)}:
     * in-memory by default, or durable JDBC under {@code -Dobjects.backend=db} in its own DuckDB file
     * ({@code -Dobjects.tags.db.url}, default {@link SpaceRoot#tagAssignmentsDbUrl()}) — a separate file for
     * the same single-writer-lock reason as the note and link stores. A DB open that fails degrades to
     * in-memory rather than blocking startup: losing durable tags is bad, refusing to boot over labels is worse.
     */
    static com.gamma.ops.tag.TagAssignmentStore openTagAssignmentStore(SpaceRoot root) {
        String backend = System.getProperty("objects.backend", "memory");
        if (!"db".equalsIgnoreCase(backend)) return new com.gamma.ops.tag.InMemoryTagAssignmentStore();
        String url = System.getProperty("objects.tags.db.url", root.tagAssignmentsDbUrl());
        try {
            com.gamma.ops.tag.TagAssignmentStore db = com.gamma.ops.tag.DbTagAssignmentStore.open(url,
                    System.getProperty("objects.db.user"), System.getProperty("objects.db.password"));
            log.info("Tag assignment backend: database ({})", url);
            return db;
        } catch (Exception e) {
            log.warn("Could not open tag DB at {} — falling back to in-memory: {}", url, e.getMessage());
            return new com.gamma.ops.tag.InMemoryTagAssignmentStore();
        }
    }

    /**
     * The in-app notification feed (Phase B2) for the single {@code appUser}. The feed is low-volume, so
     * the lean {@link com.gamma.notify.InMemoryNotificationStore} is the default and currently the only
     * backend; a durable DuckDB backend would mirror {@link #openObjectStore(SpaceRoot)} when needed.
     */
    static com.gamma.notify.NotificationStore openNotificationStore(SpaceRoot root) {
        return new com.gamma.notify.InMemoryNotificationStore();
    }

    /**
     * Select the status backend from system properties (M5):
     * {@code -Dstatus.backend=file} (default) reads the on-disk audit directly;
     * {@code -Dstatus.backend=db} projects it into a database. The DB engine is chosen by
     * {@code -Dstatus.db.url} and defaults to a local <b>DuckDB</b> file
     * ({@link SpaceRoot#statusDbUrl()}) — the bundled, zero-extra-dependency primary engine. Point
     * the URL at {@code jdbc:postgresql://…} (with the PG driver on the classpath) for a
     * future distributed deployment; {@code -Dstatus.db.user}/{@code .password} are optional.
     */
    static StatusStore openStatusStore(SpaceRoot root) {
        String backend = System.getProperty("status.backend", "file");
        if (!"db".equalsIgnoreCase(backend)) return new FileStatusStore();
        String url = System.getProperty("status.db.url");
        if (url == null) url = root.statusDbUrl();
        try {
            StatusStore db = DbStatusStore.open(url,
                    System.getProperty("status.db.user"), System.getProperty("status.db.password"));
            log.info("Status backend: database ({})", url);
            return db;
        } catch (Exception e) {
            throw new IllegalStateException("Could not open status DB at " + url, e);
        }
    }
}
