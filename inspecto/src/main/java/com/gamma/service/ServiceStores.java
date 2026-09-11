package com.gamma.service;

import com.gamma.etl.StatusStore;
import com.gamma.event.EventStore;
import com.gamma.event.InMemoryEventStore;
import com.gamma.event.ParquetEventStore;
import com.gamma.pipeline.PipelineStore;
import com.gamma.job.DbJobRunStore;
import com.gamma.util.StoreHealth;
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
 *
 * <p>⚠ The <em>connection</em> half now comes from {@link OperationalDb}: one
 * {@code -Dinspecto.db=duckdb|postgres} selection covers every operational store, so a Standard
 * deployment names its database once instead of nine times and cannot half-migrate. Precedence is
 * unchanged where it matters — a per-family {@code *.db.url} still wins — and the selection deliberately
 * does <b>not</b> touch any {@code *.backend} default, so it moves stores rather than enabling them.
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
        // ⛔ Compare lowercased, but keep the RAW value for the URL: a jdbc: value carries a path, a database
        // name and credentials, and Postgres treats all three case-sensitively. Mirrors OperationalDb.resolve.
        String raw = System.getProperty("jobs.backend", "none").trim();
        String backend = raw.toLowerCase();
        boolean pg = "postgres".equals(backend) || "postgresql".equals(backend);
        if (!"duckdb".equals(backend) && !pg && !backend.startsWith("jdbc:")) {
            StoreHealth.record(root.id(), "jobRuns", StoreHealth.Status.NOT_CONFIGURED, backend,
                    "-Djobs.backend=" + backend + " — job reporting off");
            return null;
        }
        String url = backend.startsWith("jdbc:")
                ? raw
                : OperationalDb.urlFor(OperationalDb.Family.JOB_RUNS, root.jobRunDbUrl());
        try {
            DbJobRunStore db = DbJobRunStore.open(url);
            StoreHealth.record(root.id(), "jobRuns", StoreHealth.Status.UP, url, "open");
            return db;
        } catch (Exception e) {
            log.warn("Could not open job-run DB ({}) — job reporting disabled: {}", url, e.getMessage());
            StoreHealth.degraded(root.id(), "jobRuns", url, "job reporting disabled: " + e.getMessage());
            return null;
        }
    }

    /**
     * Data-plane provenance store for PIPELINE jobs (T21), gated by {@code -Dprovenance.backend}: {@code duckdb}
     * (the bundled default engine), {@code postgres}/{@code postgresql} (resolves {@code -Dprovenance.db.url},
     * which must be a {@code jdbc:postgresql://…} URL with the PG driver on the classpath), or a raw {@code jdbc:}
     * URL. Any other value ⇒ {@code null} ⇒ pipeline runs record no per-edge counts and {@code /provenance} 404s.
     * Mirrors {@link #openJobRunStore(SpaceRoot)}.
     */
    static com.gamma.pipeline.exec.DbProvenanceStore openProvenanceStore(SpaceRoot root) {
        String raw = System.getProperty("provenance.backend", "none").trim();   // raw: see openJobRunStore
        String backend = raw.toLowerCase();
        boolean pg = "postgres".equals(backend) || "postgresql".equals(backend);
        if (!"duckdb".equals(backend) && !pg && !backend.startsWith("jdbc:")) {
            StoreHealth.record(root.id(), "provenance", StoreHealth.Status.NOT_CONFIGURED, backend,
                    "-Dprovenance.backend=" + backend + " — per-edge counts off");
            return null;
        }
        String url = backend.startsWith("jdbc:")
                ? raw
                : OperationalDb.urlFor(OperationalDb.Family.PROVENANCE, root.provenanceDbUrl());
        try {
            com.gamma.pipeline.exec.DbProvenanceStore db = com.gamma.pipeline.exec.DbProvenanceStore.open(url);
            StoreHealth.record(root.id(), "provenance", StoreHealth.Status.UP, url, "open");
            return db;
        } catch (Exception e) {
            log.warn("Could not open provenance DB ({}) — data-plane provenance disabled: {}", url, e.getMessage());
            StoreHealth.degraded(root.id(), "provenance", url, "data-plane provenance disabled: " + e.getMessage());
            return null;
        }
    }

    /**
     * Consignment output-file registry (consignment-elt plan §11.3), gated by
     * {@code -Dconsignment.outputs.backend}: {@code duckdb} (the bundled default engine),
     * {@code postgres}/{@code postgresql} (resolves {@code -Dconsignment.outputs.db.url}, which must be a
     * {@code jdbc:postgresql://…} URL with the PG driver on the classpath), or a raw {@code jdbc:} URL. Any
     * other value — including an explicit {@code none} — ⇒ {@code null} ⇒ no per-output-file registry is kept.
     *
     * <p><b>This is the one store that defaults to {@code duckdb}</b> (addressing plan D1, 2026-08-10), and the
     * reason is a shipped bug rather than the addressing work it was built for. {@code ReprocessCommand} refuses
     * to reprocess a Consignment whose output a compaction merged away, because re-ingesting rows that still
     * exist inside the merged file <b>duplicates them silently</b> — and that refusal is only decidable from this
     * registry's {@code COMPACTED_AWAY} rows. Default-off meant the fix shipped switched off in every
     * deployment. Turning it on cannot change what any reader sees: every read is still a filesystem glob, and
     * this table is consulted only where the alternative is guessing.
     *
     * <p><b>Absence is still not degraded correctness</b>, and must stay that way — {@code -Dconsignment.outputs.backend=none}
     * remains supported, and a failed open degrades to {@code null}. Output files are still revealed and still
     * recorded in the per-Consignment JSON manifest, which stays the artifact of record for <em>existence</em>;
     * this registry adds the queryable per-file index with lifecycle <em>state</em>. A store that can
     * legitimately be absent may never become the only record that a file exists — see
     * {@link com.gamma.consignment.DbConsignmentOutputStore}.
     */
    static com.gamma.consignment.DbConsignmentOutputStore openConsignmentOutputStore(SpaceRoot root) {
        String raw = System.getProperty("consignment.outputs.backend", "duckdb").trim();   // raw: see openJobRunStore
        String backend = raw.toLowerCase();
        boolean pg = "postgres".equals(backend) || "postgresql".equals(backend);
        if (!"duckdb".equals(backend) && !pg && !backend.startsWith("jdbc:")) {
            StoreHealth.record(root.id(), "consignmentOutputs", StoreHealth.Status.NOT_CONFIGURED, backend,
                    "-Dconsignment.outputs.backend=" + backend + " — no per-output-file registry");
            return null;
        }
        String url = backend.startsWith("jdbc:")
                ? raw
                : OperationalDb.urlFor(OperationalDb.Family.CONSIGNMENT_OUTPUTS, root.consignmentOutputsDbUrl());
        try {
            com.gamma.consignment.DbConsignmentOutputStore db = com.gamma.consignment.DbConsignmentOutputStore.open(url);
            StoreHealth.record(root.id(), "consignmentOutputs", StoreHealth.Status.UP, url, "open");
            return db;
        } catch (Exception e) {
            log.warn("Could not open consignment-outputs DB ({}) — output registry disabled: {}", url, e.getMessage());
            StoreHealth.degraded(root.id(), "consignmentOutputs", url, "output registry disabled: " + e.getMessage());
            return null;
        }
    }

    /**
     * The per-space windowed record-dedup ledger (D-9), gated by {@code -Ddedup.ledger.backend}: same
     * three-value contract as {@link #openConsignmentOutputStore(SpaceRoot)} — {@code duckdb} (the
     * default: a default-off dedup ledger silently emits the duplicates it was configured to drop,
     * this codebase's most repeated trap), {@code postgres}/{@code postgresql}
     * ({@code -Ddedup.ledger.db.url}), or a raw {@code jdbc:} URL. Any other value ⇒ {@code null} ⇒
     * a windowed {@code transform.dedup} REFUSES at run (RowShaper.ExecutionContext) rather than
     * degrading — unlike the fail-open registries, absence here is not degraded correctness.
     */
    static com.gamma.consignment.DbDedupLedger openDedupLedger(SpaceRoot root) {
        String raw = System.getProperty("dedup.ledger.backend", "duckdb").trim();   // raw: see openJobRunStore
        String backend = raw.toLowerCase();
        boolean pg = "postgres".equals(backend) || "postgresql".equals(backend);
        if (!"duckdb".equals(backend) && !pg && !backend.startsWith("jdbc:")) {
            StoreHealth.record(root.id(), "dedupLedger", StoreHealth.Status.NOT_CONFIGURED, backend,
                    "-Ddedup.ledger.backend=" + backend + " — a windowed transform.dedup REFUSES at run");
            return null;
        }
        String url = backend.startsWith("jdbc:")
                ? raw
                : OperationalDb.urlFor(OperationalDb.Family.DEDUP_LEDGER, root.dedupLedgerDbUrl());
        try {
            com.gamma.consignment.DbDedupLedger db = new com.gamma.consignment.DbDedupLedger(url);
            StoreHealth.record(root.id(), "dedupLedger", StoreHealth.Status.UP, url, "open");
            return db;
        } catch (Exception e) {
            log.warn("Could not open dedup-ledger DB ({}) — windowed dedup will refuse: {}", url, e.getMessage());
            StoreHealth.degraded(root.id(), "dedupLedger", url, "windowed dedup will REFUSE at run: " + e.getMessage());
            return null;
        }
    }

    /**
     * Per-file stage-progression registry (Phase 4 §2.4), gated by {@code -Dfile.stages.backend}: same
     * three-value contract as {@link #openConsignmentOutputStore(SpaceRoot)} — {@code duckdb}, {@code postgres}/
     * {@code postgresql} ({@code -Dfile.stages.db.url}), or a raw {@code jdbc:} URL. Any other value ⇒
     * {@code null} ⇒ no per-file stage index is kept; the crash-safe commit ordering
     * {@code ConsignmentIngestor.finalizeSource} enforces is unchanged either way.
     */
    /**
     * Durable delivery-receipt store (D8-SUPPRESS-1's precondition), gated by
     * {@code -Ddelivery.receipts.backend}: {@code duckdb}, {@code postgres}/{@code postgresql}
     * ({@code -Ddelivery.receipts.db.url}), or a raw {@code jdbc:} URL. Any other value — including the
     * default — ⇒ {@code null} ⇒ {@link CollectorService} keeps the bounded
     * {@link com.gamma.notify.InMemoryDeliveryReceiptStore}, which is the shipped behaviour and stays it.
     *
     * <p>⚠ Absence here is NOT degraded correctness (unlike {@link #openDedupLedger(SpaceRoot)}): nothing
     * silently produces a wrong result without it. What it costs is per-recipient suppression, which needs
     * a bounce record to survive long enough to be consulted on the next send — and the in-memory map
     * evicts oldest-first, so the bounce that should suppress an address is the record most likely to be
     * gone. That is why suppression is gated on this store rather than built over the map.
     */
    static com.gamma.notify.DbDeliveryReceiptStore openDeliveryReceiptStore(SpaceRoot root) {
        String raw = System.getProperty("delivery.receipts.backend", "none").trim();   // raw: see openJobRunStore
        String backend = raw.toLowerCase();
        boolean pg = "postgres".equals(backend) || "postgresql".equals(backend);
        if (!"duckdb".equals(backend) && !pg && !backend.startsWith("jdbc:")) {
            StoreHealth.record(root.id(), "deliveryReceipts", StoreHealth.Status.NOT_CONFIGURED, backend,
                    "-Ddelivery.receipts.backend=" + backend + " — receipts stay in the bounded in-memory map");
            return null;
        }
        String url = backend.startsWith("jdbc:")
                ? raw
                : OperationalDb.urlFor(OperationalDb.Family.DELIVERY_RECEIPTS, root.deliveryReceiptsDbUrl());
        try {
            com.gamma.notify.DbDeliveryReceiptStore db = com.gamma.notify.DbDeliveryReceiptStore.open(url, null, null);
            StoreHealth.record(root.id(), "deliveryReceipts", StoreHealth.Status.UP, url, "open");
            return db;
        } catch (Exception e) {
            log.warn("Could not open delivery-receipts DB ({}) — receipts stay in memory: {}",
                    url, e.getMessage());
            StoreHealth.degraded(root.id(), "deliveryReceipts", url, "receipts stay in memory: " + e.getMessage());
            return null;
        }
    }

    static com.gamma.consignment.DbFileStageStore openFileStageStore(SpaceRoot root) {
        String raw = System.getProperty("file.stages.backend", "none").trim();   // raw: see openJobRunStore
        String backend = raw.toLowerCase();
        boolean pg = "postgres".equals(backend) || "postgresql".equals(backend);
        if (!"duckdb".equals(backend) && !pg && !backend.startsWith("jdbc:")) {
            StoreHealth.record(root.id(), "fileStages", StoreHealth.Status.NOT_CONFIGURED, backend,
                    "-Dfile.stages.backend=" + backend + " — no per-file stage index");
            return null;
        }
        String url = backend.startsWith("jdbc:")
                ? raw
                : OperationalDb.urlFor(OperationalDb.Family.FILE_STAGES, root.fileStagesDbUrl());
        try {
            com.gamma.consignment.DbFileStageStore db = com.gamma.consignment.DbFileStageStore.open(url);
            StoreHealth.record(root.id(), "fileStages", StoreHealth.Status.UP, url, "open");
            return db;
        } catch (Exception e) {
            log.warn("Could not open file-stages DB ({}) — stage registry disabled: {}", url, e.getMessage());
            StoreHealth.degraded(root.id(), "fileStages", url, "stage registry disabled: " + e.getMessage());
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
        if (!"parquet".equalsIgnoreCase(backend)) {
            StoreHealth.record(root.id(), "events", StoreHealth.Status.NOT_CONFIGURED, backend,
                    "-Devents.backend=" + backend + " — bounded in-memory ring, nothing survives a restart");
            return new InMemoryEventStore();
        }
        String ev = System.getProperty("events.dir");
        Path dir = (ev == null) ? root.eventsDir() : Path.of(ev);
        try {
            EventStore store = ParquetEventStore.open(dir);
            log.info("Event backend: rolling Parquet ({})", dir.toAbsolutePath());
            StoreHealth.record(root.id(), "events", StoreHealth.Status.UP, dir.toAbsolutePath().toString(),
                    "rolling Parquet");
            return store;
        } catch (RuntimeException e) {
            log.warn("Could not open Parquet event store at {} — falling back to in-memory: {}",
                    dir, e.getMessage());
            // 🔴 The audit trail the Standard/Enterprise launchers ask for by passing -Devents.backend=parquet.
            // Degrading it to a bounded ring loses the trail on restart, which is exactly EVENTS-DURABLE-1.
            StoreHealth.degraded(root.id(), "events", dir.toAbsolutePath().toString(),
                    "audit trail fell back to the in-memory ring — nothing survives a restart: " + e.getMessage());
            return new InMemoryEventStore();
        }
    }

    // ⛔ The four operational-object store openers are GONE from core (EDG-01 cell 7, 2026-09-08):
    // openObjectStore / openLinkStore / openNoteStore / openTagAssignmentStore. They named
    // com.gamma.ops types, which is now an optional edition module, so opening them moved behind
    // com.gamma.service.ObjectEngineProvider and lives in inspecto-ops.
    //
    // ⚠ Two behaviours travelled with them and must not be lost there: each store is its OWN DuckDB
    // file (a file-based DuckDB holds a single-writer lock, so one file cannot serve four), and a DB
    // that fails to open degrades to in-memory rather than blocking startup.

    /**
     * The in-app notification feed (Phase B2) for the single {@code appUser}. The feed is low-volume, so
     * the lean {@link com.gamma.notify.InMemoryNotificationStore} is the default and currently the only
     * backend; a durable DuckDB backend would mirror the status store below when needed.
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
        // ⚠ ONE declaration of the default — the family owns it. This used to repeat "file" here, so
        // the two could drift and the effective default would depend on which one you read.
        String requested = System.getProperty(OperationalDb.Family.STATUS.backendProperty);
        boolean explicit = requested != null && !requested.isBlank();
        String backend = explicit ? requested : OperationalDb.Family.STATUS.backendDefault;
        // TEST-CWD-DB-1: a raw jdbc: value is "db, at exactly this URL" — the same first-class source the
        // URL_OR_ENGINE families accept. It exists so the test reactor can pin the family to an in-memory
        // DuckDB (-Dstatus.backend=jdbc:duckdb:) without touching -Dstatus.db.url, which would break the
        // shared -Dinspecto.db roster OperationalDbTest pins; the `db` default is untouched.
        boolean rawUrl = backend.startsWith("jdbc:");
        if (!rawUrl && !"db".equalsIgnoreCase(backend)) {
            StoreHealth.record(root.id(), "status", StoreHealth.Status.NOT_CONFIGURED, backend,
                    "-D" + OperationalDb.Family.STATUS.backendProperty + "=" + backend + " — on-disk audit only");
            return new FileStatusStore();
        }

        String url = rawUrl ? backend : OperationalDb.urlFor(OperationalDb.Family.STATUS, root.statusDbUrl());
        try {
            StatusStore db = DbStatusStore.open(url,
                    OperationalDb.userFor(OperationalDb.Family.STATUS), OperationalDb.passwordFor(OperationalDb.Family.STATUS));
            log.info("Status backend: database ({})", url);
            StoreHealth.record(root.id(), "status", StoreHealth.Status.UP, url, "open");
            return db;
        } catch (Exception e) {
            // 🔴 An EXPLICIT request still fails loudly: the operator asked for this database, and
            // quietly serving them something else would hide a misconfigured deployment.
            if (explicit)
                throw new IllegalStateException("Could not open status DB at " + url, e);
            // ⚠ But the DEFAULT must not be able to stop the product booting. The CSVs are the durable
            // write-ahead and FileStatusStore reads them directly, so degrading costs only the queried
            // surface — nothing is lost, and ingest is untouched. Loud, because a silent degrade would
            // leave an operator wondering why their status tables are empty.
            log.warn("Status backend: could not open the default status DB at {} — falling back to the "
                    + "on-disk audit. The ledgers are intact; only the database projection is unavailable. "
                    + "Set -D{}=file to make this deliberate, or fix the database to restore it. Cause: {}",
                    url, OperationalDb.Family.STATUS.backendProperty, e.toString());
            StoreHealth.degraded(root.id(), "status", url,
                    "fell back to the on-disk audit — the ledgers are intact, only the database projection "
                    + "is unavailable: " + e.getMessage());
            return new FileStatusStore();
        }
    }
}
