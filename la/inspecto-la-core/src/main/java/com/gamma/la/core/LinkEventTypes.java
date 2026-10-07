package com.gamma.la.core;

/**
 * The audit event types Link Analysis and the Geo studio emit — what an analyst looked at, asked for, sealed or revealed.
 *
 * <p>Moved out of {@code com.gamma.event.EventType} (the audit SPI) in D-1, Decision 3 (2026-10-01): the platform audit
 * layer should not enumerate one product's events, and the standalone LA product must be able to add its own. The values
 * are the SAME strings as before — an event type is persisted in the audit trail, so a rename would orphan history —
 * and {@code EventType} constants keep serving every platform event. Use as {@code Event.builder(LinkEventTypes.LINK_PROJECTED)…}.
 */
public final class LinkEventTypes {
    private LinkEventTypes() {}

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

    /** A named LA-18 value Measure was read over a whole Dataset ({@code GET /inv/value-measures}). {@code measure},
     *  {@code entities} (how many breach its thresholds) and {@code truncated} carry what the analyst was told. */
    public static final String LINK_VALUE_MEASURED = "LINK_VALUE_MEASURED";

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

    /** An Investigation's data coverage was assessed ({@code GET /inv/investigations/{id}/coverage}, LA-19).
     *  {@code dataset}, {@code expectedDays} and {@code missingDays} (days in the window with no rows at all). */
    public static final String LINK_INVESTIGATION_COVERAGE = "LINK_INVESTIGATION_COVERAGE";

    /** Two windows of an Investigation were compared ({@code GET /inv/investigations/{id}/compare}). {@code dataset},
     *  {@code linksOnlyA} and {@code linksOnlyB} (Working Set links present in one window only). */
    public static final String LINK_INVESTIGATION_COMPARED = "LINK_INVESTIGATION_COMPARED";

    /** An Investigation's Dossier was issued ({@code GET /inv/investigations/{id}/dossier}, LA-12). {@code at},
     *  {@code format}, the SHA-256 manifest {@code root} and {@code intact} (whether the stored evidence still
     *  agrees with its own recorded hashes) carry what was handed over — issuing evidence is an act, not a view. */
    public static final String LINK_DOSSIER_BUILT = "LINK_DOSSIER_BUILT";

    /** A Dossier manifest was checked against the store ({@code POST /inv/investigations/{id}/dossier/verify},
     *  LA-12). {@code verified}, {@code submittedRoot}, {@code currentRoot} and {@code changed} (artefacts whose
     *  bytes differ) carry the custody verdict. */
    public static final String LINK_DOSSIER_VERIFIED = "LINK_DOSSIER_VERIFIED";

    /** A Dossier was exported as a portable sealed bundle ({@code GET /inv/investigations/{id}/dossier/bundle},
     *  D-6). {@code at}, the manifest {@code root}, the bundle {@code seal}, {@code references} included and
     *  {@code masking} (the mode applied as it left) — handing evidence outside the system is an act the trail must show. */
    public static final String LINK_DOSSIER_EXPORTED = "LINK_DOSSIER_EXPORTED";

    /** A Dossier bundle was checked ({@code POST /inv/investigations/{id}/dossier/bundle/verify}, D-6).
     *  {@code verified}, {@code sealIntact}, {@code referencesIntact} and the custody verdict's {@code changed} count. */
    public static final String LINK_DOSSIER_BUNDLE_VERIFIED = "LINK_DOSSIER_BUNDLE_VERIFIED";

    /** An external reference was appended to an Investigation ({@code POST /inv/investigations/{id}/references},
     *  D-6) by its owner. {@code investigationId}, {@code system}, {@code type}, {@code id} and {@code seq}. The
     *  reference is a pointer, never dereferenced and never trusted. */
    public static final String LINK_INVESTIGATION_REFERENCE_ADDED = "LINK_INVESTIGATION_REFERENCE_ADDED";

    /** A member was granted a role on an Investigation ({@code POST /inv/investigations/{id}/members}, D7-1) by a lead.
     *  {@code investigationId}, {@code subject}, {@code role} and {@code actor} — structured attributes only. */
    public static final String LINK_INV_MEMBER_GRANTED = "LINK_INV_MEMBER_GRANTED";

    /** A member's role on an Investigation was revoked ({@code POST …/members/revoke}, D7-1) by a lead.
     *  {@code investigationId}, {@code subject}, {@code role} (the role removed) and {@code actor}. */
    public static final String LINK_INV_MEMBER_REVOKED = "LINK_INV_MEMBER_REVOKED";

    /** A Draft was forked from an Investigation ({@code POST /inv/investigations/{id}/drafts}, D7-3).
     *  {@code investigationId}, {@code draftId}, {@code actor}, {@code baseStep} and {@code pins} (how many index versions it pinned). */
    public static final String LINK_DRAFT_FORKED = "LINK_DRAFT_FORKED";

    /** One op was appended to a Draft ({@code POST .../drafts/{draftId}/ops}). {@code investigationId}, {@code draftId},
     *  {@code actor}, {@code baseStep}, {@code step}, {@code op}, and for a read-backed op its {@code dataset}, {@code rows}
     *  and {@code fingerprint} - structured attributes only, never row content. */
    public static final String LINK_DRAFT_OP_APPENDED = "LINK_DRAFT_OP_APPENDED";

    /** The latest op of a Draft was undone ({@code POST .../drafts/{draftId}/undo}). Same attributes, {@code undoes} added. */
    public static final String LINK_DRAFT_UNDONE = "LINK_DRAFT_UNDONE";

    /** A Draft was discarded ({@code POST .../drafts/{draftId}/discard}) by its actor or a lead. {@code investigationId},
     *  {@code draftId}, {@code actor} (who discarded), {@code baseStep}, {@code step} (its head) and {@code unpinned}. */
    public static final String LINK_DRAFT_DISCARDED = "LINK_DRAFT_DISCARDED";

    /** A Draft expired after 30 days idle (D7-6, D7-Q5): closed like a discard by the lazy sweep, actor {@code system}. {@code investigationId},
     *  {@code draftId}, {@code draftActor} (who forked it), {@code baseStep}, {@code step} (its head), {@code idleDays} and {@code unpinned} - ids and counts only. */
    public static final String LINK_DRAFT_EXPIRED = "LINK_DRAFT_EXPIRED";

    /** A Draft was rebased onto the main head ({@code POST .../drafts/{draftId}/rebase}, D7-5). {@code investigationId}, {@code draftId},
     *  {@code actor}, {@code fromBase}, {@code toBase}, {@code carried}, {@code dropped} and the conflict counts per kind - ids and counts only. */
    public static final String LINK_DRAFT_REBASED = "LINK_DRAFT_REBASED";

    /** A Draft was promoted into the main log ({@code POST .../drafts/{draftId}/promote}, or the approval of a pending promote, D7-5).
     *  {@code investigationId}, {@code draftId}, {@code actor} (the Draft's), {@code promotedBy}, {@code fromStep}, {@code toStep}, {@code steps}
     *  and, when four-eyes applied, {@code approvedBy} - ids only, never rows. */
    public static final String LINK_DRAFT_PROMOTED = "LINK_DRAFT_PROMOTED";

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

    /** Standing detection was enabled for a bound value-measure Alert Rule ({@code POST
     *  /inv/investigations/{id}/standing-detection}, LA-LIVE-DETECTION-1) by the Investigation's owner.
     *  {@code rule}, {@code investigationId}, {@code principal} ({@code sweep:<id>}), {@code dataset}. */
    public static final String LINK_STANDING_DETECTION_ENABLED = "LINK_STANDING_DETECTION_ENABLED";

    /** A standing-detection sweep re-decided its authority and READ the Dataset (aggregate only). {@code rule},
     *  {@code investigationId}, {@code principal}, {@code value} (the breaching-entity COUNT, never an id). */
    public static final String LINK_STANDING_DETECTION_SWEPT = "LINK_STANDING_DETECTION_SWEPT";

    /** A standing-detection sweep REFUSED to read: nothing was evaluated. {@code rule}, {@code investigationId},
     *  {@code principal}, and the stable reason {@code code}. */
    public static final String LINK_STANDING_DETECTION_REFUSED = "LINK_STANDING_DETECTION_REFUSED";

    /** Standing detection was DISABLED for a rule ({@code DELETE /inv/investigations/{id}/standing-detection/{rule}},
     *  LD-5). {@code rule}, {@code investigationId}, {@code wasEnabled}. Never an entity id. */
    public static final String LINK_STANDING_DETECTION_DISABLED = "LINK_STANDING_DETECTION_DISABLED";

    /** A bound Investigation Alert Rule was EDITED in place ({@code PUT /inv/investigations/{id}/alert-rules/{rule}},
     *  LD-5). {@code rule}, {@code investigationId}, {@code standingDetectionDropped}. */
    public static final String LINK_INVESTIGATION_ALERT_RULE_EDITED = "LINK_INVESTIGATION_ALERT_RULE_EDITED";

    /** An Investigation was linked to a Case ({@code PUT /inv/investigations/{id}/case}, or {@code caseRef} at create,
     *  LA-24) by its owner. {@code investigationId}, {@code caseId} and {@code verified} (false when Case management
     *  is not installed, so the Case could not be checked and the link grants nothing). */
    public static final String LINK_INVESTIGATION_CASE_LINKED = "LINK_INVESTIGATION_CASE_LINKED";

    /** An Investigation's Case link was removed ({@code DELETE /inv/investigations/{id}/case}, LA-24) by its owner.
     *  {@code investigationId} and the former {@code caseId}. */
    public static final String LINK_INVESTIGATION_CASE_UNLINKED = "LINK_INVESTIGATION_CASE_UNLINKED";

    /** Masked entity ids of an Investigation were revealed ({@code POST /inv/investigations/{id}/reveal}, LA-19 /
     *  D-U6) — per entity, by a holder of {@code canRevealLinkEntities}. {@code investigationId}, {@code tokens}
     *  (the pseudonyms revealed — never the raw values, so the trail does not re-leak them) and {@code count}. */
    public static final String LINK_ENTITY_REVEALED = "LINK_ENTITY_REVEALED";

    /** A sensitive expand was held for four-eyes approval instead of running (LA-19 / D-U7). {@code investigationId},
     *  {@code requestId}, {@code budget}, {@code maxFanOut} and the thresholds it exceeded. Nothing was read. */
    public static final String LINK_EXPANSION_REQUESTED = "LINK_EXPANSION_REQUESTED";

    /** A pending sensitive expand was approved by a DIFFERENT Subject and ran (LA-19 / D-U7). {@code investigationId},
     *  {@code requestId}, {@code requestedBy}, {@code step} (the log step it sealed). */
    public static final String LINK_EXPANSION_APPROVED = "LINK_EXPANSION_APPROVED";

    /** A pending sensitive expand was denied (LA-19 / D-U7); it never ran. {@code investigationId}, {@code requestId},
     *  {@code requestedBy}, {@code reason}. */
    public static final String LINK_EXPANSION_DENIED = "LINK_EXPANSION_DENIED";

    /** A graph run was started ({@code POST /inv/graph/runs}, LA separation D-4). {@code investigationId},
     *  {@code algorithm}, {@code nodes}/{@code edges} (the measured input), {@code key} (the Working Set's relation
     *  key), {@code engine}, {@code runId}. Never the run's parameters: they can embed entity ids. */
    public static final String LINK_GRAPH_RUN_STARTED = "LINK_GRAPH_RUN_STARTED";

    /** A graph run finished with a result. Same attributes as {@link #LINK_GRAPH_RUN_STARTED} plus {@code elapsedMs}
     *  and {@code cached} (the answer came from the result cache). */
    public static final String LINK_GRAPH_RUN_COMPLETED = "LINK_GRAPH_RUN_COMPLETED";

    /** A graph run was cancelled by its starter or an administrator and produced no result. */
    public static final String LINK_GRAPH_RUN_CANCELLED = "LINK_GRAPH_RUN_CANCELLED";

    /** A graph run went past its stated budget and produced no result. Adds {@code exceeded} (NODES, EDGES, TIMEOUT
     *  or WORK) and the budget that was in force ({@code maxNodes}, {@code maxEdges}, {@code timeoutMs}). */
    public static final String LINK_GRAPH_RUN_BUDGET_EXCEEDED = "LINK_GRAPH_RUN_BUDGET_EXCEEDED";

    /** A graph run failed inside the engine. Adds {@code failure} (the exception CLASS name, never its message). */
    public static final String LINK_GRAPH_RUN_FAILED = "LINK_GRAPH_RUN_FAILED";

    /** An edge/node index build was started ({@code POST /inv/index/builds}, LA separation D-3 step 4). {@code dataset},
     *  {@code mappingHash} (the hash of the edge mapping - never the column names, which are the analyst's and can embed
     *  data), {@code runId}. */
    public static final String LINK_INDEX_BUILD_STARTED = "LINK_INDEX_BUILD_STARTED";

    /** An index build published a version. Adds {@code version}, {@code rows} (rows of the relation), {@code edges}
     *  (indexed edges per direction), {@code buckets} and {@code elapsedMs}. */
    public static final String LINK_INDEX_BUILD_COMPLETED = "LINK_INDEX_BUILD_COMPLETED";

    /** An index build was cancelled by its starter or an administrator; nothing was published. */
    public static final String LINK_INDEX_BUILD_CANCELLED = "LINK_INDEX_BUILD_CANCELLED";

    /** An index build failed; nothing was published. Adds {@code failure} (the exception CLASS name, never its message). */
    public static final String LINK_INDEX_BUILD_FAILED = "LINK_INDEX_BUILD_FAILED";

    /** A SCHEDULED index build (the {@code la.index.build} Job, run as the delegated principal {@code index-build:<job>})
     *  was attempted or refused. Adds {@code dataset}, {@code result} (BUILT | UP_TO_DATE | REFUSED | FAILED | RUNNING),
     *  {@code mode} and, on a refusal, the stable {@code code}. Never a column name or a row value. */
    public static final String LINK_INDEX_SCHEDULED_RUN = "LINK_INDEX_SCHEDULED_RUN";
}
