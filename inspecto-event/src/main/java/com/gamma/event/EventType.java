package com.gamma.event;

/**
 * Well-known {@link Event#type()} constants. {@code Event.type} is a free-form {@code String} (the
 * model is <em>extensible</em> per the platform requirement), so these are conventions rather than a
 * closed enum — a caller may emit a new type without touching this class.
 *
 * <h3>Two families</h3>
 * <ul>
 *   <li>{@link #LOG} — an automatically captured SLF4J log record (INFO and above). High volume,
 *       low structure (message + logger name).</li>
 *   <li>The rest — <em>domain</em> facts emitted explicitly at lifecycle points. Lower volume, high
 *       structure (they carry typed {@link Event#attributes()} and a {@link Event#correlationId()}).</li>
 * </ul>
 *
 * @since 4.0.0
 */
@com.gamma.api.PublicApi(since = "4.0.0")
public final class EventType {

    private EventType() {}

    /** A captured SLF4J/logback log record (level ≥ {@link EventLevel#CAPTURE_THRESHOLD}). */
    public static final String LOG = "LOG";

    // ── audit trail (who did what, when, where) ─────────────────────────────────────
    /** A user/system action recorded for the security audit trail; "who / what / where" detail rides
     *  in {@link AuditAttrs} keys. Emitted centrally for state-changing Control API requests. */
    public static final String AUDIT = "AUDIT";
    /** An attempt to reach a forbidden/unknown API route (the auth-free analogue of 401/403):
     *  a non-GET request that matched no route (404) or a disallowed method on a read-only route (405). */
    public static final String ACCESS_DENIED = "ACCESS_DENIED";

    // ── service / pipeline lifecycle ──────────────────────────────────────────────
    public static final String SERVICE_STARTED     = "SERVICE_STARTED";
    /** A space's on-disk tree departs from the storage-layout contract (config/data/audit/duckdb axes must
     *  not mix; canonical subdirs must exist). Emitted at boot as a {@link EventLevel#WARN} advisory — a
     *  violation is never fatal. The {@code kind} attribute names the rule; {@code path} the offending entry. */
    public static final String LAYOUT_CONTRACT_VIOLATION = "LAYOUT_CONTRACT_VIOLATION";
    public static final String PIPELINE_REGISTERED = "PIPELINE_REGISTERED";
    /** A registered pipeline's config path was removed from the active registry ahead of the next
     *  poll cycle (e.g. an onboarding draft discard) — see {@code CollectorService#unregisterPipeline}. */
    public static final String PIPELINE_UNREGISTERED = "PIPELINE_UNREGISTERED";
    public static final String PIPELINE_PAUSED     = "PIPELINE_PAUSED";
    public static final String PIPELINE_RESUMED    = "PIPELINE_RESUMED";
    /** A pipeline's identity migrated to a new id (full rename, not a display-name-only {@code label}).
     *  {@code oldId}/{@code newId} attributes carry both ids; {@link Event#pipeline()} is the new id. */
    public static final String PIPELINE_RENAMED    = "PIPELINE_RENAMED";
    /** A live scheduler setting was changed through {@code PUT /system/scheduler} or
     *  {@code /settings/scheduler} — Consignment caps, poll cadences, or the intake globals. Emitted
     *  ONLY when a value actually changed, with one {@code <key>} attribute per change carrying
     *  {@code "<old> -> <new>"} and the {@code tier}/{@code scope} it applied to. The generic audit
     *  trail already records who/when/status for the request; this records WHAT the numbers became,
     *  which is the half an incident review needs and a path-classified audit row cannot carry. */
    public static final String SCHEDULER_SETTINGS_CHANGED = "SCHEDULER_SETTINGS_CHANGED";

    // ── batch / ingest facts (the headline operational signal) ──────────────────────
    public static final String BATCH_COMMITTED  = "BATCH_COMMITTED";
    public static final String BATCH_FAILED     = "BATCH_FAILED";
    public static final String FILE_RECEIVED    = "FILE_RECEIVED";
    public static final String FILE_QUARANTINED = "FILE_QUARANTINED";
    /** A source connector listed a candidate file during discovery (Phase E). */
    public static final String FILE_DISCOVERED  = "FILE_DISCOVERED";
    /** A remote file's bytes were retrieved (staged/streamed) from the source connector (Phase E). The
     *  {@code bytes} attribute carries the transferred size. */
    public static final String FILE_FETCHED     = "FILE_FETCHED";
    /** A fetched file passed its integrity check — size match and/or checksum verified (Phase E). */
    public static final String FILE_VALIDATED   = "FILE_VALIDATED";
    /** Retrieval (or integrity validation) of a remote file failed; it was skipped this cycle (Phase E). */
    public static final String FILE_FETCH_FAILED = "FILE_FETCH_FAILED";
    /** A processed source file was finalized on the source side via a post-action — moved/renamed/deleted (Phase F).
     *  The {@code action} attribute carries the kind (DELETE/MOVE/RENAME/TAG). */
    public static final String FILE_ARCHIVED    = "FILE_ARCHIVED";
    /** A source's circuit breaker tripped OPEN after repeated connectivity failures; the source is being skipped
     *  until its cooldown elapses (Phase F). */
    public static final String SOURCE_CIRCUIT_OPEN = "SOURCE_CIRCUIT_OPEN";
    /** A discovered file passed the readiness gate — quiescent / size-stable, safe to ingest (Phase B). */
    public static final String FILE_STABLE      = "FILE_STABLE";
    /** A file at a known path was re-seen with changed content (size/mtime/checksum differs) (Phase C). */
    public static final String FILE_CHANGED     = "FILE_CHANGED";
    /** An expected file in a configured sequence is missing — a hole in the series (Phase D). The
     *  {@code expected} attribute carries the missing key; {@code sequence}/{@code unit} describe the series. */
    public static final String SEQUENCE_GAP     = "SEQUENCE_GAP";
    /** The record-grain {@code dedup} Step (§2.4) dropped one or more duplicate rows by business key —
     *  a reject stream the user tunes where it rests, never wires (§2.6). Emitted by the Stage-2
     *  executor ({@code PipelineExecutor}, since 2026-09-06) once per dedup node per Consignment when
     *  the {@code duplicate} relation is non-empty — the flat lane's counter went with
     *  {@code applyRecordDedup} on 2026-08-11 and the constant was emitter-less in between. The
     *  {@code keys} and {@code dropped} attributes carry the dedup key list and the count, {@code node}
     *  the Step id; {@code correlationId} is the batch id. */
    public static final String DEDUP_RECORDS_DROPPED = "DEDUP_RECORDS_DROPPED";

    // ── job / enrichment ────────────────────────────────────────────────────────────
    public static final String JOB_STARTED   = "JOB_STARTED";
    public static final String JOB_FAILED    = "JOB_FAILED";
    /** The one signal-ledger event type (job-framework §8.1). A {@code com.gamma.signal.Signal} persists
     *  as an Event of this type; its dotted signal-type, severity and JSON payload ride in the attributes,
     *  its correlationId in the first-class field. {@code GET /signals} is the ledger view over these. */
    public static final String SIGNAL = "SIGNAL";
    /** A delete/maintenance job targets a resting store with an active producer/consumer — the one
     *  cross-driver hazard the deletion fence guards (§3.8 rule 4, T25). The {@code store},
     *  {@code activeProducers} and {@code activeConsumers} attributes name the racing flows. */
    public static final String STORE_DELETE_CONFLICT = "STORE_DELETE_CONFLICT";
    /** A delete/maintenance job targets a store the deletion fence can never cover
     *  ({@code FENCE-STORE-SILENTLY-INERT-1}) — {@code DeletionFence.Coverage.VIEW_ONLY},
     *  {@code CONSUMED_ONLY} or {@code UNMATCHED}. The first two are attested by design (nothing rests, or
     *  the producer is elsewhere); {@code UNMATCHED} is the typo class — no configured pipeline names this
     *  store at all. The {@code store} and {@code reason} attributes name which. Advisory only: the delete
     *  proceeds regardless either way. */
    public static final String STORE_DELETE_UNFENCED = "STORE_DELETE_UNFENCED";
    /** A pipeline run's data-plane provenance failed the conservation invariant at a non-amplifying node —
     *  records entered that did not leave (silent data loss) or were unexpectedly amplified (§11.4, T22). The
     *  {@code node}, {@code recordsIn}, {@code recordsOut} and {@code kind} (LOSS/AMPLIFICATION) attributes
     *  describe the imbalance; {@code correlationId} is the run's {@code batchId}. */
    public static final String PIPELINE_CONSERVATION_IMBALANCE = "PIPELINE_CONSERVATION_IMBALANCE";
    /** Read-alias for {@link #PIPELINE_CONSERVATION_IMBALANCE}'s pre-rename value, persisted in existing
     *  event-ledger rows — matched on read only; nothing is ever written under this value again. */
    public static final String FLOW_CONSERVATION_IMBALANCE_LEGACY = "FLOW_CONSERVATION_IMBALANCE";

    // ── cross-space sharing (the Exchange — Share Grants) ───────────────────────────
    /** An owner Space listed a Dataset/Widget as shareable in the Exchange. {@code owner}/{@code kind}/
     *  {@code item} attributes name the offered item. */
    public static final String EXCHANGE_OFFERED   = "EXCHANGE_OFFERED";
    /** A consumer Space requested use of an offered item; {@code consumer}/{@code owner}/{@code kind}/
     *  {@code item}/{@code purpose} carry the request. */
    public static final String EXCHANGE_REQUESTED = "EXCHANGE_REQUESTED";
    /** An owner approved a request — the grant is now {@code active}. */
    public static final String EXCHANGE_GRANTED   = "EXCHANGE_GRANTED";
    /** An owner denied a pending request. */
    public static final String EXCHANGE_DENIED    = "EXCHANGE_DENIED";
    /** An owner revoked an active grant — the consumer's access is withdrawn (fail-closed). */
    public static final String EXCHANGE_REVOKED   = "EXCHANGE_REVOKED";
    /** A new snapshot of an offered Dataset was published to the Exchange (S2); {@code owner}/{@code item}/
     *  {@code version}/{@code rows} attributes carry the refresh. Consumers can gate their own jobs on it. */
    public static final String EXCHANGE_REFRESHED = "EXCHANGE_REFRESHED";

    // ── link analysis + geo studio (what an analyst looked at) ─────────────────────
    /** An Entity Projection was served over a Dataset ({@code POST /inv/projection}). {@code dataset},
     *  {@code rows} and {@code truncated} carry what the analyst actually saw — a projection cut short
     *  by the limit is a partial picture, and an audit that cannot say so is worthless. */
    public static final String LINK_PROJECTED = "LINK_PROJECTED";
    /** An analyst expanded one entity's one-hop neighborhood onto the canvas
     *  ({@code POST /inv/projection/neighbors}) — a different analytic act from the initial projection.
     *  Adds the expanded {@code value} to {@link #LINK_PROJECTED}'s attributes. */
    public static final String LINK_EXPANDED = "LINK_EXPANDED";
    /** A server-side multi-hop traversal was run over a Dataset ({@code POST /inv/traversal/recursive-paths},
     *  LA-11). {@code startNode}, optional {@code targetNode}, {@code maxDepth}, {@code paths} and
     *  {@code truncated} carry what was walked and whether a fence cut it short. */
    public static final String LINK_TRAVERSED = "LINK_TRAVERSED";
    /** A branching motif (structuring &amp; co.) was run over a whole Dataset ({@code POST /inv/pattern/branching},
     *  LA-14b). {@code matches}, {@code truncated} and — when the pattern could not be evaluated —
     *  {@code refusal} carry what the analyst was told. */
    public static final String LINK_PATTERN_MATCHED = "LINK_PATTERN_MATCHED";
    /** The cross-Dataset schema-relationship model was read ({@code GET /inv/schema/relationships}).
     *  {@code datasetsScanned}/{@code datasetsSkipped}/{@code relationships} carry the sweep's reach;
     *  it spans every Dataset, so it names no single one and cannot truncate. */
    public static final String LINK_SCHEMA_INSPECTED = "LINK_SCHEMA_INSPECTED";
    /** Candidate key columns were profiled for cardinality and value overlap
     *  ({@code POST /inv/schema/overlap-profile}, LA-15). A separate act from
     *  {@link #LINK_SCHEMA_INSPECTED}: that one reads names, this one reads VALUES — it runs aggregates
     *  over every Dataset in scope, so the trail must be able to tell the cheap schema read from the
     *  expensive data read. {@code columnsProfiled}/{@code pairsProfiled}/{@code pairsConsidered} and
     *  {@code truncated} carry the sweep's reach and whether the pair budget cut it short. */
    public static final String LINK_OVERLAP_PROFILED = "LINK_OVERLAP_PROFILED";
    /** A Link Analysis evidence snapshot was SEALED ({@code POST /inv/snapshots}, LA-03).
     *  {@code snapshotId}, {@code nodes} and {@code edges} carry what was frozen. A distinct act from
     *  {@link #LINK_PROJECTED}: that one records what an analyst LOOKED AT, this one records what they
     *  committed to as evidence — the record is immutable from this moment, and a re-POST of the same id
     *  is refused rather than replacing it, so this event has no "updated" counterpart by design. */
    public static final String LINK_SNAPSHOT_SEALED = "LINK_SNAPSHOT_SEALED";
    /** A sealed snapshot was attached to a Case ({@code POST /inv/snapshots/attach}, LA-03).
     *  ⚠ Separate from {@link #LINK_SNAPSHOT_SEALED} because attaching does NOT reopen the sealed record —
     *  it is a relationship, and writing it into the snapshot would invalidate the fingerprint that makes
     *  the snapshot evidence. {@code snapshotId} and {@code caseId} carry the link. */
    public static final String LINK_SNAPSHOT_ATTACHED = "LINK_SNAPSHOT_ATTACHED";
    /** A Link Analysis Investigation was created ({@code POST /inv/investigations}, LA-10) — bound to one
     *  Dataset and projection mapping. {@code investigationId} and {@code dataset} carry the binding. */
    public static final String LINK_INVESTIGATION_CREATED = "LINK_INVESTIGATION_CREATED";
    /** One step was appended to an Investigation's op log ({@code POST /inv/investigations/{id}/ops} or
     *  {@code /undo}, LA-10). {@code op} ({@code undo} for a log edit), {@code step} and, for a Dataset-reading
     *  op, {@code rows}, {@code truncated} and the sealed read's {@code fingerprint}. */
    public static final String LINK_INVESTIGATION_STEPPED = "LINK_INVESTIGATION_STEPPED";
    /** A re-ordered log was FORKED into a new Investigation ({@code POST /inv/investigations/{id}/reorder},
     *  LA-10, decision D-E4). The original is untouched; {@code parentId} and {@code investigationId} carry the
     *  lineage. */
    public static final String LINK_INVESTIGATION_FORKED = "LINK_INVESTIGATION_FORKED";
    /** An Investigation's log was fully re-evaluated ({@code POST /inv/investigations/{id}/replay}, LA-10).
     *  {@code equivalent} reports the incremental/replay equivalence check; with {@code reread},
     *  {@code diverged} reports whether current data no longer matches a sealed read. */
    public static final String LINK_INVESTIGATION_REPLAYED = "LINK_INVESTIGATION_REPLAYED";
    /** An Investigation's Dossier was issued ({@code GET /inv/investigations/{id}/dossier}, LA-12). {@code at},
     *  {@code format}, the SHA-256 manifest {@code root} and {@code intact} (whether the stored evidence still
     *  agrees with its own recorded hashes) carry what was handed over — issuing evidence is an act, not a view. */
    public static final String LINK_DOSSIER_BUILT = "LINK_DOSSIER_BUILT";
    /** A Dossier manifest was checked against the store ({@code POST /inv/investigations/{id}/dossier/verify},
     *  LA-12). {@code verified}, {@code submittedRoot}, {@code currentRoot} and {@code changed} (artefacts whose
     *  bytes differ) carry the custody verdict. */
    public static final String LINK_DOSSIER_VERIFIED = "LINK_DOSSIER_VERIFIED";
    /** An Investigation's Working Set was read as a derived relation ({@code GET /inv/investigations/{id}/working-set},
     *  LA-20). {@code relation} (entities | links | excluded), {@code rows} served, {@code total},
     *  {@code truncated}, {@code cached} and the relation {@code key} (the sealed log's hash) — the LA-04
     *  query-audit shape, so a read of the relation is as visible as the projection it replaces. */
    public static final String LINK_INVESTIGATION_WORKING_SET_READ = "LINK_INVESTIGATION_WORKING_SET_READ";
    /** An Investigation's op log was saved as an <b>Investigation Template</b> ({@code POST
     *  /inv/investigations/{id}/template}, LA-23). {@code templateId}, {@code investigationId}, {@code parameters}
     *  and {@code dropped} — how many analyst-judgement ops (exclude · hide · keep) were left behind, per D-E8. */
    public static final String LINK_INVESTIGATION_TEMPLATE_SAVED = "LINK_INVESTIGATION_TEMPLATE_SAVED";
    /** An Investigation Template was instantiated into a new Investigation ({@code POST
     *  /inv/investigation-templates/{id}/instantiate}, LA-23). {@code templateId}, {@code investigationId},
     *  {@code dataset} and {@code steps}; every {@code expand} read fresh data and is sealed in the new log. */
    public static final String LINK_INVESTIGATION_TEMPLATE_INSTANTIATED = "LINK_INVESTIGATION_TEMPLATE_INSTANTIATED";
    /** The Measures over an Investigation's Working Set were read ({@code GET /inv/investigations/{id}/measures},
     *  LA-23) — the LA-04 query-audit shape: {@code investigationId}, {@code key} (the sealed log's hash) and
     *  {@code measure} when one was asked for. */
    public static final String LINK_INVESTIGATION_MEASURED = "LINK_INVESTIGATION_MEASURED";
    /** An Alert Rule was bound to a Measure over an Investigation's Working Set ({@code POST
     *  /inv/investigations/{id}/alert-rules}, LA-23) by the Investigation's owner. {@code rule},
     *  {@code investigationId}, {@code relation}, {@code measure}, {@code threshold}. */
    public static final String LINK_INVESTIGATION_ALERT_RULE_BOUND = "LINK_INVESTIGATION_ALERT_RULE_BOUND";
    /** A Geo point projection was served over a Dataset ({@code POST /geo/projection}); {@code dataset},
     *  {@code points}, {@code truncated} and {@code skipped} carry the served result. */
    public static final String GEO_PROJECTED = "GEO_PROJECTED";
    /** A Geo origin→destination route projection was served ({@code POST /geo/routes}); as
     *  {@link #GEO_PROJECTED} but with {@code routes} as the result size. */
    public static final String GEO_ROUTES_PROJECTED = "GEO_ROUTES_PROJECTED";

    // ── operational-object bridge (Phase 2 ties back to here) ───────────────────────
    public static final String ALERT_FIRED     = "ALERT_FIRED";
    /** A fired Alert Rule's condition RECOVERED (DUCKLE-C1) — the all-clear. ⛔ Never cooldown-held:
     *  suppressing a recovery would deliver the alarm and drop the reassurance. Emitted alongside the
     *  {@code alert-rule.cleared} Signal; carries {@code rule}/{@code dataset}/{@code maximumAge}. */
    public static final String ALERT_CLEARED   = "ALERT_CLEARED";
    /** A scheduled report/export artifact was produced (BI-4); {@code attributes.path} points at it. */
    public static final String REPORT_READY    = "REPORT_READY";
    /** A data-quality {@code Expectation} evaluated with violating records (ING-6) — raises an Incident + notifies. */
    public static final String EXPECTATION_FAILED = "EXPECTATION_FAILED";

    /** A managed object (ALERT/INCIDENT/…) was created in its workflow's initial state (Phase 2). */
    public static final String OBJECT_OPENED   = "OBJECT_OPENED";
    /** A managed object changed state via a workflow transition (ack/resolve/…) (Phase 2). */
    public static final String OBJECT_ACTIVITY = "OBJECT_ACTIVITY";
    /** An object (an INCIDENT) passed its {@code dueAt} while still unresolved — an SLA breach (Phase 3). */
    public static final String OBJECT_SLA_BREACH = "OBJECT_SLA_BREACH";
    /** Two objects were correlated by an {@code OBJECT_LINK} (e.g. {@code Case CONTAINS Incident}) (Phase 4). */
    public static final String OBJECT_LINKED = "OBJECT_LINKED";
    /** A note was added to an object — a comment or an attachment reference ({@code noteKind} attr) (Phase 4). */
    public static final String OBJECT_NOTE = "OBJECT_NOTE";
    /** An object was (re)assigned to a person; {@code from}/{@code to} attrs carry the change — the
     *  assignment history parallel to {@link #OBJECT_ACTIVITY}. */
    public static final String OBJECT_ASSIGNED = "OBJECT_ASSIGNED";
    /** An SLA-breached INCIDENT was escalated. ⛔ RETIRE-HALVES-1 retired the escalation engine, so
     *  nothing emits this any more; the constant stays because {@code EventType} is {@code @PublicApi}
     *  and stored events from before the retirement still carry the type. */
    public static final String OBJECT_ESCALATED = "OBJECT_ESCALATED";
}
